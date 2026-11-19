#!/usr/bin/env python3
"""Record a pinned old production tree plus a test-only seed overlay, and the actual built APKs."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

OLD_SOURCE = "ca9ce535c09d35ceca6aba9f686c60f58f93bc09"
SCHEMA = "app/schemas/com.artflow.studio.data.local.database.ArtFlowDatabase/1.json"
SCHEMA_SHA = "afbdb33dcf9e2e6f2610560ca1adf4cd876c5241a3f56b62a162361c5c493dde"
DRIVER = "tools/package-upgrade/src/com/artflow/studio/InstalledUpgradeSeedTest.kt"
OVERLAY = "app/src/androidTest/java/com/artflow/studio/InstalledUpgradeSeedTest.kt"
APK_PATHS = {"old": ("baseline", "app/build/outputs/apk/debug/app-debug.apk"),
             "seed": ("baseline", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"),
             "candidate": ("candidate", "app/build/outputs/apk/debug/app-debug.apk"),
             "tests": ("candidate", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")}


def sha256(path: Path) -> str:
    if not path.is_file() or path.is_symlink():
        raise ValueError(f"Missing or linked input: {path}")
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def record(path: Path) -> dict:
    return {"path": str(path.resolve()), "sha256": sha256(path), "sizeBytes": path.stat().st_size}


def git(root: Path, *arguments: str) -> str:
    return subprocess.check_output(["git", "-C", str(root), *arguments], text=True, timeout=60).strip()


def require_source(root: Path, revision: str, allowed_untracked=()) -> None:
    if git(root, "rev-parse", "HEAD") != revision:
        raise ValueError("The checked-out source revision changed")
    if git(root, "diff", "HEAD", "--name-only"):
        raise ValueError("Tracked baseline/candidate sources must remain unchanged")
    actual = set(git(root, "ls-files", "--others", "--exclude-standard").splitlines())
    if actual != set(allowed_untracked):
        raise ValueError(f"Unexpected untracked build inputs: {sorted(actual)}")


def write(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def prepare(candidate: Path, baseline: Path, output: Path) -> dict:
    output.mkdir(parents=True, exist_ok=False)
    candidate_revision = git(candidate, "rev-parse", "HEAD")
    require_source(candidate, candidate_revision)
    require_source(baseline, OLD_SOURCE)
    if sha256(baseline / SCHEMA) != SCHEMA_SHA or sha256(candidate / SCHEMA) != SCHEMA_SHA:
        raise ValueError("The real historical Room v1 schema does not match the committed migration contract")
    value = {"schemaVersion": 1, "phase": "prepared", "baselineOrigin": "rebuilt-pinned-source",
             "oldSource": OLD_SOURCE, "candidateSource": candidate_revision,
             "roots": {"candidate": str(candidate.resolve()), "baseline": str(baseline.resolve())},
             "oldTree": git(baseline, "rev-parse", "HEAD^{tree}"),
             "candidateTree": git(candidate, "rev-parse", "HEAD^{tree}"),
             "schemaSha256": SCHEMA_SHA, "driver": record(candidate / DRIVER),
             "baselineProductionModified": False, "allowedOldTestOverlay": OVERLAY,
             "sourceProvenance": "Measured in the workflow; not a reproducible-build or remote-attestation claim",
             "publisherQualified": False, "sources": {}}
    for name, root in (("old", baseline), ("candidate", candidate)):
        path = output / f"{name}-source.tar.gz"
        git(root, "archive", "--format=tar.gz", "--output", str(path), "HEAD")
        value["sources"][name] = record(path)
    destination = baseline / OVERLAY
    if destination.exists():
        raise ValueError("The seed test overlay must be a new test-only source")
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(candidate / DRIVER, destination)
    shutil.copyfile(candidate / DRIVER, output / "InstalledUpgradeSeedTest.kt")
    require_source(baseline, OLD_SOURCE, [OVERLAY])
    write(output / "build-inputs.json", value)
    return value


def finish(output: Path) -> dict:
    marker = output / "finish.claimed"
    marker.open("x").close()
    value = json.loads((output / "build-inputs.json").read_text())
    roots = {name: Path(path) for name, path in value["roots"].items()}
    require_source(roots["candidate"], value["candidateSource"])
    require_source(roots["baseline"], OLD_SOURCE, [OVERLAY])
    if (sha256(roots["candidate"] / DRIVER) != value["driver"]["sha256"]
            or sha256(roots["baseline"] / OVERLAY) != value["driver"]["sha256"]):
        raise ValueError("The test-only driver changed during the builds")
    value["apks"] = {}
    for role, (root, relative) in APK_PATHS.items():
        source = roots[root] / relative
        original = record(source)
        target = output / "apks" / f"{role}.apk"
        target.parent.mkdir(exist_ok=True)
        with target.open("xb") as out, source.open("rb") as stream:
            shutil.copyfileobj(stream, out)
        retained = record(target)
        if retained["sha256"] != original["sha256"] or retained["sizeBytes"] != original["sizeBytes"]:
            raise ValueError("An APK changed while its evidence copy was retained")
        value["apks"][role] = retained
    value["tooling"] = {"javaHome": os.environ.get("JAVA_HOME"), "androidHome": os.environ.get("ANDROID_HOME"),
                        "gradleWrapper": {name: record(root / "gradle/wrapper/gradle-wrapper.jar") for name, root in roots.items()},
                        "gradleDistributionInputs": {name: record(root / "gradle/wrapper/gradle-wrapper.properties") for name, root in roots.items()}}
    value["phase"] = "built-with-test-only-baseline-overlay"
    write(output / "build-provenance.json", value)
    return value


def validate(value: dict) -> None:
    if (type(value.get("schemaVersion")) is not int or value.get("schemaVersion") != 1
            or value.get("phase") != "built-with-test-only-baseline-overlay"
            or value.get("oldSource") != OLD_SOURCE or value.get("schemaSha256") != SCHEMA_SHA
            or value.get("baselineProductionModified") is not False or value.get("publisherQualified") is not False
            or value.get("allowedOldTestOverlay") != OVERLAY or value.get("baselineOrigin") != "rebuilt-pinned-source"):
        raise ValueError("The automatic campaign needs a complete pinned-source/test-overlay build record")
    if set(value.get("apks", {})) != set(APK_PATHS) or set(value.get("sources", {})) != {"old", "candidate"}:
        raise ValueError("Missing built APK or exact source archive")
    for item in [*value["apks"].values(), *value["sources"].values(), value["driver"]]:
        path = Path(item["path"])
        if sha256(path) != item["sha256"] or path.stat().st_size != item["sizeBytes"]:
            raise ValueError("Retained build input/output bytes changed")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    initial = commands.add_parser("prepare")
    initial.add_argument("--candidate", type=Path, required=True)
    initial.add_argument("--baseline", type=Path, required=True)
    initial.add_argument("--output", type=Path, required=True)
    final = commands.add_parser("finish")
    final.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    value = prepare(args.candidate.resolve(), args.baseline.resolve(), args.output.resolve()) if args.action == "prepare" else finish(args.output.resolve())
    print(value["phase"])


if __name__ == "__main__":
    main()
