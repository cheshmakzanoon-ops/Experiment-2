#!/usr/bin/env python3
"""Require every declared device test to run and publish exact-revision emulator evidence.

The source inventory deliberately supports the repository's plain JUnit4 Kotlin tests. It
fails on unrecognised declarations instead of quietly lowering the expected test count.
Emulator correctness and CPU timings do not establish reference-app or physical-pen parity.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path


TEST_METHOD = re.compile(r"@Test\s+fun\s+([A-Za-z_]\w*)\s*\(")
API_LIMITED_TESTS = {
    "com.artflow.studio.CanvasInputDeviceTest#cancelledPointerFlagsRejectPalmWithoutRejectingPen": (
        33, "Android reports FLAG_CANCELED from API 33",
    ),
    "com.artflow.studio.data.ExportDeviceTest#galleryPublishesPngAsImageAndMp4AsVideo": (
        29, "Scoped MediaStore publication requires API 29",
    ),
}


def source_inventory(root: Path) -> set[str]:
    expected: set[str] = set()
    for path in sorted(root.rglob("*.kt")):
        # Test declarations are code, not examples in comments. Our tests have one top-level
        # class per file, no inherited or parameterised @Test methods, and no escaped names.
        code = re.sub(r"/\*.*?\*/|//[^\n]*", "", path.read_text(), flags=re.S)
        if re.search(r"(?m)^import\s+org\.junit\.Test\s+as\b", code):
            raise ValueError(f"Unsupported aliased test annotation in {path}; update the inventory parser")
        count = len(re.findall(r"@(?:[A-Za-z_]\w*\.)*Test\b", code))
        if not count:
            continue
        names = TEST_METHOD.findall(code)
        package = re.search(r"(?m)^package\s+([\w.]+)\s*$", code)
        classes = re.findall(r"(?m)^class\s+([A-Za-z_]\w*)\b", code)
        if count != len(names) or package is None or len(classes) != 1:
            raise ValueError(f"Unsupported test declarations in {path}; update the inventory parser")
        for name in names:
            identity = f"{package.group(1)}.{classes[0]}#{name}"
            if identity in expected:
                raise ValueError(f"Duplicate source test: {identity}")
            expected.add(identity)
    if not expected:
        raise ValueError("No device tests found in the source inventory")
    return expected


def read_results(root: Path) -> dict[str, str]:
    results: dict[str, str] = {}
    reports = sorted(root.rglob("TEST-*.xml"))
    if not reports:
        raise ValueError("No connected-device JUnit XML reports found")
    for path in reports:
        document = ET.parse(path).getroot()
        if document.tag not in ("testsuite", "testsuites"):
            raise ValueError(f"Unsupported JUnit report root in {path}")
        if document.find("failure") is not None or document.find("error") is not None:
            raise ValueError(f"Suite-level runner failure in {path}")
        suites = [document] if document.tag == "testsuite" else list(document.iter("testsuite"))
        for suite in suites:
            if suite.find("failure") is not None or suite.find("error") is not None:
                raise ValueError(f"Suite-level runner failure in {path}")
            cases = suite.findall("testcase")
            counts: Counter[str] = Counter()
            for case in cases:
                classname, name = case.get("classname"), case.get("name")
                if not classname or not name:
                    raise ValueError(f"Unnamed test case in {path}")
                identity = f"{classname}#{name}"
                if identity in results:
                    raise ValueError(f"Duplicate result for {identity}; stale or repeated reports")
                states = [state for state in ("failure", "error", "skipped") if case.find(state) is not None]
                if len(states) > 1:
                    raise ValueError(f"Conflicting result states for {identity}")
                state = states[0] if states else "passed"
                results[identity] = state
                counts[state] += 1
            for field, actual in (("tests", len(cases)), ("failures", counts["failure"]),
                                  ("errors", counts["error"]), ("skipped", counts["skipped"])):
                if field in suite.attrib and int(suite.attrib[field]) != actual:
                    raise ValueError(f"JUnit {field} count disagrees with test cases in {path}")
    if not results:
        raise ValueError("JUnit XML contains no executed test cases")
    return results


def read_runtime(root: Path) -> dict[str, str]:
    files = sorted(root.rglob("runtime-environment.txt"))
    if len(files) != 1:
        raise ValueError(f"Expected one measured runtime report, found {len(files)}")
    values = dict(line.split("=", 1) for line in files[0].read_text().splitlines() if "=" in line)
    if not all(values.get(key) for key in ("sdk", "pageSize", "abis", "fingerprint")):
        raise ValueError("Incomplete measured runtime report")
    return values


def evaluate(expected: set[str], results: dict[str, str], api: int) -> tuple[list[str], list[dict[str, str]]]:
    errors = [f"Missing test: {case}" for case in sorted(expected - results.keys())]
    errors += [f"Unexpected test (wrong source/report): {case}" for case in sorted(results.keys() - expected)]
    skips = []
    for case, state in sorted(results.items()):
        if state in ("failure", "error"):
            errors.append(f"{state.upper()}: {case}")
        elif state == "skipped":
            minimum, reason = API_LIMITED_TESTS.get(case, (0, "No declared API limitation"))
            skips.append({"test": case, "reason": reason})
            if api >= minimum:
                errors.append(f"Unexpected skip on API {api}: {case}")
    return errors, skips


def markdown(report: dict) -> str:
    counts = report["counts"]
    runtime = report.get("runtime", {})
    lines = [
        f"## Emulator verification — API {report['expectedApi']} / {report['expectedPageSize']} byte pages",
        "", f"Revision: `{report['revision']}`", "",
        f"**{'PASS' if not report['problems'] else 'FAIL'}** — {counts.get('passed', 0)} passed, "
        f"{counts.get('failure', 0)} failures, {counts.get('error', 0)} errors, "
        f"{counts.get('skipped', 0)} API-limited skips; {report['expectedTests']} declared tests.",
        "", f"Measured runtime: API {runtime.get('sdk', '?')}, {runtime.get('pageSize', '?')} byte pages, "
        f"{runtime.get('abis', '?')}. Minified launch: {'passed' if report['minifiedLaunch'] else 'not verified'}.",
        "", "| Test class | Passed | Skipped | Failed/error |", "| --- | ---: | ---: | ---: |",
    ]
    for name, values in sorted(report["classes"].items()):
        lines.append(f"| {name.removeprefix('com.artflow.studio.')} | {values.get('passed', 0)} | "
                     f"{values.get('skipped', 0)} | {values.get('failure', 0) + values.get('error', 0)} |")
    for skip in report["skips"]:
        lines += ["", f"Skip: `{skip['test']}` — {skip['reason']}."]
    for problem in report["problems"]:
        lines += ["", f"- {problem}"]
    lines += ["", "These checks verify this Android implementation. They do not establish Procreate pixel "
              "equivalence, physical stylus latency, or artist acceptance.", ""]
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--results", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--launch", type=Path, required=True)
    parser.add_argument("--api", type=int, required=True)
    parser.add_argument("--page-size", type=int, choices=(4096, 16384), required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = {"schemaVersion": 1, "revision": args.revision, "expectedApi": args.api,
              "expectedPageSize": args.page_size, "expectedTests": 0, "counts": {}, "classes": {},
              "skips": [], "problems": [], "minifiedLaunch": False}
    try:
        if not re.fullmatch(r"[0-9a-f]{40}", args.revision):
            raise ValueError("A full Git commit SHA is required")
        expected = source_inventory(args.source)
        report["expectedTests"] = len(expected)
        results = read_results(args.results)
        report["counts"] = dict(Counter(results.values()))
        report["problems"], report["skips"] = evaluate(expected, results, args.api)
        classes: dict[str, Counter] = {}
        for case, state in results.items():
            classes.setdefault(case.split("#")[0], Counter())[state] += 1
        report["classes"] = {name: dict(counts) for name, counts in classes.items()}
        runtime = read_runtime(args.evidence)
        report["runtime"] = runtime
        if int(runtime["sdk"]) != args.api or int(runtime["pageSize"]) != args.page_size:
            report["problems"].append("Measured Android API/page size differs from the requested lane")
        report["minifiedLaunch"] = "Minified release-equivalent launch: PASS" in args.launch.read_text().splitlines()
        if not report["minifiedLaunch"]:
            report["problems"].append("No successful minified launch evidence")
    except (OSError, ValueError, ET.ParseError) as error:
        report["problems"].append(str(error))
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "verification.json").write_text(json.dumps(report, indent=2) + "\n")
    (args.output / "verification.md").write_text(markdown(report))
    print(markdown(report))
    return int(bool(report["problems"]))


if __name__ == "__main__":
    sys.exit(main())
