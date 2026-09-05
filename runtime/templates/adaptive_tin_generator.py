# -*- coding: utf-8 -*-
"""
Generate a constrained adaptive TIN for UrbanFlood region initialization.

The core mesh workflow follows hole.py:
1. Use the DEM extent as the outer boundary.
2. Add polygon features marked as holes and remove their interiors.
3. Add polygon or line features marked as constraints without cutting holes.
4. Use the triangle package with maximum area and minimum angle constraints.
5. Export p_a_v_poi.csv and p_a_v_tri.csv for the existing ANUGA reader.
"""

import argparse
import json
import os
import sys
import warnings

from pyproj import CRS

try:
    from pyproj import datadir as _pyproj_datadir

    os.environ.setdefault("PROJ_LIB", _pyproj_datadir.get_data_dir())
except Exception:
    pass

import geopandas as gpd
import numpy as np
import pandas as pd
import triangle
from shapely.geometry import (
    GeometryCollection,
    LineString,
    MultiLineString,
    MultiPolygon,
    Polygon,
    box,
    shape as shapely_shape,
)
from shapely.ops import unary_union


def flatten_to_polygons(geom):
    if geom is None or geom.is_empty:
        return []
    if isinstance(geom, Polygon):
        return [geom]
    if isinstance(geom, MultiPolygon):
        return list(geom.geoms)
    if isinstance(geom, GeometryCollection):
        out = []
        for item in geom.geoms:
            out.extend(flatten_to_polygons(item))
        return out
    return []


def flatten_to_lines(geom):
    if geom is None or geom.is_empty:
        return []
    if isinstance(geom, LineString):
        return [geom]
    if isinstance(geom, MultiLineString):
        return list(geom.geoms)
    if isinstance(geom, GeometryCollection):
        out = []
        for item in geom.geoms:
            out.extend(flatten_to_lines(item))
        return out
    return []


def fix_geometry(geom):
    if geom is None or geom.is_empty:
        return None
    if not geom.is_valid:
        geom = geom.buffer(0)
    if geom is None or geom.is_empty:
        return None
    return geom


def extract_ring_points_and_segments(ring_coords, offset):
    pts = np.asarray(ring_coords[:-1], dtype=float)
    n = len(pts)
    if n < 3:
        return np.empty((0, 2), dtype=float), []
    segs = [(offset + i, offset + (i + 1) % n) for i in range(n)]
    return pts, segs


def polygon_exterior_to_vertices_segments(poly, offset):
    pts, segs = extract_ring_points_and_segments(list(poly.exterior.coords), offset)
    return pts, segs, offset + len(pts)


def polygon_all_rings_to_vertices_segments(poly, offset):
    all_pts = []
    all_segs = []
    holes = []

    pts, segs = extract_ring_points_and_segments(list(poly.exterior.coords), offset)
    if len(pts) > 0:
        all_pts.append(pts)
        all_segs.extend(segs)
        offset += len(pts)

    for interior in poly.interiors:
        pts, segs = extract_ring_points_and_segments(list(interior.coords), offset)
        if len(pts) == 0:
            continue
        all_pts.append(pts)
        all_segs.extend(segs)

        ip = Polygon(interior).representative_point()
        holes.append([ip.x, ip.y])
        offset += len(pts)

    if not all_pts:
        return np.empty((0, 2), dtype=float), all_segs, holes, offset
    return np.vstack(all_pts), all_segs, holes, offset


def line_to_vertices_segments(line, offset):
    coords = list(line.coords)
    if len(coords) < 2:
        return np.empty((0, 2), dtype=float), [], offset

    pts = np.asarray(coords, dtype=float)
    segs = [(offset + i, offset + i + 1) for i in range(len(pts) - 1)]
    return pts, segs, offset + len(pts)


def deduplicate_vertices(vertices, segments, tol=1e-8):
    try:
        from scipy.spatial import cKDTree
    except ImportError:
        return vertices, segments

    if len(vertices) == 0:
        return vertices, segments

    tree = cKDTree(vertices)
    parent = np.arange(len(vertices), dtype=np.int32)

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(a, b):
        ra, rb = find(a), find(b)
        if ra == rb:
            return
        if ra < rb:
            parent[rb] = ra
        else:
            parent[ra] = rb

    for i, j in tree.query_pairs(tol):
        union(i, j)

    roots = np.array([find(i) for i in range(len(vertices))])
    unique_roots = sorted(set(roots))
    old_to_new = {old: new for new, old in enumerate(unique_roots)}
    new_vertices = vertices[unique_roots]
    remap = np.array([old_to_new[roots[i]] for i in range(len(vertices))])

    new_segments = []
    seen = set()
    for a, b in segments:
        na = int(remap[a])
        nb = int(remap[b])
        if na == nb:
            continue
        key = tuple(sorted((na, nb)))
        if key in seen:
            continue
        seen.add(key)
        new_segments.append((na, nb))

    return new_vertices, new_segments


def read_ascii_dem_header(dem_path):
    header = {}
    with open(dem_path, "r", encoding="utf-8", errors="ignore") as fp:
        for _ in range(6):
            parts = fp.readline().strip().split()
            if len(parts) >= 2:
                header[parts[0].lower()] = parts[1]

    ncols = int(header["ncols"])
    nrows = int(header["nrows"])
    cellsize = float(header["cellsize"])
    nodata = float(header.get("nodata_value", header.get("nodata", np.nan)))

    if "xllcorner" in header:
        x_origin = float(header["xllcorner"])
    elif "xllcenter" in header:
        x_origin = float(header["xllcenter"]) - 0.5 * cellsize
    else:
        raise ValueError("DEM header missing xllcorner/xllcenter")

    if "yllcorner" in header:
        y_origin = float(header["yllcorner"])
    elif "yllcenter" in header:
        y_origin = float(header["yllcenter"]) - 0.5 * cellsize
    else:
        raise ValueError("DEM header missing yllcorner/yllcenter")

    return {
        "ncols": ncols,
        "nrows": nrows,
        "cellsize": cellsize,
        "nodata": nodata,
        "x_origin": x_origin,
        "y_origin": y_origin,
        "x_max": x_origin + ncols * cellsize,
        "y_max": y_origin + nrows * cellsize,
    }


def read_dem_data(dem_path, header):
    with open(dem_path, "r", encoding="utf-8", errors="ignore") as fp:
        for _ in range(6):
            fp.readline()
        data = np.loadtxt(fp)

    shape = (header["nrows"], header["ncols"])
    if data.shape != shape:
        data = data.reshape(shape)
    return data


def read_dem_crs(dem_path):
    prj_path = os.path.splitext(dem_path)[0] + ".prj"
    if not os.path.isfile(prj_path):
        return None
    try:
        with open(prj_path, "r", encoding="utf-8", errors="ignore") as fp:
            text = fp.read().strip()
        if not text:
            return None
        return CRS.from_wkt(text)
    except Exception as exc:
        warnings.warn("Unable to parse DEM projection file: %s" % exc)
        return None


def read_feature_shp_without_crs(shp_path):
    import fiona

    geometries = []
    with fiona.open(shp_path) as src:
        for feature in src:
            geom_mapping = feature.get("geometry")
            if geom_mapping is None:
                continue
            geom = fix_geometry(shapely_shape(geom_mapping))
            if geom is not None:
                geometries.append(geom)
    return gpd.GeoDataFrame({"geometry": geometries}, geometry="geometry")


def read_feature_shp(shp_path, target_crs=None):
    try:
        gdf = gpd.read_file(shp_path)
    except Exception as exc:
        warnings.warn(
            "%s CRS metadata could not be parsed; reading geometries without CRS. %s"
            % (shp_path, exc)
        )
        gdf = read_feature_shp_without_crs(shp_path)

    gdf = gdf[gdf.geometry.notnull()].copy()
    if gdf.empty:
        return gpd.GeoDataFrame(geometry=[])

    try:
        source_crs = gdf.crs
    except Exception as exc:
        warnings.warn("%s CRS metadata ignored: %s" % (shp_path, exc))
        source_crs = None

    if source_crs is None and target_crs is not None:
        warnings.warn("%s has no CRS; assuming DEM CRS." % shp_path)
        try:
            gdf = gdf.set_crs(target_crs)
        except Exception as exc:
            warnings.warn("%s CRS assignment failed; using coordinates as-is. %s" % (shp_path, exc))
    elif target_crs is not None and source_crs is not None:
        try:
            gdf = gdf.to_crs(target_crs)
        except Exception as exc:
            warnings.warn("%s CRS reprojection failed; using coordinates as-is. %s" % (shp_path, exc))

    return gdf


def clip_and_union_hole_features(gdf, boundary_geom, min_area=1.0):
    clipped_polys = []
    boundary_geom = fix_geometry(boundary_geom)

    for geom in gdf.geometry:
        geom = fix_geometry(geom)
        if geom is None or not geom.intersects(boundary_geom):
            continue
        try:
            clipped = geom.intersection(boundary_geom)
        except Exception:
            clipped = geom.buffer(0).intersection(boundary_geom.buffer(0))

        clipped = fix_geometry(clipped)
        if clipped is None:
            continue

        for poly in flatten_to_polygons(clipped):
            poly = fix_geometry(poly)
            if poly is not None and poly.area >= min_area:
                clipped_polys.append(poly)

    if not clipped_polys:
        return None

    holes_geom = unary_union(clipped_polys)
    holes_geom = fix_geometry(holes_geom)
    final_polys = []
    for poly in flatten_to_polygons(holes_geom):
        poly = fix_geometry(poly)
        if poly is not None and poly.area >= min_area:
            final_polys.append(poly)

    if not final_polys:
        return None
    return fix_geometry(unary_union(final_polys))


def collect_features(feature_configs, target_crs, dem_boundary, min_hole_area):
    hole_gdfs = []
    constraint_geoms = []
    summaries = []

    for cfg in feature_configs:
        shp_path = cfg.get("path")
        if not shp_path:
            continue
        if not os.path.isfile(shp_path):
            raise FileNotFoundError("Feature shapefile not found: %s" % shp_path)

        gdf = read_feature_shp(shp_path, target_crs=target_crs)
        is_hole = bool(cfg.get("hole", False))
        feature_type = cfg.get("feature_type") or ("hole" if is_hole else "constraint")

        if is_hole:
            poly_gdf = gdf[gdf.geometry.geom_type.isin(["Polygon", "MultiPolygon"])].copy()
            if not poly_gdf.empty:
                hole_gdfs.append(poly_gdf)
            summaries.append({
                "path": shp_path,
                "feature_type": feature_type,
                "mode": "hole",
                "feature_count": int(len(poly_gdf)),
            })
        else:
            count = 0
            for geom in gdf.geometry:
                if geom is None or geom.is_empty:
                    continue
                clipped = geom.intersection(dem_boundary) if geom.intersects(dem_boundary) else None
                clipped = fix_geometry(clipped)
                if clipped is None:
                    continue
                constraint_geoms.append(clipped)
                count += 1
            summaries.append({
                "path": shp_path,
                "feature_type": feature_type,
                "mode": "constraint",
                "feature_count": count,
            })

    holes_geom = None
    if hole_gdfs:
        merged = gpd.GeoDataFrame(
            pd.concat(hole_gdfs, ignore_index=True),
            geometry="geometry",
            crs=target_crs,
        )
        holes_geom = clip_and_union_hole_features(merged, dem_boundary, min_area=min_hole_area)

    return holes_geom, constraint_geoms, summaries


def add_constraint_geometry(geom, dem_boundary, all_vertices, all_segments, offset):
    geom = fix_geometry(geom)
    if geom is None:
        return offset

    polygons = flatten_to_polygons(geom)
    for poly in polygons:
        poly = fix_geometry(poly)
        if poly is None:
            continue
        pts, segs, _, offset = polygon_all_rings_to_vertices_segments(poly, offset)
        if len(pts) > 0:
            all_vertices.append(pts)
            all_segments.extend(segs)

    if polygons:
        return offset

    for line in flatten_to_lines(geom):
        line = line.intersection(dem_boundary) if line.intersects(dem_boundary) else None
        if line is None or line.is_empty:
            continue
        for clipped_line in flatten_to_lines(line):
            pts, segs, offset = line_to_vertices_segments(clipped_line, offset)
            if len(pts) > 0:
                all_vertices.append(pts)
                all_segments.extend(segs)

    return offset


def build_tin(dem_boundary, holes_geom, constraint_geoms, max_area, min_angle, dedup_tol):
    dem_boundary = fix_geometry(dem_boundary)
    if dem_boundary is None:
        raise ValueError("DEM boundary is empty.")

    all_vertices = []
    all_segments = []
    all_holes = []
    offset = 0

    for dem_poly in flatten_to_polygons(dem_boundary):
        pts, segs, dem_internal_holes, offset = polygon_all_rings_to_vertices_segments(
            dem_poly,
            offset,
        )
        if len(pts) > 0:
            all_vertices.append(pts)
            all_segments.extend(segs)
            all_holes.extend(dem_internal_holes)

    if holes_geom is not None and not holes_geom.is_empty:
        for hole_poly in flatten_to_polygons(holes_geom):
            hole_poly = fix_geometry(hole_poly)
            if hole_poly is None:
                continue
            hole_poly = fix_geometry(hole_poly.intersection(dem_boundary))
            if hole_poly is None:
                continue
            for poly in flatten_to_polygons(hole_poly):
                if poly.area <= 0:
                    continue
                pts, segs, offset = polygon_exterior_to_vertices_segments(poly, offset)
                if len(pts) > 0:
                    all_vertices.append(pts)
                    all_segments.extend(segs)
                    rp = poly.representative_point()
                    all_holes.append([rp.x, rp.y])

    for geom in constraint_geoms:
        offset = add_constraint_geometry(geom, dem_boundary, all_vertices, all_segments, offset)

    if not all_vertices:
        raise ValueError("No boundary vertices available for triangulation.")

    vertices = np.vstack(all_vertices)
    vertices, all_segments = deduplicate_vertices(vertices, all_segments, tol=dedup_tol)

    tri_input = {
        "vertices": vertices,
        "segments": np.asarray(all_segments, dtype=np.int32),
    }
    if all_holes:
        tri_input["holes"] = np.asarray(all_holes, dtype=float)

    if max_area is not None and max_area > 0:
        opts = "pDq%.1fa%.8fzQ" % (float(min_angle), float(max_area))
    else:
        opts = "pDq%.1fzQ" % float(min_angle)

    result = triangle.triangulate(tri_input, opts)
    out_vertices = result.get("vertices")
    out_triangles = result.get("triangles")
    if out_vertices is None or out_triangles is None:
        raise RuntimeError("Triangle failed to return valid triangles.")

    return out_vertices, out_triangles, {
        "input_vertices": int(len(vertices)),
        "input_segments": int(len(all_segments)),
        "hole_points": int(len(all_holes)),
        "triangle_options": opts,
    }


def sample_dem_value(x, y, data, header):
    col = int(np.floor((x - header["x_origin"]) / header["cellsize"] + 1e-9))
    top = header["y_origin"] + header["nrows"] * header["cellsize"]
    row = int(np.floor((top - y) / header["cellsize"] + 1e-9))
    if row < 0 or row >= header["nrows"] or col < 0 or col >= header["ncols"]:
        return np.nan
    return data[row, col]


def filter_triangles(vertices, triangles, dem_boundary, holes_geom, data, header):
    filtered = []
    nodata = header["nodata"]

    for tri in triangles:
        coords = [
            tuple(vertices[int(tri[0])]),
            tuple(vertices[int(tri[1])]),
            tuple(vertices[int(tri[2])]),
        ]
        poly = Polygon(coords)
        if not poly.is_valid:
            poly = poly.buffer(0)
        if poly.is_empty or poly.area <= 0:
            continue

        centroid = poly.centroid
        if not centroid.within(dem_boundary):
            continue
        if holes_geom is not None and not holes_geom.is_empty and centroid.within(holes_geom):
            continue

        z = sample_dem_value(centroid.x, centroid.y, data, header)
        if np.isnan(z):
            continue
        if nodata is not None and not np.isnan(nodata) and np.isclose(z, nodata):
            continue

        filtered.append([int(tri[0]), int(tri[1]), int(tri[2])])

    return np.asarray(filtered, dtype=np.int32)


def compact_vertices(vertices, triangles):
    used = sorted(set(int(idx) for tri in triangles for idx in tri))
    remap = {old: new for new, old in enumerate(used)}
    new_vertices = vertices[used]
    new_triangles = np.asarray(
        [[remap[int(idx)] for idx in tri] for tri in triangles],
        dtype=np.int32,
    )
    return new_vertices, new_triangles


def write_outputs(vertices, triangles, output_dir):
    os.makedirs(output_dir, exist_ok=True)
    poi_path = os.path.join(output_dir, "p_a_v_poi.csv")
    tri_path = os.path.join(output_dir, "p_a_v_tri.csv")
    base_path = os.path.join(output_dir, "p_a_v.csv")

    with open(poi_path, "w", encoding="utf-8", newline="") as fp:
        fp.write("id,x,y\n")
        for idx, point in enumerate(vertices):
            fp.write("%d,%.12f,%.12f\n" % (idx, point[0], point[1]))

    with open(tri_path, "w", encoding="utf-8", newline="") as fp:
        fp.write("tinid,p0,p1,p2,p0x,p0y,p1x,p1y,p2x,p2y\n")
        for tin_id, tri in enumerate(triangles, start=1):
            p0 = vertices[int(tri[0])]
            p1 = vertices[int(tri[1])]
            p2 = vertices[int(tri[2])]
            fp.write(
                "%d,%d,%d,%d,%.12f,%.12f,%.12f,%.12f,%.12f,%.12f\n"
                % (
                    tin_id,
                    int(tri[0]),
                    int(tri[1]),
                    int(tri[2]),
                    p0[0],
                    p0[1],
                    p1[0],
                    p1[1],
                    p2[0],
                    p2[1],
                )
            )

    with open(base_path, "w", encoding="utf-8") as fp:
        fp.write("poi,tri\n")

    return poi_path, tri_path, base_path


def run(config):
    dem_path = config["dem"]
    output_dir = config.get("output_dir") or os.path.dirname(dem_path)
    max_area = float(config.get("max_area", 300.0))
    min_angle = float(config.get("min_angle", 25.0))
    min_hole_area = float(config.get("min_hole_area", 1.0))
    dedup_tol = float(config.get("dedup_tol", 1e-8))
    features = config.get("features") or []

    header = read_ascii_dem_header(dem_path)
    dem_data = read_dem_data(dem_path, header)
    dem_boundary = box(header["x_origin"], header["y_origin"], header["x_max"], header["y_max"])
    dem_crs = read_dem_crs(dem_path)

    holes_geom, constraint_geoms, feature_summaries = collect_features(
        features,
        dem_crs,
        dem_boundary,
        min_hole_area,
    )

    vertices, triangles, mesh_input_summary = build_tin(
        dem_boundary,
        holes_geom,
        constraint_geoms,
        max_area,
        min_angle,
        dedup_tol,
    )

    filtered_triangles = filter_triangles(vertices, triangles, dem_boundary, holes_geom, dem_data, header)
    if len(filtered_triangles) == 0:
        raise RuntimeError("All generated triangles were filtered out.")

    vertices, filtered_triangles = compact_vertices(vertices, filtered_triangles)
    poi_path, tri_path, base_path = write_outputs(vertices, filtered_triangles, output_dir)

    return {
        "poi_csv": poi_path,
        "tri_csv": tri_path,
        "base_csv": base_path,
        "vertex_count": int(len(vertices)),
        "triangle_count": int(len(filtered_triangles)),
        "dem_bounds": [
            header["x_origin"],
            header["y_origin"],
            header["x_max"],
            header["y_max"],
        ],
        "hole_polygon_count": len(flatten_to_polygons(holes_geom)) if holes_geom is not None else 0,
        "constraint_feature_count": len(constraint_geoms),
        "features": feature_summaries,
        "mesh_input": mesh_input_summary,
    }


def main():
    parser = argparse.ArgumentParser(description="Generate UrbanFlood constrained adaptive TIN CSV files.")
    parser.add_argument("--config", required=True, help="Path to JSON configuration.")
    args = parser.parse_args()

    with open(args.config, "r", encoding="utf-8") as fp:
        config = json.load(fp)

    summary = run(config)
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(json.dumps({"error": str(exc)}, ensure_ascii=False), file=sys.stderr)
        raise
