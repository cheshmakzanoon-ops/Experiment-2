#!/usr/bin/env python3
"""Host-side failure and evidence contracts; these never qualify an Android campaign."""
import copy
import io
import json
from pathlib import Path
import sqlite3
import subprocess
import tarfile
import tempfile
import unittest
from unittest import mock

import run_package_upgrade as upgrade


class ApkCompatibilityTests(unittest.TestCase):
    def setUp(self):
        app = {"packageName": upgrade.PACKAGE, "versionCode": 3, "debuggable": True,
               "signerSha256": ["a" * 64], "sha256": "b" * 64}
        self.apks = {"old": app, "candidate": {**app, "sha256": "c" * 64},
                     "tests": {**app, "packageName": upgrade.TEST_PACKAGE,
                               "runner": "com.artflow.studio.HiltTestRunner", "targetPackage": upgrade.PACKAGE}}

    def test_same_version_is_only_an_engineering_replacement(self):
        self.assertEqual("same-version-engineering-replacement", upgrade.validate_apks(self.apks))

    def test_increasing_code_is_still_a_test_update_not_publisher_qualification(self):
        self.apks["candidate"]["versionCode"] = 4
        self.assertEqual("increasing-version-test-update", upgrade.validate_apks(self.apks))

    def test_incompatible_inputs_reject_before_install(self):
        changes = [("candidate", "versionCode", 2), ("candidate", "signerSha256", ["d" * 64]),
                   ("tests", "signerSha256", ["d" * 64]), ("old", "debuggable", False),
                   ("candidate", "debuggable", False), ("candidate", "sha256", "b" * 64),
                   ("tests", "targetPackage", "another.app"), ("old", "packageName", "another.app")]
        for role, key, value in changes:
            with self.subTest(role=role, key=key):
                modified = copy.deepcopy(self.apks)
                modified[role][key] = value
                with self.assertRaises(ValueError):
                    upgrade.validate_apks(modified)

    def test_certificate_parser_does_not_accept_source_stamp_or_public_key_digest(self):
        valid = "Signer #1 certificate SHA-256 digest: " + "AB" * 32
        self.assertEqual(["ab" * 32], upgrade.parse_certificates(valid))
        for text in ("Signer #1 public key SHA-256 digest: " + "AB" * 32,
                     "Source Stamp Signer certificate SHA-256 digest: " + "AB" * 32,
                     valid + "\nSigner #2 certificate SHA-256 digest: " + "CD" * 32):
            with self.subTest(text=text), self.assertRaises(ValueError):
                upgrade.parse_certificates(text)

    def test_manifest_reports_debuggability_and_instrumentation_target(self):
        xml = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
           package="com.artflow.studio.test" android:versionCode="3" android:versionName="1.1.0">
           <uses-sdk android:minSdkVersion="26"/><application android:debuggable="true"/>
           <instrumentation android:name="com.artflow.studio.HiltTestRunner" android:targetPackage="com.artflow.studio"/>
           </manifest>'''
        value = upgrade.parse_apk_manifest(xml)
        self.assertEqual(26, value["minSdk"])
        self.assertTrue(value["debuggable"])
        self.assertEqual(upgrade.PACKAGE, value["targetPackage"])


class SnapshotTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.db = self.root / "artflow_database"
        with sqlite3.connect(self.db) as connection:
            connection.executescript('''PRAGMA user_version=2;
            CREATE TABLE projects(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, width INTEGER, height INTEGER, dpi INTEGER);
            CREATE TABLE brushes(id INTEGER PRIMARY KEY AUTOINCREMENT, value REAL, payload BLOB);
            CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT, category TEXT);
            INSERT INTO projects VALUES(5000000000, 'seed',64,64,72);
            INSERT INTO brushes(value,payload) VALUES(0.125,X'00ff');
            INSERT INTO settings VALUES('general.onboardingSeen','true','general');
            INSERT INTO settings VALUES('theme.mode','LIGHT','general');
            INSERT INTO settings VALUES('input.stylusOnly','false','general');
            INSERT INTO settings VALUES('color.palettes','[{"name":"Ocean"}]','color');
            INSERT INTO settings VALUES('brush.savedLibrary.v1','[{"name":"Ink"}]','brush');''')

    def archive(self, extra=()):
        path = self.root / ("snapshot-" + str(len(list(self.root.glob("*.tar")))) + ".tar")
        with tarfile.open(path, "w") as archive:
            archive.add(self.db, arcname="databases/artflow_database")
            for suffix in ("-wal", "-shm"):
                if Path(str(self.db) + suffix).is_file():
                    archive.add(str(self.db) + suffix, arcname="databases/artflow_database" + suffix)
            for index, name in enumerate(("canvas.artflow", "autosave.artflow", "canvas.png", "thumbnail.png")):
                data = name.encode()
                item = tarfile.TarInfo("files/projects/5000000000/" + name)
                item.size, item.mtime = len(data), 100 + index
                archive.addfile(item, io.BytesIO(data))
            for item, content in extra:
                archive.addfile(item, io.BytesIO(content))
        return path

    def test_stopped_snapshot_retains_long_identity_storage_classes_and_recovery_order(self):
        value = upgrade.read_snapshot(self.archive())
        self.assertEqual(5000000000, upgrade.validate_seed(value, "seed"))
        brush = value["database"]["tables"]["brushes"]["rows"][0]
        self.assertEqual({"type": "real", "value": "3fc0000000000000"}, brush[1])
        self.assertEqual({"type": "blob", "value": "00ff"}, brush[2])

    def test_uncheckpointed_wal_rows_are_read_from_the_actual_snapshot(self):
        connection = sqlite3.connect(self.db)
        self.addCleanup(connection.close)
        connection.execute("PRAGMA journal_mode=WAL")
        connection.execute("PRAGMA wal_autocheckpoint=0")
        connection.execute("INSERT INTO settings VALUES('only.in.wal','retained','general')")
        connection.commit()
        value = upgrade.read_snapshot(self.archive())
        settings = upgrade.rows(value["database"], "settings")
        self.assertTrue(any(row["key"] == "only.in.wal" and row["value"] == "retained" for row in settings))
        self.assertIn("databases/artflow_database-wal", value["files"])

    def test_traversal_links_and_duplicate_archive_paths_are_rejected(self):
        cases = []
        for name in ("../outside", "/tmp/escaped", "files/../../outside", "files\\outside"):
            item = tarfile.TarInfo(name)
            item.size = 1
            cases.append(item)
        linked = tarfile.TarInfo("files/link")
        linked.type, linked.linkname = tarfile.SYMTYPE, "/etc/passwd"
        cases.append(linked)
        duplicate = tarfile.TarInfo("files/projects/5000000000/canvas.png")
        duplicate.size = 1
        cases.append(duplicate)
        for item in cases:
            with self.subTest(name=item.name), self.assertRaises(ValueError):
                upgrade.read_snapshot(self.archive([(item, b"x")]))

    def test_missing_newer_recovery_or_real_library_cannot_qualify_seed(self):
        original = upgrade.read_snapshot(self.archive())
        missing = copy.deepcopy(original)
        del missing["files"]["files/projects/5000000000/autosave.artflow"]
        not_newer = copy.deepcopy(original)
        not_newer["files"]["files/projects/5000000000/autosave.artflow"]["modifiedSeconds"] = 100
        empty_library = copy.deepcopy(original)
        table = empty_library["database"]["tables"]["settings"]
        for row in table["rows"]:
            if row[0]["value"] == "brush.savedLibrary.v1":
                row[1]["value"] = "[]"
        wrong_version = copy.deepcopy(original)
        wrong_version["database"]["version"] = 1
        for value in (missing, not_newer, empty_library, wrong_version):
            with self.subTest(value=value), self.assertRaises(ValueError):
                upgrade.validate_seed(value, "seed")

    def test_byte_change_or_mtime_change_is_not_hidden_by_equal_sql_rows(self):
        before = upgrade.read_snapshot(self.archive())
        for key, value in (("sha256", "a" * 64), ("modifiedSeconds", 999)):
            after = copy.deepcopy(before)
            after["files"]["files/projects/5000000000/canvas.png"][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                upgrade.assert_same_data(before, after)

    def test_file_budget_is_enforced(self):
        with mock.patch.object(upgrade, "MAX_SNAPSHOT", 10), self.assertRaises(ValueError):
            upgrade.read_snapshot(self.archive())


class CampaignEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def test_bad_disposable_ack_never_runs_adb_and_preserves_first_failure(self):
        campaign = self.root / "campaign"
        args = ["prepare", "--campaign", str(campaign), "--serial", "emulator-5554", "--disposable-device", "wrong",
                "--allow-package", upgrade.PACKAGE, "--old-source", "a" * 40, "--candidate-source", "b" * 40,
                "--baseline-origin", "rebuilt-pinned-source"]
        for role in ("old", "candidate", "test"):
            args += [f"--{role}-apk", "not-present.apk", f"--{role}-sha256", "a" * 64]
        with mock.patch.object(upgrade, "Commands") as commands:
            self.assertEqual(1, upgrade.main(args))
            commands.assert_not_called()
        before = (campaign / "campaign.json").read_bytes()
        self.assertFalse(json.loads(before)["qualified"])
        with mock.patch.object(upgrade, "Commands") as commands:
            self.assertEqual(2, upgrade.main(args))
            self.assertEqual(2, upgrade.main(["freeze", "--campaign", str(campaign), "--serial", "emulator-5554",
                                            "--disposable-device", "emulator-5554", "--allow-package", upgrade.PACKAGE,
                                            "--seed-record", "not-present.json"]))
            commands.assert_not_called()
        self.assertEqual(before, (campaign / "campaign.json").read_bytes())

    def test_original_subprocess_timeout_and_bytes_are_retained(self):
        commands = upgrade.Commands(self.root / "commands", "emulator-5554", "adb")
        def fail(argv, **kwargs):
            kwargs["stdout"].write(b"first failure\n")
            kwargs["stderr"].write(b"timeout detail\n")
            raise subprocess.TimeoutExpired(argv, 30)
        with mock.patch.object(subprocess, "run", side_effect=fail), self.assertRaises(ValueError):
            commands.shell("bounded", "am", "instrument", "-w", upgrade.RUNNER)
        self.assertEqual("first failure\n", (commands.root / "001-bounded.txt").read_text())
        record = json.loads((commands.root / "001-bounded.json").read_text())
        self.assertTrue(record["timedOut"])
        self.assertEqual(["adb", "-s", "emulator-5554", "shell"], record["argv"][:4])

    def test_install_replace_uses_r_without_downgrade_clear_or_uninstall(self):
        path = self.root / "candidate.apk"
        path.write_bytes(b"pinned candidate")
        result = self.root / "result.txt"
        result.write_text("Performing Streamed Install\nSuccess\n")
        commands = mock.Mock()
        commands.adb_command.return_value = result
        upgrade.install(commands, {"path": str(path), "sha256": upgrade.sha256(path)}, "replace", "-r")
        commands.adb_command.assert_called_once_with("replace", "install", "-r", str(path), timeout=120)

    def test_mutated_apk_is_refused_without_device_mutation(self):
        path = self.root / "candidate.apk"
        path.write_bytes(b"different input")
        commands = mock.Mock()
        with self.assertRaises(ValueError):
            upgrade.install(commands, {"path": str(path), "sha256": "a" * 64}, "replace", "-r")
        commands.adb_command.assert_not_called()

    def test_seed_change_stops_before_candidate_install(self):
        commands = mock.Mock()
        campaign = {"phase": "seed-frozen", "apks": {"old": {}, "candidate": {}, "tests": {}}, "seed": {"files": {}, "database": {}}}
        with mock.patch.object(upgrade, "validate_apks"), mock.patch.object(upgrade, "installed_apk"), \
                mock.patch.object(upgrade, "require_absent"), mock.patch.object(upgrade, "snapshot", return_value={"files": {"changed": {}}, "database": {}}), \
                mock.patch.object(upgrade, "install") as install, self.assertRaises(ValueError):
            upgrade.upgrade(mock.Mock(), campaign, commands)
        install.assert_not_called()

    def test_success_requires_nonce_artifact_phase_and_every_assertion(self):
        campaign = {"token": "49ab616e-0ce0-4e1e-9e72-67fd6658e97d", "projectId": 5000000000,
                    "apks": {"old": {"sha256": "a" * 64}, "candidate": {"sha256": "b" * 64}}}
        report = {"schemaVersion": 1, "campaignToken": campaign["token"], "phase": "reopen", "baselineSha256": "c" * 64,
                  "candidateApkSha256": "b" * 64, "oldApkSha256": "a" * 64, "projectId": 5000000000,
                  "packageName": upgrade.PACKAGE, "success": True, "pid": 123,
                  "assertions": {key: True for key in ("freshProcess", "editedPixelsReopened", "settingsPreserved", "otherProjectsPreserved", "recoveryResolved")}}
        upgrade.verify_probe_report(report, campaign, "reopen", "c" * 64)
        for key, value in (("campaignToken", "old-token"), ("candidateApkSha256", "a" * 64), ("phase", "edit"),
                           ("baselineSha256", "d" * 64), ("success", False), ("success", 1), ("schemaVersion", True),
                           ("assertions", {}), ("assertions", {key: 1 for key in report["assertions"]}), ("pid", 0), ("pid", True)):
            with self.subTest(key=key), self.assertRaises(ValueError):
                upgrade.verify_probe_report({**report, key: value}, campaign, "reopen", "c" * 64)


if __name__ == "__main__":
    unittest.main()
