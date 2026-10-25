#!/usr/bin/env python3
"""Preserve failed-attempt app tombstones without changing device or test state.

The accepted runner snapshot binds a PID and device timestamp to this attempt.
Only matching full text tombstones are retained. Stable remote/local hashes prove
transfer integrity, not completeness of the debugger-generated native report.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time

PACKAGE = "com.artflow.studio"
TOKEN_PATTERN = re.compile(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
TOMBSTONE_PATTERN = re.compile(r"tombstone_[0-9]{2,3}")
# Leave time for manifest publication before the harness's 58s/2s hard timeout.
MAX_SECONDS = 55
COMMAND_SECONDS = 10
MAX_CANDIDATES = 64
MAX_FILES = 8
MAX_FILE_BYTES = 32 * 1024 * 1024
MAX_TOTAL_BYTES = 64 * 1024 * 1024
HEADER_BYTES = 8192


class CaptureError(Exception):
    pass


class Adb:
    def __init__(self):
        self.deadline = time.monotonic() + MAX_SECONDS

    def run(self, arguments: list[str], destination: Path | None = None) -> bytes:
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise CaptureError("Overall tombstone capture deadline exhausted")
        command = ["adb", *arguments]
        try:
            if destination is None:
                result = subprocess.run(command, capture_output=True,
                                        timeout=min(COMMAND_SECONDS, remaining))
            else:
                with destination.open("wb") as stream:
                    result = subprocess.run(command, stdout=stream, stderr=subprocess.PIPE,
                                            timeout=min(COMMAND_SECONDS, remaining))
        except (OSError, subprocess.TimeoutExpired) as error:
            raise CaptureError(f"{' '.join(command)}: {type(error).__name__}: {error}") from error
        if result.returncode:
            detail = result.stderr.decode(errors="replace")[:2000]
            raise CaptureError(f"{' '.join(command)} exited {result.returncode}: {detail}")
        return result.stdout or b""


def timestamp(value: object) -> datetime:
    if not isinstance(value, str):
        raise ValueError("Timestamp is not a string")
    parsed = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("Timestamp lacks a timezone")
    return parsed.astimezone(timezone.utc)


def file_digest(path: Path) -> dict:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return {"sizeBytes": path.stat().st_size, "sha256": digest.hexdigest()}


def attempt_identity(provenance: Path, status: int, token: str) -> dict:
    if status == 0 or not TOKEN_PATTERN.fullmatch(token):
        raise ValueError("A failed instrumentation status and valid attempt token are required")
    source = json.loads(provenance.read_text())
    if (not isinstance(source, dict) or source.get("schemaVersion") != 1
            or source.get("attemptToken") != token or source.get("instrumentationStatus") != status):
        raise ValueError("APK provenance does not belong to this failed attempt")
    captures = source.get("runnerCaptures")
    if not isinstance(captures, list) or len(captures) != 1:
        raise ValueError("Exactly one runner identity is required")
    capture = captures[0]
    if (not isinstance(capture, dict) or capture.get("accepted") is not True
            or type(capture.get("pid")) is not int or not 0 < capture["pid"] < 2 ** 31):
        raise ValueError("Runner identity is absent, invalid or not accepted")
    timestamp(capture.get("capturedAtUtc"))
    return {"pid": capture["pid"], "capturedAtUtc": capture["capturedAtUtc"],
            "provenance": {"path": str(provenance), **file_digest(provenance)}}


def tombstone_identity(header: bytes) -> dict:
    text = header.decode("utf-8", errors="replace")
    command = re.findall(r"^Cmdline: (.+)$", text, re.MULTILINE)
    process = re.findall(r"^pid: ([0-9]+), tid: ([0-9]+), name: .*  >>> (.+) <<<$", text, re.MULTILINE)
    dates = re.findall(r"^Timestamp: (.+)$", text, re.MULTILINE)
    if len(command) != 1 or len(process) != 1 or len(dates) != 1:
        raise ValueError("Missing or ambiguous tombstone process/timestamp header")
    timestamp(dates[0])
    return {"pid": int(process[0][0]), "tid": int(process[0][1]), "cmdline": command[0],
            "processName": process[0][2], "timestamp": dates[0]}


def matches_attempt(identity: dict, runner: dict, upper_bound: datetime) -> bool:
    return (identity["pid"] == runner["pid"]
            and identity["cmdline"] == PACKAGE and identity["processName"] == PACKAGE
            and timestamp(runner["capturedAtUtc"]) <= timestamp(identity["timestamp"]) < upper_bound)


def remote_stat(adb: Adb, path: str, errors: list[str]) -> dict:
    record = {"sizeBytes": None, "modifiedEpochSeconds": None, "sha256": None}
    try:
        raw = adb.run(["shell", "stat", "-c", "%s:%Y", path]).decode().strip()
        record["statOutput"] = raw
        if not re.fullmatch(r"[0-9]+:[0-9]+", raw):
            raise CaptureError("Invalid remote stat output")
        record["sizeBytes"], record["modifiedEpochSeconds"] = map(int, raw.split(":"))
    except (CaptureError, UnicodeError) as error:
        errors.append(str(error))
    return record


def remote_hash(adb: Adb, path: str, size: int, errors: list[str]) -> str | None:
    if not re.fullmatch(r"/data/tombstones/tombstone_[0-9]{2,3}", path):
        raise ValueError("Unsafe remote tombstone path")
    try:
        # The numeric size and strict path are the only interpolated shell values.
        # Bound hashing too: a recycled/growing slot must not cause an unlimited read.
        raw = adb.run(["shell", f"head -c {size} {path} | sha256sum"]).decode().strip()
        match = re.fullmatch(r"([0-9a-f]{64})\s+-", raw)
        if match is None:
            raise CaptureError("Invalid remote SHA256 output")
        return match.group(1)
    except (CaptureError, UnicodeError) as error:
        errors.append(str(error))
    return None


def preserve(adb: Adb, output: Path, name: str, runner: dict, upper: datetime,
             remaining_bytes: int) -> dict:
    remote = "/data/tombstones/" + name
    record = {"devicePath": remote, "retainedPath": None, "transferIntegrityVerified": False,
              "debuggerContentCompleteness": "not_assessed", "identityMatchesAttempt": False,
              "reservedBytes": 0, "observedTransferredBytes": 0, "errors": []}
    errors = record["errors"]
    try:
        if not TOMBSTONE_PATTERN.fullmatch(name):
            raise ValueError("Unsafe tombstone basename")
        before = remote_stat(adb, remote, errors)
        record["remoteBefore"] = before
        size = before["sizeBytes"]
        if size is None or size <= 0 or size > min(MAX_FILE_BYTES, remaining_bytes):
            errors.append("Remote size is unavailable, empty or exceeds the remaining capture byte limit")
            return record
        # Reserve the whole bounded read before any I/O which may partially fail.
        # Local hashing/read/rename errors cannot release this attempt's byte budget.
        record["reservedBytes"] = size
        before["sha256"] = remote_hash(adb, remote, size, errors)
        with tempfile.TemporaryDirectory(prefix="artflow-native-tombstone-") as staging:
            temporary = Path(staging) / name
            transferred = False
            try:
                adb.run(["exec-out", "head", "-c", str(size), remote], temporary)
                transferred = True
            except CaptureError as error:
                errors.append(str(error))
            if not temporary.exists():
                return record
            record["observedTransferredBytes"] = temporary.stat().st_size
            record["local"] = file_digest(temporary)
            with temporary.open("rb") as stream:
                identity = tombstone_identity(stream.read(HEADER_BYTES))
            record["identity"] = identity
            record["identityMatchesAttempt"] = matches_attempt(identity, runner, upper)
            if not record["identityMatchesAttempt"]:
                errors.append("Copied tombstone changed to an unrelated process or time; bytes discarded")
                return record
            # Only an independently rechecked app header may leave private staging.
            # A truncated transfer without a complete matching header is never uploaded.
            destination = output / "files" / (name + ".partial")
            destination.parent.mkdir(exist_ok=True)
            record["retainedPath"] = str(destination.relative_to(output))
            shutil.move(str(temporary), destination)
            after = remote_stat(adb, remote, errors)
            after["sha256"] = remote_hash(adb, remote, size, errors)
            record["remoteAfter"] = after
            retained = file_digest(destination)
            record["retainedLocal"] = retained
            local = record["local"]
            size_matches = size == local["sizeBytes"] == retained["sizeBytes"] == after["sizeBytes"]
            hashes_match = (before["sha256"] is not None and before["sha256"] == local["sha256"]
                            == retained["sha256"] == after["sha256"])
            stable = before["modifiedEpochSeconds"] == after["modifiedEpochSeconds"]
            if not (size_matches and hashes_match and stable):
                errors.append("Remote before/after metadata and local bytes do not establish an exact stable transfer")
            if transferred and size_matches:
                complete_path = destination.with_suffix("")
                destination.rename(complete_path)
                record["retainedPath"] = str(complete_path.relative_to(output))
            record["transferIntegrityVerified"] = (transferred and size_matches and hashes_match and stable
                                                     and not errors)
    except (CaptureError, OSError, ValueError, UnicodeError) as error:
        errors.append(f"{type(error).__name__}: {error}")
    finally:
        retained = record["retainedPath"]
        if retained is not None:
            try:
                if not (output / retained).is_file():
                    record["retainedPath"] = None
            except OSError as error:
                record["transferIntegrityVerified"] = False
                errors.append(f"Could not check retained file: {error}")
    return record


def collect(output: Path, provenance: Path, instrumentation_status: int,
            attempt_token: str, adb: Adb | None = None) -> dict:
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    manifest = {"schemaVersion": 1, "attemptToken": attempt_token,
                "instrumentationStatus": instrumentation_status,
                "collectedAtUtc": datetime.now(timezone.utc).isoformat(),
                "captureStatus": "unavailable", "runner": None, "access": {},
                "limits": {"seconds": MAX_SECONDS, "commandSeconds": COMMAND_SECONDS,
                           "candidateHeaders": MAX_CANDIDATES, "files": MAX_FILES,
                           "bytesPerFile": MAX_FILE_BYTES, "totalBytes": MAX_TOTAL_BYTES,
                           "headerBytes": HEADER_BYTES},
                "candidates": [], "files": [], "errors": [],
                "debuggerContentCompleteness": "not_assessed",
                "protobufPolicy": "Presence recorded; bytes not copied without independent process identity"}
    errors = manifest["errors"]
    adb = adb or Adb()
    try:
        runner = attempt_identity(provenance, instrumentation_status, attempt_token)
        manifest["runner"] = runner
        for key, command in (("uid", ["shell", "id", "-u"]),
                             ("bootId", ["shell", "cat", "/proc/sys/kernel/random/boot_id"])):
            try:
                manifest["access"][key] = adb.run(command).decode().strip()
            except (CaptureError, UnicodeError) as error:
                errors.append(str(error))
        now = adb.run(["shell", "date", "-u", "+%Y-%m-%dT%H:%M:%SZ"]).decode().strip()
        manifest["access"]["deviceTimeUtc"] = now
        # Device date has one-second resolution. This exclusive bound includes that second.
        upper = timestamp(now) + timedelta(seconds=1)
        if timestamp(runner["capturedAtUtc"]) >= upper:
            raise CaptureError("Runner capture is later than the device collection time")
        names = adb.run(["shell", "ls", "-1", "/data/tombstones"]).decode().splitlines()
        names = {name.strip() for name in names}
        candidates = sorted(name for name in names if TOMBSTONE_PATTERN.fullmatch(name))
        manifest["listedTextFiles"] = len(candidates)
        if len(candidates) > MAX_CANDIDATES:
            manifest["skippedCandidateNames"] = candidates[MAX_CANDIDATES:]
            errors.append("Candidate header count limit reached")
        reserved_bytes = 0
        transferred_bytes = 0
        for name in candidates[:MAX_CANDIDATES]:
            candidate = {"devicePath": "/data/tombstones/" + name, "matchesAttempt": False,
                         "protobufPresent": name + ".pb" in names, "errors": []}
            manifest["candidates"].append(candidate)
            try:
                header = adb.run(["exec-out", "head", "-c", str(HEADER_BYTES), candidate["devicePath"]])
                identity = tombstone_identity(header)
                candidate["identity"] = identity
                candidate["matchesAttempt"] = matches_attempt(identity, runner, upper)
                if not candidate["matchesAttempt"]:
                    continue
                if len(manifest["files"]) >= MAX_FILES:
                    candidate["errors"].append("Retained file count limit reached")
                    continue
                record = preserve(adb, output, name, runner, upper, MAX_TOTAL_BYTES - reserved_bytes)
                manifest["files"].append(record)
                reserved_bytes += record["reservedBytes"]
                transferred_bytes += record["observedTransferredBytes"]
            except (CaptureError, OSError, ValueError, UnicodeError) as error:
                candidate["errors"].append(f"{type(error).__name__}: {error}")
        manifest["reservedBytes"] = reserved_bytes
        manifest["observedTransferredBytes"] = transferred_bytes
        incomplete = errors or any(c["errors"] for c in manifest["candidates"])
        if manifest["files"]:
            verified = all(f["transferIntegrityVerified"] for f in manifest["files"])
            manifest["captureStatus"] = "captured" if verified and not incomplete else "incomplete"
        else:
            manifest["captureStatus"] = "unavailable" if incomplete else "no_matching_tombstones"
    except (CaptureError, OSError, ValueError, UnicodeError) as error:
        errors.append(f"{type(error).__name__}: {error}")
    finally:
        (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--apk-provenance", type=Path, required=True)
    parser.add_argument("--instrumentation-status", type=int, required=True)
    parser.add_argument("--attempt-token", required=True)
    args = parser.parse_args()
    collect(args.output, args.apk_provenance, args.instrumentation_status, args.attempt_token)
    print(f"Native tombstone manifest written: {args.output / 'manifest.json'}")


if __name__ == "__main__":
    main()
