#!/usr/bin/env python3
"""Record a pinned old production tree plus a test-only seed overlay, and the actual built APKs."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tarfile

OLD_SOURCE = "ca9ce535c09d35ceca6aba9f686c60f58f93bc09"
SCHEMA = "app/schemas/com.artflow.studio.data.local.database.ArtFlowDatabase/1.json"
SCHEMA_SHA = "afbdb33dcf9e2e6f2610560ca1adf4cd876c5241a3f56b62a162361c5c493dde"
DRIVER = "tools/package-upgrade/src/com/artflow/studio/InstalledUpgradeSeedTest.kt"
OVERLAY = "app/src/androidTest/java/com/artflow/studio/InstalledUpgradeSeedTest.kt"
APK_PATHS = {"old": ("baseline", "app/build/outputs/apk/debug/app-debug.apk"),
             "seed": ("baseline", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"),
             "candidate": ("candidate", "app/build/outputs/apk/debug/app-debug.apk"),
             "tests": ("candidate", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")}
MAX_SOURCE_BYTES = 256 * 1024 * 1024
MAX_SOURCE_ENTRIES = 20_000


def require_unlinked(path: Path) -> None:
    if any(item.is_symlink() for item in (path, *path.parents)):
        raise ValueError(f"Linked input/output path: {path}")


def load(path: Path) -> dict:
    require_unlinked(path)
    def number(text):
        value = float(text)
        if not math.isfinite(value):
            raise ValueError("Nonfinite build evidence number")
        return value
    def constant(text):
        raise ValueError(f"Invalid build evidence number: {text}")
    value = json.loads(path.read_text(), parse_float=number, parse_constant=constant)
    if not isinstance(value, dict):
        raise ValueError("Build evidence must be a JSON object")
    return value


def sha256(path: Path) -> str:
    require_unlinked(path)
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
    require_unlinked(path)
    data = json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n"
    with path.open("x") as stream:
        stream.write(data)


def git_object(kind: str, data: bytes) -> str:
    return hashlib.sha1(kind.encode() + b" " + str(len(data)).encode() + b"\0" + data).hexdigest()


def verify_record(item: dict) -> Path:
    if (not isinstance(item, dict) or set(item) != {"path", "sha256", "sizeBytes"}
            or not isinstance(item["path"], str) or not Path(item["path"]).is_absolute()
            or not isinstance(item["sha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", item["sha256"])
            or type(item["sizeBytes"]) is not int or item["sizeBytes"] <= 0):
        raise ValueError("Malformed retained build input/output identity")
    path = Path(item["path"])
    if sha256(path) != item["sha256"] or path.stat().st_size != item["sizeBytes"]:
        raise ValueError("Retained build input/output bytes changed")
    return path


def archive_tree(path: Path, revision: str) -> tuple[str, dict]:
    """Hash the complete retained Git tree without extracting any archive path."""
    tree, files, seen, total = {}, {}, set(), 0
    with tarfile.open(path, "r:gz") as archive:
        for member in archive:
            name = member.name.rstrip("/") if member.isdir() else member.name
            relative = PurePosixPath(name)
            if (not name or str(relative) != name or relative.is_absolute() or ".." in relative.parts
                    or "\\" in name or "\0" in name or name in seen
                    or not (member.isfile() or member.isdir()) or len(relative.parts) > 100):
                raise ValueError(f"Invalid source archive entry: {member.name}")
            seen.add(name)
            if len(seen) > MAX_SOURCE_ENTRIES:
                raise ValueError("Source archive entry budget exceeded")
            if member.isdir():
                continue
            total += member.size
            if member.size < 0 or total > MAX_SOURCE_BYTES:
                raise ValueError("Source archive byte budget exceeded")
            blob = hashlib.sha1(b"blob " + str(member.size).encode() + b"\0")
            content = hashlib.sha256()
            stream = archive.extractfile(member)
            if stream is None:
                raise ValueError("Missing source archive content")
            size = 0
            with stream:
                for data in iter(lambda: stream.read(65536), b""):
                    size += len(data)
                    blob.update(data)
                    content.update(data)
            if size != member.size:
                raise ValueError("Incomplete source archive content")
            files[name] = content.hexdigest()
            directory = tree
            for component in relative.parts[:-1]:
                directory = directory.setdefault(component, {})
                if not isinstance(directory, dict):
                    raise ValueError("Conflicting source archive paths")
            if relative.name in directory:
                raise ValueError("Conflicting source archive paths")
            directory[relative.name] = (b"100755" if member.mode & 0o111 else b"100644", blob.digest())
        if archive.pax_headers.get("comment") != revision:
            raise ValueError("Source archive does not identify the recorded Git commit")
    def tree_hash(directory):
        body = bytearray()
        for name, value in sorted(directory.items(), key=lambda item: item[0].encode() + (b"/" if isinstance(item[1], dict) else b"")):
            mode, digest = (b"40000", bytes.fromhex(tree_hash(value))) if isinstance(value, dict) else value
            body.extend(mode + b" " + name.encode() + b"\0" + digest)
        return git_object("tree", bytes(body))
    return tree_hash(tree), files


def validate_sources(value: dict) -> None:
    if set(value.get("sources", {})) != {"old", "candidate"} or set(value.get("commitObjects", {})) != {"old", "candidate"}:
        raise ValueError("Missing exact source archive or Git commit object")
    driver = verify_record(value["driver"])
    for role in ("old", "candidate"):
        revision, tree = value.get(role + "Source"), value.get(role + "Tree")
        if (not isinstance(revision, str) or not re.fullmatch(r"[0-9a-f]{40}", revision)
                or not isinstance(tree, str) or not re.fullmatch(r"[0-9a-f]{40}", tree)):
            raise ValueError("Invalid exact Git source/tree identity")
        commit = verify_record(value["commitObjects"][role])
        if commit.stat().st_size > 1024 * 1024:
            raise ValueError("Oversized Git commit object")
        data = commit.read_bytes()
        if git_object("commit", data) != revision or data.split(b"\n", 1)[0] != b"tree " + tree.encode():
            raise ValueError("Retained Git commit does not bind the recorded source and tree")
        archive = verify_record(value["sources"][role])
        actual, files = archive_tree(archive, revision)
        if actual != tree or files.get(SCHEMA) != SCHEMA_SHA:
            raise ValueError("Retained source tree or historical schema differs from its Git identity")
        if role == "old" and OVERLAY in files:
            raise ValueError("The old production source must precede the test-only overlay")
        if role == "candidate" and files.get(DRIVER) != sha256(driver):
            raise ValueError("The retained UI driver is not the candidate's committed test-only source")


def prepare(candidate: Path, baseline: Path, output: Path) -> dict:
    for path in (candidate, baseline, output):
        require_unlinked(path)
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
             "schemaSha256": SCHEMA_SHA,
             "baselineProductionModified": False, "allowedOldTestOverlay": OVERLAY,
             "sourceProvenance": "Measured in the workflow; not a reproducible-build or remote-attestation claim",
             "publisherQualified": False, "sources": {}, "commitObjects": {}}
    for name, root in (("old", baseline), ("candidate", candidate)):
        path = output / f"{name}-source.tar.gz"
        with path.open("xb") as stream:
            subprocess.run(["git", "-C", str(root), "archive", "--format=tar.gz", "HEAD"], stdout=stream, check=True, timeout=60)
        value["sources"][name] = record(path)
        commit = output / f"{name}-commit.object"
        with commit.open("xb") as stream:
            subprocess.run(["git", "-C", str(root), "cat-file", "commit", "HEAD"], stdout=stream, check=True, timeout=60)
        value["commitObjects"][name] = record(commit)
    destination = baseline / OVERLAY
    require_unlinked(destination)
    require_unlinked(candidate / DRIVER)
    destination.parent.mkdir(parents=True, exist_ok=True)
    for path in (destination, output / "InstalledUpgradeSeedTest.kt"):
        with path.open("xb") as stream, (candidate / DRIVER).open("rb") as source:
            shutil.copyfileobj(source, stream)
    value["driver"] = record(output / "InstalledUpgradeSeedTest.kt")
    require_source(baseline, OLD_SOURCE, [OVERLAY])
    validate_sources(value)
    write(output / "build-inputs.json", value)
    return value


def finish(output: Path) -> dict:
    require_unlinked(output)
    marker = output / "finish.claimed"
    marker.open("x").close()
    value = load(output / "build-inputs.json")
    validate_sources(value)
    roots = {name: Path(path) for name, path in value["roots"].items()}
    for root in roots.values():
        require_unlinked(root)
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
    if set(value.get("apks", {})) != set(APK_PATHS):
        raise ValueError("Missing built APK or exact source archive")
    validate_sources(value)
    for item in value["apks"].values():
        verify_record(item)


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
    value = prepare(args.candidate.absolute(), args.baseline.absolute(), args.output.absolute()) if args.action == "prepare" else finish(args.output.absolute())
    print(value["phase"])


if __name__ == "__main__":
    main()
