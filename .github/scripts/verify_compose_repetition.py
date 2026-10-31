#!/usr/bin/env python3
"""Strict inventory for the three API36 Compose diagnosis methods per invocation.

Focused diagnostic; never substitutes for the required 270-case CI acceptance gate.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

EXPECTED = {
    "com.artflow.studio.RuntimeEnvironmentTest#processPageSizeMatchesRequestedTestEnvironment",
    "com.artflow.studio.BrushAttributeUiTest#studioRetainsTheChosenGroupAcrossTabsAndPublishesOnlyOnUse",
    "com.artflow.studio.ArtworkWorkflowTest#createPaintSaveReopenRecreateAndDiscardPreserveTheSavedPixels",
}


def check(results: Path) -> dict:
    cases: dict[str, dict] = {}
    files = sorted(results.rglob("TEST-*.xml")) if results.is_dir() else []
    for path in files:
        root = ET.parse(path).getroot()
        for case in root.iter("testcase"):
            identity = f"{case.get('classname')}#{case.get('name')}"
            if identity in cases:
                raise ValueError(f"Duplicate testcase {identity} from {path}")
            problems = [
                (kind, (node.get("message") or node.text or "").strip()[:600])
                for kind in ("failure", "error", "skipped")
                for node in case.findall(kind)
            ]
            cases[identity] = {"problems": problems, "file": str(path)}
    seen = set(cases)
    missing = sorted(EXPECTED - seen)
    unexpected = sorted(seen - EXPECTED)
    failed = {name: case["problems"] for name, case in cases.items() if case["problems"]}
    report = {
        "result_files": len(files),
        "expected_count": len(EXPECTED),
        "observed_count": len(cases),
        "missing": missing,
        "unexpected": unexpected,
        "failed_or_skipped": failed,
        "pass": bool(files) and not (missing or unexpected or failed),
    }
    return report


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--results", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        summary = check(args.results)
    except (OSError, ET.ParseError, ValueError) as exc:
        summary = {"pass": False, "parse_error": str(exc)}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")
    print(json.dumps(summary, sort_keys=True))
    return 0 if summary["pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
