#!/usr/bin/env python3
"""Collect physical-device identity and validate an owner-approved measurement campaign.

This does not measure pen latency, run workloads, verify publisher signatures, or certify a
release. A complete reviewed campaign qualifies only its exact artifacts and declared workloads.
Empty, missing, synthetic, failed, or unknown evidence never qualifies.
"""
from __future__ import annotations

import argparse
import csv
from datetime import datetime, timezone
import hashlib
import io
import json
import math
from pathlib import Path, PurePosixPath
import re
import statistics
import subprocess
import uuid
import zipfile

from collect_installed_apks import digest, inspect_apk


ROLES = {"android8_low_memory", "android15_phone", "android16_phone",
         "tablet_active_stylus", "arm64_16k"}
WORKLOADS = {"drawing", "large_canvas", "long_session", "low_memory", "stylus"}
STYLUS_CHECKS = {"pressure", "tilt", "palm_rejection"}
# These are measurement meanings and sources, not acceptance thresholds.
METRICS = {
    "present_to_present_ms": ("frame_presentation", "ms", {"surface_presentation", "optical_video"}),
    "presented_fps": ("frame_presentation", "fps", {"surface_presentation", "optical_video"}),
    "input_to_photon_ms": ("input_to_photon", "ms", {"optical_video", "photodiode"}),
    "total_pss_mib": ("memory", "MiB", {"dumpsys_meminfo", "perfetto_process_memory"}),
    "thermal_status": ("thermal", "status", {"thermalservice", "thermal_api"}),
    "skin_temperature_c": ("thermal", "C", {"external_temperature_probe"}),
}
HEX40 = re.compile(r"[0-9a-f]{40}")
HEX64 = re.compile(r"[0-9a-f]{64}")
IDENTIFIER = re.compile(r"[a-zA-Z0-9][a-zA-Z0-9_.-]*")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def nonblank(value: object) -> bool:
    return isinstance(value, str) and bool(value.strip()) and value.strip().lower() not in {
        "unknown", "todo", "tbd", "pending", "n/a", "none"}


def number(value: object, minimum: float = 0, strict: bool = False) -> bool:
    return (type(value) in (int, float) and math.isfinite(value)
            and (value > minimum if strict else value >= minimum))


def timestamp(value: object) -> datetime:
    require(isinstance(value, str) and value.endswith("Z"), "UTC timestamp ending in Z is required")
    result = datetime.fromisoformat(value[:-1] + "+00:00")
    require(result.utcoffset().total_seconds() == 0, "Timestamp must be UTC")
    return result


def load_json(path: Path) -> dict:
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, f"Duplicate JSON key: {key}")
            result[key] = value
        return result

    def invalid(value):
        raise ValueError(f"Non-finite JSON number: {value}")

    result = json.loads(path.read_text(), object_pairs_hook=pairs, parse_constant=invalid)
    require(isinstance(result, dict), "JSON document must be an object")
    return result


def decision_digest(document: dict, section: str, evidence_key: str) -> str:
    """Bind a decision to all content except its own evidence reference (no hash cycle)."""
    payload = dict(document)
    payload[section] = dict(document[section])
    payload[section].pop(evidence_key, None)
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=True, allow_nan=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def local_file(root: Path, relative: object) -> Path:
    require(isinstance(relative, str) and bool(relative), "Evidence path is required")
    parts = PurePosixPath(relative).parts
    require(parts and not PurePosixPath(relative).is_absolute() and ".." not in parts
            and "\\" not in relative and PurePosixPath(relative).as_posix() == relative,
            f"Evidence path must be a canonical relative path: {relative}")
    current = root
    require(not current.is_symlink(), "Evidence root must not be a symlink")
    for part in parts:
        current = current / part
        require(not current.is_symlink(), f"Linked evidence is forbidden: {relative}")
    require(current.resolve().is_relative_to(root.resolve()) and current.is_file(),
            f"Missing or escaped evidence: {relative}")
    return current


def reference(root: Path, relative: str, allow_empty: bool = False) -> dict:
    path = local_file(root, relative)
    size = path.stat().st_size
    require(allow_empty or size > 0, f"Empty evidence: {relative}")
    with path.open("rb") as stream:
        checksum = digest(stream)
    return {"path": relative, "sizeBytes": size, "sha256": checksum}


def checked_reference(root: Path, value: object, allow_empty: bool = False) -> Path:
    require(isinstance(value, dict), "A path, sizeBytes and SHA-256 evidence reference is required")
    require(set(value) == {"path", "sizeBytes", "sha256"}, "Malformed evidence reference")
    require(type(value["sizeBytes"]) is int and value["sizeBytes"] >= 0
            and isinstance(value["sha256"], str) and HEX64.fullmatch(value["sha256"]),
            "Invalid evidence size or SHA-256")
    require(reference(root, value["path"], allow_empty) == value,
            f"Evidence bytes changed: {value['path']}")
    return local_file(root, value["path"])


def evidence_list(root: Path, values: object) -> None:
    require(isinstance(values, list) and values, "Raw evidence must not be empty")
    paths = []
    for value in values:
        paths.append(checked_reference(root, value))
    require(len(paths) == len(set(paths)), "Duplicate raw evidence references")


def indexed(items: object, name: str) -> dict:
    require(isinstance(items, list) and items, f"{name} must be a nonempty list")
    result = {}
    for item in items:
        require(isinstance(item, dict) and isinstance(item.get("id"), str)
                and IDENTIFIER.fullmatch(item["id"]), f"Invalid {name} identity")
        require(item["id"] not in result, f"Duplicate {name} identity: {item['id']}")
        result[item["id"]] = item
    return result


def command_arguments(package: str) -> dict:
    return {
        "properties": ["shell", "getprop"],
        "page_size": ["shell", "getconf", "PAGE_SIZE"],
        "memory": ["shell", "cat", "/proc/meminfo"],
        "package": ["shell", "dumpsys", "package", package],
        "apk_paths": ["shell", "pm", "path", package],
        "display": ["shell", "dumpsys", "display"],
        "input": ["shell", "dumpsys", "input"],
        "battery": ["shell", "dumpsys", "battery"],
        "thermal": ["shell", "dumpsys", "thermalservice"],
    }


def checked_apk(path: Path) -> dict:
    inspected = inspect_apk(path)
    require(not inspected["errors"], "Incomplete APK snapshot")
    with zipfile.ZipFile(path) as archive:
        require("AndroidManifest.xml" in archive.namelist(), "APK manifest is missing")
    return inspected


def capture(output: Path, serial: str, package: str, campaign_id: str, revision: str,
            adb: str = "adb") -> dict:
    require(nonblank(serial), "An explicit adb serial is required")
    require(re.fullmatch(r"[a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)+", package),
            "Invalid Android package")
    require(HEX40.fullmatch(revision), "Exact 40-character source revision is required")
    require(str(uuid.UUID(campaign_id)) == campaign_id, "Canonical campaign UUID is required")
    output.mkdir(parents=True, exist_ok=False)
    (output / "raw").mkdir()
    report = {"schemaVersion": 1, "campaignId": campaign_id, "revision": revision,
              "serial": serial, "packageName": package, "startedAtUtc": utc_now(),
              "commands": {}, "installedApks": [], "errors": [], "qualified": False,
              "scope": "Identity capture only; no physical, stylus, or performance qualification"}

    def execute(name, arguments):
        try:
            result = subprocess.run([adb, "-s", serial, *arguments], capture_output=True, timeout=30,
                                    check=False)
            status, stdout, stderr = result.returncode, result.stdout, result.stderr
        except subprocess.TimeoutExpired as error:
            status, stdout, stderr = None, error.stdout or b"", (error.stderr or b"") + b"\nTimed out\n"
        except OSError as error:
            status, stdout, stderr = None, b"", str(error).encode()
        record = {"arguments": arguments, "exitCode": status}
        for suffix, data in (("stdout", stdout), ("stderr", stderr)):
            name_path = f"raw/{name}.{suffix}.txt"
            (output / name_path).write_bytes(data)
            record[suffix] = reference(output, name_path, allow_empty=True)
        report["commands"][name] = record
        return status, stdout.decode("utf-8", errors="replace")

    for name, arguments in command_arguments(package).items():
        status, _ = execute(name, arguments)
        # Older Android devices may not expose thermalservice. This remains unknown;
        # the campaign must supply an approved external thermal measurement instead.
        if status != 0 and name != "thermal":
            report["errors"].append(f"Identity command failed: {name}, exit={status}")
    paths = (output / "raw/apk_paths.stdout.txt").read_text().splitlines()
    valid_paths = bool(paths) and all(re.fullmatch(r"package:/[^\r\n]+\.apk", item) for item in paths)
    if not valid_paths or len(paths) != len(set(paths)):
        report["errors"].append("Missing, duplicate, or malformed installed APK paths")
    else:
        (output / "installed").mkdir()
        for index, line in enumerate(paths):
            remote, relative = line[len("package:"):], f"installed/{index:02d}.apk"
            status, _ = execute(f"pull_{index:02d}", ["pull", remote, str((output / relative).resolve())])
            try:
                require(status == 0, "adb pull failed")
                item = reference(output, relative)
                checked_apk(output / relative)
                report["installedApks"].append(item)
            except (OSError, ValueError) as error:
                report["errors"].append(f"APK capture {index}: {error}")
    report["completedAtUtc"] = utc_now()
    report["captureComplete"] = not report["errors"]
    (output / "capture.json").write_text(json.dumps(report, indent=2) + "\n")
    return report


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def measured_runtime(path: Path, plan: dict) -> dict:
    root, record = path.parent, load_json(path)
    require(type(record.get("schemaVersion")) is int and record["schemaVersion"] == 1
            and record.get("campaignId") == plan["campaignId"]
            and record.get("revision") == plan["source"]["revision"]
            and record.get("packageName") == plan["application"]["packageName"],
            "Capture belongs to another campaign, source, or package")
    require(record.get("captureComplete") is True and record.get("errors") == []
            and nonblank(record.get("serial")), "Incomplete device capture")
    require(timestamp(record["startedAtUtc"]) <= timestamp(record["completedAtUtc"]),
            "Device capture timestamps are reversed")
    outputs = {}
    expected_commands = command_arguments(record["packageName"])
    commands = record.get("commands")
    require(isinstance(commands, dict), "Device command records missing")
    for name, arguments in expected_commands.items():
        item = commands.get(name)
        require(isinstance(item, dict) and item.get("arguments") == arguments,
                f"Missing or substituted identity command: {name}")
        stdout = checked_reference(root, item.get("stdout"), allow_empty=True)
        checked_reference(root, item.get("stderr"), allow_empty=True)
        if name != "thermal":
            require(type(item.get("exitCode")) is int and item["exitCode"] == 0,
                    f"Failed device identity command: {name}")
            require(stdout.stat().st_size > 0, f"Empty device identity: {name}")
        outputs[name] = stdout.read_text()
    props = dict(re.findall(r"(?m)^\[([^\]]+)\]: \[([^\]]*)\]$", outputs["properties"]))
    required_props = ("ro.product.manufacturer", "ro.product.model", "ro.build.fingerprint",
                      "ro.build.version.sdk", "ro.product.cpu.abilist", "ro.hardware")
    require(all(nonblank(props.get(key)) for key in required_props), "Incomplete measured Android properties")
    emulator = (props.get("ro.kernel.qemu") == "1" or props.get("ro.boot.qemu") == "1"
                or re.search(r"(?i)goldfish|ranchu|cuttlefish|emulator|generic|sdk_gphone",
                             " ".join(props.get(key, "") for key in required_props)))
    require(not emulator, "Emulator or virtual-device properties cannot qualify physical hardware")
    total = re.findall(r"(?m)^MemTotal:\s+(\d+)\s+kB\s*$", outputs["memory"])
    require(len(total) == 1 and int(total[0]) > 0, "Measured MemTotal is missing or ambiguous")
    sdk, page_size = int(props["ro.build.version.sdk"]), int(outputs["page_size"].strip())
    require(sdk >= 26 and page_size in (4096, 16384), "Unknown or unsupported measured runtime")
    codes = re.findall(r"(?m)^\s*versionCode=(\d+)\b", outputs["package"])
    names = re.findall(r"(?m)^\s*versionName=(.+?)\s*$", outputs["package"])
    require(set(codes) == {str(plan["application"]["versionCode"])}
            and set(names) == {plan["application"]["versionName"]}, "Installed application version mismatch")
    require("DEBUGGABLE" not in outputs["package"], "Installed application is debuggable")
    flags = re.findall(r"(?m)^\s*(?:pkgFlags|flags)=\[([^\]]+)\]", outputs["package"])
    require(flags, "Installed package flags were not measured")
    primary_abis = set(re.findall(r"(?m)^\s*primaryCpuAbi=(\S+)\s*$", outputs["package"]))
    require(len(primary_abis) == 1 and primary_abis <= {"arm64-v8a", "armeabi-v7a", "x86_64", "x86"},
            "Installed primary ABI is missing or unknown")
    primary_abi = next(iter(primary_abis))
    require(primary_abi in props["ro.product.cpu.abilist"].split(","), "Installed ABI contradicts measured device ABIs")
    installed = record.get("installedApks")
    require(isinstance(installed, list) and installed, "Installed APK bytes are missing")
    remote_paths = outputs["apk_paths"].splitlines()
    require(len(remote_paths) == len(installed) and len(set(remote_paths)) == len(remote_paths)
            and all(re.fullmatch(r"package:/[^\r\n]+\.apk", item) for item in remote_paths),
            "Installed APK inventory does not match pm path")
    hashes = []
    for index, item in enumerate(installed):
        apk = checked_reference(root, item)
        require(item["path"] == f"installed/{index:02d}.apk", "Unexpected installed APK snapshot path")
        pull = commands.get(f"pull_{index:02d}")
        require(isinstance(pull, dict) and type(pull.get("exitCode")) is int and pull["exitCode"] == 0
                and isinstance(pull.get("arguments"), list) and len(pull["arguments"]) == 3
                and pull["arguments"][:2] == ["pull", remote_paths[index][len("package:"):]],
                "Missing or mismatched installed APK pull record")
        checked_reference(root, pull.get("stdout"), allow_empty=True)
        checked_reference(root, pull.get("stderr"), allow_empty=True)
        checked_apk(apk)
        hashes.append(item["sha256"])
    require(sorted(hashes) == sorted(item["sha256"] for item in plan["application"]["artifacts"]),
            "Installed APK bytes do not equal the approved artifact set")
    return {"serial": record["serial"], "manufacturer": props["ro.product.manufacturer"],
            "model": props["ro.product.model"], "fingerprint": props["ro.build.fingerprint"],
            "sdk": sdk, "abis": props["ro.product.cpu.abilist"].split(","),
            "installedPrimaryAbi": primary_abi,
            "kernelPageSizeBytes": page_size, "totalRamMiB": int(total[0]) / 1024,
            "captureStartedAtUtc": record["startedAtUtc"],
            "captureCompletedAtUtc": record["completedAtUtc"], "installedApkSha256": sorted(hashes)}


def validate_plan(root: Path, plan: dict, revision: str) -> tuple[dict, dict, dict]:
    require(type(plan.get("schemaVersion")) is int and plan["schemaVersion"] == 1, "Unknown plan schema")
    require(str(uuid.UUID(plan["campaignId"])) == plan["campaignId"], "Canonical campaign UUID required")
    source, app, acceptance = plan["source"], plan["application"], plan["acceptance"]
    require(isinstance(revision, str) and HEX40.fullmatch(revision)
            and source.get("revision") == revision, "Plan does not match the exact requested source revision")
    require(isinstance(source.get("gitTree"), str) and HEX40.fullmatch(source["gitTree"])
            and nonblank(source.get("buildId")), "Exact source tree and build identity are required")
    require(app.get("packageName") == "com.artflow.studio" and type(app.get("versionCode")) is int
            and app["versionCode"] > 0 and nonblank(app.get("versionName")), "Invalid application identity")
    require(app.get("buildType") == "release" and app.get("debuggable") is False
            and app.get("minified") is True, "Physical campaign requires the minified non-debuggable release build")
    build = load_json(checked_reference(root, source.get("buildEvidence")))
    expected_build = {"schemaVersion": 1, "revision": revision, "gitTree": source["gitTree"],
                      "buildId": source["buildId"], "sourceDirty": False,
                      **{key: app[key] for key in ("packageName", "versionCode", "versionName",
                                                  "buildType", "debuggable", "minified")}}
    require(all(type(build.get(key)) is type(value) and build[key] == value
                for key, value in expected_build.items()), "Retained build identity does not match the approved plan")
    evidence_list(root, app.get("artifacts"))
    require(len({item["sha256"] for item in app["artifacts"]}) == len(app["artifacts"]),
            "Duplicate approved APK bytes")
    inspected = [checked_apk(checked_reference(root, item)) for item in app["artifacts"]]
    require(any(item["dex"] for item in inspected), "Approved APK set has no executable application DEX")
    require(build.get("apkSha256") == sorted(item["sha256"] for item in app["artifacts"]),
            "Build identity is not bound to the exact APK set")
    require(nonblank(acceptance.get("owner")), "Named acceptance owner is required")
    timestamp(acceptance.get("approvedAtUtc"))
    approval = load_json(checked_reference(root, acceptance.get("approvalEvidence")))
    require(type(approval.get("schemaVersion")) is int and approval["schemaVersion"] == 1
            and approval.get("owner") == acceptance["owner"]
            and approval.get("approvedAtUtc") == acceptance["approvedAtUtc"]
            and approval.get("planSha256") == decision_digest(plan, "acceptance", "approvalEvidence"),
            "Owner approval does not cover the exact artifact, source, workload and acceptance plan")
    require(isinstance(acceptance.get("requiredRoles"), list)
            and set(acceptance["requiredRoles"]) == ROLES
            and len(acceptance["requiredRoles"]) == len(ROLES), "Required physical device coverage was changed or omitted")
    require(number(acceptance.get("lowMemoryMaxTotalRamMiB"), strict=True),
            "Owner must define the low-memory hardware ceiling; no default is supplied")
    criteria = indexed(acceptance.get("criteria"), "criteria")
    domains = set()
    for item in criteria.values():
        require(item.get("metric") in METRICS, "Unknown measurement metric")
        domain, unit, _ = METRICS[item["metric"]]
        require(item.get("unit") == unit, "Metric unit mismatch")
        require(item.get("statistic") in {"min", "max", "mean", "p50", "p95", "p99"}
                and item.get("operator") in {"<", "<=", ">", ">="} and number(item.get("limit")),
                "Explicit finite acceptance limit, comparison and statistic are required")
        require(type(item.get("minSamples")) is int and item["minSamples"] > 0
                and number(item.get("minDurationSeconds"), strict=True),
                "Owner must approve sample count and measured duration")
        require(nonblank(item.get("definition")) and nonblank(item.get("protocol")),
                "Metric boundaries and measurement protocol must be defined")
        domains.add(domain)
    require(domains == {"frame_presentation", "input_to_photon", "memory", "thermal"},
            "Frame presentation, optical input-to-photon, memory, and thermal criteria are all required")
    workloads = indexed(acceptance.get("workloads"), "workloads")
    tags = set()
    for item in workloads.values():
        require(isinstance(item.get("tags"), list) and item["tags"] and set(item["tags"]) <= WORKLOADS,
                "Missing or unknown workload tags")
        tags.update(item["tags"])
        require(nonblank(item.get("description")) and nonblank(item.get("steps"))
                and nonblank(item.get("brush")), "Workload, exact steps and brush must be defined")
        canvas = item.get("canvas", {})
        require(all(type(canvas.get(key)) is int and canvas[key] > 0
                    for key in ("width", "height", "layers", "frames")), "Workload canvas must be explicit")
        require(number(item.get("durationSeconds"), strict=True) and number(item.get("warmupSeconds")),
                "Workload duration and excluded warmup must be explicit")
        checked_reference(root, item.get("projectEvidence"))
        checked_reference(root, item.get("brushSettingsEvidence"))
    require(tags == WORKLOADS, "Drawing, large-canvas, long-session, low-memory and stylus workloads are required")
    runs = indexed(acceptance.get("runs"), "planned runs")
    roles, exercised = set(), set()
    for item in runs.values():
        require(item.get("role") in ROLES and item.get("workloadId") in workloads,
                "Planned run role/workload is missing")
        selected = item.get("criterionIds")
        require(isinstance(selected, list) and selected and len(set(selected)) == len(selected)
                and set(selected) <= criteria.keys(), "Planned run has missing, repeated or unknown criteria")
        run_domains = {METRICS[criteria[key]["metric"]][0] for key in selected}
        run_tags = set(workloads[item["workloadId"]]["tags"])
        require({"frame_presentation", "memory"} <= run_domains,
                "Every physical run needs frame-presentation and memory evidence")
        if "long_session" in run_tags:
            require("thermal" in run_domains, "Long sessions require measured thermal evidence")
            require(all(criteria[key]["minDurationSeconds"] >= workloads[item["workloadId"]]["durationSeconds"]
                        for key in selected), "Long-session metric coverage must span the approved session duration")
        if item["role"] == "android8_low_memory":
            require("low_memory" in run_tags, "Low-memory hardware must run the low-memory workload")
        if item["role"] == "tablet_active_stylus":
            require("stylus" in run_tags, "Tablet stylus role must execute the stylus workload")
        if "stylus" in run_tags:
            require(item.get("inputSource") == "physical_stylus" and "input_to_photon" in run_domains,
                    "Stylus runs require actual pen input and optical latency measurement")
        require(item.get("inputSource") in {"physical_stylus", "physical_touch"},
                "Injected input cannot qualify this physical campaign")
        roles.add(item["role"])
        exercised.add(item["workloadId"])
    require(roles == ROLES and exercised == workloads.keys(), "Planned physical roles or workloads are unexercised")
    require(set().union(*(set(item["criterionIds"]) for item in runs.values())) == criteria.keys(),
            "Approved criteria were not assigned to a run")
    return criteria, workloads, runs


def samples(path: Path, metric: str) -> tuple[list[float], float, float]:
    reader = csv.DictReader(io.StringIO(path.read_text()))
    require(reader.fieldnames == ["sample_id", "elapsed_seconds", "value"], "Unexpected sample CSV columns")
    values, times, identities = [], [], set()
    for row in reader:
        require(set(row) == {"sample_id", "elapsed_seconds", "value"}
                and nonblank(row["sample_id"]) and row["sample_id"] not in identities,
                "Duplicate, empty or malformed sample identity")
        elapsed, value = float(row["elapsed_seconds"]), float(row["value"])
        require(number(elapsed) and number(value), "Samples must be finite and nonnegative")
        require(not times or elapsed >= times[-1], "Samples must be in monotonic observation order")
        if metric in {"present_to_present_ms", "input_to_photon_ms", "total_pss_mib"}:
            require(value > 0, "Duration and memory samples must be positive")
        if metric == "thermal_status":
            require(value.is_integer() and 0 <= value <= 6, "Android thermal status must be an integer from 0 to 6")
        identities.add(row["sample_id"])
        times.append(elapsed)
        values.append(value)
    require(values, "No measured samples")
    return values, times[-1] - times[0], times[-1]


def statistic(values: list[float], name: str) -> float:
    if name == "mean":
        return statistics.fmean(values)
    if name == "min":
        return min(values)
    if name == "max":
        return max(values)
    # Nearest-rank percentile, deliberately specified rather than library-dependent interpolation.
    return sorted(values)[math.ceil(int(name[1:]) * len(values) / 100) - 1]


def validate_measurement(root: Path, measurement: dict, criterion: dict, duration: float) -> dict:
    metric = criterion["metric"]
    domain, unit, methods = METRICS[metric]
    require(measurement.get("method") in methods and measurement.get("unit") == unit,
            "Measurement method/unit cannot establish this metric; software timing is not optical latency")
    require(nonblank(measurement.get("scope")), "Exact measured surface, sensor or optical endpoints are required")
    if measurement.get("method") == "surface_presentation":
        require(nonblank(measurement.get("surfaceName")), "The actual GLSurfaceView layer must be identified")
        checked_reference(root, measurement.get("surfaceCoverageEvidence"))
    evidence_list(root, measurement.get("rawEvidence"))
    checked_reference(root, measurement.get("analysisEvidence"))
    sample_path = checked_reference(root, measurement.get("samples"))
    require(sample_path not in {checked_reference(root, item) for item in measurement["rawEvidence"]},
            "Derived CSV cannot substitute for the raw capture")
    if domain == "input_to_photon":
        require(number(measurement.get("uncertaintyMs"), strict=True)
                and nonblank(measurement.get("calibration")),
                "Physical latency needs measured uncertainty and calibration details")
    values, span, last = samples(sample_path, metric)
    require(len(values) >= criterion["minSamples"] and span >= criterion["minDurationSeconds"],
            "Insufficient measured samples or duration")
    require(last <= duration, "Samples extend outside the declared measured run")
    result = statistic(values, criterion["statistic"])
    # Uncertainty is accounted for conservatively at the decision boundary, not silently discarded.
    uncertainty = measurement.get("uncertaintyMs", 0) if domain == "input_to_photon" else 0
    comparison = result + uncertainty if criterion["operator"] in {"<", "<="} else result - uncertainty
    limit, operator = criterion["limit"], criterion["operator"]
    passed = {"<": comparison < limit, "<=": comparison <= limit,
              ">": comparison > limit, ">=": comparison >= limit}[operator]
    return {"criterionId": criterion["id"], "metric": metric, "unit": unit, "samples": len(values),
            "observedSpanSeconds": span, "statistic": criterion["statistic"], "value": result,
            "comparisonValueIncludingUncertainty": comparison, "operator": operator,
            "limit": limit, "passed": passed}


def verify(plan_path: Path, campaign_path: Path, revision: str) -> dict:
    report = {"schemaVersion": 1, "revision": revision, "status": "UNQUALIFIED", "qualified": False,
              "publisherSignatureQualified": False, "releaseApproved": False,
              "scope": "Exact recorded APK set, physical devices, workloads and owner-approved criteria only",
              "problems": [], "failures": [], "devices": {}, "runs": {}}
    try:
        root = plan_path.parent
        require(plan_path.parent.resolve() == campaign_path.parent.resolve(),
                "Plan and campaign must share one evidence root")
        plan = load_json(local_file(root, plan_path.name))
        campaign = load_json(local_file(root, campaign_path.name))
        require(type(campaign.get("schemaVersion")) is int and campaign["schemaVersion"] == 1,
                "Unknown campaign schema")
        require(checked_reference(root, campaign.get("plan")) == plan_path, "Campaign uses another plan")
        report["plan"] = reference(root, plan_path.name)
        report["campaign"] = reference(root, campaign_path.name)
        criteria, workloads, planned = validate_plan(root, plan, revision)
        report["campaignId"] = plan["campaignId"]
        report["source"] = plan["source"]
        report["application"] = plan["application"]
        approved = timestamp(plan["acceptance"]["approvedAtUtc"])
        devices = indexed(campaign.get("devices"), "devices")
        measured = {}
        for identity, item in devices.items():
            require(item.get("physical") is True and nonblank(item.get("operator")),
                    "Named physical-hardware observation is required; emulators/unknowns do not qualify")
            checked_reference(root, item.get("physicalEvidence"))
            require(item.get("formFactor") in {"phone", "tablet"}, "Measured physical form factor is required")
            runtime = measured_runtime(checked_reference(root, item.get("capture")), plan)
            after = measured_runtime(checked_reference(root, item.get("postCapture")), plan)
            require(all(runtime[key] == after[key] for key in (
                "serial", "manufacturer", "model", "fingerprint", "sdk", "abis", "kernelPageSizeBytes",
                "installedPrimaryAbi", "totalRamMiB", "installedApkSha256")),
                    "Device, runtime or installed artifact changed during the campaign")
            require(timestamp(runtime["captureCompletedAtUtc"]) < timestamp(after["captureStartedAtUtc"]),
                    "Separate before/after identity captures must bracket the measured runs")
            runtime["postCaptureStartedAtUtc"] = after["captureStartedAtUtc"]
            runtime["postCaptureCompletedAtUtc"] = after["captureCompletedAtUtc"]
            require(approved <= timestamp(runtime["captureStartedAtUtc"]),
                    "Acceptance criteria must be approved before device collection")
            measured[identity] = runtime
            report["devices"][identity] = runtime
        require(len({item["serial"] for item in measured.values()}) == len(measured),
                "One physical device was relabelled with multiple identities")
        runs = indexed(campaign.get("runs"), "observed runs")
        require(runs.keys() == planned.keys(), "Missing or unexpected attempts; preserve failures and approve a new campaign for retests")
        ends, used_devices = [], set()
        for identity, item in runs.items():
            spec = planned[identity]
            require(item.get("deviceId") in devices, "Run device is missing")
            device, runtime = devices[item["deviceId"]], measured[item["deviceId"]]
            used_devices.add(item["deviceId"])
            role = spec["role"]
            if role == "android8_low_memory":
                require(runtime["sdk"] in {26, 27} and runtime["totalRamMiB"] <= plan["acceptance"]["lowMemoryMaxTotalRamMiB"],
                        "Android 8 low-memory role does not match measured OS/RAM")
            if role in {"android15_phone", "android16_phone"}:
                require(runtime["sdk"] == {"android15_phone": 35, "android16_phone": 36}[role]
                        and device["formFactor"] == "phone", "Phone OS/form-factor role mismatch")
            if role == "tablet_active_stylus":
                require(device["formFactor"] == "tablet", "Tablet role was measured on another form factor")
            if role == "arm64_16k":
                require("arm64-v8a" in runtime["abis"] and runtime["kernelPageSizeBytes"] == 16384
                        and runtime["sdk"] >= 35 and runtime["installedPrimaryAbi"] == "arm64-v8a",
                        "ARM64 16 KB role lacks measured hardware/runtime coverage")
            if item.get("status") == "failed":
                report["failures"].append(f"{identity}: workload failed")
            require(item.get("status") == "completed", f"{identity}: run failed, skipped, unknown or incomplete")
            require(item.get("inputSource") == spec["inputSource"], "Input source is missing or synthetic")
            start, end = timestamp(item.get("startedAtUtc")), timestamp(item.get("endedAtUtc"))
            workload = workloads[spec["workloadId"]]
            duration = (end - start).total_seconds() - workload["warmupSeconds"]
            require(timestamp(runtime["captureCompletedAtUtc"]) <= start <= end
                    <= timestamp(runtime["postCaptureStartedAtUtc"])
                    and duration >= workload["durationSeconds"], "Workload timestamps/duration do not cover the approved protocol")
            ends.append(end)
            evidence_list(root, item.get("rawEvidence"))
            environment = item.get("environment", {})
            require(number(environment.get("ambientTemperatureC"))
                    and number(environment.get("displayRefreshHz"), strict=True)
                    and number(environment.get("brightnessPercent")) and environment["brightnessPercent"] <= 100
                    and number(environment.get("batteryStartPercent")) and environment["batteryStartPercent"] <= 100
                    and number(environment.get("batteryEndPercent")) and environment["batteryEndPercent"] <= 100
                    and type(environment.get("charging")) is bool and nonblank(environment.get("powerMode")),
                    "Measured runtime/thermal/display conditions are incomplete")
            evidence_list(root, environment.get("evidence"))
            observations = indexed(item.get("measurements"), "measurements")
            require(observations.keys() == set(spec["criterionIds"]), "Missing, repeated or unplanned metric observations")
            results = []
            for key, observation in observations.items():
                if observation.get("method") in {"thermalservice", "thermal_api"}:
                    require(runtime["sdk"] >= 29, "Thermal status is unavailable on this Android API")
                result = validate_measurement(root, observation, criteria[key], duration)
                if not result["passed"]:
                    report["failures"].append(f"{identity}/{key}: measured acceptance criterion failed")
                results.append(result)
            if "stylus" in workload["tags"] or spec["inputSource"] == "physical_stylus":
                stylus = device.get("stylus", {})
                require(nonblank(stylus.get("manufacturer")) and nonblank(stylus.get("model")),
                        "Actual physical stylus identity is required")
                checked_reference(root, stylus.get("evidence"))
                checks = indexed(item.get("stylusChecks"), "stylus checks")
                require(STYLUS_CHECKS <= checks.keys(), "Pressure, tilt and palm-rejection observations are required")
                for key, check in checks.items():
                    if check.get("status") == "failed":
                        report["failures"].append(f"{identity}/{key}: physical stylus check failed")
                    require(check.get("status") == "passed" and nonblank(check.get("expected"))
                            and nonblank(check.get("observed")), f"{identity}/{key}: stylus result failed or unknown")
                    evidence_list(root, check.get("evidence"))
            report["runs"][identity] = {"deviceId": item["deviceId"], "role": role, "results": results}
        require(used_devices == devices.keys(), "Unexercised device identities are not coverage")
        review = campaign.get("review", {})
        if review.get("decision") == "rejected":
            report["failures"].append("Reviewer rejected the retained physical campaign")
        require(review.get("decision") == "accepted" and nonblank(review.get("reviewer")),
                "Named reviewer must accept raw capture provenance, metric derivation, and observed hardware")
        require(timestamp(review.get("reviewedAtUtc")) >= max(ends), "Review predates measured work")
        decision = load_json(checked_reference(root, review.get("evidence")))
        require(type(decision.get("schemaVersion")) is int and decision["schemaVersion"] == 1
                and decision.get("reviewer") == review["reviewer"]
                and decision.get("reviewedAtUtc") == review["reviewedAtUtc"]
                and decision.get("decision") == "accepted"
                and decision.get("campaignSha256") == decision_digest(campaign, "review", "evidence"),
                "Reviewer decision does not cover this exact campaign and its retained evidence")
        report["acceptanceOwner"] = plan["acceptance"]["owner"]
        report["reviewer"] = review["reviewer"]
    except (OSError, ValueError, KeyError, TypeError, AttributeError, csv.Error, OverflowError,
            zipfile.BadZipFile) as error:
        report["problems"].append(f"{type(error).__name__}: {error}")
    report["qualified"] = not report["problems"] and not report["failures"]
    report["status"] = ("QUALIFIED_FOR_RECORDED_PHYSICAL_CAMPAIGN" if report["qualified"]
                        else "FAILED" if report["failures"] else "UNQUALIFIED")
    return report


def initialize(output: Path) -> None:
    """Write incomplete forms, never fabricated results or default passing thresholds."""
    output.mkdir(parents=True, exist_ok=False)
    criteria = []
    for identity, metric, statistic_name in (
            ("frames", "present_to_present_ms", "p95"),
            ("optical_latency", "input_to_photon_ms", "p95"),
            ("memory", "total_pss_mib", "max"),
            ("thermal", "skin_temperature_c", "max")):
        criteria.append({"id": identity, "metric": metric, "unit": METRICS[metric][1],
                         "statistic": statistic_name, "operator": "<=", "limit": None,
                         "minSamples": None, "minDurationSeconds": None, "definition": None,
                         "protocol": None})
    workloads = [{"id": name, "tags": [name], "description": None, "steps": None, "brush": None,
                  "canvas": {key: None for key in ("width", "height", "layers", "frames")},
                  "durationSeconds": None, "warmupSeconds": None, "projectEvidence": None,
                  "brushSettingsEvidence": None} for name in sorted(WORKLOADS)]
    matrix = (("android8_low_memory", "low_memory"), ("android15_phone", "drawing"),
              ("android16_phone", "large_canvas"), ("tablet_active_stylus", "stylus"),
              ("arm64_16k", "long_session"))
    runs = [{"id": role, "role": role, "workloadId": workload,
             "inputSource": "physical_stylus" if workload == "stylus" else "physical_touch",
             "criterionIds": ["frames", "memory"] + (["optical_latency"] if workload == "stylus"
                                                      else ["thermal"] if workload == "long_session" else [])}
            for role, workload in matrix]
    plan = {"schemaVersion": 1, "campaignId": str(uuid.uuid4()),
            "source": {"revision": None, "gitTree": None, "buildId": None, "buildEvidence": None},
            "application": {"packageName": "com.artflow.studio", "versionCode": None, "versionName": None,
                            "buildType": "release", "debuggable": False, "minified": True, "artifacts": []},
            "acceptance": {"owner": None, "approvedAtUtc": None, "approvalEvidence": None,
                           "requiredRoles": sorted(ROLES), "lowMemoryMaxTotalRamMiB": None,
                           "criteria": criteria, "workloads": workloads, "runs": runs}}
    (output / "plan.json").write_text(json.dumps(plan, indent=2) + "\n")
    campaign = {"schemaVersion": 1, "plan": reference(output, "plan.json"), "devices": [], "runs": [],
                "review": {"reviewer": None, "reviewedAtUtc": None, "decision": "unknown", "evidence": None}}
    (output / "campaign.json").write_text(json.dumps(campaign, indent=2) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    template = commands.add_parser("init", help="Create incomplete forms; no acceptance limits or results")
    template.add_argument("--output", type=Path, required=True)
    approval_hash = commands.add_parser("approval-digest", help="Hash the exact plan for its named owner's approval")
    approval_hash.add_argument("--plan", type=Path, required=True)
    review_hash = commands.add_parser("review-digest", help="Hash the completed campaign for its reviewer's decision")
    review_hash.add_argument("--campaign", type=Path, required=True)
    recorder = commands.add_parser("capture", help="Read one explicit device; never installs or injects input")
    recorder.add_argument("--output", type=Path, required=True)
    recorder.add_argument("--serial", required=True)
    recorder.add_argument("--package", default="com.artflow.studio")
    recorder.add_argument("--campaign-id", required=True)
    recorder.add_argument("--revision", required=True)
    recorder.add_argument("--adb", default="adb")
    hasher = commands.add_parser("reference", help="Hash one retained file relative to the campaign root")
    hasher.add_argument("--root", type=Path, required=True)
    hasher.add_argument("--file", required=True)
    verifier = commands.add_parser("verify")
    verifier.add_argument("--plan", type=Path, required=True)
    verifier.add_argument("--campaign", type=Path, required=True)
    verifier.add_argument("--revision", required=True)
    verifier.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "init":
        initialize(args.output)
        print("UNQUALIFIED: incomplete plan/campaign forms created; no hardware measurements collected")
        return
    if args.command == "approval-digest":
        print(decision_digest(load_json(args.plan), "acceptance", "approvalEvidence"))
        return
    if args.command == "review-digest":
        print(decision_digest(load_json(args.campaign), "review", "evidence"))
        return
    if args.command == "capture":
        report = capture(args.output, args.serial, args.package, args.campaign_id, args.revision, args.adb)
        print(json.dumps({"captureComplete": report["captureComplete"], "qualified": False,
                          "errors": report["errors"]}, indent=2))
        raise SystemExit(0 if report["captureComplete"] else 1)
    if args.command == "reference":
        print(json.dumps(reference(args.root, args.file), indent=2))
        return
    report = verify(args.plan, args.campaign, args.revision)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    # Verification output must not replace either input or retained evidence.
    require(not args.output.exists(), "Choose a new verification output path; existing evidence is immutable")
    args.output.write_text(json.dumps(report, indent=2, allow_nan=False) + "\n")
    print(json.dumps(report, indent=2, allow_nan=False))
    raise SystemExit(0 if report["qualified"] else 1)


if __name__ == "__main__":
    main()
