"""Adversarial publisher gate fixtures; generated fixture keys never leave temporary dirs."""
from __future__ import annotations

import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest
import warnings
import zipfile

from qualify_publisher_artifact import (
    PACKAGE, REPOSITORY, Tools, archive_entries, file_record, load_object, parser, qualify,
    validate_manifest, verify_approval, verify_package, verify_provenance, verify_signature,
)


REVISION = "a" * 40
EXPECTED = {"packageName": PACKAGE, "versionCode": 3, "versionName": "1.1.0", "minSdk": 26, "targetSdk": 36}
MANIFEST = f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{PACKAGE}"
 android:versionCode="3" android:versionName="1.1.0"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>
 <application android:label="Publisher fixture" android:debuggable="false"/></manifest>'''


def write_json(path, value):
    path.write_text(json.dumps(value), encoding="utf-8")


def write_zip(path, entries):
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, value in entries.items():
            archive.writestr(name, value)


def zip_entries(path):
    with zipfile.ZipFile(path) as archive:
        return {name: archive.read(name) for name in archive.namelist()}


def approval(root, certificate, role="upload"):
    path = root / "approval.json"
    write_json(path, {"schemaVersion": 1, "approvedForPublisherRelease": True, "certificateSha256": certificate,
                      "certificateRole": role, "packageName": PACKAGE, "approvedBy": "Synthetic fixture owner",
                      "approvedAt": "2020-01-01T00:00:00Z", "certificateSource": "Generated fixture certificate only",
                      "approvalReference": "Synthetic unit test; not publisher approval"})
    return path


def provenance(root, artifact):
    """Synthetic retained-provider metadata, explicitly never real CI evidence."""
    run = {"id": 7, "run_attempt": 1, "head_sha": REVISION, "repository": {"full_name": REPOSITORY},
           "status": "completed", "conclusion": "success", "html_url": f"https://github.com/{REPOSITORY}/actions/runs/7",
           "run_started_at": "2020-01-01T00:00:00Z", "updated_at": "2020-01-01T01:00:00Z"}
    write_json(root / "run.json", run)
    source = io.BytesIO()
    with tarfile.open(fileobj=source, mode="w:gz", format=tarfile.PAX_FORMAT, pax_headers={"comment": REVISION}) as archive:
        data = b"// Synthetic source fixture; not an executed build\n"
        entry = tarfile.TarInfo("app/build.gradle.kts")
        entry.size = len(data)
        archive.addfile(entry, io.BytesIO(data))
    write_zip(root / "source.zip", {"REVISION.txt": REVISION + "\n", "artflow-source.tar.gz": source.getvalue()})
    write_zip(root / "build.zip", {"app-release" + artifact.suffix: artifact.read_bytes()})
    for index, name in enumerate(("source", "build"), 1):
        measured = file_record(root / (name + ".zip"))
        write_json(root / (name + "-metadata.json"), {"id": index, "workflow_run": {"id": 7, "head_sha": REVISION},
                   "size_in_bytes": measured["sizeBytes"], "digest": "sha256:" + measured["sha256"],
                   "created_at": "2020-01-01T00:30:00Z"})
    value = {"schemaVersion": 1, "buildType": "release", "revision": REVISION, "runAttempt": 1, "runMetadata": "run.json",
             "sourceArtifact": {"archive": "source.zip", "metadata": "source-metadata.json"},
             "buildArtifact": {"archive": "build.zip", "metadata": "build-metadata.json", "packageEntry": "app-release" + artifact.suffix}}
    path = root / "provenance.json"
    write_json(path, value)
    return path


class ManifestAndInputTests(unittest.TestCase):
    def test_packaged_identity_and_sdk(self):
        self.assertEqual("1.1.0", validate_manifest(MANIFEST, EXPECTED)["versionName"])
        for old, new in ((PACKAGE, PACKAGE + ".test"), ('versionCode="3"', 'versionCode="4"'),
                         ('versionName="1.1.0"', 'versionName="debug"'), ('minSdkVersion="26"', 'minSdkVersion="27"'),
                         ('targetSdkVersion="36"', 'targetSdkVersion="35"')):
            with self.subTest(new=new), self.assertRaises(ValueError):
                validate_manifest(MANIFEST.replace(old, new), EXPECTED)

    def test_rejects_debug_testonly_runner_host_and_unresolved_flags(self):
        variants = [MANIFEST.replace('debuggable="false"', 'debuggable="true"'),
                    MANIFEST.replace('debuggable="false"', 'debuggable="@bool/unknown"'),
                    MANIFEST.replace('debuggable="false"', 'testOnly="true"'),
                    MANIFEST.replace('</manifest>', '<instrumentation android:name="runner"/></manifest>')]
        for host in (".SelectionCanvasTestActivity", "com.artflow.studio.HiltTestRunner",
                     "dagger.hilt.android.testing.HiltTestApplication", "androidx.activity.ComponentActivity"):
            variants.append(MANIFEST.replace('/></manifest>', f'><activity android:name="{host}"/></application></manifest>'))
        for xml in variants:
            with self.subTest(xml=xml), self.assertRaises(ValueError):
                validate_manifest(xml, EXPECTED)

    def test_rejects_entities_missing_sdk_and_version_major(self):
        variants = ["<!DOCTYPE manifest [<!ENTITY x 'fixture'>]>" + MANIFEST,
                    MANIFEST.replace(' android:minSdkVersion="26"', ''),
                    MANIFEST.replace(' android:targetSdkVersion="36"', ''),
                    MANIFEST.replace('android:versionCode="3"', 'android:versionCode="3" android:versionCodeMajor="1"'),
                    MANIFEST.replace('android:minSdkVersion="26"', 'android:minSdkVersion="26" android:maxSdkVersion="36"')]
        for xml in variants:
            with self.subTest(xml=xml), self.assertRaises(ValueError):
                validate_manifest(xml, EXPECTED)

    def test_missing_facts_leave_a_retained_unqualified_report(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = qualify(parser().parse_args(["--output-dir", str(root / "result")]))
            self.assertFalse(report["qualified"])
            self.assertFalse(report["publicReleaseQualified"])
            self.assertFalse(report["sourceToBinaryReproduced"])
            self.assertTrue((root / "result/verification.json").is_file())
            self.assertGreaterEqual(len(report["problems"]), 3)

    def test_duplicate_json_keys_are_not_silently_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "approval.json"
            path.write_text('{"approved":false,"approved":true}')
            with self.assertRaisesRegex(ValueError, "Duplicate JSON"):
                load_object(path)

    def test_no_old_report_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / "verification.json").write_text("retained evidence")
            with self.assertRaisesRegex(ValueError, "empty output directory"):
                qualify(parser().parse_args(["--output-dir", str(path)]))
            self.assertEqual("retained evidence", (path / "verification.json").read_text())

    def test_missing_and_mismatched_approval(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(ValueError):
                verify_approval(None, "b" * 64, "upload", PACKAGE)
            path = approval(root, "b" * 64)
            self.assertEqual("upload", verify_approval(path, "b" * 64, "upload", PACKAGE)["approval"]["certificateRole"])
            with self.assertRaises(ValueError):
                verify_approval(path, "c" * 64, "upload", PACKAGE)
            with self.assertRaises(ValueError):
                verify_approval(path, "b" * 64, "app-signing", PACKAGE)

    def test_test_runner_code_is_rejected_even_without_manifest_component(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "candidate.apk"
            write_zip(path, {"AndroidManifest.xml": b"fixture", "classes10.dex": b"dex\n035\0Lcom/artflow/studio/HiltTestRunner;"})
            with self.assertRaisesRegex(ValueError, "Test host/runner code"):
                verify_package(path, EXPECTED, None, parser().parse_args(["--output-dir", directory]))

    def test_all_bundle_module_manifests_are_checked(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "candidate.aab"
            write_zip(artifact, {"base/manifest/AndroidManifest.xml": b"proto fixture", "base/dex/classes.dex": b"dex\nfixture",
                                 "feature/manifest/AndroidManifest.xml": b"proto fixture"})
            bundletool = root / "bundletool.jar"
            bundletool.write_bytes(b"Tool invocation is replaced only in this module-routing unit test")
            options = parser().parse_args(["--output-dir", directory, "--bundletool", str(bundletool)])

            class ManifestTools:
                def run(self, label, args):
                    if "--module=feature" in args:
                        return MANIFEST.replace('debuggable="false"', 'testOnly="true"')
                    return MANIFEST

            with self.assertRaisesRegex(ValueError, "testOnly"):
                verify_package(artifact, EXPECTED, ManifestTools(), options)


class ProvenanceTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.artifact = self.root / "release.aab"
        write_zip(self.artifact, {"base/manifest/AndroidManifest.xml": b"fixture", "base/dex/classes.dex": b"dex\nfixture",
                                  "META-INF/services/retained": b"must stay unchanged"})
        self.path = provenance(self.root, self.artifact)

    def edit_json(self, name, edit):
        path = self.root / name
        value = json.loads(path.read_text())
        edit(value)
        write_json(path, value)

    def test_exact_retained_payload_can_differ_only_by_signature_metadata(self):
        entries = zip_entries(self.artifact)
        entries["META-INF/PUBLISH.SF"] = b"synthetic signing metadata"
        entries["META-INF/PUBLISH.RSA"] = b"synthetic signing metadata"
        write_zip(self.artifact, entries)
        result = verify_provenance(self.path, self.artifact, REVISION)
        self.assertTrue(result["payloadMatchesRetainedCi"])
        self.assertFalse(result["sourceToBinaryReproduced"])

    def test_sha_string_alone_cannot_supply_provenance(self):
        with self.assertRaises(ValueError):
            verify_provenance(None, self.artifact, REVISION)
        (self.root / "source.zip").unlink()
        with self.assertRaises(ValueError):
            verify_provenance(self.path, self.artifact, REVISION)

    def test_changed_payload_including_non_signing_meta_inf_fails(self):
        for member in ("base/dex/classes.dex", "META-INF/services/retained"):
            original = zip_entries(self.artifact)
            changed = dict(original, **{member: b"changed"})
            write_zip(self.artifact, changed)
            with self.subTest(member=member), self.assertRaisesRegex(ValueError, "payload differs"):
                verify_provenance(self.path, self.artifact, REVISION)
            write_zip(self.artifact, original)

    def test_wrong_revision_run_attempt_or_provider_digest_fails(self):
        cases = [("run.json", "head_sha", "b" * 40), ("run.json", "run_attempt", 2),
                 ("run.json", "conclusion", "failure"), ("build-metadata.json", "digest", "sha256:" + "b" * 64),
                 ("build-metadata.json", "created_at", "2019-12-31T00:30:00Z"),
                 ("provenance.json", "buildType", "benchmark")]
        for name, key, value in cases:
            path = self.root / name
            original = path.read_bytes()
            self.edit_json(name, lambda document: document.update({key: value}))
            with self.subTest(name=name, key=key), self.assertRaises(ValueError):
                verify_provenance(self.path, self.artifact, REVISION)
            path.write_bytes(original)

    def test_source_archive_revision_is_checked_beyond_revision_txt(self):
        original = zip_entries(self.root / "source.zip")
        for mismatch in ("revision-file", "git-archive"):
            source = dict(original)
            if mismatch == "revision-file":
                source["REVISION.txt"] = "b" * 40
            else:
                changed = io.BytesIO()
                with tarfile.open(fileobj=changed, mode="w:gz", format=tarfile.PAX_FORMAT,
                                  pax_headers={"comment": "b" * 40}) as archive:
                    entry = tarfile.TarInfo("app/build.gradle.kts")
                    entry.size = 1
                    archive.addfile(entry, io.BytesIO(b"x"))
                source["artflow-source.tar.gz"] = changed.getvalue()
            write_zip(self.root / "source.zip", source)
            measured = file_record(self.root / "source.zip")
            self.edit_json("source-metadata.json", lambda document: document.update(
                {"digest": "sha256:" + measured["sha256"], "size_in_bytes": measured["sizeBytes"]}))
            with self.subTest(mismatch=mismatch), self.assertRaisesRegex(ValueError, "revision"):
                verify_provenance(self.path, self.artifact, REVISION)

    def test_unsafe_linked_and_duplicate_archive_paths_fail(self):
        for name in ("../secret", "/absolute", "path\nspoof"):
            write_zip(self.artifact, {name: b"bad"})
            with self.subTest(name=name), self.assertRaises(ValueError):
                archive_entries(self.artifact)
        with zipfile.ZipFile(self.artifact, "w") as archive:
            linked = zipfile.ZipInfo("linked-entry")
            linked.external_attr = 0o120777 << 16
            archive.writestr(linked, "unread-target")
        with self.assertRaisesRegex(ValueError, "linked"):
            archive_entries(self.artifact)
        with warnings.catch_warnings():
            warnings.filterwarnings("ignore", message="Duplicate name")
            with zipfile.ZipFile(self.artifact, "w") as archive:
                archive.writestr("duplicate", b"first")
                archive.writestr("duplicate", b"second")
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            archive_entries(self.artifact)
        (self.root / "run-linked.json").symlink_to(self.root / "run.json")
        self.edit_json("provenance.json", lambda document: document.update({"runMetadata": "run-linked.json"}))
        with self.assertRaisesRegex(ValueError, "Linked"):
            verify_provenance(self.path, self.artifact, REVISION)
        self.edit_json("provenance.json", lambda document: document.update({"runMetadata": "../outside.json"}))
        with self.assertRaisesRegex(ValueError, "inside the provenance directory"):
            verify_provenance(self.path, self.artifact, REVISION)


class OfficialSignatureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not (shutil.which("jarsigner") and shutil.which("keytool")):
            message = "JDK signing tools unavailable; real signature fixtures not executed"
            if os.environ.get("ARTFLOW_REQUIRE_PUBLISHER_TOOL_TESTS") == "1":
                raise AssertionError(message)
            raise unittest.SkipTest(message)
        cls.temporary = tempfile.TemporaryDirectory(prefix="artflow-generated-fixture-keys-")
        cls.root = Path(cls.temporary.name)
        cls.certificates = {}
        for name, subject, extra in (("publisher", "CN=Publisher Gate Synthetic Fixture", []),
                                     ("debug", "CN=Android Debug,O=Android,C=US", []),
                                     ("expired", "CN=Expired Synthetic Fixture", ["-startdate", "2020/01/01 00:00:00", "-validity", "1"])):
            cls.command("keytool", ["-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048",
                        "-dname", subject, "-keystore", str(cls.root / (name + ".p12")),
                        "-storepass", "synthetic-fixture-only", "-keypass", "synthetic-fixture-only", *extra])
            cls.command("keytool", ["-exportcert", "-alias", "fixture", "-keystore", str(cls.root / (name + ".p12")),
                        "-storepass", "synthetic-fixture-only", "-file", str(cls.root / (name + ".der"))])
            cls.certificates[name] = hashlib.sha256((cls.root / (name + ".der")).read_bytes()).hexdigest()
            artifact = cls.root / (name + ".aab")
            write_zip(artifact, {"base/manifest/AndroidManifest.xml": b"Synthetic signature fixture only",
                                 "base/dex/classes.dex": b"Synthetic signature fixture only"})
            cls.sign(artifact, name)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    @staticmethod
    def command(tool, args):
        result = subprocess.run([tool, *args], capture_output=True, text=True, timeout=40, check=False)
        if result.returncode:
            raise AssertionError(result.stdout + result.stderr)
        return result.stdout

    @classmethod
    def sign(cls, artifact, name):
        cls.command("jarsigner", ["-keystore", str(cls.root / (name + ".p12")), "-storepass", "synthetic-fixture-only",
                                 str(artifact), "fixture"])

    def setUp(self):
        self.working = tempfile.TemporaryDirectory()
        self.addCleanup(self.working.cleanup)
        self.path = Path(self.working.name)
        self.options = parser().parse_args(["--output-dir", str(self.path)])
        self.tools = Tools(self.path, [])

    def test_real_strict_jarsigner_accepts_approved_current_certificate(self):
        result = verify_signature(self.root / "publisher.aab", self.certificates["publisher"], "upload", self.tools, self.options)
        self.assertTrue(result["verified"])
        self.assertTrue(any("-strict" in command["command"] for command in self.tools.commands))

    def test_real_signer_fingerprint_mismatch_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "does not match"):
            verify_signature(self.root / "publisher.aab", "c" * 64, "upload", self.tools, self.options)

    def test_expired_and_debug_certificates_fail_even_when_caller_lists_them(self):
        for name, error in (("expired", "expired"), ("debug", "debug signing")):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, error):
                verify_signature(self.root / (name + ".aab"), self.certificates[name], "upload", self.tools, self.options)

    def test_unsigned_bundle_is_rejected(self):
        artifact = self.path / "unsigned.aab"
        write_zip(artifact, {"base/manifest/AndroidManifest.xml": b"unsigned"})
        with self.assertRaises(ValueError):
            verify_signature(artifact, self.certificates["publisher"], "upload", self.tools, self.options)

    def test_actual_digest_tampering_and_unsigned_entries_are_rejected(self):
        for name, value in (("base/manifest/AndroidManifest.xml", b"tampered"), ("base/assets/unsigned", b"unsigned"),
                            ("META-INF/services/unsigned", b"unsigned")):
            artifact = self.path / "tampered.aab"
            entries = zip_entries(self.root / "publisher.aab")
            entries[name] = value
            write_zip(artifact, entries)
            with self.subTest(name=name), self.assertRaises(ValueError):
                verify_signature(artifact, self.certificates["publisher"], "upload", self.tools, self.options)

    def test_aab_upload_certificate_cannot_be_labelled_app_signing(self):
        with self.assertRaisesRegex(ValueError, "approved upload certificate"):
            verify_signature(self.root / "publisher.aab", self.certificates["publisher"], "app-signing", self.tools, self.options)

    def test_real_apk_tools_and_complete_gate_on_synthetic_fixture(self):
        sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
        android_jar = Path(sdk or "/unavailable") / "platforms/android-36/android.jar"
        if not all(shutil.which(tool) for tool in ("apksigner", "apkanalyzer", "aapt2", "d8", "javac")) or not android_jar.is_file():
            message = "Android SDK tools/API36 unavailable; actual APK fixture not executed"
            if os.environ.get("ARTFLOW_REQUIRE_PUBLISHER_TOOL_TESTS") == "1":
                self.fail(message)
            self.skipTest(message)
        manifest = self.path / "AndroidManifest.xml"
        manifest.write_text(MANIFEST)
        unsigned = self.path / "unsigned.apk"
        self.command("aapt2", ["link", "--manifest", str(manifest), "-I", str(android_jar), "-o", str(unsigned)])
        java = self.path / "Fixture.java"
        java.write_text("package com.artflow.studio; public final class Fixture { public static int value() { return 1; } }")
        self.command("javac", ["--release", "8", "-d", str(self.path), str(java)])
        dex = self.path / "dex"
        dex.mkdir()
        self.command("d8", ["--min-api", "26", "--lib", str(android_jar), "--output", str(dex),
                            str(self.path / "com/artflow/studio/Fixture.class")])
        with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.write(dex / "classes.dex", "classes.dex")
        artifact = self.path / "publisher.apk"
        self.command("apksigner", ["sign", "--ks", str(self.root / "publisher.p12"), "--ks-key-alias", "fixture",
                                   "--ks-pass", "pass:synthetic-fixture-only", "--key-pass", "pass:synthetic-fixture-only",
                                   "--out", str(artifact), str(unsigned)])
        evidence = provenance(self.path, unsigned)
        # APK signing changes ZIP/signature bytes. Compare every unsigned CI payload entry.
        value = json.loads(evidence.read_text())
        value["buildArtifact"]["packageEntry"] = "app-release.apk"
        write_json(evidence, value)
        approved = approval(self.path, self.certificates["publisher"], "app-signing")
        options = parser().parse_args([str(artifact), "--output-dir", str(self.path / "result"), "--revision", REVISION,
                    "--expected-package", PACKAGE, "--expected-version-code", "3", "--expected-version-name", "1.1.0",
                    "--expected-min-sdk", "26", "--expected-target-sdk", "36", "--certificate-role", "app-signing",
                    "--approved-cert-sha256", self.certificates["publisher"], "--approval-record", str(approved), "--provenance", str(evidence)])
        report = qualify(options)
        self.assertTrue(report["publisherArtifactQualified"], report["problems"])
        self.assertFalse(report["publicReleaseQualified"])
        self.assertFalse(report["sourceToBinaryReproduced"])
        with self.assertRaisesRegex(ValueError, "does not match"):
            verify_signature(artifact, "c" * 64, "app-signing", self.tools, self.options)
        entries = zip_entries(artifact)
        entries["classes.dex"] += b"tampering"
        write_zip(artifact, entries)
        with self.assertRaises(ValueError):
            verify_signature(artifact, self.certificates["publisher"], "app-signing", self.tools, self.options)


if __name__ == "__main__":
    unittest.main()
