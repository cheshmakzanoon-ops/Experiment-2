#!/usr/bin/env python3
"""Fail-closed publisher APK/AAB checks; never an overall production release approval.

Uses the existing native/ZIP preflight and official Android/JDK tools. No private
keystore is opened. See docs/verification/publisher-artifact-qualification.md.
"""
from __future__ import annotations

import argparse
import base64
from datetime import datetime, timezone
import hashlib
import io
import json
import math
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import struct
import subprocess
import tarfile
import tempfile
import xml.etree.ElementTree as ET
import zipfile
import zlib

from verify_android_artifacts import verify_archive


ANDROID = "{http://schemas.android.com/apk/res/android}"
REPOSITORY = "cheshmakzanoon-ops/Experiment-2"
PACKAGE = "com.artflow.studio"
MIN_SDK, TARGET_SDK = 26, 36
MAX_ENTRY_BYTES = 512 * 1024 * 1024
MAX_ARCHIVE_BYTES = 2 * 1024 * 1024 * 1024
SIGNATURE_ENTRY = re.compile(r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))", re.I)
TEST_NAMES = (
    "com.artflow.studio.SelectionCanvasTestActivity", "com.artflow.studio.HiltTestRunner",
    "dagger.hilt.android.testing.HiltTestApplication", "androidx.test.", "androidx.compose.ui.test.",
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def digest_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def file_record(path: Path) -> dict:
    require(path.is_file() and not path.is_symlink() and path.stat().st_size > 0,
            f"Missing, empty, or linked evidence file: {path}")
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    return {"path": str(path), "sizeBytes": path.stat().st_size, "sha256": digest}


def fingerprint(value: str | None) -> str:
    require(isinstance(value, str), "An explicitly approved certificate SHA-256 is required")
    normalized = value.replace(":", "").lower()
    require(bool(re.fullmatch(r"[0-9a-f]{64}", normalized)), "Invalid certificate SHA-256")
    return normalized


def utc_date(value: str) -> datetime:
    require(isinstance(value, str), "An ISO-8601 timestamp is required")
    result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    require(result.tzinfo is not None, "Timestamp must include a timezone")
    return result.astimezone(timezone.utc)


def load_object(path: Path) -> dict:
    file_record(path)
    require(path.stat().st_size <= 4 * 1024 * 1024, f"Oversized JSON evidence: {path}")
    def unique_keys(pairs):
        value = {}
        for key, item in pairs:
            require(key not in value, f"Duplicate JSON key: {key}")
            value[key] = item
        return value

    def finite_number(token):
        value = float(token)
        require(math.isfinite(value), f"Non-finite JSON number: {token}")
        return value

    value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_keys,
                       parse_constant=finite_number, parse_float=finite_number)
    require(isinstance(value, dict), f"Expected a JSON object: {path}")
    return value


def archive_entries(path: Path) -> dict:
    """Measure all payload entries; ignore only top-level JAR signing metadata."""
    payload, total = {}, 0
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate archive paths")
        for entry in archive.infolist():
            name = entry.filename
            require(not any(ord(char) < 32 for char in name) and "\\" not in name
                    and not name.startswith("/") and ".." not in PurePosixPath(name).parts,
                    "Unsafe archive path")
            require(not entry.flag_bits & 1 and (entry.external_attr >> 16) & 0o170000 != 0o120000,
                    f"Encrypted or linked archive entry: {name}")
            total += entry.file_size
            require(entry.file_size <= MAX_ENTRY_BYTES and total <= MAX_ARCHIVE_BYTES,
                    "Archive exceeds publisher verification size budget")
            if entry.is_dir() or SIGNATURE_ENTRY.fullmatch(name):
                continue
            with archive.open(entry) as stream:
                digest = hashlib.file_digest(stream, "sha256").hexdigest()
            payload[name] = {"sizeBytes": entry.file_size, "sha256": digest}
    require(bool(payload), "Archive contains no payload")
    return payload


class Tools:
    def __init__(self, output: Path, commands: list):
        self.output, self.commands = output, commands

    def run(self, label: str, args: list[str]) -> str:
        executable = shutil.which(args[0])
        require(executable is not None, f"Required official tool unavailable: {args[0]}")
        path = self.output / f"{len(self.commands):02d}-{label}.txt"
        command = [executable, *map(str, args[1:])]
        record = {"command": command, "exitCode": None, "log": None}
        self.commands.append(record)
        env = {**os.environ, "LC_ALL": "C", "LANG": "C"}
        with path.open("x", encoding="utf-8") as stream:
            try:
                result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT,
                                        env=env, timeout=120, check=False)
                record["exitCode"] = result.returncode
            except subprocess.TimeoutExpired:
                stream.write("\nPublisher verifier: command timed out\n")
                raise ValueError(f"Official verification tool timed out: {label}") from None
            finally:
                stream.flush()
                if path.stat().st_size:
                    record["log"] = file_record(path)
        require(result.returncode == 0, f"{label} failed (exit {result.returncode}); retain its command log")
        require(path.stat().st_size <= 20 * 1024 * 1024, f"Oversized tool output: {label}")
        return path.read_text(encoding="utf-8")


def validate_manifest(xml: str, expected: dict, *, base: bool = True) -> dict:
    require("<!DOCTYPE" not in xml.upper() and "<!ENTITY" not in xml.upper(), "Manifest entities are not accepted")
    root = ET.fromstring(xml)
    require(root.tag == "manifest", "Official tool did not return an Android manifest")
    require(root.get("package") == expected["packageName"], "Packaged application ID mismatch")
    applications = root.findall("application")
    require(len(applications) == 1, "Expected one application element")
    app = applications[0]
    for flag in ("debuggable", "testOnly"):
        require(app.get(ANDROID + flag, "false") in ("false", "0"), f"Publisher application is {flag}, or unresolved")
    require(not root.findall("instrumentation"), "Instrumentation runner packaged in publisher manifest")
    for element in root.iter():
        for value in element.attrib.values():
            qualified = expected["packageName"] + value if value.startswith(".") else value
            require(not any(name in qualified for name in TEST_NAMES)
                    and qualified != "androidx.activity.ComponentActivity",
                    f"Test-only host/runner in packaged manifest: {qualified}")
    observed = {"packageName": root.get("package"), "debuggable": False, "testOnly": False}
    if base:
        sdk = root.findall("uses-sdk")
        require(len(sdk) == 1, "Packaged manifest must declare its SDK range")
        observed.update({"versionCode": root.get(ANDROID + "versionCode"),
                         "versionName": root.get(ANDROID + "versionName"),
                         "minSdk": sdk[0].get(ANDROID + "minSdkVersion"),
                         "targetSdk": sdk[0].get(ANDROID + "targetSdkVersion")})
        require(root.get(ANDROID + "versionCodeMajor", "0") == "0", "Unexpected major version code")
        require(sdk[0].get(ANDROID + "maxSdkVersion") is None, "Unexpected maximum SDK restriction")
        for name in ("versionCode", "versionName", "minSdk", "targetSdk"):
            require(observed[name] == str(expected[name]), f"Packaged {name} mismatch: {observed[name]!r}")
    return observed


def verify_package(path: Path, expected: dict, tools: Tools, options: argparse.Namespace) -> dict:
    payload = archive_entries(path)
    errors, inventory = verify_archive(path)
    require(not errors, "Existing APK/AAB preflight failed: " + "; ".join(errors))
    with zipfile.ZipFile(path) as archive:
        dex_entries = [name for name in payload if re.fullmatch(r"(?:[^/]+/dex/)?classes(?:[2-9]|[1-9][0-9]+)?\.dex", name)]
        require(bool(dex_entries), "No application DEX payload")
        for name in dex_entries:
            data = archive.read(name)
            require(data.startswith(b"dex\n"), f"Invalid DEX payload: {name}")
            for test_name in TEST_NAMES:
                descriptor = ("L" + test_name.replace(".", "/")).encode()
                require(descriptor not in data, f"Test host/runner code packaged in {name}: {test_name}")
    if path.suffix.lower() == ".apk":
        xml = tools.run("apk-manifest", [options.apkanalyzer, "manifest", "print", str(path)])
        manifests = {"base": validate_manifest(xml, expected)}
    else:
        require(options.bundletool is not None, "A caller-supplied official bundletool JAR is required for AAB inspection")
        bundletool = file_record(options.bundletool)
        command = [options.java, "-jar", str(options.bundletool)]
        tools.run("bundle-validate", [*command, "validate", f"--bundle={path}"])
        modules = sorted(name.split("/")[0] for name in payload
                         if re.fullmatch(r"[^/]+/manifest/AndroidManifest.xml", name))
        require("base" in modules, "Bundle has no base module")
        manifests = {}
        for index, module in enumerate(modules):
            xml = tools.run(f"bundle-manifest-{index}", [*command, "dump", "manifest", f"--bundle={path}", f"--module={module}"])
            manifests[module] = validate_manifest(xml, expected, base=module == "base")
        manifests["bundletool"] = bundletool
    return {"manifests": manifests, "nativeInventory": inventory,
            "payloadEntries": len(payload), "payloadSha256": digest_bytes(json.dumps(payload, sort_keys=True).encode())}


def der_item(data: bytes, position: int = 0) -> tuple[int, bytes, int]:
    require(position + 2 <= len(data), "Truncated certificate DER")
    tag, size = data[position], data[position + 1]
    position += 2
    if size & 128:
        count = size & 127
        require(0 < count <= 4 and position + count <= len(data), "Invalid certificate DER length")
        size = int.from_bytes(data[position:position + count], "big")
        position += count
    require(position + size <= len(data), "Truncated certificate DER value")
    return tag, data[position:position + size], position + size


def certificate_validity(der: bytes) -> dict:
    # X.509 Certificate / TBSCertificate / validity. Signature verification remains
    # the official tool's responsibility; this only prevents trust-anchor expiry bypass.
    tag, certificate, _ = der_item(der)
    require(tag == 0x30, "Invalid certificate sequence")
    tag, tbs, _ = der_item(certificate)
    require(tag == 0x30, "Invalid TBSCertificate sequence")
    tag, _, offset = der_item(tbs)
    if tag != 0xA0:  # Optional explicit version.
        offset = 0
    for _ in range(3):  # serial number, signature algorithm, issuer
        _, _, offset = der_item(tbs, offset)
    tag, validity, _ = der_item(tbs, offset)
    require(tag == 0x30, "Missing certificate validity")
    dates, offset = [], 0
    for _ in range(2):
        tag, encoded, offset = der_item(validity, offset)
        value = encoded.decode("ascii")
        if tag == 0x17 and re.fullmatch(r"[0-9]{12}Z", value):
            year = int(value[:2])
            value = ("20" if year < 50 else "19") + value
        else:
            require(tag == 0x18 and bool(re.fullmatch(r"[0-9]{14}Z", value)), "Unsupported certificate validity encoding")
        dates.append(datetime.strptime(value, "%Y%m%d%H%M%SZ").replace(tzinfo=timezone.utc))
    require(offset == len(validity), "Ambiguous certificate validity")
    require(dates[0] <= datetime.now(timezone.utc) <= dates[1], "Signing certificate is expired or not yet valid")
    return {"notBefore": dates[0].isoformat(), "notAfter": dates[1].isoformat()}


def certificate_from_output(output: str, approved: str) -> tuple[bytes, dict]:
    certificates = re.findall(r"-----BEGIN CERTIFICATE-----\s*([A-Za-z0-9+/=\s]+?)\s*-----END CERTIFICATE-----", output)
    require(len(certificates) == 1, "Exactly one signing certificate is required; multiple signers/chains need separate reviewed support")
    der = base64.b64decode(re.sub(r"\s", "", certificates[0]), validate=True)
    require(digest_bytes(der) == approved, "Actual signing certificate does not match explicitly approved SHA-256")
    require(not re.search(r"(?i)\bCN\s*=\s*[\"']?Android Debug\b", output), "Android debug signing certificate is never a publisher certificate")
    return der, {"sha256": digest_bytes(der), **certificate_validity(der)}


def verify_signature(path: Path, approved: str, role: str, tools: Tools, options: argparse.Namespace) -> dict:
    expected_role = "app-signing" if path.suffix.lower() == ".apk" else "upload"
    require(role == expected_role, f"{path.suffix.upper()} requires the approved {expected_role} certificate, not {role!r}")
    if expected_role == "app-signing":
        output = tools.run("apksigner-verify", [options.apksigner, "verify", "--verbose", "--print-certs-pem", "-Werr", str(path)])
        require(re.search(r"(?m)^Verifies\s*$", output) is not None, "APK verification did not report success")
        require(re.findall(r"(?m)^Signer #(\d+) certificate SHA-256 digest:", output) == ["1"],
                "Exactly one APK signer is required; key rotation needs separate reviewed support")
        _, certificate = certificate_from_output(output, approved)
    else:
        java_options = ["-J-Duser.language=en", "-J-Duser.country=US", "-J-Duser.timezone=UTC"]
        output = tools.run("bundle-public-certificate", [options.keytool, *java_options, "-printcert", "-rfc", "-jarfile", str(path)])
        require(re.findall(r"(?m)^Signer #(\d+):", output) == ["1"], "Exactly one AAB signer is required")
        der, certificate = certificate_from_output(output, approved)
        with tempfile.TemporaryDirectory(prefix="artflow-public-cert-") as directory:
            public = Path(directory) / "approved.der"
            public.write_bytes(der)
            truststore = Path(directory) / "public-certificates.p12"
            # This temporary store contains a PUBLIC certificate only, no signing key.
            tools.run("trust-approved-public-certificate", [options.keytool, *java_options, "-importcert", "-noprompt",
                      "-alias", "approved-publisher", "-file", str(public), "-keystore", str(truststore),
                      "-storepass", "public-certificates-only", "-storetype", "PKCS12"])
            output = tools.run("jarsigner-verify", [options.jarsigner, *java_options, "-verify", "-strict", "-verbose", "-certs",
                               "-keystore", str(truststore), "-storepass", "public-certificates-only", str(path), "approved-publisher"])
        require(re.search(r"(?m)^jar verified\.\s*$", output) is not None, "AAB was not cryptographically verified")
        require(not re.search(r"(?i)\b(unsigned entries|disabled|weak|security risk|nonexistent entries)\b", output),
                "AAB signature has unapproved algorithm or entry warnings")
    return {"certificateRole": role, "certificate": certificate, "verified": True,
            "scope": "Installed APK identity" if role == "app-signing" else "Bundle upload identity; not Play-delivered APK identity"}


def verify_approval(path: Path | None, approved: str, role: str, package: str) -> dict:
    require(path is not None, "A retained publisher certificate approval record is required")
    value = load_object(path)
    require(value.get("schemaVersion") == 1 and value.get("approvedForPublisherRelease") is True,
            "Approval must explicitly approve publisher release use")
    require(fingerprint(value.get("certificateSha256")) == approved and value.get("certificateRole") == role
            and value.get("packageName") == package, "Certificate approval identity/role mismatch")
    for field in ("approvedBy", "certificateSource", "approvalReference"):
        require(isinstance(value.get(field), str) and bool(value[field].strip()), f"Missing approval {field}")
    require(utc_date(value.get("approvedAt")) <= datetime.now(timezone.utc), "Approval timestamp is in the future")
    return {"record": file_record(path), "approval": value,
            "authority": "Caller-supplied publisher approval; verifier does not authenticate the approver"}


def evidence_path(root: Path, relative: str) -> Path:
    require(isinstance(relative, str) and bool(relative), "Missing retained evidence path")
    parts = PurePosixPath(relative)
    require(not parts.is_absolute() and ".." not in parts.parts and "\\" not in relative,
            "Retained evidence paths must stay inside the provenance directory")
    path = root
    for part in parts.parts:
        path = path / part
        require(not path.is_symlink(), "Linked retained evidence is not accepted")
    file_record(path)
    return path


def verify_provenance(path: Path | None, artifact: Path, revision: str) -> dict:
    require(path is not None, "Retained CI source/build provenance is required")
    value = load_object(path)
    require(value.get("schemaVersion") == 1 and value.get("buildType") == "release", "Provenance must identify a release build")
    require(value.get("revision") == revision, "Provenance source revision mismatch")
    root = path.parent
    run_path = evidence_path(root, value.get("runMetadata"))
    run = load_object(run_path)
    require(run.get("head_sha") == revision and run.get("repository", {}).get("full_name") == REPOSITORY,
            "Retained CI run is not this repository and exact source revision")
    require(run.get("status") == "completed" and run.get("conclusion") == "success", "Retained CI run did not succeed")
    run_id, attempt = run.get("id"), run.get("run_attempt")
    require(type(run_id) is int and run_id > 0 and type(attempt) is int and attempt > 0, "Invalid CI run/attempt")
    require(value.get("runAttempt") == attempt, "Retained CI attempt mismatch")
    run_url = f"https://github.com/{REPOSITORY}/actions/runs/{run_id}"
    require(run.get("html_url") == run_url, "Retained run URL mismatch")
    start, end = utc_date(run.get("run_started_at")), utc_date(run.get("updated_at"))
    require(start <= end, "Invalid CI attempt time range")

    def retained(name):
        item = value.get(name)
        require(isinstance(item, dict), f"Missing retained {name}")
        archive = evidence_path(root, item.get("archive"))
        metadata_path = evidence_path(root, item.get("metadata"))
        metadata, measured = load_object(metadata_path), file_record(archive)
        workflow = metadata.get("workflow_run", {})
        require(workflow.get("id") == run_id and workflow.get("head_sha") == revision,
                f"{name} belongs to another CI run/revision")
        require(type(metadata.get("id")) is int and metadata["id"] > 0, f"Invalid {name} provider artifact ID")
        require(metadata.get("digest") == "sha256:" + measured["sha256"]
                and metadata.get("size_in_bytes") == measured["sizeBytes"], f"Retained {name} provider digest/size mismatch")
        require(start <= utc_date(metadata.get("created_at")) <= end, f"{name} is from another CI attempt")
        archive_entries(archive)
        return archive, {"archive": measured, "metadata": file_record(metadata_path), "artifactId": metadata["id"],
                         "url": f"{run_url}/artifacts/{metadata['id']}"}

    source, source_record = retained("sourceArtifact")
    build, build_record = retained("buildArtifact")
    with zipfile.ZipFile(source) as archive:
        revision_files = [name for name in archive.namelist() if PurePosixPath(name).name == "REVISION.txt"]
        archives = [name for name in archive.namelist() if PurePosixPath(name).name == "artflow-source.tar.gz"]
        require(len(revision_files) == len(archives) == 1, "Retained source must contain unique REVISION.txt and git source archive")
        require(archive.read(revision_files[0]).decode("ascii").strip() == revision, "Retained source revision differs")
        source_bytes = archive.read(archives[0])
        with tarfile.open(fileobj=io.BytesIO(source_bytes), mode="r:gz") as git_source:
            require(git_source.pax_headers.get("comment") == revision, "Git source archive does not record this exact revision")
            require(any(item.name == "app/build.gradle.kts" and item.isfile() for item in git_source), "Retained git source archive lacks the app build input")
        source_record["gitArchiveSha256"] = digest_bytes(source_bytes)
    member = value.get("buildArtifact", {}).get("packageEntry")
    require(isinstance(member, str) and Path(member).suffix.lower() == artifact.suffix.lower(), "Retained build package type mismatch")
    with zipfile.ZipFile(build) as archive, tempfile.TemporaryDirectory(prefix="artflow-ci-package-") as directory:
        require(member in archive.namelist(), "Retained CI archive does not contain the specified package")
        candidate = Path(directory) / ("ci-release" + artifact.suffix.lower())
        with archive.open(member) as stream, candidate.open("wb") as output:
            shutil.copyfileobj(stream, output)
        ci_payload, publisher_payload = archive_entries(candidate), archive_entries(artifact)
        require(ci_payload == publisher_payload, "Publisher package payload differs from retained exact-revision CI package")
        build_record["packageEntry"] = member
        build_record["packageSha256"] = file_record(candidate)["sha256"]
    return {"record": file_record(path), "runMetadata": file_record(run_path), "runId": run_id,
            "runAttempt": attempt, "runUrl": f"{run_url}/attempts/{attempt}", "sourceArtifact": source_record,
            "buildArtifact": build_record, "payloadMatchesRetainedCi": True, "sourceToBinaryReproduced": False,
            "trustBoundary": "Retained provider metadata supplied by the release owner; no online authentication or reproducible-build attestation"}


def qualify(options: argparse.Namespace) -> dict:
    output = options.output_dir
    require(not output.is_symlink(), "A linked output directory is not accepted")
    output.mkdir(parents=True, exist_ok=True)
    require(not output.is_symlink(), "A linked output directory is not accepted")
    require(not any(output.iterdir()), "Use an empty output directory; prior qualification evidence must not be overwritten")
    # Empty-directory inspection alone does not prevent two concurrent attempts.
    # Retain this exclusive claim even when an attempt fails or is interrupted.
    with (output / "qualification-attempt.json").open("x", encoding="utf-8") as stream:
        json.dump({"schemaVersion": 1, "documentType": "publisher_artifact_attempt",
                   "revision": options.revision, "startedAt": datetime.now(timezone.utc).isoformat()},
                  stream, indent=2, allow_nan=False)
        stream.write("\n")
    report = {"schemaVersion": 1, "documentType": "publisher_artifact_qualification", "revision": options.revision,
              "artifact": None,
              "qualified": False, "publisherArtifactQualified": False, "publicReleaseQualified": False,
              "sourceToBinaryReproduced": False, "checks": {}, "commands": [], "problems": [],
              "remainingGates": ["Exact-final-revision two complete green matrices and three API35/16 KB executions",
                                 "Physical-device/stylus/performance and package-upgrade evidence", "Publisher/store/prelaunch/rollout sign-off"]}
    tools = Tools(output, report["commands"])

    def check(name, operation):
        try:
            report["checks"][name] = {"passed": True, "evidence": operation()}
        except (OSError, ValueError, TypeError, KeyError, AttributeError, ET.ParseError,
                zipfile.BadZipFile, tarfile.TarError, struct.error, RuntimeError, zlib.error) as error:
            report["checks"][name] = {"passed": False, "error": str(error)}
            report["problems"].append(f"{name}: {error}")

    expected = {"packageName": options.expected_package, "versionCode": options.expected_version_code,
                "versionName": options.expected_version_name, "minSdk": options.expected_min_sdk,
                "targetSdk": options.expected_target_sdk}
    report["expected"] = expected

    def inputs():
        require(bool(re.fullmatch(r"[0-9a-f]{40}", options.revision or "")), "An exact 40-character final source revision is required")
        require(expected["packageName"] == PACKAGE, "Explicit production package identity is required")
        require(type(expected["versionCode"]) is int and expected["versionCode"] > 0
                and isinstance(expected["versionName"], str) and bool(expected["versionName"].strip()), "Explicit expected version code/name are required")
        require(expected["minSdk"] == MIN_SDK and expected["targetSdk"] == TARGET_SDK,
                f"Explicit supported SDK policy must be min {MIN_SDK}, target {TARGET_SDK}")
        fingerprint(options.approved_cert_sha256)
        require(options.artifact is not None and options.artifact.suffix.lower() in (".apk", ".aab"), "An actual APK or AAB is required")
        return file_record(options.artifact)

    check("inputs", inputs)
    approved = None
    try:
        approved = fingerprint(options.approved_cert_sha256)
    except ValueError:
        pass
    check("approval", lambda: verify_approval(options.approval_record, approved, options.certificate_role, expected["packageName"]))
    if options.artifact is not None:
        artifact = options.artifact.resolve()
        # Hash before and after all external tools to reject changing input bytes.
        check("artifactBefore", lambda: file_record(options.artifact))
        report["artifact"] = report["checks"]["artifactBefore"].get("evidence")
        check("package", lambda: verify_package(artifact, expected, tools, options))
        check("signature", lambda: verify_signature(artifact, approved, options.certificate_role, tools, options))
        check("provenance", lambda: verify_provenance(options.provenance, artifact, options.revision))
        check("artifactAfter", lambda: file_record(options.artifact))
        if report["checks"]["artifactBefore"] != report["checks"]["artifactAfter"]:
            report["problems"].append("Artifact bytes changed during qualification")
    else:
        report["problems"].append("No package, signature, or retained provenance could be checked without an artifact")
    report["qualified"] = report["publisherArtifactQualified"] = not report["problems"]
    with (output / "verification.json").open("x", encoding="utf-8") as stream:
        stream.write(json.dumps(report, indent=2, allow_nan=False) + "\n")
    return report


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("artifact", nargs="?", type=Path)
    result.add_argument("--output-dir", type=Path, required=True)
    result.add_argument("--revision")
    result.add_argument("--expected-package")
    result.add_argument("--expected-version-code", type=int)
    result.add_argument("--expected-version-name")
    result.add_argument("--expected-min-sdk", type=int)
    result.add_argument("--expected-target-sdk", type=int)
    result.add_argument("--approved-cert-sha256")
    result.add_argument("--certificate-role", choices=("app-signing", "upload"))
    result.add_argument("--approval-record", type=Path)
    result.add_argument("--provenance", type=Path)
    result.add_argument("--apksigner", default="apksigner")
    result.add_argument("--apkanalyzer", default="apkanalyzer")
    result.add_argument("--bundletool", type=Path)
    result.add_argument("--java", default="java")
    result.add_argument("--jarsigner", default="jarsigner")
    result.add_argument("--keytool", default="keytool")
    return result


def main() -> int:
    options = parser().parse_args()
    try:
        report = qualify(options)
    except (OSError, ValueError) as error:
        print(f"UNQUALIFIED: {error}")
        return 1
    print("PUBLISHER ARTIFACT QUALIFIED" if report["qualified"] else "UNQUALIFIED")
    for problem in report["problems"]:
        print("  " + problem)
    print(f"Report: {options.output_dir / 'verification.json'}")
    print("Overall production release approval remains separate.")
    return 0 if report["qualified"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
