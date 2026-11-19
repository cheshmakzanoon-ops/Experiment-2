#!/usr/bin/env python3
"""Verify a pinned emulator kernel without changing the device or runtime settings."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys


CACHE_INFO = "/data/misc/apexdata/com.android.art/dalvik-cache/cache-info.xml"
COMMANDS = (
    ("properties", ["shell", "getprop"], True),
    ("kernel-release", ["shell", "uname", "-r"], True),
    ("proc-version", ["shell", "cat", "/proc/version"], True),
    ("modules", ["shell", "cat", "/proc/modules"], True),
    ("boot-id", ["shell", "cat", "/proc/sys/kernel/random/boot_id"], True),
    ("kernel-command-line", ["shell", "cat", "/proc/cmdline"], False),
    ("dmesg", ["shell", "dmesg"], True),
    ("logcat", ["logcat", "-b", "all", "-d", "-v", "threadtime"], True),
    ("art-cache-info", ["shell", "cat", CACHE_INFO], False),
    ("getconf-page-size", ["shell", "getconf", "PAGESIZE"], False),
)
MODULE_FAILURES = tuple(re.compile(pattern, re.IGNORECASE) for pattern in (
    r"\bunknown symbol\b",
    r"\bdisagrees about version of symbol\b",
    r"\bversion magic\b.*\bshould be\b",
    r"\bvermagic\b.*\b(?:mismatch|invalid|failed|failure|error)\b",
    r"\binvalid module format\b",
    r"\b(?:insmod|modprobe|libmodprobe|kernel module)\b.*\b(?:failed|failure|error|cannot|can't)\b",
    r"\b(?:failed|failure|error|cannot|can't)\b.*\b(?:insmod|modprobe|libmodprobe|kernel module)\b",
    r"\b(?:insmod|modprobe|module)\b.*\b(?:key rejected|required key not available)\b",
    r"\bloading of unsigned module is rejected\b",
))
# Stock userdebug images accept their unsigned modules and report this exact taint warning.
# It is not an ABI/load failure: https://docs.kernel.org/admin-guide/module-signing.html
PERMISSIVE_SIGNATURE = re.compile(
    r"\bmodule verification failed: signature and/or required key missing - tainting kernel\s*$",
    re.IGNORECASE,
)


def run_adb(arguments):
    """Retain failed commands too, including missing adb and bounded transport timeouts."""
    try:
        result = subprocess.run(
            ["adb", *arguments], capture_output=True, text=True,
            encoding="utf-8", errors="replace", timeout=20, check=False,
        )
        return {"returncode": result.returncode, "stdout": result.stdout, "stderr": result.stderr}
    except (OSError, subprocess.TimeoutExpired) as error:
        def decoded(value):
            return value.decode("utf-8", errors="replace") if isinstance(value, bytes) else value or ""

        return {
            "returncode": None,
            "stdout": decoded(getattr(error, "stdout", "")),
            "stderr": decoded(getattr(error, "stderr", "")),
            "error": f"{type(error).__name__}: {error}",
        }


def read_manifest(path, output, errors):
    try:
        raw = path.read_text(encoding="utf-8")
        (output / "manifest.json").write_text(raw, encoding="utf-8")
        manifest = json.loads(raw)
        if not isinstance(manifest, dict):
            raise ValueError("The manifest must be a JSON object")
        for key in ("kernel_release", "expected_fingerprint", "kernel_path"):
            value = manifest.get(key)
            if not isinstance(value, str) or not value.strip() or "\n" in value or "\r" in value:
                raise ValueError(f"Manifest {key} must be a nonempty single-line string")
        for key in ("expected_api", "kernel_bytes"):
            if type(manifest.get(key)) is not int or manifest[key] <= 0:
                raise ValueError(f"Manifest {key} must be a positive integer")
        if not isinstance(manifest.get("kernel_sha256"), str) or not re.fullmatch(
            r"[0-9a-f]{64}", manifest["kernel_sha256"]
        ):
            raise ValueError("Manifest kernel_sha256 must contain 64 lowercase hexadecimal digits")
        return manifest
    except (OSError, UnicodeError, ValueError) as error:
        errors.append(f"Manifest verification failed: {error}")
        return None


def verify_kernel_file(manifest, manifest_path, errors):
    path = Path(manifest["kernel_path"])
    if not path.is_absolute():
        path = manifest_path.parent / path
    observed = {"path": str(path)}
    try:
        digest = hashlib.sha256()
        size = 0
        with path.open("rb") as source:
            while chunk := source.read(1024 * 1024):
                size += len(chunk)
                digest.update(chunk)
        observed.update(bytes=size, sha256=digest.hexdigest())
        if size != manifest["kernel_bytes"]:
            errors.append(f"Kernel byte count differs: expected {manifest['kernel_bytes']}, observed {size}")
        if observed["sha256"] != manifest["kernel_sha256"]:
            errors.append("Kernel SHA-256 differs from the prepared manifest")
    except OSError as error:
        errors.append(f"Cannot read the pinned kernel: {error}")
    return observed


def module_failures(text):
    return [line for line in text.splitlines() if (
        any(pattern.search(line) for pattern in MODULE_FAILURES)
        or ("module verification failed" in line.lower() and not PERMISSIVE_SIGNATURE.search(line))
    )]


def verify(manifest_path, output, runner=run_adb):
    output.mkdir(parents=True, exist_ok=True)
    errors = []
    warnings = []
    manifest = read_manifest(manifest_path, output, errors)
    report = {
        "schemaVersion": 1,
        "capturedAtUtc": datetime.now(timezone.utc).isoformat(),
        "manifestPath": str(manifest_path),
        "expected": manifest,
        "errors": errors,
        "warnings": warnings,
        "commands": {},
    }
    if manifest is not None:
        report["localKernel"] = verify_kernel_file(manifest, manifest_path, errors)
    observations = {}
    # Continue collection after a mismatch so the failed setup remains reviewable.
    for name, arguments, required in COMMANDS:
        result = runner(arguments)
        observations[name] = result["stdout"]
        (output / f"{name}.txt").write_text(result["stdout"], encoding="utf-8")
        (output / f"{name}.stderr.txt").write_text(result["stderr"], encoding="utf-8")
        report["commands"][name] = {
            "argv": ["adb", *arguments],
            "required": required,
            **{key: value for key, value in result.items() if key not in ("stdout", "stderr")},
        }
        if result["returncode"] != 0:
            detail = result.get("error") or result["stderr"].strip() or f"exit {result['returncode']}"
            (errors if required else warnings).append(f"{name} collection failed: {detail}")
    properties = dict(re.findall(r"^\[([^\]]+)\]: \[(.*)\]\r?$", observations["properties"], re.MULTILINE))
    expected_properties = {"ro.kernel.qemu": "1", "ro.debuggable": "1"}
    if manifest is not None:
        expected_properties.update({
            "ro.build.version.sdk": str(manifest["expected_api"]),
            "ro.build.fingerprint": manifest["expected_fingerprint"],
        })
    for key, expected in expected_properties.items():
        if properties.get(key) != expected:
            errors.append(f"{key}: expected {expected!r}, observed {properties.get(key)!r}")
    release = observations["kernel-release"].strip()
    if manifest is not None and release != manifest["kernel_release"]:
        errors.append(f"Kernel release differs: expected {manifest['kernel_release']!r}, observed {release!r}")
    for name in ("proc-version", "boot-id", "dmesg"):
        if not observations[name].strip():
            errors.append(f"Required {name} evidence is empty")
    module_logs = observations["dmesg"] + "\n" + observations["logcat"]
    failures = module_failures(module_logs)
    report["moduleFailures"] = list(dict.fromkeys(failures))
    report["moduleWarnings"] = list(dict.fromkeys(
        line for line in module_logs.splitlines() if PERMISSIVE_SIGNATURE.search(line)
    ))
    if report["moduleWarnings"]:
        warnings.append("Existing unsigned-module policy accepted a module with signature taint; see moduleWarnings")
    if failures:
        errors.append("Kernel module loading or compatibility errors were recorded; see moduleFailures")
    collector_lines = [line for line in observations["logcat"].splitlines() if "Using CollectorType" in line]
    (output / "art-collectors.txt").write_text("\n".join(collector_lines) + "\n", encoding="utf-8")
    if not collector_lines:
        warnings.append("No ART collector startup line remains in the captured log buffer")
    report["runtime"] = {
        "properties": properties,
        "kernelRelease": release,
        "bootId": observations["boot-id"].strip(),
        "moduleCount": len(observations["modules"].splitlines()),
        "collectorLines": collector_lines,
        "getconfPageSize": observations["getconf-page-size"].strip(),
        "pageSizeAcceptance": "Enforced separately by RuntimeEnvironmentTest inside instrumentation",
    }
    report["passed"] = not errors
    (output / "verification.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = verify(args.manifest, args.output)
    print(f"Emulator kernel verification: {'PASS' if report['passed'] else 'FAIL'}")
    for error in report["errors"]:
        print(error, file=sys.stderr)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
