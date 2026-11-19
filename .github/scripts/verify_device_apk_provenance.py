"""Require the full device lane's measured APK identity and disclose framework recovery.

The collector remains diagnostic and never replaces instrumentation's original exit status.
This separate acceptance check binds its retained manifest to the runner's original manifest,
the shell's invocation receipts and freshly hashed build outputs. Successful snapshot payloads
are deliberately removed by the existing collector, so this verifies retained measured evidence;
it is not a reproducible-build or publisher-authenticity claim.
"""
from __future__ import annotations

import json
from pathlib import Path
import re

from classify_android15_reference_queue_crash import is_retryable
from collect_installed_apks import PACKAGES, TOKEN_PATTERN, inspect_apk


def unlinked_file(path: Path) -> Path:
    if any(part.is_symlink() for part in (path, *path.parents)) or not path.is_file():
        raise ValueError(f"Missing or linked APK provenance evidence: {path}")
    return path


def read_object(path: Path) -> dict:
    def finite(value):
        raise ValueError(f"Nonfinite APK provenance value: {value}")

    value = json.loads(unlinked_file(path).read_text(), parse_constant=finite)
    if not isinstance(value, dict):
        raise ValueError(f"Malformed APK provenance object: {path}")
    return value


def verify_package(project: Path, capture_root: Path, retain: bool, package: str, record: dict, original: dict) -> dict:
    if (not isinstance(record, dict) or record.get("errors") != [] or record.get("runnerErrors") != []
            or not isinstance(original, dict) or original.get("packageName") != package
            or original.get("errors") != []):
        raise ValueError(f"Missing or failed APK package capture: {package}")
    installed, built, raw = record.get("installedApks"), record.get("builtApks"), original.get("apks")
    if any(not isinstance(items, list) or len(items) != 1 or not isinstance(items[0], dict)
           for items in (installed, built, raw)):
        raise ValueError(f"Expected one built and installed APK for {package}")
    installed, built, raw = installed[0], built[0], raw[0]
    paths = sorted(project.glob(PACKAGES[package]))
    if len(paths) != 1:
        raise ValueError(f"Missing or ambiguous current build APK: {package}")
    path = unlinked_file(paths[0])
    observed = inspect_apk(path)
    relative = str(path.relative_to(project))
    if observed["errors"] or not observed["dex"] or built != {"path": relative, **observed}:
        raise ValueError(f"Current built APK differs from captured build evidence: {package}")
    if (installed.get("captureComplete") is not True or installed.get("errors") != []
            or installed.get("runnerCaptureComplete") is not True or installed.get("runnerErrors") != []
            or raw.get("captureComplete") is not True or raw.get("errors") != []
            or raw.get("file") != f"installed/{package}/00.apk"
            or installed.get("snapshotFile") != raw["file"]
            or not isinstance(raw.get("devicePath"), str) or not raw["devicePath"].startswith("/")
            or installed.get("devicePath") != raw["devicePath"]
            or installed.get("matchingBuiltApks") != [relative]
            or installed.get("dex") != observed["dex"]):
        raise ValueError(f"Incomplete or inconsistent installed APK evidence: {package}")
    for field, runner in (("sha256", "runnerSha256"), ("sizeBytes", "runnerSizeBytes")):
        if any(value != observed[field] for value in (installed.get(field), installed.get(runner), raw.get(field))):
            raise ValueError(f"Installed APK bytes differ from the current build: {package}")
    if retain:
        retained_relative = f"runner-captures/00/installed/{package}/00.apk"
        if installed.get("retainedPath") != retained_relative:
            raise ValueError(f"Missing original failed-attempt APK retention: {package}")
        retained = inspect_apk(unlinked_file(capture_root / retained_relative))
        if retained != observed:
            raise ValueError(f"Original failed-attempt APK bytes changed: {package}")
    return {"path": relative, "sha256": observed["sha256"], "sizeBytes": observed["sizeBytes"]}


def verify_attempt(project: Path, root: Path, number: int) -> dict:
    name = f"attempt-{number}"
    receipts = root / "instrumentation-attempts"
    token = unlinked_file(receipts / f"{name}-token.txt").read_text().strip()
    status_text = unlinked_file(receipts / f"{name}-exit-code.txt").read_text().strip()
    if not TOKEN_PATTERN.fullmatch(token) or not re.fullmatch(r"0|[1-9][0-9]{0,2}", status_text):
        raise ValueError(f"Malformed original instrumentation receipt: {name}")
    status = int(status_text)
    if status > 255:
        raise ValueError(f"Invalid original instrumentation exit status: {name}")
    capture_root = root / "apk-provenance" / name
    capture = read_object(capture_root / "manifest.json")
    if (capture.get("schemaVersion") != 1 or capture.get("attemptToken") != token
            or type(capture.get("instrumentationStatus")) is not int
            or capture["instrumentationStatus"] != status or capture.get("errors") != []
            or capture.get("retainInstalledApks") is not bool(status)
            or not isinstance(capture.get("packages"), dict) or set(capture["packages"]) != set(PACKAGES)):
        raise ValueError(f"Missing, failed or stale collector manifest: {name}")
    runners = capture.get("runnerCaptures")
    if (not isinstance(runners, list) or len(runners) != 1 or not isinstance(runners[0], dict)
            or runners[0].get("accepted") is not True
            or runners[0].get("manifestPath") != "runner-captures/00/manifest.json"):
        raise ValueError(f"Missing or ambiguous original runner capture: {name}")
    raw = read_object(capture_root / "runner-captures/00/manifest.json")
    if (raw.get("schemaVersion") != 1 or raw.get("attemptToken") != token or raw.get("errors") != []
            or not isinstance(raw.get("packages"), dict) or set(raw["packages"]) != set(PACKAGES)
            or raw.get("pid") != runners[0].get("pid")
            or raw.get("capturedAtUtc") != runners[0].get("capturedAtUtc")):
        raise ValueError(f"Missing, failed or stale original runner manifest: {name}")
    return {"attempt": number, "attemptToken": token, "instrumentationStatus": status,
            "packages": {package: verify_package(project, capture_root, bool(status), package,
                                                 capture["packages"][package], raw["packages"][package])
                         for package in PACKAGES}}


def verify_device_run(project: Path, root: Path, api: int, page_size: int) -> dict:
    project = project.resolve()
    receipts = root / "instrumentation-attempts"
    tokens = sorted(path.name for path in receipts.glob("attempt-*-token.txt"))
    if tokens not in (["attempt-1-token.txt"], ["attempt-1-token.txt", "attempt-2-token.txt"]):
        raise ValueError("Expected one original invocation or its single declared framework recovery")
    count = len(tokens)
    expected_names = {f"attempt-{number}" for number in range(1, count + 1)}
    if {path.name for path in (root / "apk-provenance").glob("attempt-*")} != expected_names:
        raise ValueError("Collector attempts differ from the original invocation receipts")
    if {path.name for path in receipts.iterdir()} != {
            f"{name}-{kind}.txt" for name in expected_names for kind in ("token", "exit-code")}:
        raise ValueError("Incomplete or unexpected original invocation receipts")
    attempts = [verify_attempt(project, root, number) for number in range(1, count + 1)]
    if len({attempt["attemptToken"] for attempt in attempts}) != count:
        raise ValueError("Instrumentation attempts reused the same token")
    recovery = root / "android15-framework-retry"
    if count == 2:
        if (api != 35 or page_size != 16384 or attempts[0]["instrumentationStatus"] == 0
                or not is_retryable(recovery / "first-attempt-results")):
            raise ValueError("The retained original failure does not authorize framework recovery")
        unlinked_file(recovery / "first-attempt-logcat.txt")
    elif recovery.exists() or recovery.is_symlink():
        raise ValueError("Recovery evidence exists without its original two invocation receipts")
    if attempts[-1]["instrumentationStatus"] != 0:
        raise ValueError("The original final instrumentation invocation failed")
    return {"attempts": attempts, "frameworkRecovery": count == 2, "firstInvocationPassed": count == 1,
            "scope": "Retained runner/collector measurements matched freshly hashed build outputs"}
