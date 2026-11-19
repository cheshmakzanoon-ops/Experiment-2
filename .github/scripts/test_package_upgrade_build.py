"""Input/output provenance rejection tests; these fixtures do not qualify an Android upgrade."""
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest import mock

import package_upgrade_build as build
import run_installed_upgrade_ci as campaign


class BuildRecordTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        roots = {name: self.root / name for name in ("baseline", "candidate")}
        schema = (Path(build.__file__).resolve().parents[2] / build.SCHEMA).read_bytes()
        for name, root in roots.items():
            root.mkdir()
            subprocess.run(["git", "init", "-q", "--object-format=sha1", str(root)], check=True)
            inputs = {build.SCHEMA: schema, ".gitignore": b"app/build/\n",
                      "gradle/wrapper/gradle-wrapper.jar": b"host fixture only",
                      "gradle/wrapper/gradle-wrapper.properties": b"host fixture only\n"}
            if name == "candidate":
                inputs[build.DRIVER] = b"test-only host fixture driver\n"
            for relative, data in inputs.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
            subprocess.run(["git", "-C", str(root), "add", "."], check=True)
            subprocess.run(["git", "-C", str(root), "-c", "user.name=Upgrade host fixture",
                            "-c", "user.email=fixture@example.invalid", "commit", "-qm", "Host fixture only"], check=True)
        old = build.git(roots["baseline"], "rev-parse", "HEAD")
        patch = mock.patch.object(build, "OLD_SOURCE", old)
        patch.start()
        self.addCleanup(patch.stop)
        self.output = self.root / "evidence"
        build.prepare(roots["candidate"], roots["baseline"], self.output)
        for role, (name, relative) in build.APK_PATHS.items():
            path = roots[name] / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(("host APK fixture: " + role).encode())
        self.value = build.finish(self.output)

    def test_complete_measured_record_is_validated(self):
        build.validate(self.value)
        self.assertEqual(hashlib.sha256(schema_bytes(self.value)).hexdigest(), build.SCHEMA_SHA)

    def test_malformed_or_changed_candidate_commit_and_tree_are_rejected(self):
        for key, changed in (("candidateSource", "not-a-revision"), ("candidateSource", "b" * 40),
                             ("candidateTree", "not-a-tree"), ("candidateTree", "b" * 40), ("oldTree", "b" * 40)):
            with self.subTest(key=key, changed=changed), self.assertRaises(ValueError):
                build.validate({**self.value, key: changed})

    def test_rehashed_substitute_source_archive_is_rejected(self):
        for role in ("old", "candidate"):
            value = copy.deepcopy(self.value)
            path = self.root / (role + "-substitute.tar.gz")
            path.write_bytes(b"arbitrary bytes with a fresh receipt")
            value["sources"][role] = build.record(path)
            with self.subTest(role=role), self.assertRaises((ValueError, tarfile.TarError)):
                build.validate(value)

    def test_rehashed_source_content_or_schema_cannot_reuse_the_commit_identity(self):
        import io
        source = Path(self.value["sources"]["candidate"]["path"])
        for changed_name in (build.DRIVER, build.SCHEMA):
            target = self.root / ("changed-" + Path(changed_name).name + ".tar.gz")
            with tarfile.open(source, "r:gz") as original, tarfile.open(target, "w:gz", format=tarfile.PAX_FORMAT,
                    pax_headers={"comment": self.value["candidateSource"]}) as changed:
                for member in original:
                    data = original.extractfile(member).read() if member.isfile() else None
                    if member.name == changed_name:
                        data = b"changed but freshly hashed source\n"
                        member.size = len(data)
                    changed.addfile(member, None if data is None else io.BytesIO(data))
            value = copy.deepcopy(self.value)
            value["sources"]["candidate"] = build.record(target)
            with self.subTest(changed_name=changed_name), self.assertRaises(ValueError):
                build.validate(value)

    def test_rehashed_commit_and_uncommitted_driver_are_rejected(self):
        for role, key in (("candidate", "commitObjects"), ("old", "commitObjects"), (None, "driver")):
            value = copy.deepcopy(self.value)
            path = self.root / (str(role) + "-substitute")
            path.write_bytes(b"substituted content with an updated file receipt")
            if role is None:
                value[key] = build.record(path)
            else:
                value.setdefault(key, {})[role] = build.record(path)
            with self.subTest(role=role, key=key), self.assertRaises(ValueError):
                build.validate(value)

    def test_first_build_report_and_links_are_never_overwritten(self):
        report = self.root / "report.json"
        report.write_text("first outcome")
        outside = self.root / "outside.json"
        outside.write_text("outside")
        link = self.root / "linked.json"
        link.symlink_to(outside)
        for path in (report, link):
            with self.subTest(path=path), self.assertRaises((ValueError, FileExistsError)):
                build.write(path, {"changed": True})
        self.assertEqual("first outcome", report.read_text())
        self.assertEqual("outside", outside.read_text())
        before = (self.output / "build-provenance.json").read_bytes()
        with self.assertRaises(FileExistsError):
            build.finish(self.output)
        self.assertEqual(before, (self.output / "build-provenance.json").read_bytes())

    def test_nonfinite_json_evidence_is_rejected(self):
        path = self.root / "nonfinite.json"
        for number in ("NaN", "Infinity", "-Infinity", "1e9999"):
            path.write_text(json.dumps(self.value)[:-1] + ',"value":' + number + '}')
            with self.subTest(number=number), mock.patch("sys.argv", ["run", "--build-record", str(path), "--serial", "emulator-5554"]), \
                    mock.patch.object(campaign.run_package_upgrade, "main", return_value=1) as phase, self.assertRaises(ValueError):
                campaign.main()
            phase.assert_not_called()

    def test_source_identity_production_overlay_or_publisher_claim_is_rejected(self):
        for key, value in (("schemaVersion", True), ("oldSource", "b" * 40), ("schemaSha256", "b" * 64), ("baselineProductionModified", True),
                           ("publisherQualified", True), ("allowedOldTestOverlay", "app/src/main/Injected.kt"),
                           ("baselineOrigin", "archived-apk"), ("phase", "prepared")):
            with self.subTest(key=key), self.assertRaises(ValueError):
                build.validate({**self.value, key: value})

    def test_missing_apks_or_source_archives_are_rejected(self):
        for kind, name in (("apks", "seed"), ("apks", "candidate"), ("sources", "old")):
            changed = copy.deepcopy(self.value)
            del changed[kind][name]
            with self.subTest(kind=kind, name=name), self.assertRaises(ValueError):
                build.validate(changed)

    def test_changed_bytes_are_rejected_even_at_same_length(self):
        for item in [*self.value["apks"].values(), *self.value["sources"].values(), self.value["driver"]]:
            path = Path(item["path"])
            previous = path.read_bytes()
            path.write_bytes(b"x" * len(previous))
            with self.subTest(path=path), self.assertRaises(ValueError):
                build.validate(self.value)
            path.write_bytes(previous)

    def test_linked_build_input_is_rejected(self):
        path = Path(self.value["driver"]["path"])
        saved = path.with_suffix(".original")
        path.rename(saved)
        path.symlink_to(saved)
        with self.assertRaises(ValueError):
            build.validate(self.value)

    def test_only_the_specific_untracked_test_overlay_is_admitted(self):
        for result in ((build.OLD_SOURCE, "app/src/main/Changed.kt", build.OVERLAY),
                       (build.OLD_SOURCE, "", build.OVERLAY + "\napp/src/main/Injected.kt"),
                       ("b" * 40, "", build.OVERLAY)):
            with self.subTest(result=result), mock.patch.object(build, "git", side_effect=result), self.assertRaises(ValueError):
                build.require_source(self.root, build.OLD_SOURCE, [build.OVERLAY])
        with mock.patch.object(build, "git", side_effect=(build.OLD_SOURCE, "", build.OVERLAY)):
            build.require_source(self.root, build.OLD_SOURCE, [build.OVERLAY])

    def test_ci_stops_after_first_failed_phase_without_retry(self):
        path = self.root / "build-provenance.json"
        path.write_text(json.dumps(self.value))
        with mock.patch("sys.argv", ["run", "--build-record", str(path), "--serial", "emulator-5554"]), \
                mock.patch.object(campaign.run_package_upgrade, "main", side_effect=[0, 1]) as run:
            self.assertEqual(1, campaign.main())
        self.assertEqual(["prepare", "seed"], [call.args[0][0] for call in run.call_args_list])
        initial = run.call_args_list[0].args[0]
        self.assertIn("--build-record-sha256", initial)

    def test_ci_rejects_contradictory_terminal_campaign(self):
        path = self.root / "build-provenance.json"
        path.write_text(json.dumps(self.value))
        destination = self.root / "campaign"
        destination.mkdir()
        result = {"phase": "passed-scoped-package-campaign", "qualified": True, "installedV1MigrationQualified": True,
                  "replacementScope": "increasing-version-test-update", "oldSource": self.value["oldSource"],
                  "candidateSource": self.value["candidateSource"], "publisherQualified": False,
                  "storeReleaseQualified": False, "automatedOldAppSeed": {}}
        for key, value in (("phase", "failed-upgrade"), ("qualified", False), ("publisherQualified", True),
                           ("candidateSource", "b" * 40), ("firstFailure", {}), ("manualOldAppSeed", {})):
            (destination / "campaign.json").write_text(json.dumps({**result, key: value}))
            with self.subTest(key=key), mock.patch("sys.argv", ["run", "--build-record", str(path), "--serial", "emulator-5554"]), \
                    mock.patch.object(campaign.run_package_upgrade, "main", return_value=0), self.assertRaises(ValueError):
                campaign.main()


def schema_bytes(value):
    with tarfile.open(value["sources"]["old"]["path"], "r:gz") as archive:
        return archive.extractfile(build.SCHEMA).read()


if __name__ == "__main__":
    unittest.main()
