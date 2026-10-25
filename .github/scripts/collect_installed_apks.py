#!/usr/bin/env python3
"""Verify APK snapshots copied by the instrumentation runner before UTP uninstall.

Only the canonical connected-test output and this attempt's token establish
installed provenance. Built APKs are recorded separately and never substituted.
Successful attempts keep manifests; failed attempts also retain original snapshots.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import zipfile

MAX_DEX_BYTES = 256 * 1024 * 1024
EVIDENCE_ROOT = Path("app/build/outputs/connected_android_test_additional_output")
TOKEN_PATTERN = re.compile(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
PACKAGES = {
    "com.artflow.studio": "app/build/outputs/apk/debug/*.apk",
    "com.artflow.studio.test": "app/build/outputs/apk/androidTest/debug/*.apk",
}


def digest(stream) -> str:
    value = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b""):
        value.update(block)
    return value.hexdigest()


def inspect_apk(path: Path, expected_capture: dict | None = None) -> dict:
    record = {"sizeBytes": None, "sha256": None, "dex": [], "errors": []}
    try:
        record["sizeBytes"] = path.stat().st_size
        with path.open("rb") as stream:
            record["sha256"] = digest(stream)
    except OSError as error:
        record["errors"].append(f"Could not hash APK: {error}")
        return record
    if expected_capture is not None:
        expected_size, expected_hash = expected_capture.get("sizeBytes"), expected_capture.get("sha256")
        if type(expected_size) is not int or expected_size <= 0 or expected_size != record["sizeBytes"]:
            record["errors"].append("Snapshot size does not match the runner's complete copy")
        if (not isinstance(expected_hash, str) or not re.fullmatch(r"[0-9a-f]{64}", expected_hash)
                or expected_hash != record["sha256"]):
            record["errors"].append("Snapshot SHA256 does not match the runner's complete copy")
        if record["errors"]:
            return record
    try:
        with zipfile.ZipFile(path) as archive:
            seen = set()
            for entry in archive.infolist():
                if not re.fullmatch(r"classes\d*\.dex", entry.filename):
                    continue
                item = {"name": entry.filename, "sizeBytes": entry.file_size,
                        "headerOffset": entry.header_offset, "sha256": None}
                record["dex"].append(item)
                if entry.filename in seen:
                    record["errors"].append(f"Duplicate DEX entry: {entry.filename}")
                seen.add(entry.filename)
                if entry.file_size > MAX_DEX_BYTES:
                    record["errors"].append(f"DEX entry exceeds hash limit: {entry.filename}")
                    continue
                with archive.open(entry) as stream:
                    item["sha256"] = digest(stream)
    except Exception as error:
        # Codec-specific corruption (including zlib.error and EOFError) must leave
        # the original APK and whole-file hash available for diagnosis.
        kind = f"{type(error).__module__}.{type(error).__name__}"
        record["errors"].append(f"Could not inspect APK ZIP ({kind}): {error}")
    return record


def snapshot_namespaces(canonical: Path) -> list[Path]:
    namespaces = []
    for parent in canonical.rglob("apk-provenance"):
        if parent.parent.name != "test-evidence" or not parent.is_dir() or parent.is_symlink():
            continue
        for directory in parent.iterdir():
            if (directory.is_dir() and not directory.is_symlink()
                    and directory.resolve().is_relative_to(canonical.resolve())):
                namespaces.append(directory)
    return sorted(namespaces)


def stage_snapshot(namespace: Path, output: Path, index: int, retain: bool, errors: list) -> tuple[Path, dict]:
    destination = output / "runner-captures" / f"{index:02d}"
    record = {"sourceDirectory": str(namespace), "retainedDirectory": None,
              "manifestPath": None, "accepted": False}
    try:
        destination.parent.mkdir(parents=True, exist_ok=True)
        if retain:
            # Move even malformed metadata and partial copies before inspecting anything.
            shutil.move(str(namespace), destination)
            record["retainedDirectory"] = str(destination.relative_to(output))
            record["manifestPath"] = str((destination / "manifest.json").relative_to(output))
            return destination, record
        destination.mkdir()
        shutil.copyfile(namespace / "manifest.json", destination / "manifest.json")
        record["manifestPath"] = str((destination / "manifest.json").relative_to(output))
    except OSError as error:
        errors.append({"error": "Could not preserve runner snapshot metadata or bytes", "detail": str(error)})
    return namespace, record


def snapshot_file(namespace: Path, package: str, relative: object) -> Path | None:
    if not isinstance(relative, str):
        return None
    parts = PurePosixPath(relative).parts
    if (len(parts) != 3 or parts[:2] != ("installed", package)
            or not re.fullmatch(r"[0-9]{2,}\.apk", parts[2])):
        return None
    path = namespace.joinpath(*parts)
    if path.is_symlink() or not path.resolve().is_relative_to(namespace.resolve()):
        return None
    return path


def inspect_package(package: str, source: object, namespace: Path, output: Path,
                    retained: bool, retain_required: bool, record: dict) -> None:
    if not isinstance(source, dict) or source.get("packageName") != package:
        record["errors"].append({"error": "Missing or mismatched runner package identity"})
        return
    record["runnerErrors"] = source.get("errors")
    if source.get("errors") != []:
        record["errors"].append({"error": "Runner reported package errors", "details": source.get("errors")})
    apks = source.get("apks")
    if not isinstance(apks, list) or not apks:
        record["errors"].append({"error": "No runner APK records"})
        return
    seen = set()
    for source_apk in apks:
        if not isinstance(source_apk, dict):
            record["errors"].append({"error": "Malformed runner APK record"})
            continue
        item = {"devicePath": source_apk.get("devicePath"), "snapshotFile": source_apk.get("file"),
                "runnerSizeBytes": source_apk.get("sizeBytes"), "runnerSha256": source_apk.get("sha256"),
                "runnerCaptureComplete": source_apk.get("captureComplete"), "runnerErrors": source_apk.get("errors"),
                "captureComplete": False, "retainedPath": None, "errors": []}
        record["installedApks"].append(item)
        if source_apk.get("captureComplete") is not True or source_apk.get("errors") != []:
            item["errors"].append({"error": "Runner copy is incomplete or reported errors"})
        if not isinstance(item["devicePath"], str) or not item["devicePath"].startswith("/"):
            item["errors"].append({"error": "Invalid installed source path"})
        path = snapshot_file(namespace, package, source_apk.get("file"))
        if path is None:
            item["errors"].append({"error": "Invalid snapshot file path"})
            continue
        if path in seen:
            item["errors"].append({"error": "Duplicate snapshot file path"})
            continue
        seen.add(path)
        if retained:
            item["retainedPath"] = str(path.relative_to(output)) if path.is_file() else None
            partial = Path(str(path) + ".partial")
            if partial.is_file() and partial.resolve().is_relative_to(output.resolve()):
                item["partialRetainedPath"] = str(partial.relative_to(output))
        elif retain_required:
            item["errors"].append({"error": "Failed-attempt snapshot bytes were not retained"})
        inspection = inspect_apk(path, source_apk)
        item["errors"].extend({"error": error} for error in inspection.pop("errors"))
        item.update(inspection)
        item["matchingBuiltApks"] = [built["path"] for built in record["builtApks"]
                                     if item["sha256"] is not None and built["sha256"] == item["sha256"]]
        item["captureComplete"] = item["sha256"] is not None and not item["errors"]


def remove_success_payloads(namespaces: list[Path], errors: list) -> None:
    for namespace in namespaces:
        for path in namespace.rglob("*"):
            if not path.name.endswith((".apk", ".partial")):
                continue
            if not path.resolve().is_relative_to(namespace.resolve()):
                errors.append({"error": "Refused snapshot cleanup outside its namespace", "path": str(path)})
                continue
            try:
                if path.is_file() or path.is_symlink():
                    path.unlink()
            except OSError as error:
                errors.append({"error": "Could not remove successful snapshot payload", "path": str(path), "detail": str(error)})


def collect(output: Path, project: Path, instrumentation_status: int, attempt_token: str) -> dict:
    output, project = output.resolve(), project.resolve()
    output.mkdir(parents=True, exist_ok=False)
    manifest = {"schemaVersion": 1, "attemptToken": attempt_token,
                "capturedAtUtc": datetime.now(timezone.utc).isoformat(),
                "instrumentationStatus": instrumentation_status, "retainInstalledApks": instrumentation_status != 0,
                "runnerCaptures": [], "ignoredCaptureTokens": [], "packages": {}, "errors": []}
    namespaces = snapshot_namespaces(project / EVIDENCE_ROOT)
    candidates = []
    if TOKEN_PATTERN.fullmatch(attempt_token):
        candidates = [path for path in namespaces if path.name == attempt_token]
        manifest["ignoredCaptureTokens"] = sorted({path.name for path in namespaces if path.name != attempt_token})
    else:
        manifest["errors"].append({"error": "Invalid attempt token"})
    staged = []
    for index, namespace in enumerate(candidates):
        path, record = stage_snapshot(namespace, output, index, bool(instrumentation_status), manifest["errors"])
        manifest["runnerCaptures"].append(record)
        staged.append((path, record))
    try:
        for package, pattern in PACKAGES.items():
            record = {"builtApks": [], "installedApks": [], "errors": []}
            manifest["packages"][package] = record
            for path in sorted(project.glob(pattern)):
                record["builtApks"].append({"path": str(path.relative_to(project)), **inspect_apk(path)})
            if not record["builtApks"]:
                record["errors"].append({"error": "no built APKs", "pattern": pattern})
        if len(staged) != 1:
            manifest["errors"].append({"error": "Expected exactly one runner snapshot for this attempt",
                                       "matchingSnapshots": len(staged)})
        else:
            namespace, record = staged[0]
            try:
                source = json.loads((namespace / "manifest.json").read_text())
            except (OSError, ValueError) as error:
                manifest["errors"].append({"error": "Could not read runner manifest", "detail": str(error)})
            else:
                if (not isinstance(source, dict) or source.get("schemaVersion") != 1
                        or source.get("attemptToken") != attempt_token or not isinstance(source.get("packages"), dict)):
                    manifest["errors"].append({"error": "Mismatched or malformed runner manifest"})
                else:
                    record["accepted"] = True
                    record["capturedAtUtc"], record["pid"] = source.get("capturedAtUtc"), source.get("pid")
                    if source.get("errors") != []:
                        manifest["errors"].append({"error": "Runner reported capture errors", "details": source.get("errors")})
                    for package in PACKAGES:
                        inspect_package(package, source["packages"].get(package), namespace, output,
                                        record["retainedDirectory"] is not None, bool(instrumentation_status),
                                        manifest["packages"][package])
    except Exception as error:
        manifest["errors"].append({"error": "Unexpected snapshot inspection error",
                                   "detail": f"{type(error).__name__}: {error}"})
    finally:
        if instrumentation_status == 0:
            # Ignored tokens may be the only originals left after a failed attempt's
            # retention error. A successful retry must not delete those earlier bytes.
            remove_success_payloads(candidates, manifest["errors"])
        (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--project", type=Path, default=Path.cwd())
    parser.add_argument("--instrumentation-status", type=int, required=True)
    parser.add_argument("--attempt-token", required=True)
    args = parser.parse_args()
    collect(args.output, args.project, args.instrumentation_status, args.attempt_token)
    print(f"APK provenance manifest written: {args.output / 'manifest.json'}")


if __name__ == "__main__":
    main()
