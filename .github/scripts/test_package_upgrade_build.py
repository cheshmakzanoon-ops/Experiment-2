"""Input/output provenance rejection tests; these fixtures do not qualify an Android upgrade."""
import copy
import json
from pathlib import Path
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
        self.value = {"schemaVersion": 1, "phase": "built-with-test-only-baseline-overlay", "oldSource": build.OLD_SOURCE,
                      "candidateSource": "a" * 40, "schemaSha256": build.SCHEMA_SHA,
                      "baselineProductionModified": False, "publisherQualified": False,
                      "allowedOldTestOverlay": build.OVERLAY, "baselineOrigin": "rebuilt-pinned-source",
                      "apks": {}, "sources": {}}
        for kind, names in (("apks", build.APK_PATHS), ("sources", ("old", "candidate"))):
            for name in names:
                path = self.root / (kind + "-" + name)
                path.write_bytes((kind + " " + name).encode())
                self.value[kind][name] = build.record(path)
        driver = self.root / "driver.kt"
        driver.write_text("test-only driver")
        self.value["driver"] = build.record(driver)

    def test_complete_measured_record_is_validated(self):
        build.validate(self.value)

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


if __name__ == "__main__":
    unittest.main()
