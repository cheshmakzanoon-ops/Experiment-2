#!/usr/bin/env python3
"""Qualify a copy-signed normal release payload through a separate UI-only Android APK.

One attempt, on an explicitly designated fresh API 36 emulator. No app data is injected,
cleared or read through run-as. No publisher key or production approval is involved.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile
import uuid
import xml.etree.ElementTree as ET
import zipfile
import zlib

import qualify_publisher_artifact as publisher
import package_upgrade_build as source_evidence

APP = "com.artflow.studio"
DRIVER = "com.artflow.qualification"
RUNNER = DRIVER + "/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS = DRIVER + ".ReleasePayloadWorkflowTest"
TEST_METHOD = "paintSaveProcessDeathReopenAndSharePreservePixels"
ANDROID = publisher.ANDROID
TOKEN = re.compile(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
CHECKS = (
    "productionProcess", "nonDebuggableRelease", "visibleUiCreatedArtwork",
    "pointerPaintedNonblankPixels", "visibleUiSaved", "freshProcessReopened",
    "fileProviderGrantRead", "allReopenedExportPixelsEqual",
)
APK_PATHS = {
    "unsigned": "app/build/outputs/apk/release/app-release-unsigned.apk",
    "driver": "release-ui/build/outputs/apk/release/release-ui-release.apk",
}
SHRINKER_FILES = ("mapping.txt", "configuration.txt", "usage.txt", "resources.txt")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def write_json(path: Path, value: dict) -> None:
    # Receipts are immutable: an earlier failure or attempt cannot become a later success.
    source_evidence.require_unlinked(path)
    encoded = json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n"
    with path.open("x", encoding="utf-8") as stream:
        stream.write(encoded)


def sha(path: Path) -> str:
    return publisher.file_record(path)["sha256"]


def retained(source: Path, destination: Path) -> dict:
    before = publisher.file_record(source)
    with source.open("rb") as input_stream, destination.open("xb") as output:
        shutil.copyfileobj(input_stream, output)
    result = publisher.file_record(destination)
    require(result["sha256"] == before["sha256"], "Input changed while retaining its bytes")
    return result


class Commands:
    def __init__(self, root: Path):
        root.mkdir()
        self.root, self.index = root, 0

    def run(self, label: str, argv: list[str], *, timeout: int = 60, required: bool = True,
            suffix: str = ".txt") -> tuple[dict, Path]:
        self.index += 1
        stem = self.root / f"{self.index:03d}-{label}"
        output, error = stem.with_suffix(suffix), stem.with_suffix(".stderr.txt")
        record = {"argv": argv, "startedAtUtc": now(), "exitCode": None, "timedOut": False,
                  "stdout": str(output), "stderr": str(error)}
        try:
            with output.open("xb") as out, error.open("xb") as err:
                result = subprocess.run(argv, stdout=out, stderr=err, check=False, timeout=timeout,
                                        env={**os.environ, "LC_ALL": "C", "LANG": "C"})
            record["exitCode"] = result.returncode
        except subprocess.TimeoutExpired:
            record["timedOut"] = True
        except OSError as failure:
            record["launchError"] = str(failure)
        finally:
            record["finishedAtUtc"] = now()
            write_json(stem.with_suffix(".json"), record)
        if required:
            require(record["exitCode"] == 0 and not record["timedOut"],
                    f"{label} failed; its original stdout/stderr are retained at {stem}")
        return record, output

    def text(self, label: str, argv: list[str], **kwargs) -> str:
        _, output = self.run(label, argv, **kwargs)
        require(output.stat().st_size <= 20 * 1024 * 1024, f"Oversized tool output: {label}")
        return output.read_text(encoding="utf-8")


def git(root: Path, *arguments: str) -> str:
    return subprocess.check_output(["git", "-C", str(root), *arguments], text=True, timeout=60).strip()


def clean_source(root: Path, revision: str) -> None:
    require(bool(re.fullmatch(r"[0-9a-f]{40}", revision)), "An exact source revision is required")
    require(git(root, "rev-parse", "HEAD") == revision, "Source revision changed")
    require(not git(root, "status", "--porcelain=v1", "--untracked-files=all"), "Source must be clean before and after building")
    require(not (root / "keystore.properties").exists(), "This lane never opens a publisher keystore configuration")


def record_source(root: Path, output: Path) -> dict:
    source_evidence.require_unlinked(output)
    root, output = root.resolve(), output.resolve()
    require(not output.is_relative_to(root), "Evidence must be outside the source checkout")
    output.mkdir(parents=True, exist_ok=False)
    revision = git(root, "rev-parse", "HEAD")
    clean_source(root, revision)
    archive = output / "source.tar.gz"
    git(root, "archive", "--format=tar.gz", "--output", str(archive), "HEAD")
    commit = output / "source.commit"
    with commit.open("xb") as stream:
        stream.write(subprocess.check_output(["git", "-C", str(root), "cat-file", "commit", revision], timeout=60))
    value = {"schemaVersion": 1, "revision": revision, "tree": git(root, "rev-parse", "HEAD^{tree}"),
             "sourceRoot": str(root), "sourceArchive": publisher.file_record(archive),
             "commitObject": publisher.file_record(commit),
             "recordedAtUtc": now(), "publisherQualified": False,
             "provenanceScope": "Workflow source/build observation; not reproducible-build or remote attestation"}
    validate_source(value)
    write_json(output / "source.json", value)
    return value


def validate_source(value: dict) -> None:
    """Bind retained source bytes and file modes to the recorded Git commit and tree."""
    revision, tree = value.get("revision"), value.get("tree")
    require(type(value.get("schemaVersion")) is int and value["schemaVersion"] == 1
            and value.get("publisherQualified") is False, "Invalid source evidence scope")
    require(isinstance(revision, str) and re.fullmatch(r"[0-9a-f]{40}", revision) is not None
            and isinstance(tree, str) and re.fullmatch(r"[0-9a-f]{40}", tree) is not None,
            "Invalid exact source commit/tree identity")
    commit = source_evidence.verify_record(value["commitObject"])
    require(commit.stat().st_size <= 1024 * 1024, "Oversized source Git commit object")
    data = commit.read_bytes()
    require(source_evidence.git_object("commit", data) == revision
            and data.split(b"\n", 1)[0] == b"tree " + tree.encode(),
            "Retained Git commit does not bind the recorded source and tree")
    archive = source_evidence.verify_record(value["sourceArchive"])
    actual, _ = source_evidence.archive_tree(archive, revision)
    require(actual == tree, "Retained source archive differs from its committed Git tree")


def expected_version(root: Path) -> dict:
    source = (root / "app/build.gradle.kts").read_text()
    fields = {}
    for key, pattern in (("versionCode", r"(?m)^\s*versionCode\s*=\s*(\d+)\s*$"),
                         ("versionName", r'(?m)^\s*versionName\s*=\s*"([^"\n]+)"\s*$')):
        values = re.findall(pattern, source)
        require(len(values) == 1, f"An unambiguous source {key} is required")
        fields[key] = values[0]
    return {"packageName": APP, "minSdk": 26, "targetSdk": 36, **fields}


def validate_driver_manifest(xml: str) -> dict:
    require("<!DOCTYPE" not in xml.upper() and "<!ENTITY" not in xml.upper(), "Manifest entities are not accepted")
    root = ET.fromstring(xml)
    applications, instruments = root.findall("application"), root.findall("instrumentation")
    require(root.tag == "manifest" and root.get("package") == DRIVER and len(applications) == len(instruments) == 1,
            "The separate driver package/manifest is incorrect")
    app, runner = applications[0], instruments[0]
    require(runner.get(ANDROID + "targetPackage") == DRIVER
            and runner.get(ANDROID + "name") == "androidx.test.runner.AndroidJUnitRunner",
            "The UI driver must instrument itself, never the production app")
    require(app.get(ANDROID + "debuggable") == "true", "Only the separate driver is debuggable")
    require(app.get(ANDROID + "name", "android.app.Application") == "android.app.Application",
            "Unexpected replacement Application in the driver")
    receivers = [item for item in app.findall("activity")
                 if item.get(ANDROID + "name") in (".PngReceiptActivity", DRIVER + ".PngReceiptActivity")]
    require(len(receivers) == 1 and receivers[0].get(ANDROID + "exported") == "true",
            "The separate PNG chooser receiver is missing")
    return {"packageName": DRIVER, "targetPackage": DRIVER, "runner": runner.get(ANDROID + "name"),
            "debuggable": True, "selfInstrumenting": True}


def certificate_digest(text: str) -> str:
    values = re.findall(r"(?m)^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$", text)
    require(len(values) == 1, "Exactly one current test signer is required")
    return values[0].lower()


def require_payload_identity(unsigned: Path, signed: Path) -> dict:
    before, after = publisher.archive_entries(unsigned), publisher.archive_entries(signed)
    require(before == after, "Copy signing changed an application payload entry")
    return {"entryCount": len(before), "sha256": hashlib.sha256(json.dumps(before, sort_keys=True).encode()).hexdigest(),
            "entries": before, "exclusions": "Only top-level JAR signing metadata and the APK signing block"}


def reject_embedded_package(apk: Path, package: str) -> None:
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", name):
                require(("L" + package.replace(".", "/") + "/").encode() not in archive.read(name),
                        f"Forbidden cross-package test/application code in {apk.name}: {package}")


def shrinker_evidence(root: Path, destination: Path) -> dict:
    destination.mkdir()
    files = {name: retained(root / "app/build/outputs/mapping/release" / name, destination / name)
             for name in SHRINKER_FILES}
    mapping = (destination / "mapping.txt").read_text()
    renamed = [(source, target) for source, target in re.findall(r"(?m)^(com\.artflow\.studio\.[^ ]+) -> ([^ :]+):$", mapping)
               if source != target]
    require(bool(renamed), "Normal release mapping must contain obfuscated application classes")
    configuration = (destination / "configuration.txt").read_text()
    require(not re.search(r"(?m)^\s*-(dontobfuscate|dontoptimize|dontshrink)\b", configuration),
            "Normal release optimization was disabled")
    require(not any(value in configuration for value in (
        "proguard-benchmark-test-reflection", "release-qualification/test-abi", "TestAbiRules",
        "SelectionCanvasTestActivity", "com.artflow.qualification")),
        "Instrumentation retention rules leaked into the normal release")
    return {"files": files, "renamedApplicationClasses": len(renamed), "extraInstrumentationRules": False}


def prepare(output: Path, apkanalyzer: str, apksigner: str, keytool: str) -> dict:
    source = publisher.load_object(output / "source.json")
    validate_source(source)
    root = Path(source["sourceRoot"])
    clean_source(root, source["revision"])
    build = output / "build"
    build.mkdir()
    commands = Commands(build / "commands")
    commands.text("apksigner-version", [apksigner, "version"])
    commands.text("apkanalyzer-version", [apkanalyzer, "--version"])
    apks = {role: retained(root / path, build / (role + ".apk")) for role, path in APK_PATHS.items()}
    unsigned, driver = (Path(apks[role]["path"]) for role in ("unsigned", "driver"))
    unsigned_status, unsigned_log = commands.run("unsigned-signature-check", [apksigner, "verify", "--verbose", str(unsigned)], required=False)
    require(unsigned_status["exitCode"] not in (None, 0) and not unsigned_status["timedOut"],
            "Expected the normal unsigned release output; publisher-signed inputs do not belong in this lane")
    unsigned_text = unsigned_log.read_text() + Path(unsigned_status["stderr"]).read_text()
    require("DOES NOT VERIFY" in unsigned_text, "The official tool did not establish an unsigned input")
    signed = build / "release-copy-signed.apk"
    public_cert = build / "disposable-certificate.der"
    # Key bytes live outside uploaded evidence and are destroyed immediately after copy signing.
    with tempfile.TemporaryDirectory(prefix="artflow-release-ui-test-key-") as temporary:
        key = str(Path(temporary) / "disposable.p12")
        credentials = ["-keystore", key, "-storepass", "android", "-alias", "disposable-ui-test"]
        commands.text("generate-disposable-key", [keytool, "-genkeypair", *credentials,
                      "-storetype", "PKCS12", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                      "-dname", "CN=ArtFlow Disposable UI Test,OU=Qualification,O=Not Publisher", "-noprompt"])
        commands.text("export-public-certificate", [keytool, "-exportcert", *credentials, "-file", str(public_cert)])
        commands.text("sign-release-copy", [apksigner, "sign", "--ks", key, "--ks-key-alias", "disposable-ui-test",
                      "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", str(signed), str(unsigned)])
    apks["signed"] = publisher.file_record(signed)
    payload = require_payload_identity(unsigned, signed)
    signatures = {}
    for role, path in (("signed", signed), ("driver", driver)):
        report = commands.text(role + "-signature", [apksigner, "verify", "--verbose", "--print-certs", str(path)])
        signatures[role] = certificate_digest(report)
    require(signatures["signed"] == sha(public_cert), "Installed candidate is not signed by this attempt's disposable key")

    class Inspector:
        def run(self, label, args):
            return commands.text(label, args)

    expected = expected_version(root)
    app = publisher.verify_package(signed, expected, Inspector(), argparse.Namespace(apkanalyzer=apkanalyzer))
    manifest = commands.text("driver-manifest", [apkanalyzer, "manifest", "print", str(driver)])
    driver_metadata = validate_driver_manifest(manifest)
    reject_embedded_package(signed, DRIVER)
    reject_embedded_package(driver, APP)
    shrinker = shrinker_evidence(root, build / "shrinker")
    clean_source(root, source["revision"])
    value = {"schemaVersion": 1, "revision": source["revision"], "source": publisher.file_record(output / "source.json"),
             "apks": apks, "payload": payload, "application": app, "driver": driver_metadata,
             "signatures": signatures, "disposableCertificate": publisher.file_record(public_cert), "shrinker": shrinker,
             "buildVariant": "release", "buildTasks": [":app:assembleRelease", ":release-ui:assembleRelease"],
             "publisherQualified": False, "preparedAtUtc": now()}
    write_json(output / "prepared.json", value)
    return value


def instrumentation_result(text: str) -> dict:
    statuses, block = [], {}
    for line in text.splitlines():
        item = re.fullmatch(r"INSTRUMENTATION_STATUS: ([^=]+)=(.*)", line)
        if item:
            require(item[1] not in block, "Duplicate instrumentation status field")
            block[item[1]] = item[2]
        code = re.fullmatch(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", line)
        if code:
            statuses.append((int(code[1]), block))
            block = {}
    require(not block and len(statuses) == 2 and [status[0] for status in statuses] == [1, 0],
            "Exactly one started/passed test is required; failures, skips and retries are rejected")
    for _, value in statuses:
        require(value.get("class") == TEST_CLASS and value.get("test") == TEST_METHOD
                and value.get("numtests") == "1" and value.get("current") == "1", "Unexpected instrumentation inventory")
    require(re.findall(r"(?m)^INSTRUMENTATION_CODE: (-?\d+)\s*$", text) == ["-1"]
            and re.findall(r"(?m)^OK \((\d+) tests?\)\s*$", text) == ["1"]
            and not any(marker in text for marker in ("INSTRUMENTATION_FAILED", "INSTRUMENTATION_ABORTED", "FAILURES!!!")),
            "Instrumentation did not finish its only required workflow successfully")
    return {"class": TEST_CLASS, "method": TEST_METHOD, "tests": 1, "passed": 1, "skipped": 0}


def png_pixels(path: Path) -> bytes:
    """Independently decode the small non-interlaced RGB/RGBA PNG written by Bitmap.compress."""
    data = path.read_bytes()
    require(len(data) <= 512 * 1024 and data[:8] == b"\x89PNG\r\n\x1a\n", "Invalid or oversized shared PNG")
    position, compressed, header, ended = 8, bytearray(), None, False
    while position < len(data):
        require(position + 12 <= len(data), "Truncated PNG chunk")
        length = struct.unpack_from(">I", data, position)[0]
        kind = data[position + 4:position + 8]
        end = position + 12 + length
        require(end <= len(data), "Truncated PNG chunk payload")
        payload = data[position + 8:end - 4]
        require(zlib.crc32(kind + payload) & 0xFFFFFFFF == struct.unpack_from(">I", data, end - 4)[0], "PNG CRC mismatch")
        require(not ended and (header is not None or kind == b"IHDR"), "Invalid PNG chunk order")
        if kind == b"IHDR":
            require(header is None and len(payload) == 13, "Duplicate or malformed PNG header")
            header = struct.unpack(">IIBBBBB", payload)
        elif kind == b"IDAT":
            compressed.extend(payload)
        elif kind == b"IEND":
            require(not payload and end == len(data), "Trailing PNG data")
            ended = True
        elif kind[0] & 32 == 0:
            require(kind == b"PLTE", "Unsupported critical PNG chunk")
        position = end
    require(ended and header is not None, "Incomplete PNG")
    width, height, depth, color, compression, filtering, interlace = header
    require((width, height, depth, compression, filtering, interlace) == (64, 64, 8, 0, 0, 0)
            and color in (2, 6), "Expected a 64 by 64 non-interlaced 8-bit RGB/RGBA PNG")
    channels = 3 if color == 2 else 4
    stride, expected = 64 * channels, 64 * (64 * channels + 1)
    inflater = zlib.decompressobj()
    decoded = inflater.decompress(bytes(compressed), expected + 1)
    require(len(decoded) == expected and inflater.eof and not inflater.unused_data and not inflater.unconsumed_tail,
            "PNG pixel stream has the wrong bounded size")
    previous, result = bytearray(stride), bytearray()
    for row_index in range(64):
        offset = row_index * (stride + 1)
        method, row = decoded[offset], bytearray(decoded[offset + 1:offset + 1 + stride])
        require(method <= 4, "Invalid PNG row filter")
        for i in range(stride):
            left, up, upper_left = (row[i - channels] if i >= channels else 0), previous[i], (previous[i - channels] if i >= channels else 0)
            if method == 1:
                prediction = left
            elif method == 2:
                prediction = up
            elif method == 3:
                prediction = (left + up) // 2
            elif method == 4:
                base = left + up - upper_left
                distances = (abs(base - left), abs(base - up), abs(base - upper_left))
                prediction = (left, up, upper_left)[distances.index(min(distances))]
            else:
                prediction = 0
            row[i] = (row[i] + prediction) & 255
        for column in range(64):
            start = column * channels
            result.extend((row[start + 3] if channels == 4 else 255, *row[start:start + 3]))
        previous = row
    return bytes(result)


def verify_receipts(directory: Path, attempt: dict) -> dict:
    require(TOKEN.fullmatch(attempt["token"]) is not None, "Invalid attempt token")
    require(not list(directory.glob("*-error.txt")), "The receiver recorded a real share failure")
    workflow = publisher.load_object(directory / "workflow.json")
    expected = {"schemaVersion": 1, "token": attempt["token"], "revision": attempt["revision"], "success": True,
                "appApkSha256": attempt["appApkSha256"], "driverApkSha256": attempt["driverApkSha256"],
                "applicationPackage": APP, "driverPackage": DRIVER, "publisherQualified": False}
    require(all(workflow.get(key) == value for key, value in expected.items()), "Workflow identity or success is inconsistent")
    require(workflow.get("assertions") == {name: True for name in CHECKS}, "Incomplete UI workflow assertions")
    pids = [workflow.get(name) for name in ("driverPid", "firstAppPid", "reopenedAppPid")]
    require(all(type(pid) is int and pid > 0 for pid in pids) and len(set(pids)) == 3, "A separate driver and two app processes are required")
    require((directory / "started.txt").read_text() == attempt["token"], "Stale device evidence")
    pixels, phases = [], {}
    for phase in ("saved", "reopened"):
        report = publisher.load_object(directory / (phase + "-receipt.json"))
        require(all(report.get(key) == value for key, value in {
            "schemaVersion": 1, "token": attempt["token"], "phase": phase, "width": 64, "height": 64, "readGrant": True,
        }.items()), "Wrong phase/token or missing FileProvider grant")
        require(isinstance(report.get("uri"), str) and report["uri"].startswith("content://" + APP + ".fileprovider/"),
                "Unexpected shared URI authority")
        image, raw = directory / (phase + ".png"), directory / (phase + "-pixels.argb")
        actual = raw.read_bytes()
        require(len(actual) == 64 * 64 * 4 and actual == png_pixels(image), "Retained PNG differs from all decoded receipt pixels")
        require(report.get("pngSha256") == sha(image) and report.get("pixelSha256") == sha(raw), "PNG/decoded-pixel receipt digest mismatch")
        require(all(actual[index] == 255 for index in range(0, len(actual), 4)), "Flatten-white pixels must all be opaque")
        ink = sum(actual[index:index + 4] != b"\xff\xff\xff\xff" for index in range(0, len(actual), 4))
        require(8 <= ink < 4096 and report.get("inkPixels") == ink, "Pointer paint must produce nonblank partly white artwork")
        pixels.append(actual)
        phases[phase] = {"png": publisher.file_record(image), "decodedPixels": publisher.file_record(raw), "inkPixels": ink}
    require(pixels[0] == pixels[1], "Artwork pixels changed after save and app process death")
    screenshots = {}
    for name in ("blank", "painted", "reopened-export"):
        screenshot, hierarchy = directory / (name + ".png"), directory / (name + "-hierarchy.xml")
        require(screenshot.read_bytes()[:8] == b"\x89PNG\r\n\x1a\n", "Missing actual UI screenshot")
        require(ET.fromstring(hierarchy.read_text()).tag == "hierarchy", "Missing visible UI hierarchy")
        screenshots[name] = {"image": publisher.file_record(screenshot), "hierarchy": publisher.file_record(hierarchy)}
    return {"workflow": publisher.file_record(directory / "workflow.json"), "phases": phases, "screenshots": screenshots,
            "comparedPixels": 4096, "processIds": pids}


class Device:
    def __init__(self, commands: Commands, adb: str, serial: str):
        self.commands, self.adb, self.serial = commands, adb, serial

    def run(self, label: str, *args: str, **kwargs):
        return self.commands.run(label, [self.adb, "-s", self.serial, *args], **kwargs)

    def text(self, label: str, *args: str, **kwargs) -> str:
        return self.commands.text(label, [self.adb, "-s", self.serial, *args], **kwargs)

    def shell(self, label: str, *args: str, **kwargs) -> str:
        return self.text(label, "shell", shlex.join(args), **kwargs)

    def fresh(self) -> dict:
        require(self.text("device-state", "get-state").strip() == "device", "Designated emulator is unavailable")
        require(self.text("device-serial", "get-serialno").strip() == self.serial, "ADB selected a different device")
        require(self.shell("device-user", "am", "get-current-user").strip() == "0", "Only a fresh primary user is qualified")
        text = self.shell("device-properties", "getprop")
        props = dict(re.findall(r"(?m)^\[([^]]+)\]: \[([^]]*)\]", text))
        require(props.get("ro.kernel.qemu") == "1" and props.get("ro.build.version.sdk") == "36",
                "This lane requires an API 36 emulator, not a physical/personal device")
        require(bool(props.get("ro.build.fingerprint")), "Missing device build fingerprint")
        require(self.shell("device-page-size", "getconf", "PAGESIZE").strip() == "4096", "This campaign requires 4 KB pages")
        for package in (APP, DRIVER, APP + ".test"):
            packages = self.shell("registered-" + package, "pm", "list", "packages", "-u", package).splitlines()
            require("package:" + package not in packages,
                    f"{package} is already registered; use a new disposable emulator, never clear/uninstall existing data")
        return {"serial": self.serial, "api": 36, "pageSize": 4096, "emulator": True,
                "fingerprint": props["ro.build.fingerprint"], "freshPackagePreconditions": True}

    def install(self, role: str, record: dict, destination: Path) -> dict:
        path, package = Path(record["path"]), APP if role == "signed" else DRIVER
        require(sha(path) == record["sha256"], "Retained APK changed before install")
        flags = [] if role == "signed" else ["-t"]
        result = self.text("install-" + role, "install", *flags, str(path), timeout=120)
        require(re.search(r"(?m)^Success\s*$", result) is not None and "Failure [" not in result, "APK install did not succeed")
        paths = self.shell("installed-path-" + role, "pm", "path", "--user", "0", package).splitlines()
        require(len(paths) == 1 and re.fullmatch(r"package:/data/app/[A-Za-z0-9_./+=~-]+\.apk", paths[0]) is not None,
                "Exactly one installed base APK must be captured")
        installed = destination / (role + ".apk")
        self.run("pull-installed-" + role, "pull", paths[0][8:], str(installed))
        require(sha(installed) == record["sha256"], "Installed APK differs from the retained copy")
        package_dump = self.shell("installed-package-" + role, "dumpsys", "package", package)
        if role == "signed":
            flags_text = re.findall(r"(?m)^\s*(?:pkgFlags|flags)=\[([^]]+)\]", package_dump)
            require(bool(flags_text) and all("DEBUGGABLE" not in value and "TEST_ONLY" not in value for value in flags_text),
                    "Installed production package flags are missing or test/debug flags are set")
        return {**publisher.file_record(installed), "packageName": package, "devicePath": paths[0][8:]}


def run(output: Path, adb: str, serial: str, disposable: bool) -> dict:
    require(disposable and re.fullmatch(r"emulator-[0-9]+", serial) is not None,
            "Explicit --disposable-emulator and an exact emulator serial are required")
    prepared = publisher.load_object(output / "prepared.json")
    attempt = {"schemaVersion": 1, "token": str(uuid.uuid4()), "revision": prepared["revision"], "serial": serial,
               "appApkSha256": prepared["apks"]["signed"]["sha256"], "driverApkSha256": prepared["apks"]["driver"]["sha256"],
               "preparedSha256": sha(output / "prepared.json"),
               "startedAtUtc": now(), "attemptsAllowed": 1, "publisherQualified": False}
    # The exclusive receipt claims the attempt before any device mutation; reruns keep the first failure.
    write_json(output / "attempt.json", attempt)
    commands = Commands(output / "device-commands")
    device = Device(commands, adb, serial)
    installed = output / "installed"
    installed.mkdir()
    evidence = output / "device-evidence"
    evidence.mkdir()
    result = {"schemaVersion": 1, "token": attempt["token"], "revision": attempt["revision"], "success": False,
              "publisherQualified": False, "errors": [], "installed": {}}
    try:
        result["device"] = device.fresh()
        for role in ("signed", "driver"):
            result["installed"][role] = device.install(role, prepared["apks"][role], installed)
        arguments = ["am", "instrument", "-w", "-r", "-e", "class", TEST_CLASS + "#" + TEST_METHOD,
                     "-e", "releaseUiToken", attempt["token"], "-e", "releaseUiRevision", attempt["revision"],
                     "-e", "releaseUiApkSha256", attempt["appApkSha256"], "-e", "releaseUiDriverSha256", attempt["driverApkSha256"],
                     "-e", "freshDisposableInstall", "true", RUNNER]
        record, log = device.run("first-attempt-instrumentation", "shell", shlex.join(arguments), timeout=420, required=False)
        result["instrumentationCommand"], result["instrumentationLog"] = record, str(log)
        require(record["exitCode"] == 0 and not record["timedOut"], "The sole instrumentation invocation failed or timed out")
        result["instrumentation"] = instrumentation_result(log.read_text())
    except (ValueError, OSError, KeyError, TypeError) as failure:
        result["errors"].append(str(failure))
    finally:
        observations = (
            ("final-logcat", ("logcat", "-b", "all", "-d"), ".txt"),
            ("final-screen", ("exec-out", "screencap -p"), ".png"),
            ("driver-evidence", ("pull", f"/sdcard/Android/data/{DRIVER}/files/release-ui/{attempt['token']}", str(evidence)), ".txt"),
        )
        for label, args, suffix in observations:
            record, _ = device.run(label, *args, timeout=30, required=False, suffix=suffix)
            if record["exitCode"] != 0 or record["timedOut"]:
                result["errors"].append(label + " capture failed; its original command output is retained")
        if not result["errors"]:
            try:
                result["workflow"] = verify_receipts(evidence / attempt["token"], attempt)
            except (ValueError, OSError, KeyError, TypeError, ET.ParseError, zlib.error) as failure:
                result["errors"].append(str(failure))
        result["success"], result["finishedAtUtc"] = not result["errors"], now()
        write_json(output / "run-result.json", result)
    require(result["success"], "Release payload UI qualification failed: " + "; ".join(result["errors"]))
    return result


def verify(output: Path) -> dict:
    result = {"schemaVersion": 1, "success": False, "publisherQualified": False, "physicalDeviceQualified": False,
              "storeInstallQualified": False, "problems": [], "scope": "Normal release payload, disposable signing, one visible UI workflow on API 36 / 4 KB emulator"}
    try:
        source, prepared, attempt, run_result = (publisher.load_object(output / name) for name in
                                                ("source.json", "prepared.json", "attempt.json", "run-result.json"))
        validate_source(source)
        require(prepared.get("source") == publisher.file_record(output / "source.json"),
                "Prepared build is not bound to this source receipt")
        revision = source["revision"]
        require(bool(re.fullmatch(r"[0-9a-f]{40}", revision)), "Invalid pinned source")
        require(all(value.get("revision") == revision and value.get("publisherQualified") is False
                    for value in (prepared, attempt, run_result)), "Evidence source/approval scope differs")
        require(run_result.get("success") is True and run_result.get("errors") == []
                and run_result.get("token") == attempt["token"], "Original run failed or has mismatched evidence")
        require(attempt.get("preparedSha256") == sha(output / "prepared.json"), "Prepared build evidence changed after the attempt began")
        require(run_result["device"].get("serial") == attempt["serial"]
                and all(run_result["device"].get(key) == value for key, value in {
                    "api": 36, "pageSize": 4096, "emulator": True, "freshPackagePreconditions": True,
                }.items()), "Device identity/preconditions do not match this campaign")
        require(prepared.get("buildVariant") == "release" and prepared["driver"].get("selfInstrumenting") is True
                and prepared["driver"].get("targetPackage") == DRIVER, "Wrong release or driver variant")
        require(prepared["application"]["manifests"]["base"].get("debuggable") is False
                and prepared["application"]["manifests"]["base"].get("testOnly") is False, "App release flags differ")
        require(prepared["shrinker"].get("extraInstrumentationRules") is False
                and prepared["shrinker"].get("renamedApplicationClasses", 0) > 0, "Missing release shrinker evidence")
        for record in [source["sourceArchive"], source["commitObject"], prepared["source"], prepared["disposableCertificate"],
                       *prepared["apks"].values(), *prepared["shrinker"]["files"].values()]:
            require(publisher.file_record(Path(record["path"])) == record, "A retained build/provenance file changed")
        require(set(prepared["shrinker"]["files"]) == set(SHRINKER_FILES), "Incomplete R8/resource evidence")
        require(require_payload_identity(Path(prepared["apks"]["unsigned"]["path"]), Path(prepared["apks"]["signed"]["path"])) == prepared["payload"],
                "Release payload comparison no longer matches")
        require(attempt["appApkSha256"] == prepared["apks"]["signed"]["sha256"]
                and attempt["driverApkSha256"] == prepared["apks"]["driver"]["sha256"] and attempt.get("attemptsAllowed") == 1,
                "Attempt APKs or attempt count changed")
        for role in ("signed", "driver"):
            record = run_result["installed"][role]
            require(sha(Path(record["path"])) == prepared["apks"][role]["sha256"] == record["sha256"], "Installed APK digest mismatch")
        command = run_result["instrumentationCommand"]
        require(command.get("exitCode") == 0 and command.get("timedOut") is False, "Original invocation failed")
        result["instrumentation"] = instrumentation_result(Path(run_result["instrumentationLog"]).read_text())
        result["workflow"] = verify_receipts(output / "device-evidence" / attempt["token"], attempt)
        result.update({"revision": revision, "token": attempt["token"], "device": run_result["device"], "success": True})
    except (ValueError, OSError, KeyError, TypeError, ET.ParseError, zlib.error, zipfile.BadZipFile, tarfile.TarError) as failure:
        result["problems"].append(str(failure))
    write_json(output / "verification.json", result)
    with (output / "verification.md").open("x") as stream:
        stream.write("## Normal release payload UI qualification\n\n")
        stream.write("Result: **" + ("passed" if result["success"] else "failed / incomplete") + "**.\n\n")
        stream.write(result["scope"] + ".\n\n")
        stream.write("Publisher signature, store installation, physical stylus/performance and overall release approval remain unqualified.\n")
        for problem in result["problems"]:
            stream.write("\n- " + problem + "\n")
    return result


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    record = sub.add_parser("record-source")
    record.add_argument("--project", type=Path, default=Path.cwd())
    record.add_argument("--output", type=Path, required=True)
    build = sub.add_parser("prepare")
    build.add_argument("--output", type=Path, required=True)
    build.add_argument("--apkanalyzer", required=True)
    build.add_argument("--apksigner", required=True)
    build.add_argument("--keytool", default="keytool")
    execute = sub.add_parser("run")
    execute.add_argument("--output", type=Path, required=True)
    execute.add_argument("--adb", default="adb")
    execute.add_argument("--serial", required=True)
    execute.add_argument("--disposable-emulator", action="store_true")
    check = sub.add_parser("verify")
    check.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "record-source":
            record_source(args.project, args.output)
        elif args.command == "prepare":
            prepare(args.output, args.apkanalyzer, args.apksigner, args.keytool)
        elif args.command == "run":
            run(args.output, args.adb, args.serial, args.disposable_emulator)
        else:
            result = verify(args.output)
            print(json.dumps(result, indent=2))
            return 0 if result["success"] else 1
        return 0
    except (ValueError, OSError, KeyError, TypeError, ET.ParseError, subprocess.SubprocessError, zipfile.BadZipFile, tarfile.TarError) as failure:
        print(f"Release payload qualification failed: {failure}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
