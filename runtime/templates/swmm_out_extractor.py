#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Extract SWMM time series from an OUT file into JSON structures.

Dependencies:
    - pandas
    - swmmtoolbox

Example:
    python swmm_out_extractor.py --out-file example.out --output-dir ./ --prefix result
"""

from __future__ import annotations

import argparse
import json
import os
import struct
import sys
from collections import defaultdict
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional, Tuple

import pandas as pd
from swmmtoolbox import swmmtoolbox
from swmmtoolbox.swmmtoolbox import SwmmExtract


def log(message: str) -> None:
    """Write diagnostic output to standard error."""
    print(message, file=sys.stderr)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Extract data from SWMM .out file")
    parser.add_argument("--out-file", required=True, help="Path to SWMM .out file")
    parser.add_argument(
        "--output-dir",
        default=None,
        help="Directory to store output JSON files (default: same directory as out file)",
    )
    parser.add_argument(
        "--prefix",
        default="extracted_data",
        help="Prefix for generated JSON files",
    )
    parser.add_argument(
        "--max-preview",
        type=int,
        default=0,
        help="Optional preview row limit written into summary (0 = no preview)",
    )
    return parser.parse_args()


def datetime_to_string(dt: Any) -> str:
    """Convert a time index value to a stable string representation."""
    if isinstance(dt, pd.Timestamp):
        return dt.to_pydatetime().strftime("%Y-%m-%d %H:%M:%S")
    if isinstance(dt, datetime):
        return dt.strftime("%Y-%m-%d %H:%M:%S")
    return str(dt)


def batch_extract_dataframe(out_file: str, label_list: List[Tuple[str, str, str]]) -> Optional[pd.DataFrame]:
    """Extract multiple variables in one toolbox call."""
    if not label_list:
        return None
    
    try:
        res = swmmtoolbox.extract(out_file, label_list)
        if isinstance(res, pd.DataFrame):
            return res
        return None
    except Exception as exc:  # pylint: disable=broad-except
        log(f"[WARN] Batch extraction failed for {len(label_list)} variables: {exc}")
        return None


def convert_value(value: Any) -> Any:
    """Convert pandas and NumPy values to JSON-compatible values."""
    if pd.isna(value):  # type: ignore[arg-type]
        return None
    if hasattr(value, "item"):
        try:
            value = value.item()
        except Exception:  # pylint: disable=broad-except
            pass
    if isinstance(value, (int, float)):
        return float(value)
    try:
        return float(value)
    except (TypeError, ValueError):
        return str(value)


def extract_catalog_structure(catalog: List[Tuple[str, str, str]]) -> Dict[str, Dict[str, List[str]]]:
    """Build an object-variable hierarchy from the toolbox catalog."""
    object_vars: Dict[str, Dict[str, List[str]]] = defaultdict(lambda: defaultdict(list))
    for obj_type, obj_id, var_name in catalog:
        object_vars[obj_type][obj_id].append(var_name)
    return object_vars


def extract_data(out_file: str, object_vars: Dict[str, Dict[str, List[str]]]):
    """Return time-indexed and object-indexed result structures."""
    return extract_data_direct(out_file)


def swmm_datetime_to_string(date_value: float) -> str:
    """Convert a SWMM floating-point date to a timestamp string."""
    begindate = datetime(1899, 12, 30)
    days = int(date_value)
    seconds = int((date_value - days) * 86400)
    extra = seconds % 10
    if extra == 1:
        seconds -= 1
    elif extra == 9:
        seconds += 1
    return (begindate + timedelta(days=days, seconds=seconds)).strftime("%Y-%m-%d %H:%M:%S")


def extract_data_direct(out_file: str):
    """Read result blocks directly to avoid repeated per-series scans."""
    obj = SwmmExtract(out_file)
    time_based_data: Dict[str, Dict[str, Dict[str, Any]]] = {}
    object_based_data: Dict[str, Dict[str, Dict[str, Any]]] = {}

    item_layout = [
        (0, "subcatchment", obj.swmm_nsubcatch, obj.swmm_nsubcatchvars),
        (1, "node", obj.swmm_nnodes, obj.nnodevars),
        (2, "link", obj.swmm_nlinks, obj.nlinkvars),
        (4, "system", 1, obj.nsystemvars),
    ]
    value_count = (
        obj.swmm_nsubcatch * obj.swmm_nsubcatchvars
        + obj.swmm_nnodes * obj.nnodevars
        + obj.swmm_nlinks * obj.nlinkvars
        + obj.nsystemvars
    )
    value_format = f"{value_count}f"

    log(f"[INFO] Reading {obj.swmm_nperiods} periods and {value_count} variables")

    for period in range(obj.swmm_nperiods):
        date_offset = obj.startpos + period * obj.bytesperperiod
        obj.fpb.seek(date_offset, 0)
        date_value = struct.unpack("d", obj.fpb.read(8))[0]
        time_str = swmm_datetime_to_string(date_value)
        values = struct.unpack(value_format, obj.fpb.read(value_count * obj.record_size))
        time_based_data[time_str] = {}

        cursor = 0
        for item_type, item_name, object_count, var_count in item_layout:
            var_indices = obj.vars[item_type]
            for object_index in range(object_count):
                if item_type == 4:
                    obj_id = "system"
                else:
                    obj_id = obj.names[item_type][object_index]

                if obj_id not in object_based_data:
                    object_based_data[obj_id] = {}
                object_based_data[obj_id][time_str] = {}
                time_based_data[time_str][obj_id] = {}

                for var_offset in range(var_count):
                    value = convert_value(values[cursor + var_offset])
                    if item_type == 4:
                        var_name = obj.varcode[item_type][var_indices[var_offset]]
                    else:
                        var_name = obj.varcode[item_type][var_indices[var_offset]]
                    time_based_data[time_str][obj_id][var_name] = value
                    object_based_data[obj_id][time_str][var_name] = value

                cursor += var_count

        if (period + 1) % 10 == 0 or period + 1 == obj.swmm_nperiods:
            log(f"[INFO] Read period {period + 1}/{obj.swmm_nperiods}")

    try:
        obj.fpb.close()
    except Exception:  # pylint: disable=broad-except
        pass

    log("[INFO] Result extraction completed")

    return time_based_data, object_based_data


def extract_data_via_toolbox(out_file: str, object_vars: Dict[str, Dict[str, List[str]]]):
    """Fallback extraction path based on swmmtoolbox.extract."""
    time_based_data: Dict[str, Dict[str, Dict[str, Any]]] = {}
    object_based_data: Dict[str, Dict[str, Dict[str, Any]]] = {}

    all_labels: List[Tuple[str, str, str]] = []
    label_to_obj: Dict[int, Tuple[str, str, str]] = {}
    
    idx = 0
    for obj_type, objects in object_vars.items():
        for obj_id, var_names in objects.items():
            for var_name in var_names:
                all_labels.append((obj_type, obj_id, var_name))
                label_to_obj[idx] = (obj_type, obj_id, var_name)
                idx += 1
    
    total_series = len(all_labels)
    log(f"[INFO] Extracting {total_series} series")
    
    df_all = batch_extract_dataframe(out_file, all_labels)
    
    if df_all is None or df_all.empty:
        log("[ERROR] Batch extraction returned no data")
        return time_based_data, object_based_data
    
    log(f"[INFO] Extracted data frame shape: {df_all.shape}")
    log(f"[INFO] Periods: {len(df_all)}, variables: {len(df_all.columns)}")
    
    for col_idx, column in enumerate(df_all.columns):
        if col_idx >= len(label_to_obj):
            continue
            
        obj_type, obj_id, var_name = label_to_obj[col_idx]
        
        if obj_id not in object_based_data:
            object_based_data[obj_id] = {}
        
        for timestamp, value in df_all[column].items():
            time_str = datetime_to_string(timestamp)
            value = convert_value(value)
            
            if time_str not in time_based_data:
                time_based_data[time_str] = {}
            if obj_id not in time_based_data[time_str]:
                time_based_data[time_str][obj_id] = {}
            time_based_data[time_str][obj_id][var_name] = value
            
            if time_str not in object_based_data[obj_id]:
                object_based_data[obj_id][time_str] = {}
            object_based_data[obj_id][time_str][var_name] = value
        
        if (col_idx + 1) % 25 == 0:
            progress_pct = ((col_idx + 1) / total_series) * 100
            log(f"[INFO] Processed {col_idx + 1}/{total_series} series ({progress_pct:.1f}%)")
    
    log("[INFO] Result extraction completed")

    return time_based_data, object_based_data


def compute_summary(
    time_based_data: Dict[str, Dict[str, Dict[str, Any]]],
    object_based_data: Dict[str, Dict[str, Dict[str, Any]]],
    time_based_json: str,
    object_based_json: str,
    max_preview: int,
) -> Dict[str, Any]:
    """Build a compact extraction summary."""
    obj_type_count: Dict[str, int] = {}
    for obj_id in object_based_data.keys():
        obj_type = obj_id.split('_')[0] if '_' in obj_id else 'unknown'
        obj_type_count[obj_type] = obj_type_count.get(obj_type, 0) + 1

    summary: Dict[str, Any] = {
        "status": "success",
        "time_based_json": time_based_json,
        "object_based_json": object_based_json,
        "time_point_count": len(time_based_data),
        "total_objects": len(object_based_data),
        "object_type_counts": obj_type_count,
    }

    if max_preview > 0:
        summary["time_based_preview"] = take_preview(time_based_data, max_preview)
        summary["object_based_preview"] = take_preview(object_based_data, max_preview)

    return summary


def take_preview(data: Any, limit: int) -> Any:
    """Take a bounded preview from a nested mapping."""
    if isinstance(data, dict):
        result: Dict[str, Any] = {}
        for idx, (key, value) in enumerate(data.items()):
            if idx >= limit:
                break
            result[key] = take_preview(value, limit)
        return result
    return data


def ensure_output_dir(path: Optional[str], out_file: str) -> str:
    if path:
        os.makedirs(path, exist_ok=True)
        return path
    default_dir = os.path.dirname(os.path.abspath(out_file))
    os.makedirs(default_dir, exist_ok=True)
    return default_dir


def main() -> int:
    args = parse_args()
    out_file = os.path.abspath(args.out_file)
    if not os.path.exists(out_file):
        print(json.dumps({"status": "error", "message": "OUT file does not exist"}))
        return 1

    output_dir = ensure_output_dir(args.output_dir, out_file)
    prefix = args.prefix or "extracted_data"

    time_based_json = os.path.join(output_dir, f"{prefix}_time_based.json")
    object_based_json = os.path.join(output_dir, f"{prefix}_object_based.json")

    log(f"[INFO] Reading SWMM output: {out_file}")
    catalog = swmmtoolbox.catalog(out_file)
    log(f"[INFO] Catalog entries: {len(catalog)}")

    object_vars = extract_catalog_structure(catalog)
    time_based_data, object_based_data = extract_data(out_file, object_vars)

    log("[INFO] Writing time-indexed JSON")
    with open(time_based_json, "w", encoding="utf-8") as f_time:
        json.dump(time_based_data, f_time, ensure_ascii=False, indent=2, separators=(',', ': '))

    log("[INFO] Writing object-indexed JSON")
    with open(object_based_json, "w", encoding="utf-8") as f_obj:
        json.dump(object_based_data, f_obj, ensure_ascii=False, indent=2, separators=(',', ': '))

    summary = compute_summary(
        time_based_data,
        object_based_data,
        time_based_json,
        object_based_json,
        args.max_preview,
    )

    print(json.dumps(summary, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
