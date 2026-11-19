#!/usr/bin/env python3
"""Account for every selected optimized instrumentation case and its installed APK bytes.

This is scoped emulator qualification of a debug-key-signed, release-optimized test artifact.
It does not qualify a publisher key, physical-device performance, or the entire debug matrix.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import struct
import xml.etree.ElementTree as ET

from collect_installed_apks import inspect_apk, snapshot_file, snapshot_namespaces
from summarize_device_results import read_results, read_runtime, source_inventory


CLASSES = {
    "com.artflow.studio.OptimizedBuildContractTest": "Installed variant and non-debuggable manifest",
    "com.artflow.studio.OptimizedArtworkWorkflowTest": "Real input, UI save, fresh Activity reopen, UI PNG export",
    "com.artflow.studio.ArtworkWorkflowTest": "Editor navigation, layers, recreation and discarded edits",
    "com.artflow.studio.EditorRenderingTest": "Presented GL pixels, context loss and redraw",
    "com.artflow.studio.ExportUiRegressionTest": "Export controls and offline privacy policy",
    "com.artflow.studio.data.PersistenceRegressionTest": "Saved rasters, frames, recovery and storage failures",
    "com.artflow.studio.data.DatabaseMigrationDeviceTest": "Production Room upgrades, preserved artwork and recovery, rejected unsafe schemas",
    "com.artflow.studio.data.ExportDeviceTest": "Independent decoding and export/share isolation",
    "com.artflow.studio.NativeLibraryCompatibilityTest": "Real packaged native graphics library load",
    "com.artflow.studio.RuntimeEnvironmentTest": "Measured API, ABI and process page size",
}
RESULTS = Path("app/build/outputs/androidTest-results/connected/benchmark")
EVIDENCE = Path("app/build/outputs/connected_android_test_additional_output/benchmarkAndroidTest")
BUILD_CONTRACT = Path("app/build/reports/release-qualification/build-contract.json")
APK_PATHS = {
    "com.artflow.studio": Path("app/build/outputs/apk/benchmark/app-benchmark.apk"),
    "com.artflow.studio.test": Path("app/build/outputs/apk/androidTest/benchmark/app-benchmark-androidTest.apk"),
}
TOKEN_PATTERN = re.compile(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")


def selected_inventory(source: Path) -> set[str]:
    declared = source_inventory(source)
    expected = {identity for identity in declared if identity.split("#", 1)[0] in CLASSES}
    missing = CLASSES.keys() - {identity.split("#", 1)[0] for identity in expected}
    if missing:
        raise ValueError(f"Qualification classes missing from source inventory: {sorted(missing)}")
    return expected


def file_record(path: Path) -> dict:
    if not path.is_file() or path.is_symlink() or path.stat().st_size == 0:
        raise ValueError(f"Missing, empty or linked evidence file: {path}")
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    return {"path": str(path), "sizeBytes": path.stat().st_size, "sha256": digest}


def unique_file(root: Path, name: str) -> Path:
    found = sorted(root.rglob(name))
    if len(found) != 1:
        raise ValueError(f"Expected one {name}, found {len(found)}")
    file_record(found[0])
    return found[0]


def verify_build(project: Path, evidence: Path, token: str) -> dict:
    contract_path = project / BUILD_CONTRACT
    contract = json.loads(contract_path.read_text())
    expected = {"schemaVersion": 1, "buildType": "benchmark", "minifyEnabled": True,
                "shrinkResources": True, "debuggable": False, "signingConfig": "debug",
                "releaseRulesUnchanged": True, "publisherQualified": False}
    if contract != expected:
        raise ValueError("Build contract does not require unchanged release optimization and test-only signing")
    observed_path = unique_file(evidence, "qualification-build.json")
    observed = json.loads(observed_path.read_text())
    if observed != {"schemaVersion": 1, "attemptToken": token, "packageName": "com.artflow.studio",
                    "buildType": "benchmark", "debuggable": False}:
        raise ValueError("Installed build contract is not this attempt's non-debuggable benchmark application")
    mapping_root = project / "app/build/outputs/mapping/benchmark"
    shrinking = {name: file_record(mapping_root / name)
                 for name in ("mapping.txt", "configuration.txt", "usage.txt", "resources.txt")}
    mapping = (mapping_root / "mapping.txt").read_text()
    renamed = [(source, target) for source, target in re.findall(r"(?m)^(com\.artflow\.studio\.[^ ]+) -> ([^ :]+):$", mapping)
               if source != target]
    if not renamed:
        raise ValueError("R8 mapping contains no obfuscated production class")
    configuration = (mapping_root / "configuration.txt").read_text()
    if re.search(r"(?m)^\s*-(dontobfuscate|dontoptimize|dontshrink)\b", configuration):
        raise ValueError("R8 configuration disables a required production optimization")
    return {"configured": contract, "installed": observed, "configuredEvidence": file_record(contract_path),
            "installedEvidence": file_record(observed_path), "r8AndResourceShrinker": shrinking,
            "renamedProductionClasses": len(renamed)}


def verify_installed_apks(project: Path, evidence: Path, token: str) -> dict:
    namespaces = snapshot_namespaces(evidence)
    if len(namespaces) != 1 or namespaces[0].name != token:
        raise ValueError("Expected exactly one installed APK snapshot from this attempt; stale/repeated captures are rejected")
    namespace = namespaces[0]
    source = json.loads((namespace / "manifest.json").read_text())
    if (not isinstance(source, dict) or source.get("schemaVersion") != 1 or source.get("attemptToken") != token
            or source.get("errors") != [] or not isinstance(source.get("packages"), dict)
            or set(source["packages"]) != set(APK_PATHS)):
        raise ValueError("Malformed or failed runner APK capture")
    packages = {}
    for package, built_path in APK_PATHS.items():
        record = source["packages"][package]
        if not isinstance(record, dict):
            raise ValueError(f"Malformed runner APK capture for {package}")
        captures = record.get("apks")
        if (record.get("packageName") != package or record.get("errors") != []
                or not isinstance(captures, list) or len(captures) != 1):
            raise ValueError(f"Missing or failed single-APK capture for {package}")
        captured = captures[0]
        if not isinstance(captured, dict):
            raise ValueError(f"Malformed installed APK record for {package}")
        path = snapshot_file(namespace, package, captured.get("file"))
        if path is None or captured.get("captureComplete") is not True or captured.get("errors") != []:
            raise ValueError(f"Incomplete installed APK capture for {package}")
        installed = inspect_apk(path, captured)
        built = inspect_apk(project / built_path)
        if (installed["errors"] or built["errors"] or not installed["dex"] or not built["dex"]
                or installed["sha256"] != built["sha256"]):
            raise ValueError(f"Installed APK bytes do not match the freshly built benchmark APK for {package}")
        packages[package] = {"built": {"path": str(built_path), **built},
                             "installed": {"path": str(path), **installed}}
    return {"attemptToken": token, "runnerManifest": file_record(namespace / "manifest.json"), "packages": packages}


def verify_workflow_evidence(evidence: Path) -> dict:
    workflow_path = unique_file(evidence, "optimized-workflow.json")
    workflow = json.loads(workflow_path.read_text())
    if workflow != {"schemaVersion": 1, "buildType": "benchmark", "width": 64, "height": 64,
                    "paintSaveReopenExportPixelsEqual": True}:
        raise ValueError("Paint/save/reopen/export assertions did not produce complete optimized evidence")
    image = unique_file(evidence, "optimized-workflow-export.png")
    with image.open("rb") as stream:
        header = stream.read(24)
    if (len(header) != 24 or header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR"
            or struct.unpack(">II", header[16:24]) != (64, 64)):
        raise ValueError("Retained UI export is not the expected 64 by 64 PNG")
    return {"assertions": file_record(workflow_path), "export": file_record(image),
            "uiScreenshot": file_record(unique_file(evidence, "optimized-workflow-export-ui.png"))}


def verify(project: Path, output: Path, revision: str) -> dict:
    project = project.resolve()
    evidence = project / EVIDENCE
    report = {"schemaVersion": 1, "revision": revision, "buildType": "benchmark", "api": 36,
              "pageSize": 4096, "signingScope": "Disposable debug key; no publisher qualification",
              "attemptsAllowed": 1, "skipsAllowed": 0, "expectedTests": [], "classes": CLASSES,
              "results": {}, "counts": {}, "problems": []}
    problems = report["problems"]
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        problems.append("An exact 40-character source revision is required")

    def check(name, operation):
        try:
            report[name] = operation()
        except (OSError, ValueError, KeyError, TypeError, ET.ParseError) as error:
            problems.append(f"{name}: {error}")

    def attempt():
        token = (output / "attempt-token.txt").read_text().strip()
        if not TOKEN_PATTERN.fullmatch(token):
            raise ValueError("Missing or invalid instrumentation attempt token")
        status = (output / "instrumentation-exit-code.txt").read_text().strip()
        if status != "0":
            raise ValueError(f"Original instrumentation invocation failed or timed out: {status}")
        if (output / "instrumentation-log-exit-code.txt").read_text().strip() != "0":
            raise ValueError("Original instrumentation log could not be preserved completely")
        return token

    def inventory():
        expected = sorted(selected_inventory(project / "app/src/androidTest/java"))
        saved = json.loads((output / "test-inventory.json").read_text())
        if saved != expected:
            raise ValueError("Executed class inventory does not match the exact source revision")
        return expected

    check("attemptToken", attempt)
    check("instrumentationLog", lambda: file_record(output / "first-attempt-instrumentation.log"))
    check("expectedTests", inventory)
    check("results", lambda: read_results(project / RESULTS))
    results = report["results"]
    expected = set(report["expectedTests"])
    problems += [f"Missing test: {case}" for case in sorted(expected - results.keys())]
    problems += [f"Unexpected test: {case}" for case in sorted(results.keys() - expected)]
    problems += [f"Test did not pass (no skips/retries permitted): {case}: {state}"
                 for case, state in sorted(results.items()) if state != "passed"]
    report["counts"] = dict(Counter(results.values()))

    def runtime():
        measured = read_runtime(evidence)
        if measured["sdk"] != "36" or measured["pageSize"] != "4096" or "x86_64" not in measured["abis"].split(","):
            raise ValueError(f"Wrong measured optimized-test runtime: {measured}")
        return measured

    check("runtime", runtime)
    token = report.get("attemptToken", "")
    check("build", lambda: verify_build(project, evidence, token))
    check("installedApks", lambda: verify_installed_apks(project, evidence, token))
    check("workflowEvidence", lambda: verify_workflow_evidence(evidence))
    report["passed"] = not problems
    output.mkdir(parents=True, exist_ok=True)
    (output / "verification.json").write_text(json.dumps(report, indent=2) + "\n")
    lines = ["## Release-optimized instrumentation qualification", "", f"Revision: `{revision}`", "",
             f"**{'PASS' if report['passed'] else 'FAIL'}** — {report['counts'].get('passed', 0)} passed / "
             f"{len(expected)} declared cases, one attempt, zero permitted skips.", "",
             "Requested runtime: API 36, x86_64, 4096-byte process pages.", "",
             "This benchmark APK uses release R8/resource shrinking, test Compose/Android View hosts, Hilt test "
             "application wiring, and a disposable debug signing key. Publisher signing, physical-device "
             "performance and the complete seven-lane debug matrix remain separate gates.", "",
             "| Test class | Scope |", "| --- | --- |"]
    lines += [f"| `{name}` | {scope} |" for name, scope in CLASSES.items()]
    if problems:
        lines += ["", "### Failures", ""] + [f"- {problem}" for problem in problems]
    (output / "verification.md").write_text("\n".join(lines) + "\n")
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    inventory = subparsers.add_parser("inventory")
    inventory.add_argument("--source", type=Path, default=Path("app/src/androidTest/java"))
    inventory.add_argument("--output", type=Path, required=True)
    verification = subparsers.add_parser("verify")
    verification.add_argument("--project", type=Path, default=Path.cwd())
    verification.add_argument("--output", type=Path, required=True)
    verification.add_argument("--revision", required=True)
    args = parser.parse_args()
    if args.command == "inventory":
        expected = sorted(selected_inventory(args.source))
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(expected, indent=2) + "\n")
        print(",".join(CLASSES))
    else:
        report = verify(args.project, args.output, args.revision)
        print((args.output / "verification.md").read_text())
        raise SystemExit(0 if report["passed"] else 1)


if __name__ == "__main__":
    main()
