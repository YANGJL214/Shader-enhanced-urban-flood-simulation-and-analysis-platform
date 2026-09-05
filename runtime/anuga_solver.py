#! /usr/bin/env python
import warnings
import importlib
from pathlib import Path
from typing import Optional
import numpy as np
from anuga.geometry.polygon import read_point,read_point_vertices
import anuga
import os


class AnugaSolver(object):

    def __init__(self, params):
                
        self._domain_type = str(params['domain_type'])
        self._shape = tuple(params['shape'])
        self._size = tuple(params['size'])
        self._boundary_filename = str(params['boundary_filename'])
        self._point_and_vertices = list(params['point_and_vertices'])
        self._pipe_Index_Cord=str(params['pipe_Index_Cord'])
        self._rainfall=str(params['rainfall'])
        self._elevation_filename = str(params['elevation_filename'])
        self._output_dir = str(params['output_dir'])
        os.chdir(self._output_dir)

        self._output_filename = str(params['output_filename'])
        self._time_step = float(params['output_timestep'])
        self.save_timestep=float(params['save_timestep'])
        self._bdry_tags = dict(params['boundary_tags'])
        self._bdry_conditions = dict(params['boundary_conditions'])
        self._stored_quantities = dict(params['stored_quantities'])
        stage_frequency = self._stored_quantities.get('stage', 1)
        if 'height' not in self._stored_quantities:
            self._stored_quantities['height'] = stage_frequency
        self._stored_quantities.setdefault('height', 1)
        self._stored_quantities.setdefault('land_friction', 1)
        self._max_triangle_area = float(params['maximum_triangle_area'])
        self._initial_flow_depth = float(params['initial_flow_depth'])
        self._regionPtArea=str(params['regionPtArea_filename'])
        self._regionPtArea_point = None
        self._interior_poly_filename = str(params['interior_polygon_filename'])
        self._interior_poly_triangle_area = float(params['interior_polygon_triangle_area'])
        self._interior_regions = None
        self._landuse_cfg = params.get('landuse')
        self._landuse_values = None
        self._landuse_nodata = None
        self._infiltration_cfg = params.get('infiltration')
        self._infiltration_operator = None

        if self._infiltration_enabled():
            self._stored_quantities.setdefault('infiltration_rate', 2)
            self._stored_quantities.setdefault('cumulative_infiltration', 2)



        self._use_sed_operator = bool(params['toggle_sediment_transport'])
        self._inflow_concentration = float(params['inflow_sediment_concentration'])
        self._initial_concentration = float(params['initial_sediment_concentration'])
        self._use_veg_operator = bool(params['toggle_vegetation_drag'])
        self._veg_stem_diameter = params['vegetation_stem_diameter']
        self._veg_stem_spacing = params['vegetation_stem_spacing']
        self._mannings_n = float(params['Mannings_n_parameter'])
        
        self._elevation_profile = str(params['elevation_profile'])

        
        self._time = 0
        
        self.initialize_domain()
        self.apply_landuse_friction()
        self.set_boundary_conditions()
        self.set_other_domain_options()
        self.initialize_operators()
        
        
        # store initial elevations for differencing
        self._land_surface__initial_elevation = np.zeros_like(self.land_surface__elevation)
        
        
    def initialize_operators(self):
        """
        Initialize anuga operators
        
        Available operators:
        * Sed transport
        * Vegetation
        
        Operators remaining to implement:
        * Rainfall
        
        """
        
        if self._use_veg_operator:
            # better to have veg before sed transport??
            
            from anuga.operators.vegetation_operator import Vegetation_operator
            veg_op = Vegetation_operator(self.domain)
            
            self.land_vegetation__stem_spacing = self._veg_stem_spacing
            self.land_vegetation__stem_diameter = self._veg_stem_diameter
            
            
        
        if self._use_sed_operator:
            
            # this is the default flow algorithm but it's required for sed transport
            self.domain.set_flow_algorithm('DE0')
            
            from anuga.operators.sed_transport_operator import Sed_transport_operator
            sed_op = Sed_transport_operator(self.domain)
            
            
            assert self._initial_concentration <= 0.3, (
                    "Volumetric suspended sediment concentration must be <= 0.3")
                    
            assert self._inflow_concentration <= 0.3, (
                    "Inflow volumetric suspended sediment concentration must be <= 0.3")
            
            self.land_surface_water_sediment_suspended__volume_concentration = self._initial_concentration
            sed_op.set_inflow_concentration(self._inflow_concentration)
        
        
        
        
    def initialize_domain(self):
        """Initialize anuga domain"""


        assert self._domain_type[:5] in ['squar', 'recta', 'outli', 'irreg', 'bound','poVec'], (
            "Domain shape must be 'square'/'rectangle'/'rectangular' or "
            "'outline'/'irregular'. Shape '%s' is not recognized." %
            self._domain_type)


        if self._domain_type[:5] in ['squar', 'recta']:
        
            self.domain = anuga.rectangular_cross_domain(
                                self._shape[0],
                                self._shape[1],
                                len1 = self._size[0],
                                len2 = self._size[1])
                                
            # set some default values for quantities
            self.set_elevation_rectangular()
            
            
            
                                
        elif self._domain_type[:5] in ['outli', 'irreg', 'bound']:
        
            bounding_polygon = anuga.read_polygon(self._boundary_filename)
            
            filename_root = self._elevation_filename[:-4]
            
            assert self._elevation_filename[-4:] in ['.asc', '.pts'], (
                "Cannot recognize type of elevation file '%s'. "
                "Please use an .asc or .pts file." % self._elevation_filename)
            
            if self._elevation_filename[-4:] == '.asc':
                anuga.asc2dem(filename_root + '.asc')
                anuga.dem2pts(filename_root + '.dem')
            
            
            if ((self._interior_regions is None) and
                (self._interior_poly_triangle_area > 0.0) and
                (self._interior_poly_filename is not None)):
                
                try:
                    interior_poly = anuga.read_polygon(
                                    self._interior_poly_filename)
                    self._interior_regions = [[interior_poly,
                                             self._interior_poly_triangle_area]]
                except:
                    pass
            if (self._regionPtArea is not None):
                try:
                    regionPtArea_point=read_point(self._regionPtArea)
                    self._regionPtArea_point=regionPtArea_point
                except:
                    pass
                               
            # generalize the mesh creation to be able to use the sed transport operator
            evolved_quantities =  ['stage', 'xmomentum', 'ymomentum', 'concentration']
            
            anuga.pmesh.mesh_interface.create_mesh_from_regions(
                                bounding_polygon = bounding_polygon,
                                boundary_tags = self._bdry_tags,
                                interior_regions = self._interior_regions,
                                maximum_triangle_area = self._max_triangle_area,
                                regionPtArea=self._regionPtArea_point,
                                filename = filename_root + '.msh')
                                
            self.domain = anuga.Domain(filename_root + '.msh',
                                       evolved_quantities = evolved_quantities)
            
            
            self.domain.set_quantity('elevation',
                                     filename = filename_root + '.pts')
        elif self._domain_type[:5] in ['poVec']:
            from anuga.geometry.polygon import read_point, read_point_vertices
            filename_root = self._elevation_filename[:-4]
            points,vertices = read_point_vertices(self._point_and_vertices[0],
                                                  sheet_name1=self._point_and_vertices[1],
                                                  sheet_name2=self._point_and_vertices[2])
            assert self._elevation_filename[-4:] in ['.asc', '.pts'], (
                    "Cannot recognize type of elevation file '%s'. "
                    "Please use an .asc or .pts file." % self._elevation_filename)

            if self._elevation_filename[-4:] == '.asc':
                anuga.asc2dem(filename_root + '.asc')
                anuga.dem2pts(filename_root + '.dem')
            evolved_quantities = ['stage', 'xmomentum', 'ymomentum', 'concentration']
            self.domain =anuga.Domain(points,
                                      vertices,
                                      evolved_quantities =evolved_quantities,
                                      maximum_triangle_area = self._max_triangle_area)
            Br = anuga.Reflective_boundary(self.domain)
            self.domain.set_boundary({'exterior':Br})
            self.domain.set_quantity('elevation',
                                     filename=filename_root + '.pts')
        self.land_surface_water__depth = self._initial_flow_depth
        self.land_surface_water_surface__elevation += self._initial_flow_depth
        self.manning_n_parameter = self._mannings_n
                                 
        anuga.Quantity(self.domain, name='veg_diameter', register=True)
        anuga.Quantity(self.domain, name='veg_spacing', register=True)
        anuga.Quantity(self.domain, name='shear_stress', register=True)
        anuga.Quantity(self.domain, name='concentration', register=True)
        anuga.Quantity(self.domain, name='land_friction', register=True)
        if self._infiltration_enabled():
            anuga.Quantity(self.domain, name='infiltration_rate', register=True)
            anuga.Quantity(self.domain, name='cumulative_infiltration', register=True)
            self.domain.set_quantity('infiltration_rate', 0.0, location='centroids')
            self.domain.set_quantity('cumulative_infiltration', 0.0, location='centroids')

    def apply_landuse_friction(self):
        cfg = self._landuse_cfg
        centroids = self.domain.get_centroid_coordinates(absolute=True)

        if len(centroids) == 0:
            self._landuse_values = np.array([], dtype=float)
            self._landuse_nodata = None
            self.manning_n_parameter = self._mannings_n
            self._update_land_friction_quantity(np.array([], dtype=float))
            return

        if not cfg:
            self._landuse_values = None
            self._landuse_nodata = None
            self.manning_n_parameter = self._mannings_n
            self._update_land_friction_quantity()
            return

        raster_path = cfg.get('raster') or cfg.get('source')

        if not raster_path:
            self._landuse_values = None
            self._landuse_nodata = None
            self.manning_n_parameter = self._mannings_n
            self._update_land_friction_quantity()
            return

        raster_path = Path(raster_path)
        if not raster_path.is_file():
            raise FileNotFoundError(f"Landuse raster '{raster_path}' not found")

        suffix = raster_path.suffix.lower()

        values, nodata = self._sample_landuse_from_ascii(raster_path, centroids)
        self._landuse_values = values
        self._landuse_nodata = nodata


        mapping_raw = cfg.get('class_to_manning') or cfg.get('classes') or {}
        mapping = {}
        for key, value in mapping_raw.items():
            try:
                normalized_key = str(int(key))
            except (ValueError, TypeError):
                normalized_key = str(key)
            mapping[normalized_key] = float(value)

        default_n = float(cfg.get('default', cfg.get('default_manning_n', self._mannings_n)))
        friction = np.full(len(values), default_n, dtype=float)

        for idx, raw_val in enumerate(values):
            if np.isnan(raw_val):
                continue
            if nodata is not None and not np.isnan(nodata) and np.isclose(raw_val, nodata):
                continue

            candidates = []
            rounded = int(round(raw_val))
            if abs(raw_val - rounded) < 1e-6:
                candidates.append(str(rounded))
            candidates.append(str(raw_val).strip())

            for candidate in candidates:
                if candidate in mapping:
                    friction[idx] = mapping[candidate]
                    break

        self.manning_n_parameter = friction
        self._update_land_friction_quantity(friction)


    def _sample_landuse_from_ascii(self, ascii_path: Path, points: np.ndarray):
        prj_path = ascii_path.with_suffix('.prj')
        if not prj_path.is_file():
            raise FileNotFoundError(
                f"ASCII grid '{ascii_path}' requires accompanying projection file '{prj_path}'."
            )

        with ascii_path.open('r') as asc_file:
            header_lines = [asc_file.readline() for _ in range(6)]
            data = np.loadtxt(asc_file)

        header = {}
        for line in header_lines:
            parts = line.strip().split()
            if len(parts) >= 2:
                header[parts[0].lower()] = parts[1]

        ncols = int(header['ncols'])
        nrows = int(header['nrows'])
        cellsize = float(header['cellsize'])
        nodata = float(header.get('nodata_value', header.get('nodata', np.nan)))

        if 'xllcorner' in header:
            x_origin = float(header['xllcorner'])
        elif 'xllcenter' in header:
            x_origin = float(header['xllcenter']) - 0.5 * cellsize
        else:
            raise ValueError('ASCII header missing xllcorner/xllcenter')

        if 'yllcorner' in header:
            y_origin = float(header['yllcorner'])
        elif 'yllcenter' in header:
            y_origin = float(header['yllcenter']) - 0.5 * cellsize
        else:
            raise ValueError('ASCII header missing yllcorner/yllcenter')

        if data.shape != (nrows, ncols):
            data = data.reshape((nrows, ncols))

        xs = points[:, 0]
        ys = points[:, 1]

        epsilon = 1e-9
        cols = np.floor(((xs - x_origin) / cellsize) + epsilon).astype(int)
        top_edge = y_origin + nrows * cellsize
        rows = np.floor(((top_edge - ys) / cellsize) + epsilon).astype(int)

        within = (
            (cols >= 0) & (cols < ncols) &
            (rows >= 0) & (rows < nrows)
        )

        sampled = np.full(len(points), np.nan, dtype=float)
        if np.any(within):
            sampled[within] = data[rows[within], cols[within]]

        return sampled, nodata

    def _update_land_friction_quantity(self, values: Optional[np.ndarray] = None):
        if values is None:
            friction_values = np.array(self.domain.quantities['friction'].centroid_values, copy=True)
        else:
            friction_values = np.asarray(values, dtype=float)

        if friction_values.size == 0:
            friction_values = friction_values.reshape(-1)

        self.domain.set_quantity('land_friction', friction_values, location='centroids')

    def _infiltration_enabled(self):
        cfg = self._infiltration_cfg
        if not cfg:
            return False
        if isinstance(cfg, bool):
            return cfg
        if not isinstance(cfg, dict):
            return False
        enabled = cfg.get('enabled', cfg.get('toggle', True))
        if isinstance(enabled, str):
            enabled = enabled.lower() not in ('false', 'no', '0', 'off', 'none')
        return bool(enabled)

    def initialize_infiltration_operator(self):
        if not self._infiltration_enabled():
            return None
        if self._infiltration_operator is not None:
            return self._infiltration_operator

        params = self._build_horton_infiltration_parameters()
        from horton_infiltration_operator import HortonInfiltrationOperator
        self._infiltration_operator = HortonInfiltrationOperator(
            self.domain,
            max_rate=params['max_rate'],
            min_rate=params['min_rate'],
            decay=params['decay'],
            drying_time_days=params['drying_time'],
            max_infiltration=params['max_infiltration'],
            min_depth=params['min_depth'],
        )
        return self._infiltration_operator

    def _build_horton_infiltration_parameters(self):
        cfg = self._infiltration_cfg or {}
        method = str(cfg.get('method', 'horton')).lower()
        if method != 'horton':
            raise ValueError("Only Horton infiltration is supported; got '%s'" % method)

        units = cfg.get('units') or {}
        rate_units = cfg.get('rate_units', units.get('rate', 'mm/hr'))
        decay_units = cfg.get('decay_units', units.get('decay', '1/hr'))
        depth_units = cfg.get('depth_units', units.get('depth', 'mm'))

        n = len(self.domain.areas)
        default_cfg = {}
        if isinstance(cfg.get('default'), dict):
            default_cfg.update(cfg['default'])
        for key in (
            'max_rate', 'f0', 'initial_rate',
            'min_rate', 'fmin', 'decay',
            'drying_time', 'drying_time_days',
            'max_infiltration', 'max_capacity',
            'min_depth'
        ):
            if key in cfg:
                default_cfg[key] = cfg[key]

        defaults = self._normalise_horton_params(
            default_cfg, rate_units, decay_units, depth_units)

        arrays = {
            'max_rate': np.full(n, defaults['max_rate'], dtype=float),
            'min_rate': np.full(n, defaults['min_rate'], dtype=float),
            'decay': np.full(n, defaults['decay'], dtype=float),
            'drying_time': np.full(n, defaults['drying_time'], dtype=float),
            'max_infiltration': np.full(n, defaults['max_infiltration'], dtype=float),
            'min_depth': defaults['min_depth'],
        }

        class_cfg = (
            cfg.get('classes')
            or cfg.get('class_to_horton')
            or cfg.get('class_to_infiltration')
            or {}
        )
        if class_cfg:
            landuse_values, nodata = self._get_infiltration_landuse_values(cfg)
            if landuse_values is None:
                raise ValueError(
                    "Horton infiltration classes require a landuse raster. "
                    "Provide infiltration.raster or landuse.raster in the YAML."
                )
            mapping = self._normalise_horton_class_mapping(
                class_cfg, default_cfg, rate_units, decay_units, depth_units)
            strict_classes = bool(cfg.get('strict_classes', False))
            missing_classes = set()

            for idx, raw_value in enumerate(landuse_values):
                if np.isnan(raw_value):
                    continue
                if nodata is not None and not np.isnan(nodata) and np.isclose(raw_value, nodata):
                    continue

                class_params = None
                for candidate in self._landuse_key_candidates(raw_value):
                    if candidate in mapping:
                        class_params = mapping[candidate]
                        break

                if class_params is None:
                    if strict_classes:
                        missing_classes.add(str(raw_value))
                    continue

                for name in ('max_rate', 'min_rate', 'decay',
                             'drying_time', 'max_infiltration'):
                    arrays[name][idx] = class_params[name]

            if missing_classes:
                raise ValueError(
                    'Missing Horton infiltration parameters for landuse classes: '
                    + ', '.join(sorted(missing_classes))
                )

        return arrays

    def _get_infiltration_landuse_values(self, cfg):
        raster_path = cfg.get('raster') or cfg.get('landuse_raster')
        if raster_path:
            raster_path = Path(raster_path)
            if not raster_path.is_file():
                raise FileNotFoundError(
                    "Infiltration landuse raster '%s' not found" % raster_path)
            centroids = self.domain.get_centroid_coordinates(absolute=True)
            return self._sample_landuse_from_ascii(raster_path, centroids)
        return self._landuse_values, self._landuse_nodata

    def _normalise_horton_class_mapping(self, raw_mapping, default_cfg,
                                        rate_units, decay_units, depth_units):
        mapping = {}
        for key, value in raw_mapping.items():
            class_cfg = dict(default_cfg)
            if isinstance(value, dict):
                class_cfg.update(value)
            else:
                class_cfg['max_rate'] = value
            params = self._normalise_horton_params(
                class_cfg, rate_units, decay_units, depth_units)
            try:
                normalized_key = str(int(key))
            except (ValueError, TypeError):
                normalized_key = str(key)
            mapping[normalized_key] = params
        return mapping

    def _normalise_horton_params(self, cfg, rate_units, decay_units, depth_units):
        max_rate = self._get_first(cfg, ('max_rate', 'f0', 'initial_rate'), 0.0)
        min_rate = self._get_first(cfg, ('min_rate', 'fmin'), 0.0)
        decay = self._get_first(cfg, ('decay',), 0.0)
        drying_time = self._get_first(
            cfg, ('drying_time', 'drying_time_days'), 7.0)
        max_infiltration = self._get_first(
            cfg, ('max_infiltration', 'max_capacity'), 0.0)
        min_depth = self._get_first(cfg, ('min_depth',), 1.0e-9)

        return {
            'max_rate': self._convert_rate(max_rate, rate_units),
            'min_rate': self._convert_rate(min_rate, rate_units),
            'decay': self._convert_decay(decay, decay_units),
            'drying_time': float(drying_time),
            'max_infiltration': self._convert_depth(max_infiltration, depth_units),
            'min_depth': self._convert_depth(min_depth, 'm'),
        }

    @staticmethod
    def _get_first(cfg, keys, default):
        for key in keys:
            if key in cfg:
                return cfg[key]
        return default

    @staticmethod
    def _landuse_key_candidates(raw_value):
        candidates = []
        rounded = int(round(raw_value))
        if abs(raw_value - rounded) < 1e-6:
            candidates.append(str(rounded))
        candidates.append(str(raw_value).strip())
        return candidates

    @staticmethod
    def _convert_rate(value, units):
        unit = str(units).lower().replace(' ', '')
        value = float(value)
        if unit in ('m/s', 'mps', 'meter/second', 'meters/second'):
            return value
        if unit in ('mm/hr', 'mm/h', 'mm/hour', 'mmperhour'):
            return value / (1000.0 * 3600.0)
        if unit in ('m/hr', 'm/h', 'm/hour'):
            return value / 3600.0
        if unit in ('in/hr', 'in/h', 'inch/hour', 'inches/hour'):
            return value * 0.0254 / 3600.0
        raise ValueError("Unsupported Horton rate units '%s'" % units)

    @staticmethod
    def _convert_decay(value, units):
        unit = str(units).lower().replace(' ', '')
        value = float(value)
        if unit in ('1/s', 's^-1', 'persecond'):
            return value
        if unit in ('1/hr', '1/h', 'hr^-1', 'h^-1', 'perhour'):
            return value / 3600.0
        raise ValueError("Unsupported Horton decay units '%s'" % units)

    @staticmethod
    def _convert_depth(value, units):
        unit = str(units).lower().replace(' ', '')
        value = float(value)
        if unit in ('m', 'meter', 'meters'):
            return value
        if unit in ('mm', 'millimeter', 'millimeters'):
            return value / 1000.0
        if unit in ('in', 'inch', 'inches'):
            return value * 0.0254
        raise ValueError("Unsupported Horton depth units '%s'" % units)

    def set_elevation_rectangular(self):
    
            if self._elevation_profile == 'shallow linear ramp':
            
                self.domain.set_quantity('elevation', lambda x,y: -x/5.)
            
            if self._elevation_profile == 'steep linear ramp':
            
                self.domain.set_quantity('elevation', lambda x,y: -x/10.)

            if self._elevation_profile == 'tall step down':
            
                z = -x/10
                N = len(x)
                minx = np.floor(np.max(x)/2)
                maxx = np.ceil(np.max(x)/2 + 1)
                stepx = np.min(x[(x >= minx) & (x <= maxx)])
                stepz = 0.6 * (np.max(z) - np.min(z))

                for i in range(N):
                    if x[i] > stepx:
                        z[i] -= stepz
                        
                self.domain.set_quantity('elevation', z)            
            
            if self._elevation_profile == 'short step down':
            
                z = -x/10
                N = len(x)
                minx = np.floor(np.max(x)/2)
                maxx = np.ceil(np.max(x)/2 + 1)
                stepx = np.min(x[(x >= minx) & (x <= maxx)])
                stepz = 0.2 * (np.max(z) - np.min(z))

                for i in range(N):
                    if x[i] > stepx:
                        z[i] -= stepz
                        
                self.domain.set_quantity('elevation', z)
            
            
            if self._elevation_profile == 'step up':
            
                z = -x/10
                N = len(x)
                minx = np.floor(np.max(x)/2)
                maxx = np.ceil(np.max(x)/2 + 1)
                stepx = np.min(x[(x >= minx) & (x <= maxx)])
                stepz = 0.3 * (np.max(z) - np.min(z))

                for i in range(N):
                    if x[i] > stepx:
                        z[i] += stepz
                        
                self.domain.set_quantity('elevation', z)
            
            
            if self._elevation_profile == 'dam':
            
                z = -x/10.
                N = len(x)
                minx = np.floor(np.max(x)/2)
                maxx = np.ceil(np.max(x)/2)
                maxz = 0.9 * np.max(z)
    
                if len(x[(x >= minx) & (x <= maxx)]) < 5:
                    maxx = np.ceil(np.max(x)/2 + 1)
    
                for i in range(N):
                    if minx <= x[i] <= maxx:
                        z[i] = maxz
                        
                self.domain.set_quantity('elevation', z)
                       
                       
            if self._elevation_profile == 'thin cylinder':
                                              
                z = -x/10
                N = len(x)

                minx = np.floor(np.max(x)/2)
                maxx = np.ceil(np.max(x)/2 + 1)
                stepx = np.min(x[(x >= minx) & (x <= maxx)])

                miny = np.floor(np.max(y)/2)
                maxy = np.ceil(np.max(y)/2 + 1)
                stepy = np.min(y[(y >= miny) & (y <= maxy)])

                radius = 0.1 * (np.max(y) - np.min(y))

                for i in range(N):
                    if (x[i]-stepx)**2 + (y[i]-stepy)**2 < radius**2:
                        z[i] = np.max(z)
                        
                self.domain.set_quantity('elevation', z)
            
            
            if self._elevation_profile == 'thick cylinder':
                                              
                z = -x/10
                N = len(x)

                minx = np.floor(np.max(x)/2)
                maxx = np.ceil(np.max(x)/2 + 1)
                stepx = np.min(x[(x >= minx) & (x <= maxx)])

                miny = np.floor(np.max(y)/2)
                maxy = np.ceil(np.max(y)/2 + 1)
                stepy = np.min(y[(y >= miny) & (y <= maxy)])

                radius = 0.4 * (np.max(y) - np.min(y))

                for i in range(N):
                    if (x[i]-stepx)**2 + (y[i]-stepy)**2 < radius**2:
                        z[i] = np.max(z)
                        
                self.domain.set_quantity('elevation', z)
                
                
                
            if self._elevation_profile == 'wing dams':
            
                z = -x/10
                N = len(x)
                minx = np.floor(np.max(x)/2)
                stepx1 = np.min(x[(x >= minx)])
                stepx2 = np.min(x[(x > stepx1)])
                dist = 0.2 * (np.max(y) - np.min(y))
    
                for i in range(N):
                    if stepx1 <= x[i] <= stepx2:
                        if (y[i] < dist) or (y[i] > np.max(y) - dist):
                            z[i] += 1
                            
                self.domain.set_quantity('elevation', z)
                
                
            if self._elevation_profile == 'alternating dykes':
            
                z = -x/10
                N = len(x)

                minx = np.floor(np.max(x)/4)
                stepx1 = np.min(x[(x >= minx)])
                stepx2 = np.min(x[(x > stepx1)])

                minx = np.floor(np.max(x)/2)
                stepx3 = np.min(x[(x >= minx)])
                stepx4 = np.min(x[(x > stepx3)])

                minx = np.floor(3*np.max(x)/4)
                stepx5 = np.min(x[(x >= minx)])
                stepx6 = np.min(x[(x > stepx5)])

                dist = 0.3 * (np.max(y) - np.min(y))

                for i in range(N):
                    if stepx1 <= x[i] <= stepx2:
                        if (y[i] < dist):
                            z[i] += 1
            
                    if stepx3 <= x[i] <= stepx4:
                        if (y[i] > np.max(y) - dist):
                            z[i] += 1
            
                    if stepx5 <= x[i] <= stepx6:
                        if (y[i] < dist):
                            z[i] += 1
                            
                self.domain.set_quantity('elevation', z)
        
        
        
    def set_boundary_conditions(self):
        """
        Set boundary conditions for ANUGA domain
        
        Valid boundaries are (case insensitive):
        - reflective
        - transmissive
        - dirichlet / fixed (must specify stage at this boundary)
        - time (need to specify a lambda function)
        
        TODO:
        - check possible failure modes (how would anuga normally fail if the boundaries
            are not specified correctly?)
        - add defaults if not enough boundaries are specified
        - fail if receives an unknown boundary type
        - accept other inputs for time boundary (file?)
        """
        
        _bdry_conditions = {}
        
        for key, value in self._bdry_conditions.items():

            if isinstance(value, str):
                value = [value]
        
            bdry_type = value[0]
            
            if bdry_type.lower() == 'reflective':
                _bdry_conditions[key] = anuga.Reflective_boundary(self.domain)
                
                
                
            elif bdry_type.lower() == 'transmissive':
                _bdry_conditions[key] = anuga.Transmissive_boundary(self.domain)
                
                
                
            elif bdry_type.lower() in ['dirichlet', 'fixed']:
            
                dirichlet_vals = value[1:]
                
                assert len(value) > 1, ("Need to specify stage of "
                                        "Dirichlet boundary '%s'" % key)
                
                if len(dirichlet_vals) == 1: dirichlet_vals += [0., 0.]
                    
                _bdry_conditions[key] = anuga.Dirichlet_boundary(dirichlet_vals)
                
                
                
            elif bdry_type.lower() == 'time':
            
                assert len(value) > 1, ("Need to specify lambda function for "
                                        "Time boundary '%s'" % key)
                
                _bdry_conditions[key] = anuga.Time_boundary(domain = domain,
                                                                 function = value[1])
                
            else:
            
                raise ValueError("Did not recognize boundary type '%s' "
                               "of boundary '%s'" % (bdry_type, key))
                
        
        self._bdry_conditions = _bdry_conditions        
        self.domain.set_boundary(self._bdry_conditions)
        
        

    def set_other_domain_options(self):
    
        self.domain.set_name(self._output_filename)
        self.domain.set_quantities_to_be_stored(self._stored_quantities)                
        

    @property
    def grid_x(self):
        """x position of centroids"""
        var_values = self.domain.quantities['x'].centroid_values
        return var_values
        
    @property
    def grid_y(self):
        """y position of centroids"""
        var_values = self.domain.quantities['y'].centroid_values
        return var_values
        
    @property
    def grid_z(self):
        """z position of centroids"""
        return self.land_surface__elevation()

    @property
    def time_step(self):
        """The time step."""
        return self._time_step

    @time_step.setter
    def time_step(self, new_dt):
        self._time_step = new_dt

    @property
    def shape(self):
        """Number of grid rows and columns."""
        return self._shape

    @property
    def size(self):
        """Size of grid in meters."""
        return self._size


    ########
    
    @property
    def manning_n_parameter(self):
        """Manning's friction parameter"""
        return self.domain.quantities['friction'].centroid_values
        
    @manning_n_parameter.setter
    def manning_n_parameter(self, new_friction):
        self.domain.set_quantity('friction', new_friction, location='centroids')

    @property
    def land_surface__elevation(self):
        return self.domain.quantities['elevation'].centroid_values
        
    @land_surface__elevation.setter
    def land_surface__elevation(self, new_elev):
        self.domain.set_quantity('elevation', new_elev, location='centroids')

    @property
    def land_surface_water_surface__elevation(self):
        """Temperature values on the grid."""
        return self.domain.quantities['stage'].centroid_values

    @land_surface_water_surface__elevation.setter
    def land_surface_water_surface__elevation(self, new_stage):
    
        self.domain.set_quantity('stage', new_stage, location='centroids')
        
        new_depth = new_stage - self.land_surface__elevation
        self.domain.set_quantity('height', new_depth, location='centroids')
        
        
    @property
    def land_surface_water__depth(self):
        return self.domain.quantities['height'].centroid_values
        
    @land_surface_water__depth.setter
    def land_surface_water__depth(self, new_depth):
    
        self.domain.set_quantity('height', new_depth, location='centroids')
        
        new_stage = self.land_surface__elevation + new_depth
        self.domain.set_quantity('stage', new_stage, location='centroids')
        
        
    @property
    def land_surface_water_flow__x_component_of_momentum(self):
        return self.domain.quantities['xmomentum'].centroid_values
        
    @land_surface_water_flow__x_component_of_momentum.setter
    def land_surface_water_flow__x_component_of_momentum(self, new_mom):
        self.domain.set_quantity('xmomentum', new_mom, location='centroids')
        
    @property
    def land_surface_water_flow__y_component_of_momentum(self):
        return self.domain.quantities['ymomentum'].centroid_values
        
    @land_surface_water_flow__y_component_of_momentum.setter
    def land_surface_water_flow__y_component_of_momentum(self, new_mom):
        self.domain.set_quantity('ymomentum', new_mom, location='centroids')
        
    @property
    def land_surface_water_flow__shear_stress(self):
        return self.domain.quantities['shear_stress'].centroid_values
        
    @land_surface_water_flow__shear_stress.setter
    def land_surface_water_flow__shear_stress(self, new_ss):
        self.domain.set_quantity('shear_stress', new_ss, location='centroids')
    
    @property
    def land_surface_water_sediment_suspended__volume_concentration(self):
        return self.domain.quantities['concentration'].centroid_values
        
    @land_surface_water_sediment_suspended__volume_concentration.setter
    def land_surface_water_sediment_suspended__volume_concentration(self, new_c):
        self.domain.set_quantity('concentration', new_c, location='centroids')
        
    @property
    def land_vegetation__stem_spacing(self):
        return self.domain.quantities['veg_spacing'].centroid_values
        
    @land_vegetation__stem_spacing.setter
    def land_vegetation__stem_spacing(self, new_vs):
    
        if (type(new_vs) == str):
            self.domain.set_quantity('veg_spacing', filename = new_vs)
        else:
            self.domain.set_quantity('veg_spacing', new_vs, location='centroids')
        
    @property
    def land_vegetation__stem_diameter(self):
        return self.domain.quantities['veg_diameter'].centroid_values
        
    @land_vegetation__stem_diameter.setter
    def land_vegetation__stem_diameter(self, new_vd):
    
        if (type(new_vd) == str):
            self.domain.set_quantity('veg_diameter', filename = new_vd)
        else:
            self.domain.set_quantity('veg_diameter', new_vd, location='centroids')

    #########
    
    @property
    def land_surface__initial_elevation(self):
        """Initial surface elevation, for differencing"""
        return self._land_surface__initial_elevation
        
    @land_surface__initial_elevation.setter
    def land_surface__initial_elevation(self, new_init_elev):
        """Able to set the initial land surface elevation to
        set an initial elev for differencing. Use:
            
        land_surface__initial_elevation = land_surface__elevation
        """
        self._land_surface__initial_elevation[:] = new_init_elev
        
        
    def update_elev_difference(self):
        return self.land_surface__elevation - self._land_surface__initial_elevation


    #########    

    def update(self):
        """Evolve."""
        if self.save_timestep != 0 :
            for t in self.domain.evolve(yieldstep=self._time_step, finaltime=self._time, save_time=self.save_timestep):
                print(self.domain.timestepping_statistics())
        else:
            for t in self.domain.evolve(yieldstep = self._time_step, finaltime = self._time):
                print(self.domain.timestepping_statistics())
