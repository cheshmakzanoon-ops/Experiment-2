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
import zipfile

from collect_installed_apks import inspect_apk, snapshot_file, snapshot_namespaces
from summarize_device_results import read_results, read_runtime, source_inventory


CLASSES = {
    "com.artflow.studio.OptimizedBuildContractTest": "Installed variant and non-debuggable manifest",
    "com.artflow.studio.OptimizedArtworkWorkflowTest": "Real input, UI save, fresh Activity reopen, UI PNG export",
    "com.artflow.studio.ArtworkWorkflowTest": "Editor navigation, layers, recreation and discarded edits",
    "com.artflow.studio.EditorRenderingTest": "Presented GL pixels, context loss and redraw",
    "com.artflow.studio.ExportUiRegressionTest": "Export controls and offline privacy policy",
    "com.artflow.studio.data.PersistenceRegressionTest": "Saved rasters, frames, recovery and storage failures",
    "com.artflow.studio.data.DocumentBoundaryDeviceTest": "Metadata rejection, saved-selection admission and bounded undo/redo with real storage",
    "com.artflow.studio.data.DatabaseMigrationDeviceTest": "Production Room upgrades, preserved artwork and recovery, rejected unsafe schemas",
    "com.artflow.studio.data.ExportDeviceTest": "Independent decoding and export/share isolation",
    "com.artflow.studio.NativeLibraryCompatibilityTest": "Real packaged native graphics library load",
    "com.artflow.studio.RuntimeEnvironmentTest": "Measured API, ABI and process page size",
}
RESULTS = Path("app/build/outputs/androidTest-results/connected/benchmark")
EVIDENCE = Path("app/build/outputs/connected_android_test_additional_output/benchmarkAndroidTest")
BUILD_CONTRACT = Path("app/build/reports/release-qualification/build-contract.json")
TEST_ABI = Path("app/build/reports/release-qualification/test-abi")
TEST_ABI_FIXTURES = Path("app/build/reports/release-qualification/test-abi-fixtures")
REFLECTION_RULES = Path("app/proguard-benchmark-test-reflection.pro")
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


def normalized_rules(text: str) -> str:
    return re.sub(r"\s+", " ", re.sub(r"(?m)#.*$", "", text)).strip()


def verify_test_abi(project: Path, configuration: str) -> dict:
    """Bind the benchmark-only boundary to retained pre-R8 bytecode and applied exact rules."""
    directory = project / TEST_ABI
    report_path = directory / "report.json"
    report_record = file_record(report_path)
    report = json.loads(report_path.read_text())
    if (not isinstance(report, dict) or report.get("schemaVersion") != 1
            or report.get("generator") != "R8 TraceReferences"
            or report.get("scope") != "benchmark-instrumentation-boundary"
            or report.get("allowObfuscation") is not False
            or report.get("publisherReleaseRulesModified") is not False
            or not isinstance(report.get("r8Version"), str) or not report["r8Version"]):
        raise ValueError("Malformed generated test ABI scope or obfuscation contract")
    tool = report.get("r8Tool")
    if (not isinstance(tool, dict) or set(tool) != {"file", "sizeBytes", "sha256"}
            or not isinstance(tool["file"], str) or not tool["file"].endswith(".jar")
            or type(tool["sizeBytes"]) is not int or tool["sizeBytes"] <= 0
            or not isinstance(tool["sha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", tool["sha256"])):
        raise ValueError("Missing R8 tool identity for generated test ABI")
    expected_files = {"appInput": "app-input.jar", "testInput": "test-input.jar", "rules": "keep-rules.pro"}
    if not isinstance(report.get("files"), dict) or set(report["files"]) != set(expected_files):
        raise ValueError("Missing generated test ABI input or rule records")
    records = {}
    for key, name in expected_files.items():
        record = file_record(directory / name)
        if report["files"][key] != {"file": name, "sizeBytes": record["sizeBytes"], "sha256": record["sha256"]}:
            raise ValueError(f"Generated test ABI artifact hash mismatch: {name}")
        records[key] = record
    inputs = {}
    for key, count_key in (("appInput", "appInputClasses"), ("testInput", "testInputClasses")):
        try:
            with zipfile.ZipFile(directory / expected_files[key]) as archive:
                names = archive.namelist()
        except zipfile.BadZipFile as error:
            raise ValueError(f"Malformed generated test ABI bytecode archive: {key}") from error
        if (not names or len(names) != len(set(names))
                or any(not re.fullmatch(r"[A-Za-z0-9_$/-]+\.class", name) for name in names)
                or any(name.startswith("/") or ".." in name for name in names)
                or type(report.get(count_key)) is not int or report[count_key] != len(names)):
            raise ValueError(f"Malformed generated test ABI class inventory: {key}")
        inputs[key] = {name[:-6].replace("/", ".") for name in names}
    if inputs["appInput"] & inputs["testInput"]:
        raise ValueError("Test ABI source contains app-owned duplicate classes and can hide references")
    boundary = report.get("boundary")
    if not isinstance(boundary, dict) or set(boundary) != {"classes", "fields", "methods", "packages"}:
        raise ValueError("Malformed generated test ABI boundary inventory")
    for key, values in boundary.items():
        if (not isinstance(values, list) or any(not isinstance(value, str) or not value for value in values)
                or values != sorted(set(values))):
            raise ValueError(f"Malformed generated test ABI symbol list: {key}")
    if not boundary["classes"] or not set(boundary["classes"]) <= inputs["appInput"]:
        raise ValueError("Generated test ABI retains classes outside its application input")
    unresolved = report.get("externalUnresolvedReferences")
    if (not isinstance(unresolved, list) or any(not isinstance(value, str) or not value for value in unresolved)
            or unresolved != sorted(set(unresolved))):
        raise ValueError("Malformed external reference audit in generated test ABI")
    rules = (directory / "keep-rules.pro").read_text()
    # TraceReferences emits exact members. No wildcard keep, warning suppression or global
    # optimization switch is allowed in the generated boundary, including future tool changes.
    if "*" in rules or "?" in rules:
        raise ValueError("Generated test ABI contains a wildcard instead of an exact symbol")
    kept_classes = []
    kept_enum_names = []
    members = {"fields": 0, "methods": 0}
    block = False
    for raw in rules.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        keep = re.fullmatch(r"-keep(?P<allow>,allowobfuscation)? (?P<kind>class|interface|@interface|enum) "
                            r"(?P<name>[A-Za-z0-9_.$-]+)(?P<body> \{)?", line)
        if keep and not block:
            if keep["allow"]:
                raise ValueError("Generated test ABI does not preserve its exact external symbol names")
            if keep["kind"] == "enum":
                kept_enum_names.append(keep["name"])
            kept_classes.append(keep["name"])
            block = bool(keep["body"])
        elif line == "}" and block:
            block = False
        elif block and not line.startswith("-") and line.endswith(";"):
            members["methods" if "(" in line else "fields"] += 1
        elif not block and re.fullmatch(r"-keeppackagenames [A-Za-z0-9_.$,-]+", line):
            retained_packages = set(line.split(" ", 1)[1].split(","))
            if not retained_packages <= set(boundary["packages"]):
                raise ValueError("Generated test ABI package constraint was not traced")
        else:
            raise ValueError(f"Unexpected generated test ABI rule: {line}")
    if (block or len(kept_classes) != len(set(kept_classes))
            or set(kept_classes) != set(boundary["classes"])
            or any(members[key] != len(boundary[key]) for key in members)):
        raise ValueError("Generated test ABI rules do not match the retained symbol inventory")
    reflection_path = project / REFLECTION_RULES
    reflection_record = file_record(reflection_path)
    reflection = reflection_path.read_text()
    expected_reflection = """
        -keepclassmembers class com.artflow.studio.presentation.ui.components.canvas.ArtFlowCanvasView {
            kotlinx.coroutines.Job selectionJob;
        }
        -keepclassmembers class androidx.activity.OnBackPressedDispatcher {
            kotlin.collections.ArrayDeque onBackPressedCallbacks;
        }
    """
    if normalized_rules(reflection) != normalized_rules(expected_reflection):
        raise ValueError("Benchmark reflection rules exceed the two audited private fields")
    applied = normalized_rules(configuration)
    if normalized_rules(rules) not in applied or normalized_rules(reflection) not in applied:
        raise ValueError("R8 did not apply the recorded generated ABI and reflection rules")
    fixtures = verify_test_abi_fixtures(project, report)
    return {"scope": report["scope"], "allowsObfuscation": False,
            "publisherReleaseEquivalent": False, "report": report_record,
            "r8Version": report["r8Version"], "r8Tool": tool, "artifacts": records,
            "keptClasses": len(boundary["classes"]), "keptFields": len(boundary["fields"]),
            "keptMethods": len(boundary["methods"]), "retainedPackages": boundary["packages"],
            "enumNamesPreservedByR8": kept_enum_names,
            "reflectionRules": reflection_record, "reflectionFields": 2,
            "externalUnresolvedReferenceCount": len(unresolved), "fixtures": fixtures}


def verify_test_abi_fixtures(project: Path, abi: dict) -> dict:
    """Require real R8 linkage fixtures using the exact Kotlin dependency bytes traced for the app."""
    directory = project / TEST_ABI_FIXTURES
    report_path = directory / "report.json"
    report_record = file_record(report_path)
    value = json.loads(report_path.read_text())
    if (not isinstance(value, dict) or type(value.get("schemaVersion")) is not int
            or value["schemaVersion"] != 1 or value.get("scope") != "benchmark-test-abi-fixtures"
            or value.get("passed") is not True or value.get("publisherQualified") is not False
            or any(type(value.get(key)) is not int or value[key] != expected
                   for key, expected in (("tests", 6), ("failures", 0), ("errors", 0), ("skipped", 0)))
            or value.get("r8Tool") != abi["r8Tool"]):
        raise ValueError("Missing complete, unskipped ABI fixture success with the same R8 tool")
    inputs = value.get("inputs")
    if (not isinstance(inputs, dict) or set(inputs) != {"applicationInput", "kotlin", "annotations"}
            or inputs["applicationInput"] != abi["files"]["appInput"]):
        raise ValueError("ABI fixtures did not use the recorded application bytecode")
    retained = {}
    with zipfile.ZipFile(project / TEST_ABI / "app-input.jar") as application:
        for role, prefix, required in (
            ("kotlin", "kotlin/", "kotlin/coroutines/ContinuationInterceptor$DefaultImpls.class"),
            ("annotations", "org/jetbrains/annotations/", "org/jetbrains/annotations/NotNull.class"),
        ):
            path = directory / f"{role}-input.jar"
            record = file_record(path)
            expected = {"file": path.name, "sizeBytes": record["sizeBytes"], "sha256": record["sha256"]}
            item = inputs[role]
            if (not isinstance(item, dict) or set(item) != {*expected, "classes"}
                    or any(item[key] != expected[key] for key in expected)
                    or type(item["classes"]) is not int or item["classes"] <= 0):
                raise ValueError(f"ABI fixture dependency identity is invalid: {role}")
            names = sorted(name for name in application.namelist() if name.startswith(prefix) and name.endswith(".class"))
            try:
                fixture_archive = zipfile.ZipFile(path)
            except zipfile.BadZipFile as error:
                raise ValueError(f"Malformed ABI fixture dependency archive: {role}") from error
            with fixture_archive as fixture:
                actual = fixture.namelist()
                if (required not in names or len(actual) != len(set(actual))
                        or sorted(actual) != names or item["classes"] != len(names)
                        or any(fixture.read(name) != application.read(name) for name in names)):
                    raise ValueError(f"ABI fixture dependency differs from the actual application classes: {role}")
            retained[role] = record
    return {"report": report_record, "tests": value["tests"], "skipped": value["skipped"], "inputs": retained}


def verify_build(project: Path, evidence: Path, token: str) -> dict:
    contract_path = project / BUILD_CONTRACT
    contract = json.loads(contract_path.read_text())
    expected = {"schemaVersion": 2, "buildType": "benchmark", "minifyEnabled": True,
                "shrinkResources": True, "debuggable": False, "signingConfig": "debug",
                "publisherReleaseRulesUnchanged": True, "benchmarkAddsGeneratedTestAbiRules": True,
                "benchmarkAddsAuditedReflectionFields": True, "testAbiAllowsObfuscation": False,
                "publisherQualified": False}
    if contract != expected:
        raise ValueError("Build contract does not declare benchmark test ABI constraints, optimization and test-only signing")
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
            "renamedProductionClasses": len(renamed), "testAbi": verify_test_abi(project, configuration)}


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
              "optimizationScope": "R8 enabled; traced instrumentation ABI and two reflection fields are preserved only in benchmark",
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

    def source_revision():
        path = output / "REVISION.txt"
        record = file_record(path)
        if path.read_text(encoding="ascii").strip() != revision:
            raise ValueError("Requested revision differs from the retained prebuild source revision")
        return {"revision": revision, "receipt": record}

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

    # The workflow records HEAD before building. A caller cannot relabel unchanged runtime
    # evidence by supplying a different valid SHA to this verifier. This is receipt consistency,
    # not source-to-binary reproduction or authentication of externally supplied evidence.
    check("sourceRevision", source_revision)
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
             "application wiring, and a disposable debug signing key. It additionally preserves the exact traced "
             "instrumentation ABI names and two audited reflection fields, so its optimization "
             "constraints differ from the publisher release. Publisher signing, physical-device "
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
