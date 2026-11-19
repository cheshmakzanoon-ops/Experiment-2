#!/usr/bin/env python3
"""One retained, opt-in package replacement on an explicitly disposable Android device.

prepare installs the old debug APK only on a device with neither package registered.
seed drives the pinned old application's real UI, or an operator may seed it manually.
freeze preserves the stopped app's real data. upgrade uses install -r, then runs its UI workflow
in its separately evidenced campaign mode. No command clears data or uninstalls a package.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shlex
import shutil
import sqlite3
import struct
import subprocess
import sys
import tarfile
import tempfile
import uuid
import xml.etree.ElementTree as ET

import package_upgrade_build

PACKAGE = "com.artflow.studio"
TEST_PACKAGE = PACKAGE + ".test"
RUNNER = TEST_PACKAGE + "/com.artflow.studio.HiltTestRunner"
METHOD = PACKAGE + ".OptimizedArtworkWorkflowTest#realPointerPaintSurvivesSaveFreshActivityReopenAndUiPngExport"
SEED_METHOD = PACKAGE + ".InstalledUpgradeSeedTest#seedSavedArtworkNewerRecoveryAndPreferencesThroughOldUi"
PINNED_V1_SOURCE = "ca9ce535c09d35ceca6aba9f686c60f58f93bc09"
ANDROID = "{http://schemas.android.com/apk/res/android}"
SHA = re.compile(r"[0-9a-f]{64}")
TOKEN = re.compile(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
MAX_SNAPSHOT = 256 * 1024 * 1024
TABLES = {"projects": "id", "brushes": "id", "settings": "key", "sqlite_sequence": "name"}
SEED_ATTESTATIONS = (
    "createdThroughOldAppUi", "savedAndReopenedThroughOldAppUi",
    "customPaletteCreatedThroughOldAppUi", "savedBrushCreatedThroughOldAppUi",
    "newerRecoveryCreatedThroughOldAppUi",
)


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def write_json(path: Path, value: dict) -> None:
    temporary = path.with_suffix(path.suffix + ".partial")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")
    temporary.replace(path)


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def retain(source: Path, destination: Path, expected: str | None = None) -> dict:
    if not source.is_file() or source.is_symlink() or source.stat().st_size <= 0:
        raise ValueError(f"Missing, empty or linked input: {source}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("xb") as output, source.open("rb") as input_stream:
        shutil.copyfileobj(input_stream, output)
    digest = sha256(destination)
    if expected is not None and (not SHA.fullmatch(expected) or digest != expected):
        raise ValueError(f"Pinned SHA-256 mismatch for {source}; the received bytes are retained")
    return {"path": str(destination), "sha256": digest, "sizeBytes": destination.stat().st_size}


class Commands:
    def __init__(self, root: Path, serial: str, adb: str):
        self.root, self.serial, self.adb = root, serial, adb
        root.mkdir()
        self.index = 0

    def run(self, name: str, argv: list[str], *, timeout: int = 30,
            suffix: str = ".txt", required: bool = True) -> Path:
        self.index += 1
        base = self.root / f"{self.index:03d}-{name}"
        output = base.with_suffix(suffix)
        error_path = base.with_suffix(".stderr.txt")
        record = {"argv": argv, "startedAtUtc": now(), "exitCode": None, "timedOut": False,
                  "stdout": output.name, "stderr": error_path.name}
        try:
            with output.open("xb") as out, error_path.open("xb") as err:
                completed = subprocess.run(argv, stdout=out, stderr=err, timeout=timeout, check=False)
            record["exitCode"] = completed.returncode
        except subprocess.TimeoutExpired:
            record["timedOut"] = True
        except OSError as error:
            record["launchError"] = str(error)
        finally:
            record["finishedAtUtc"] = now()
            write_json(base.with_suffix(".json"), record)
        if required and (record["exitCode"] != 0 or record["timedOut"]):
            raise ValueError(f"{name} failed; first command output retained at {output}")
        return output

    def adb_command(self, name: str, *arguments: str, **kwargs) -> Path:
        return self.run(name, [self.adb, "-s", self.serial, *arguments], **kwargs)

    def shell(self, name: str, *arguments: str, **kwargs) -> Path:
        return self.adb_command(name, "shell", shlex.join(arguments), **kwargs)

    def diagnostics(self, token: str) -> None:
        # These observations never clear logcat, restart a process or overwrite an earlier phase.
        self.adb_command("final-logcat", "logcat", "-b", "all", "-d", required=False, timeout=20)
        self.shell("final-package", "dumpsys", "package", PACKAGE, required=False)
        self.adb_command("final-screen", "exec-out", "screencap -p", suffix=".png", required=False)
        self.adb_command("final-probe-evidence", "pull",
                         f"/sdcard/Android/data/{PACKAGE}/files/upgrade-{token}/test-evidence",
                         str(self.root / "final-probe-evidence"), required=False, timeout=30)
        self.adb_command("final-old-ui-evidence", "pull",
                         f"/sdcard/Android/data/{PACKAGE}/files/upgrade-{token}-old/test-evidence",
                         str(self.root / "final-old-ui-evidence"), required=False, timeout=30)


def parse_apk_manifest(text: str) -> dict:
    root = ET.fromstring(text)
    app = root.find("application")
    package = root.get("package")
    if root.tag != "manifest" or app is None or not package:
        raise ValueError("APK manifest is missing its package/application")
    instrumentation = root.find("instrumentation")
    sdk = root.find("uses-sdk")
    return {"packageName": package, "versionCode": int(root.get(ANDROID + "versionCode", "-1")),
            "versionName": root.get(ANDROID + "versionName"),
            "debuggable": app.get(ANDROID + "debuggable", "false") == "true",
            "minSdk": 1 if sdk is None else int(sdk.get(ANDROID + "minSdkVersion", "1")),
            "runner": None if instrumentation is None else instrumentation.get(ANDROID + "name"),
            "targetPackage": None if instrumentation is None else instrumentation.get(ANDROID + "targetPackage")}


def parse_certificates(text: str) -> list[str]:
    certificates = re.findall(r"(?m)^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$", text)
    if len(certificates) != 1:
        raise ValueError("This campaign requires one current signer per APK; signer rotation/multiple signers need a separate campaign")
    return [value.lower() for value in certificates]


def inspect_apk(commands: Commands, path: Path, name: str, tools: dict) -> dict:
    metadata = commands.run(name + "-manifest", [tools["apkanalyzer"], "manifest", "print", str(path)])
    signature = commands.run(name + "-signature", [tools["apksigner"], "verify", "--verbose", "--print-certs", str(path)])
    return {**parse_apk_manifest(metadata.read_text()), "signerSha256": parse_certificates(signature.read_text()),
            "sha256": sha256(path), "sizeBytes": path.stat().st_size, "path": str(path)}


def validate_apks(apks: dict) -> str:
    old, candidate, tests = (apks[key] for key in ("old", "candidate", "tests"))
    if old["packageName"] != PACKAGE or candidate["packageName"] != PACKAGE or tests["packageName"] != TEST_PACKAGE:
        raise ValueError("APK package identities do not match this explicit campaign")
    if not old["debuggable"] or not candidate["debuggable"]:
        raise ValueError("This stopped-data/run-as campaign supports debuggable test APKs only; no release/publisher qualification")
    if tests["targetPackage"] != PACKAGE or tests["runner"] != "com.artflow.studio.HiltTestRunner":
        raise ValueError("The test APK must target the candidate through its HiltTestRunner")
    if not old["signerSha256"] == candidate["signerSha256"] == tests["signerSha256"]:
        raise ValueError("Old, candidate and instrumentation signing certificates differ; never assume CI debug keys match")
    if old["versionCode"] < 1 or candidate["versionCode"] < old["versionCode"]:
        raise ValueError("A version-code downgrade is forbidden")
    if old["sha256"] == candidate["sha256"]:
        raise ValueError("Old and candidate APKs are identical; this is not a replacement campaign")
    if "seed" in apks:
        seed = apks["seed"]
        if (seed["packageName"] != TEST_PACKAGE or seed["targetPackage"] != PACKAGE
                or seed["runner"] != "com.artflow.studio.HiltTestRunner"
                or seed["signerSha256"] != old["signerSha256"]):
            raise ValueError("The old UI seed APK must target the old application with the same test signer")
    return "increasing-version-test-update" if candidate["versionCode"] > old["versionCode"] else "same-version-engineering-replacement"


def device(commands: Commands) -> dict:
    if commands.adb_command("device-state", "get-state").read_text().strip() != "device":
        raise ValueError("The designated device is unavailable or unauthorized")
    if commands.adb_command("device-serial", "get-serialno").read_text().strip() != commands.serial:
        raise ValueError("ADB did not select the exact designated serial")
    if commands.shell("android-user", "am", "get-current-user").read_text().strip() != "0":
        raise ValueError("Only a disposable device's primary Android user is supported")
    properties = commands.shell("device-properties", "getprop").read_text()
    values = dict(re.findall(r"(?m)^\[([^]]+)\]: \[([^]]*)\]", properties))
    required = ("ro.build.fingerprint", "ro.build.version.sdk", "ro.product.model")
    if any(not values.get(key) for key in required):
        raise ValueError("Device identity properties are incomplete")
    return {"serial": commands.serial, "fingerprint": values["ro.build.fingerprint"],
            "api": int(values["ro.build.version.sdk"]), "model": values["ro.product.model"],
            "pageSize": int(commands.shell("page-size", "getconf", "PAGESIZE").read_text().strip())}


def require_absent(commands: Commands, package: str) -> None:
    text = commands.shell("registered-" + package, "pm", "list", "packages", "-u", package).read_text()
    if f"package:{package}" in text.splitlines():
        raise ValueError(f"{package} is already registered, including retained uninstalled data; use a fresh disposable device")


def installed_apk(commands: Commands, package: str, expected: dict, label: str) -> dict:
    text = commands.shell(label + "-paths", "pm", "path", "--user", "0", package).read_text()
    paths = [line.removeprefix("package:").strip() for line in text.splitlines() if line.startswith("package:")]
    if len(paths) != 1 or not re.fullmatch(r"/data/app/[A-Za-z0-9_./+=~-]+\.apk", paths[0]):
        raise ValueError("Exactly one installed base APK with a safe package-manager path is required")
    output = commands.root / (label + ".apk")
    commands.adb_command(label + "-pull", "pull", paths[0], str(output), timeout=60)
    if sha256(output) != expected["sha256"]:
        raise ValueError(f"Installed {package} bytes differ from the pinned APK")
    return {"devicePath": paths[0], "path": str(output), "sha256": sha256(output)}


def install(commands: Commands, apk: dict, name: str, *flags: str) -> None:
    path = Path(apk["path"])
    if sha256(path) != apk["sha256"]:
        raise ValueError("Retained APK changed after preflight")
    result = commands.adb_command(name, "install", *flags, str(path), timeout=120).read_text()
    if not re.search(r"(?m)^Success\s*$", result) or "Failure [" in result:
        raise ValueError(f"{name} did not report a successful Android package install")


def typed_cell(value) -> dict:
    if value is None:
        return {"type": "null"}
    if isinstance(value, int):
        return {"type": "integer", "value": str(value)}
    if isinstance(value, float):
        return {"type": "real", "value": struct.pack(">d", value).hex()}
    if isinstance(value, bytes):
        return {"type": "blob", "value": value.hex()}
    return {"type": "text", "value": value}


def database_snapshot(root: Path) -> dict:
    path = root / "artflow_database"
    if not path.is_file():
        raise ValueError("The old app did not create its production database")
    with sqlite3.connect(path.as_uri() + "?mode=ro", uri=True) as connection:
        if connection.execute("PRAGMA quick_check").fetchall() != [("ok",)]:
            raise ValueError("Stopped source database failed SQLite quick_check")
        version = connection.execute("PRAGMA user_version").fetchone()[0]
        tables = {}
        for name, order in TABLES.items():
            cursor = connection.execute(f'SELECT * FROM "{name}" ORDER BY "{order}"')
            tables[name] = {"columns": [item[0] for item in cursor.description],
                            "rows": [[typed_cell(value) for value in row] for row in cursor]}
        return {"version": version, "tables": tables}


def read_snapshot(path: Path) -> dict:
    files, size = {}, 0
    with tempfile.TemporaryDirectory(prefix="artflow-upgrade-db-") as temporary:
        db_root = Path(temporary)
        with tarfile.open(path, "r:") as archive:
            seen = set()
            for member in archive:
                relative = PurePosixPath(member.name)
                if (relative.is_absolute() or ".." in relative.parts or not relative.parts
                        or relative.parts[0] not in {"files", "databases"} or "\\" in member.name
                        or str(relative) in seen or not (member.isfile() or member.isdir())):
                    raise ValueError(f"Unsafe or duplicate snapshot member: {member.name}")
                seen.add(str(relative))
                if member.isdir():
                    continue
                size += member.size
                if member.size < 0 or size > MAX_SNAPSHOT:
                    raise ValueError("Snapshot exceeds the bounded disposable fixture budget")
                stream = archive.extractfile(member)
                if stream is None:
                    raise ValueError("Snapshot member has no content")
                with stream:
                    data = stream.read()
                if len(data) != member.size:
                    raise ValueError("Incomplete snapshot member")
                files[str(relative)] = {"sizeBytes": member.size, "sha256": hashlib.sha256(data).hexdigest(),
                                        "modifiedSeconds": int(member.mtime)}
                if str(relative) in {"databases/artflow_database", "databases/artflow_database-wal", "databases/artflow_database-shm"}:
                    (db_root / relative.name).write_bytes(data)
        return {"archiveSha256": sha256(path), "files": files, "database": database_snapshot(db_root)}


def snapshot(commands: Commands, name: str) -> dict:
    archive = commands.adb_command(name, "exec-out", shlex.join(["run-as", PACKAGE, "tar", "-cf", "-", "files", "databases"]),
                                   suffix=".tar", timeout=90)
    value = read_snapshot(archive)
    value["archivePath"] = str(archive)
    write_json(commands.root / (name + ".manifest.json"), value)
    return value


def rows(database: dict, table: str) -> list[dict]:
    value = database["tables"][table]
    def plain(cell):
        if cell["type"] == "null":
            return None
        return int(cell["value"]) if cell["type"] == "integer" else cell["value"]
    return [dict(zip(value["columns"], map(plain, row), strict=True)) for row in value["rows"]]


def validate_seed(seed: dict, name: str) -> int:
    if seed["database"]["version"] not in (1, 2):
        raise ValueError("Only actual Room v1-to-v2 migration and v2 continuity are supported")
    projects = [row for row in rows(seed["database"], "projects") if row["name"] == name]
    if len(projects) != 1 or (projects[0]["width"], projects[0]["height"], projects[0]["dpi"]) != (64, 64, 72):
        raise ValueError(f"Create exactly one old-app artwork named {name} at 64 x 64 pixels, 72 dpi")
    project = projects[0]
    project_id = project["id"]
    if project_id <= 0:
        raise ValueError("The seeded artwork needs a positive production database identity")
    prefix = f"files/projects/{project_id}/"
    required = [prefix + file for file in ("canvas.artflow", "autosave.artflow", "canvas.png", "thumbnail.png")]
    if any(file not in seed["files"] or seed["files"][file]["sizeBytes"] <= 0 for file in required):
        raise ValueError("Saved artwork, a thumbnail, flattened pixels and a newer recovery snapshot are required")
    saved, recovered = (seed["files"][prefix + file] for file in ("canvas.artflow", "autosave.artflow"))
    if recovered["modifiedSeconds"] <= saved["modifiedSeconds"] or recovered["sha256"] == saved["sha256"]:
        raise ValueError("Recovery must be newer than and different from the saved old-app document")
    settings = {row["key"]: row["value"] for row in rows(seed["database"], "settings")}
    expected = {"general.onboardingSeen": "true", "theme.mode": "LIGHT", "input.stylusOnly": "false"}
    if any(settings.get(key) != value for key, value in expected.items()):
        raise ValueError("Complete onboarding and seed Light theme with finger drawing enabled in the old app")
    if any(not settings.get(key) or settings[key] in {"[]", "{}", "null"}
           for key in ("color.palettes", "brush.savedLibrary.v1")):
        raise ValueError("Create a custom palette and a saved brush in the old app")
    return project_id


def verify_seed_report(value: dict, campaign: dict) -> None:
    expected = {"schemaVersion": 1, "mode": "automated-old-app-ui", "campaignToken": campaign["token"],
                "packageName": PACKAGE, "oldSource": PINNED_V1_SOURCE,
                "oldApkSha256": campaign["apks"]["old"]["sha256"], "projectName": campaign["projectName"],
                "databaseVersion": 1, "success": True}
    if any(value.get(key) != item or type(value.get(key)) is not type(item) for key, item in expected.items()):
        raise ValueError("Missing, stale or mismatched old-app UI seed proof")
    for key in ("pid", "projectId"):
        if type(value.get(key)) is not int or value[key] <= 0:
            raise ValueError("The UI seed must identify its real process and project")
    assertions = value.get("assertions")
    if not isinstance(assertions, dict) or set(assertions) != set(SEED_ATTESTATIONS) or any(v is not True for v in assertions.values()):
        raise ValueError("The old-app UI seed must prove every required UI step")


def require_one_test(output: str, phase: str) -> None:
    if (not re.search(r"OK \(1 test\)", output) or "INSTRUMENTATION_CODE: -1" not in output
            or re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -[234]", output)):
        raise ValueError(f"The first {phase} instrumentation invocation did not pass exactly one test")


def assert_same_data(before: dict, after: dict) -> None:
    if before["files"] != after["files"] or before["database"] != after["database"]:
        raise ValueError("Stopped app data changed; original and observed snapshots are retained")


def require_ack(args) -> None:
    if not re.fullmatch(r"[A-Za-z0-9_.:-]+", args.serial):
        raise ValueError("An explicit valid ADB serial is required")
    if args.disposable_device != args.serial or args.allow_package != PACKAGE:
        raise ValueError("Explicit --disposable-device SERIAL and --allow-package com.artflow.studio acknowledgements are required")


def launch(commands: Commands, name: str) -> None:
    output = commands.shell(name, "am", "start", "-W", "-n", PACKAGE + "/.presentation.ui.MainActivity", timeout=60).read_text()
    if not re.search(r"(?m)^Status: ok\s*$", output):
        raise ValueError("The real application Activity did not launch successfully")


def prepare(args, campaign: dict, commands: Commands) -> None:
    for field in ("old_source", "candidate_source"):
        if not re.fullmatch(r"[0-9a-f]{40}", getattr(args, field)):
            raise ValueError("Exact historical and candidate 40-character source revisions are required")
    campaign.update({"oldSource": args.old_source, "candidateSource": args.candidate_source,
                     "baselineOrigin": args.baseline_origin, "sourceProvenance": "operator-supplied; APK hashes are measured",
                     "device": device(commands), "apks": {}})
    for name, source, pin in (("old", args.old_apk, args.old_sha256), ("candidate", args.candidate_apk, args.candidate_sha256),
                              ("tests", args.test_apk, args.test_sha256)):
        target = args.campaign / "inputs" / (name + ".apk")
        retain(source, target, pin)
        campaign["apks"][name] = inspect_apk(commands, target, name, campaign["tools"])
    if bool(args.seed_apk) != bool(args.seed_sha256):
        raise ValueError("Automated seeding requires both a seed APK and its pinned hash")
    if bool(args.build_record) != bool(args.build_record_sha256):
        raise ValueError("A build record and its pinned hash must be supplied together")
    if args.seed_apk:
        if args.old_source != PINNED_V1_SOURCE or args.baseline_origin != "rebuilt-pinned-source":
            raise ValueError("The automatic old-app UI driver is audited for the exact pinned Room-v1 source only")
        target = args.campaign / "inputs" / "seed.apk"
        retain(args.seed_apk, target, args.seed_sha256)
        campaign["apks"]["seed"] = inspect_apk(commands, target, "seed", campaign["tools"])
        if args.build_record is None or args.build_record_sha256 is None:
            raise ValueError("Automated seeding needs the measured pinned-source/test-overlay build record")
    if args.build_record is not None:
        receipt = retain(args.build_record, args.campaign / "inputs" / "build-provenance.json", args.build_record_sha256)
        measured = json.loads(Path(receipt["path"]).read_text())
        package_upgrade_build.validate(measured)
        if (measured["oldSource"] != args.old_source or measured["candidateSource"] != args.candidate_source
                or set(measured["apks"]) != set(campaign["apks"])
                or any(measured["apks"][role]["sha256"] != apk["sha256"] for role, apk in campaign["apks"].items())):
            raise ValueError("The measured build record does not identify these source revisions and APKs")
        campaign["buildRecord"] = receipt
        campaign["sourceProvenance"] = "measured pinned-source/test-overlay build record; no independent remote attestation"
    campaign["replacementScope"] = validate_apks(campaign["apks"])
    if any(apk["minSdk"] > campaign["device"]["api"] for apk in campaign["apks"].values()):
        raise ValueError("The designated device is below an APK's minimum SDK")
    require_absent(commands, PACKAGE)
    require_absent(commands, TEST_PACKAGE)
    # All compatibility and disposable-target checks precede the first device mutation.
    write_json(args.campaign / "campaign.json", campaign)
    install(commands, campaign["apks"]["old"], "install-old")
    campaign["installedOld"] = installed_apk(commands, PACKAGE, campaign["apks"]["old"], "installed-old")
    launch(commands, "launch-old-app")
    campaign["phase"] = "awaiting-old-app-ui-seed"


def seed(args, campaign: dict, commands: Commands) -> None:
    if campaign["phase"] != "awaiting-old-app-ui-seed" or "seed" not in campaign["apks"]:
        raise ValueError("seed requires the prepared pinned old-app UI driver")
    validate_apks(campaign["apks"])
    installed_apk(commands, PACKAGE, campaign["apks"]["old"], "old-before-seed")
    require_absent(commands, TEST_PACKAGE)
    commands.shell("stop-old-before-ui-seed", "am", "force-stop", PACKAGE)
    install(commands, campaign["apks"]["seed"], "install-old-ui-driver", "-t")
    installed_apk(commands, TEST_PACKAGE, campaign["apks"]["seed"], "installed-old-ui-driver")
    external = f"/sdcard/Android/data/{PACKAGE}/files/upgrade-{campaign['token']}-old"
    output = commands.shell(
        "instrumentation-old-ui-seed", "am", "instrument", "-w", "-r", "-e", "class", SEED_METHOD,
        "-e", "artflowPackageUpgradeToken", campaign["token"],
        "-e", "artflowOldApkSha256", campaign["apks"]["old"]["sha256"],
        "-e", "artflowOldSource", campaign["oldSource"], "-e", "additionalTestOutputDir", external,
        RUNNER, timeout=240,
    ).read_text()
    # Retain the first invocation's device evidence even if instrumentation failed.
    destination = args.campaign / "old-ui-evidence"
    commands.adb_command("pull-old-ui-evidence", "pull", external + "/test-evidence", str(destination), timeout=60, required=False)
    require_one_test(output, "old UI seed")
    reports = list(destination.rglob("old-app-ui-seed.json"))
    if len(reports) != 1:
        raise ValueError("The old application did not retain exactly one UI seed report")
    value = json.loads(reports[0].read_text())
    verify_seed_report(value, campaign)
    campaign["automatedOldAppSeed"] = {"report": retain(reports[0], args.campaign / "old-app-seed-report.json"), "proof": value}
    campaign["phase"] = "old-app-ui-seeded"


def freeze(args, campaign: dict, commands: Commands) -> None:
    if campaign["phase"] not in ("awaiting-old-app-ui-seed", "old-app-ui-seeded"):
        raise ValueError("freeze requires the single prepared old-app campaign")
    installed_apk(commands, PACKAGE, campaign["apks"]["old"], "old-before-freeze")
    if campaign["phase"] == "old-app-ui-seeded":
        if args.seed_record is not None:
            raise ValueError("Do not substitute a manual attestation for the measured automatic UI seed")
        recorded = campaign["automatedOldAppSeed"]["report"]
        if sha256(Path(recorded["path"])) != recorded["sha256"]:
            raise ValueError("The first old-app UI seed report changed")
        proof = json.loads(Path(recorded["path"]).read_text())
        verify_seed_report(proof, campaign)
        if proof != campaign["automatedOldAppSeed"]["proof"]:
            raise ValueError("The cached old-app UI proof changed")
    else:
        if args.seed_record is None:
            raise ValueError("Manual seeding requires a named operator's --seed-record")
        record = retain(args.seed_record, args.campaign / "old-app-seed-record.json")
        seed_record = json.loads(Path(record["path"]).read_text())
        if (seed_record.get("schemaVersion") != 1 or any(seed_record.get(key) is not True for key in SEED_ATTESTATIONS)
                or not isinstance(seed_record.get("operator"), str) or not seed_record["operator"].strip()):
            raise ValueError("A named operator must attest each bounded old-app UI seed step")
        campaign["manualOldAppSeed"] = record
    commands.adb_command("old-seed-screen", "exec-out", "screencap -p", suffix=".png")
    commands.shell("old-seed-activity", "dumpsys", "activity", "top")
    commands.shell("stop-old-for-snapshot", "am", "force-stop", PACKAGE)
    seed = snapshot(commands, "old-app-data")
    project_id = validate_seed(seed, campaign["projectName"])
    if "automatedOldAppSeed" in campaign:
        proof = campaign["automatedOldAppSeed"]["proof"]
        if seed["database"]["version"] != proof["databaseVersion"] or project_id != proof["projectId"]:
            raise ValueError("The stopped actual seed does not match the old-app UI proof")
    campaign["seed"] = seed
    campaign["projectId"] = project_id
    campaign["phase"] = "seed-frozen"


def verify_probe_report(value: dict, campaign: dict, phase: str, baseline_hash: str) -> None:
    expected = {"schemaVersion": 1, "campaignToken": campaign["token"], "phase": phase,
                "baselineSha256": baseline_hash, "candidateApkSha256": campaign["apks"]["candidate"]["sha256"],
                "oldApkSha256": campaign["apks"]["old"]["sha256"], "projectId": campaign["projectId"],
                "packageName": PACKAGE, "success": True,
                "oldDatabaseVersion": campaign["seed"]["database"]["version"], "databaseVersion": 2}
    if (any(value.get(key) != item or type(value.get(key)) is not type(item) for key, item in expected.items())):
        raise ValueError(f"Missing, stale or incomplete {phase} campaign proof")
    if type(value.get("pid")) is not int or value["pid"] <= 0:
        raise ValueError("Campaign proof must identify the executing process")
    assertions = {"edit": {"allRowsPreservedBeforeEdit", "allArtworkBytesPreservedBeforeEdit", "settingsAndLibraryDecoded",
                            "savedPixelsMatchOldFlattened", "newerRecoveryLoadedThroughUi", "realPointerChangedArtwork", "savedThroughUi"},
                  "reopen": {"freshProcess", "editedPixelsReopened", "settingsPreserved", "otherProjectsPreserved", "recoveryResolved"}}
    actual = value.get("assertions")
    if (not isinstance(actual, dict) or set(actual) != assertions[phase]
            or any(checked is not True for checked in actual.values())):
        raise ValueError(f"{phase} campaign proof does not contain every required assertion")


def upgrade(args, campaign: dict, commands: Commands) -> None:
    if campaign["phase"] != "seed-frozen":
        raise ValueError("upgrade requires a frozen seed and permits only one replacement attempt")
    validate_apks(campaign["apks"])
    installed_apk(commands, PACKAGE, campaign["apks"]["old"], "old-before-replacement")
    if "seed" in campaign["apks"]:
        installed_apk(commands, TEST_PACKAGE, campaign["apks"]["seed"], "old-ui-driver-before-replacement")
    else:
        require_absent(commands, TEST_PACKAGE)
    commands.shell("stop-old-before-replacement", "am", "force-stop", PACKAGE)
    assert_same_data(campaign["seed"], snapshot(commands, "immediately-before-replacement"))
    campaign["phase"] = "replacement-started"
    write_json(args.campaign / "campaign.json", campaign)
    install(commands, campaign["apks"]["candidate"], "replace-with-candidate", "-r")
    campaign["installedCandidate"] = installed_apk(commands, PACKAGE, campaign["apks"]["candidate"], "installed-candidate")
    assert_same_data(campaign["seed"], snapshot(commands, "after-install-before-launch"))
    campaign["installPreservedStoppedData"] = True
    launch(commands, "launch-real-candidate")
    commands.adb_command("candidate-real-launch-screen", "exec-out", "screencap -p", suffix=".png")
    commands.shell("stop-candidate-before-probe", "am", "force-stop", PACKAGE)
    install(commands, campaign["apks"]["tests"], "install-candidate-test-apk", "-r", "-t")
    installed_apk(commands, TEST_PACKAGE, campaign["apks"]["tests"], "installed-tests")
    baseline = {"schemaVersion": 1, "campaignToken": campaign["token"], "packageName": PACKAGE,
                "projectId": campaign["projectId"], "projectName": campaign["projectName"],
                "oldApkSha256": campaign["apks"]["old"]["sha256"],
                "candidateApkSha256": campaign["apks"]["candidate"]["sha256"],
                "database": campaign["seed"]["database"],
                "files": {name: record for name, record in campaign["seed"]["files"].items() if name.startswith("files/")}}
    baseline_path = args.campaign / "baseline.json"
    write_json(baseline_path, baseline)
    baseline_hash = sha256(baseline_path)
    campaign["baselineSha256"] = baseline_hash
    remote_tmp = "/data/local/tmp/artflow-upgrade-" + campaign["token"] + ".json"
    remote_private = "files/package-upgrade/" + campaign["token"]
    commands.adb_command("push-baseline", "push", str(baseline_path), remote_tmp)
    commands.shell("create-campaign-directory", "run-as", PACKAGE, "mkdir", "-p", remote_private)
    commands.shell("copy-baseline", "run-as", PACKAGE, "cp", remote_tmp, remote_private + "/baseline.json")
    external = f"/sdcard/Android/data/{PACKAGE}/files/upgrade-{campaign['token']}"
    reports = {}
    for phase in ("edit", "reopen"):
        commands.shell("stop-before-" + phase, "am", "force-stop", PACKAGE)
        output = commands.shell(
            "instrumentation-" + phase, "am", "instrument", "-w", "-r", "-e", "class", METHOD,
            "-e", "artflowPackageUpgradeToken", campaign["token"], "-e", "artflowPackageUpgradePhase", phase,
            "-e", "artflowPackageUpgradeBaselineSha256", baseline_hash,
            "-e", "additionalTestOutputDir", external, RUNNER, timeout=180,
        ).read_text()
        require_one_test(output, phase)
        destination = args.campaign / ("probe-" + phase)
        commands.adb_command("pull-" + phase + "-evidence", "pull", external + "/test-evidence", str(destination), timeout=60)
        paths = list(destination.rglob("package-upgrade-" + phase + ".json"))
        if len(paths) != 1:
            raise ValueError("Instrumentation did not retain exactly one campaign report")
        value = json.loads(paths[0].read_text())
        verify_probe_report(value, campaign, phase, baseline_hash)
        reports[phase] = value
    if reports["edit"]["pid"] == reports["reopen"]["pid"] or reports["reopen"].get("editPid") != reports["edit"]["pid"]:
        raise ValueError("Reopen did not occur in a fresh process linked to the first edit")
    campaign["probeReports"] = reports
    commands.shell("stop-after-probe", "am", "force-stop", PACKAGE)
    campaign["postCampaignData"] = snapshot(commands, "after-edit-save-reopen")
    campaign["phase"] = "passed-scoped-package-campaign"
    campaign["qualified"] = True
    campaign["installedV1MigrationQualified"] = campaign["seed"]["database"]["version"] == 1


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    sub = result.add_subparsers(dest="action", required=True)
    for action in ("prepare", "seed", "freeze", "upgrade"):
        command = sub.add_parser(action)
        command.add_argument("--campaign", type=Path, required=True)
        command.add_argument("--serial", required=True)
        command.add_argument("--disposable-device", required=True, help="Repeat the designated serial to authorize this disposable device")
        command.add_argument("--allow-package", required=True, help="Explicitly authorize com.artflow.studio test-package mutations")
        if action == "prepare":
            command.add_argument("--adb", default="adb")
            command.add_argument("--apkanalyzer", default="apkanalyzer")
            command.add_argument("--apksigner", default="apksigner")
            for name in ("old", "candidate", "test"):
                command.add_argument("--" + name + "-apk", type=Path, required=True)
                command.add_argument("--" + name + "-sha256", required=True)
            command.add_argument("--old-source", required=True)
            command.add_argument("--candidate-source", required=True)
            command.add_argument("--baseline-origin", choices=("archived-apk", "rebuilt-pinned-source"), required=True)
            command.add_argument("--seed-apk", type=Path)
            command.add_argument("--seed-sha256")
            command.add_argument("--build-record", type=Path)
            command.add_argument("--build-record-sha256")
        elif action == "freeze":
            command.add_argument("--seed-record", type=Path)
    return result


def main(argv=None) -> int:
    args = parser().parse_args(argv)
    args.campaign = args.campaign.resolve()
    commands = None
    campaign = None
    try:
        if args.action == "prepare":
            args.campaign.mkdir(parents=True, exist_ok=False)
            token = str(uuid.uuid4())
            campaign = {"schemaVersion": 1, "token": token, "createdAtUtc": now(), "serial": args.serial,
                        "projectName": "Package upgrade " + token[:8], "phase": "preflight", "qualified": False,
                        "publisherQualified": False, "installedV1MigrationQualified": False, "storeReleaseQualified": False,
                        "disposableDeviceAcknowledged": args.disposable_device, "packageAcknowledged": args.allow_package,
                        "tools": {name: getattr(args, name) for name in ("adb", "apkanalyzer", "apksigner")}}
        else:
            campaign = json.loads((args.campaign / "campaign.json").read_text())
            if campaign["serial"] != args.serial or not TOKEN.fullmatch(campaign["token"]):
                raise ValueError("The campaign belongs to a different device or has an invalid token")
            if campaign.get("firstFailure") or campaign["phase"] == "passed-scoped-package-campaign":
                raise ValueError("This campaign has a final outcome; retain it and use a new disposable device for another attempt")
        # Exclusively claim the phase before issuing even diagnostic commands. A repeated phase
        # cannot replace its first failure, evidence, or the campaign's status.
        phase_marker = args.campaign / (args.action + ".claimed")
        phase_marker.open("x").close()
    except (OSError, ValueError, KeyError) as error:
        print(f"Campaign not started: {error}", file=sys.stderr)
        return 2
    try:
        require_ack(args)
        commands = Commands(args.campaign / (args.action + "-commands"), args.serial, campaign["tools"]["adb"])
        if args.action != "prepare":
            current_device = device(commands)
            if current_device != campaign["device"]:
                raise ValueError("Device identity changed since preparation")
        {"prepare": prepare, "seed": seed, "freeze": freeze, "upgrade": upgrade}[args.action](args, campaign, commands)
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError, tarfile.TarError, sqlite3.Error) as error:
        campaign["qualified"] = False
        campaign["firstFailure"] = {"phase": args.action, "atUtc": now(), "error": str(error)}
        campaign["phase"] = "failed-" + args.action
        print(f"Unqualified: {error}", file=sys.stderr)
        return_code = 1
    else:
        return_code = 0
        print(f"{campaign['phase']}: {args.campaign / 'campaign.json'}")
        if args.action == "prepare":
            print(f"Seed the OLD app through its UI. Artwork name: {campaign['projectName']}")
    finally:
        campaign["updatedAtUtc"] = now()
        write_json(args.campaign / "campaign.json", campaign)
        if commands is not None:
            commands.diagnostics(campaign["token"])
    return return_code


if __name__ == "__main__":
    sys.exit(main())
