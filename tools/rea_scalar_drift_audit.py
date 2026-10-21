#!/usr/bin/env python3
"""Quantify settings at risk of changing under ArtFlow's approximate brush import/export.

No native drawing behavior is inferred. Source references are retained only as
aggregated counts, never proprietary brush contents or resource bytes.
"""
from __future__ import annotations

import argparse
from collections import Counter
import json
import math
from pathlib import Path

PINNED_SHA = "7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e"


def clamp(value: float, floor: float, ceiling: float) -> float:
    return max(floor, min(value, ceiling))


def different(a: float, b: float) -> bool:
    return not math.isclose(a, b, rel_tol=1e-6, abs_tol=1e-6)


def vulnerable_fields(root: dict) -> set[str]:
    result = set()
    def compare(name: str, transform):
        value = root.get(name)
        if type(value) not in (float, int) or not math.isfinite(value):
            return
        if different(float(value), float(transform(value))):
            result.add(name)

    compare("plotSpacing", lambda v: clamp(v, 0.01, 1))
    compare("textureScale", lambda v: clamp(v, 0.25, 8))
    compare("maxSize", lambda v: clamp(v, 0.05, 8))
    compare("paintSize", lambda v: clamp(v, 0.05, 1))
    compare("shapeCount", lambda v: math.floor(clamp(v, 0, 1) * 15 + 0.5) / 15)
    compare("textureMovement", lambda v: 1 if clamp(v, 0, 1) >= 0.5 else 0)
    return result


def audit(corpus: dict, expected=(394, 534)) -> dict:
    if corpus.get("reference_sha256") != PINNED_SHA:
        raise ValueError("Wrong original IPA SHA-256")
    brushes = corpus.get("brushes")
    if not isinstance(brushes, list) or len(brushes) != expected[0]:
        raise ValueError("Unexpected logical brush count")
    components = 0
    per_field = Counter()
    histogram = Counter()
    affected = 0
    seen = set()
    for brush in brushes:
        path = brush.get("path")
        if not isinstance(path, str) or path in seen:
            raise ValueError("Repeated brush path")
        seen.add(path)
        archives = brush.get("archives")
        if not isinstance(archives, list) or [a.get("path") for a in archives] not in (
            ["Brush.archive"], ["Brush.archive", "Sub01/Brush.archive"]
        ):
            raise ValueError("Invalid primary/secondary grouping")
        for part in archives:
            components += 1
            root = part.get("top", {}).get("root")
            if not isinstance(root, dict):
                raise ValueError("Missing settings root")
            risky = vulnerable_fields(root)
            per_field.update(risky)
            histogram[len(risky)] += 1
            affected += bool(risky)
    if components != expected[1]:
        raise ValueError("Unexpected brush settings component count")
    return {
        "source_sha256": PINNED_SHA,
        "method": "Static comparison of serialized values to ArtFlow's approximate scalar mappings",
        "logical_brushes": len(brushes),
        "settings_components": components,
        "components_with_at_least_one_drift_risk": affected,
        "individual_field_occurrences_at_risk": dict(sorted(per_field.items())),
        "number_of_risky_fields_per_component": dict(sorted((str(k), v) for k, v in histogram.items())),
        "limitations": [
            "A drift risk is not an observed visual rendering failure.",
            "Fields are counted per component, not per user-visible brush.",
            "ArtFlow's current source-aware writer can keep original values until their mapped controls change.",
            "Reference-native field units, special zero values and drawing behavior remain unverified.",
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpus", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    report = audit(json.loads(args.corpus.read_text(encoding="utf-8")))
    args.out.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({
        "components": report["settings_components"],
        "at_risk": report["components_with_at_least_one_drift_risk"],
        "field_counts": report["individual_field_occurrences_at_risk"],
    }, sort_keys=True))


if __name__ == "__main__":
    main()
