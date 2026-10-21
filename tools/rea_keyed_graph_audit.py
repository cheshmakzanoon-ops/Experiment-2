#!/usr/bin/env python3
"""Verify REA's keyed-archive object graphs against the hash-pinned brush corpus.

Accepts REA 6.7.0 inspect-keyed-archive JSON evidence. It reads graphs only:
no native classes are instantiated and no proprietary data is redistributed.
"""
from __future__ import annotations

import argparse
from collections import Counter
import json
import math
from pathlib import Path
import re

EXPECTED_IPA = "7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e"
POINT = re.compile(r"^\{\s*([-+0-9.eE]+)\s*,\s*([-+0-9.eE]+)\s*\}$")
CLASSES = (
    "SilicaBrush", "NSArray", "NSMutableArray", "ValkyrieBrushRollParameters",
    "ValkyrieMagnitudinalCurve", "NSDate",
)
ROLL = ("activationFalloff", "activationOrigin", "mode", "activationRange")


def uid(value: object, count: int) -> int:
    if not isinstance(value, dict) or set(value) != {"UID"}:
        raise ValueError("Expected REA UID reference")
    number = value["UID"]
    if type(number) is not int or number < 0 or number >= count:
        raise ValueError("Invalid reference index")
    return number


def verified_samples(corpus: dict, evidence: list[dict]) -> dict:
    if corpus.get("reference_sha256") != EXPECTED_IPA:
        raise ValueError("Wrong original IPA SHA-256")
    brushes = corpus.get("brushes")
    if not isinstance(brushes, list) or len(brushes) != 394:
        raise ValueError("Expected exactly 394 logical brush containers")
    components = {}
    for brush in brushes:
        for item in brush.get("archives", []):
            sha = item.get("sha256")
            if not isinstance(sha, str) or not re.fullmatch(r"[0-9a-f]{64}", sha) or sha in components:
                raise ValueError("Invalid or duplicate brush archive identity")
            components[sha] = item["path"]
    if len(components) != 534:
        raise ValueError("Expected 534 settings archives")

    report = {"source_sha256": EXPECTED_IPA, "provider": "REA 6.7.0 inspect-keyed-archive",
              "samples": [], "totals": {}, "limitations": [
                  "These classes are serialized object descriptions, not recovered native implementation.",
                  "Graph analysis cannot infer source algorithms, decryption, or pixel-accurate drawing.",
                  "Only the supplied sampled Brush.archive objects, not all 534 archives, have REA object graphs.",
              ]}
    totals = Counter()
    checked = set()
    for sample in evidence:
        normalized = sample.get("normalized_result") or {}
        digest = normalized.get("target_sha256")
        subject = sample.get("subject") or {}
        if digest not in components or digest != (subject.get("digest") or {}).get("sha256"):
            raise ValueError("REA evidence digest does not match any pinned settings archive")
        if digest in checked:
            raise ValueError("Repeated REA graph sample")
        checked.add(digest)
        objects = normalized.get("objects")
        if not isinstance(objects, list) or not 1 <= len(objects) <= 5000:
            raise ValueError("Malformed REA object table")
        index = {}
        for item in objects:
            if type(item) is not dict or type(item.get("id")) is not int or item["id"] in index:
                raise ValueError("Malformed or duplicate object id")
            index[item["id"]] = item
        if set(index) != set(range(len(objects))):
            raise ValueError("Object id table is not complete")
        root = uid((normalized.get("roots") or {}).get("root"), len(index))
        if index[root].get("class_name") != "SilicaBrush":
            raise ValueError("Root is not a serialized SilicaBrush")
        distribution = Counter(item.get("class_name") for item in objects if item.get("class_name"))
        unexpected = set(distribution) - set(CLASSES)
        if unexpected:
            raise ValueError(f"Unrecognized native archive classes: {sorted(unexpected)}")
        if distribution["SilicaBrush"] != 1:
            raise ValueError("Unexpected root brush count")
        point_count = 0
        for item in objects:
            value = item.get("value")
            if not isinstance(value, dict):
                continue
            if item.get("class_name") == "ValkyrieMagnitudinalCurve":
                target_id = uid(value.get("points"), len(index))
                array = index[target_id]
                if array.get("class_name") != "NSMutableArray":
                    raise ValueError("Curve points do not refer to NSMutableArray")
                entries = (array.get("value") or {}).get("NS.objects")
                if not isinstance(entries, list) or not 2 <= len(entries) <= 16:
                    raise ValueError("Invalid curve point count")
                for entry in entries:
                    point_id = uid(entry, len(index))
                    point = index[point_id].get("value")
                    parsed = POINT.fullmatch(point) if isinstance(point, str) else None
                    if not parsed:
                        raise ValueError("REA curve control point is not a coordinate string")
                    if not all(math.isfinite(float(v)) and 0 <= float(v) <= 1 for v in parsed.groups()):
                        raise ValueError("REA point outside the normalized coordinate domain")
                point_count += len(entries)
            if item.get("class_name") == "ValkyrieBrushRollParameters":
                if not all(k in value for k in ROLL):
                    raise ValueError("Incomplete roll-parameter object")
                if not all(type(value[k]) in (int, float) and math.isfinite(value[k]) for k in ROLL):
                    raise ValueError("Non-finite roll-parameter fields")
            if item.get("class_name") == "NSDate":
                if not isinstance(value.get("NS.time"), (int, float)) or not math.isfinite(value["NS.time"]):
                    raise ValueError("Invalid archived creation date")
        if distribution["ValkyrieMagnitudinalCurve"] != distribution["NSMutableArray"]:
            raise ValueError("Mismatch between curves and mutable point arrays")
        summary = {
            "archive_sha256": digest,
            "component": components[digest],
            "total_objects": len(objects),
            "classes": dict(sorted(distribution.items())),
            "curve_control_points": point_count,
            "graph_verified": True,
        }
        report["samples"].append(summary)
        totals.update(distribution)
        totals["all_objects"] += len(objects)
        totals["curve_control_points"] += point_count
    report["totals"] = dict(sorted(totals.items()))
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpus", type=Path)
    parser.add_argument("rea", type=Path, nargs="+")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    report = verified_samples(
        json.loads(args.corpus.read_text(encoding="utf-8")),
        [json.loads(path.read_text(encoding="utf-8")) for path in args.rea],
    )
    args.out.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(report["totals"], sort_keys=True))


if __name__ == "__main__":
    main()
