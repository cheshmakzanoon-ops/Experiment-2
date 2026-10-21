#!/usr/bin/env python3
"""Classify settings keys in the hash-pinned REA IPA brush corpus.

Read-only schema inventory. No native code, artwork, or complete settings
dictionaries are copied into the output.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import json
import math
from pathlib import Path
import re

EXPECTED_SHA = "7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e"
VALID_KEY = re.compile(r"[A-Za-z][A-Za-z0-9]{0,95}\Z")


def kind_of(name: str, value: object) -> str:
    if isinstance(value, bool):
        return "boolean"
    if type(value) is int:
        return "integer"
    if type(value) is float:
        if not math.isfinite(value):
            raise ValueError("Non-finite scalar in reference evidence")
        return "real"
    if type(value) is str:
        return "text"
    if isinstance(value, dict):
        if "points" in value:
            return "curve-object"
        if set(value) == {"bytes", "sha256"}:
            if not isinstance(value["bytes"], int) or value["bytes"] < 0:
                raise ValueError("Invalid size of summarized data field")
            if not isinstance(value["sha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", value["sha256"]):
                raise ValueError("Invalid data digest")
            return "binary-data"
        return "structured-object"
    raise ValueError(f"Unexpected setting value type: {type(value).__name__}")


def inspect(corpus: dict, expected=(394, 534)) -> dict:
    if corpus.get("reference_sha256") != EXPECTED_SHA:
        raise ValueError("Unexpected reference source hash")
    brushes = corpus.get("brushes")
    if not isinstance(brushes, list) or len(brushes) != expected[0]:
        raise ValueError("Unexpected logical brush count")
    used_paths: set[str] = set()
    by_field: dict[str, Counter] = defaultdict(Counter)
    categories = Counter()
    field_counts = Counter()
    primitive_counts = Counter()
    components = 0
    compounds = 0
    largest_text = 0
    overlong_text = 0
    max_binary_bytes = 0

    for brush in brushes:
        path = brush.get("path")
        if not isinstance(path, str) or not path.endswith(".brush") or path in used_paths:
            raise ValueError("Invalid or duplicated brush container path")
        used_paths.add(path)
        archives = brush.get("archives")
        if not isinstance(archives, list) or [a.get("path") for a in archives] not in (
            ["Brush.archive"], ["Brush.archive", "Sub01/Brush.archive"]
        ):
            raise ValueError("Invalid primary/secondary brush grouping")
        compounds += len(archives) == 2
        for archive in archives:
            components += 1
            root = archive.get("top", {}).get("root")
            if not isinstance(root, dict):
                raise ValueError("Invalid keyed settings root")
            field_counts[len(root)] += 1
            primitives = 0
            for key, value in root.items():
                if not isinstance(key, str) or not VALID_KEY.fullmatch(key):
                    raise ValueError("Invalid source settings field name")
                kind = kind_of(key, value)
                by_field[key][kind] += 1
                categories[kind] += 1
                if kind in ("boolean", "integer", "real", "text"):
                    primitives += 1
                    if kind == "text":
                        largest_text = max(largest_text, len(value))
                        overlong_text += len(value) > 256
                if kind == "binary-data":
                    max_binary_bytes = max(max_binary_bytes, value["bytes"])
            primitive_counts[primitives] += 1
    if components != expected[1]:
        raise ValueError("Unexpected settings component count")
    if any(len(types) != 1 for types in by_field.values()):
        raise ValueError("One setting key has inconsistent source value types")
    distinct = Counter(next(iter(types)) for types in by_field.values())
    return {
        "source_sha256": EXPECTED_SHA,
        "method": "REA/plistlib source schema inspection, not runtime behavior",
        "logical_brushes": len(brushes),
        "compound_brushes": compounds,
        "settings_components": components,
        "distinct_fields": len(by_field),
        "distinct_by_kind": dict(sorted(distinct.items())),
        "occurrences_by_kind": dict(sorted(categories.items())),
        "field_counts": {"min": min(field_counts), "max": max(field_counts)},
        "primitive_fields_per_component": {"min": min(primitive_counts), "max": max(primitive_counts)},
        "largest_text_length": largest_text,
        "noncapturable_text_values": overlong_text,
        "largest_binary_field_bytes": max_binary_bytes,
        "structured_field_names": sorted(k for k, v in by_field.items() if "structured-object" in v),
        "binary_field_names": sorted(k for k, v in by_field.items() if "binary-data" in v),
        "limitations": [
            "Schema preservation is not binary-identical NSKeyedArchiver output.",
            "Edited ArtFlow settings can replace original values with approximations.",
            "No native application logic or reference GPU pixel output was recovered.",
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpus", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    result = inspect(json.loads(args.corpus.read_text(encoding="utf-8")))
    args.out.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({k: result[k] for k in (
        "settings_components", "distinct_fields", "distinct_by_kind", "primitive_fields_per_component"
    )}, sort_keys=True))


if __name__ == "__main__":
    main()
