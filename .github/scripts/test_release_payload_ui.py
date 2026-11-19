import hashlib
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest import mock
import zipfile
import zlib

import release_payload_ui as qualification


def digest(value):
    return hashlib.sha256(value).hexdigest()


def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)


def png(raw, *, rgba=True, filters=False):
    channels = 4 if rgba else 3
    previous, encoded = bytes(64 * channels), bytearray()
    for y in range(64):
        row = bytearray()
        for x in range(64):
            alpha, red, green, blue = raw[(y * 64 + x) * 4:(y * 64 + x + 1) * 4]
            row.extend((red, green, blue, alpha) if rgba else (red, green, blue))
        method = y % 5 if filters else 0
        encoded.append(method)
        for index, byte in enumerate(row):
            left = row[index - channels] if index >= channels else 0
            above = previous[index]
            corner = previous[index - channels] if index >= channels else 0
            base = left + above - corner
            paeth = min((left, above, corner), key=lambda value: abs(base - value))
            prediction = (0, left, above, (left + above) // 2, paeth)[method]
            encoded.append((byte - prediction) % 256)
        previous = row
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 64, 64, 8, 6 if rgba else 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(encoded)) + chunk(b"IEND", b""))


def test_log():
    def status(code):
        return (f"INSTRUMENTATION_STATUS: class={qualification.TEST_CLASS}\n"
                f"INSTRUMENTATION_STATUS: test={qualification.TEST_METHOD}\n"
                "INSTRUMENTATION_STATUS: current=1\nINSTRUMENTATION_STATUS: numtests=1\n"
                f"INSTRUMENTATION_STATUS_CODE: {code}\n")
    return status(1) + status(0) + "INSTRUMENTATION_RESULT: stream=\n\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n"


class ReleasePayloadUiTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def write_json(self, name, data):
        path = self.root / name
        path.write_text(json.dumps(data))
        return path

    def fixture(self):
        attempt = {"token": "12345678-1234-1234-1234-123456789abc", "revision": "1" * 40,
                   "appApkSha256": "a" * 64, "driverApkSha256": "b" * 64}
        raw = b"\xff\x00\x33\x55" * 32 + b"\xff\xff\xff\xff" * (4096 - 32)
        image = png(raw)
        (self.root / "started.txt").write_text(attempt["token"])
        self.write_json("workflow.json", {
            "schemaVersion": 1, **attempt, "success": True, "publisherQualified": False,
            "applicationPackage": qualification.APP, "driverPackage": qualification.DRIVER,
            "driverPid": 10, "firstAppPid": 20, "reopenedAppPid": 30,
            "assertions": {name: True for name in qualification.CHECKS},
        })
        for phase in ("saved", "reopened"):
            (self.root / (phase + ".png")).write_bytes(image)
            (self.root / (phase + "-pixels.argb")).write_bytes(raw)
            self.write_json(phase + "-receipt.json", {
                "schemaVersion": 1, "token": attempt["token"], "phase": phase, "width": 64, "height": 64,
                "readGrant": True, "uri": "content://" + qualification.APP + ".fileprovider/export/" + phase + ".png",
                "pngSha256": digest(image), "pixelSha256": digest(raw), "inkPixels": 32,
            })
        for name in ("blank", "painted", "reopened-export"):
            (self.root / (name + ".png")).write_bytes(image)
            (self.root / (name + "-hierarchy.xml")).write_text('<hierarchy><node package="com.artflow.studio" /></hierarchy>')
        return attempt, raw

    def test_signature_metadata_only_can_change_payload_comparison(self):
        unsigned, signed = self.root / "unsigned.apk", self.root / "signed.apk"
        with zipfile.ZipFile(unsigned, "w") as archive:
            archive.writestr("classes.dex", b"dex\npayload")
            archive.writestr("assets/model.SF", b"real asset")
        with zipfile.ZipFile(signed, "w") as archive:
            archive.writestr("classes.dex", b"dex\npayload")
            archive.writestr("assets/model.SF", b"real asset")
            archive.writestr("META-INF/CERT.SF", b"signature")
            archive.writestr("META-INF/CERT.RSA", b"certificate")
        self.assertEqual(qualification.require_payload_identity(unsigned, signed)["entryCount"], 2)
        with zipfile.ZipFile(signed, "w") as archive:
            archive.writestr("classes.dex", b"dex\npayload")
            archive.writestr("assets/model.SF", b"changed real asset")
        with self.assertRaisesRegex(ValueError, "payload entry"):
            qualification.require_payload_identity(unsigned, signed)

    def test_app_cannot_accidentally_package_driver_classes(self):
        path = self.root / "app.apk"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("classes2.dex", b"dex\nLcom/artflow/qualification/VisibleUi;")
        with self.assertRaisesRegex(ValueError, "cross-package"):
            qualification.reject_embedded_package(path, qualification.DRIVER)

    def test_driver_must_target_itself_and_have_its_own_receiver(self):
        manifest = ('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.artflow.qualification">'
                    '<application android:debuggable="true"><activity android:name=".PngReceiptActivity" android:exported="true" /></application>'
                    '<instrumentation android:name="androidx.test.runner.AndroidJUnitRunner" android:targetPackage="com.artflow.qualification" /></manifest>')
        self.assertTrue(qualification.validate_driver_manifest(manifest)["selfInstrumenting"])
        for mutated in (manifest.replace('targetPackage="com.artflow.qualification"', 'targetPackage="com.artflow.studio"'),
                        manifest.replace('debuggable="true"', 'debuggable="false"'),
                        manifest.replace('name=".PngReceiptActivity"', 'name=".WrongActivity"')):
            with self.subTest(manifest=mutated), self.assertRaises(ValueError):
                qualification.validate_driver_manifest(mutated)

    def test_only_exact_single_passing_instrumentation_case_is_accepted(self):
        self.assertEqual(qualification.instrumentation_result(test_log())["passed"], 1)
        for text in (test_log().replace("STATUS_CODE: 0", "STATUS_CODE: -3"),
                     test_log().replace("STATUS_CODE: 0", "STATUS_CODE: -4"),
                     test_log().replace("STATUS_CODE: 0", "STATUS_CODE: -2"),
                     test_log().replace("numtests=1", "numtests=2"),
                     test_log().replace(qualification.TEST_METHOD, "differentTest"),
                     test_log().replace("OK (1 test)", "OK (0 tests)"),
                     test_log() + test_log(), test_log().replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"),
                     test_log() + "INSTRUMENTATION_FAILED: crashed\n"):
            with self.subTest(log=text), self.assertRaises(ValueError):
                qualification.instrumentation_result(text)

    def test_png_decoder_compares_every_rgb_rgba_pixel_and_all_filters(self):
        raw = b"".join(bytes((255, index % 256, index * 13 % 256, index * 71 % 256)) for index in range(4096))
        for rgba in (False, True):
            path = self.root / f"{rgba}.png"
            path.write_bytes(png(raw, rgba=rgba, filters=True))
            self.assertEqual(qualification.png_pixels(path), raw)

    def test_png_crc_trailing_data_and_dimensions_fail(self):
        raw = b"\xff" * (4096 * 4)
        image = png(raw)
        wrong_dimensions = image[:8] + chunk(b"IHDR", struct.pack(">IIBBBBB", 65, 64, 8, 6, 0, 0, 0)) + image[33:]
        for malformed in (image[:-4] + b"wrong", image + b"extra", wrong_dimensions):
            path = self.root / "malformed.png"
            path.write_bytes(malformed)
            with self.assertRaises(ValueError):
                qualification.png_pixels(path)

    def test_success_compares_all_pixels_and_separate_processes(self):
        attempt, _ = self.fixture()
        result = qualification.verify_receipts(self.root, attempt)
        self.assertEqual(result["comparedPixels"], 4096)
        self.assertEqual(result["processIds"], [10, 20, 30])

    def test_receipt_token_hash_grant_pid_and_assertion_mismatches_fail(self):
        attempt, _ = self.fixture()
        edits = (
            ("workflow.json", "token", "87654321-1234-1234-1234-123456789abc"),
            ("workflow.json", "appApkSha256", "c" * 64),
            ("workflow.json", "reopenedAppPid", 20),
            ("workflow.json", "publisherQualified", True),
            ("workflow.json", "assertions", {}),
            ("saved-receipt.json", "token", "87654321-1234-1234-1234-123456789abc"),
            ("saved-receipt.json", "pngSha256", "d" * 64),
            ("saved-receipt.json", "readGrant", False),
            ("saved-receipt.json", "uri", "content://wrong.authority/image.png"),
        )
        for file, key, value in edits:
            path = self.root / file
            original = path.read_text()
            changed = json.loads(original)
            changed[key] = value
            path.write_text(json.dumps(changed))
            with self.subTest(field=(file, key)), self.assertRaises(ValueError):
                qualification.verify_receipts(self.root, attempt)
            path.write_text(original)

    def test_a_single_changed_reopened_pixel_fails_even_with_consistent_hash_receipts(self):
        attempt, raw = self.fixture()
        modified = raw[:4] + b"\xff\x00\x22\x44" + raw[8:]
        image = png(modified)
        (self.root / "reopened.png").write_bytes(image)
        (self.root / "reopened-pixels.argb").write_bytes(modified)
        path = self.root / "reopened-receipt.json"
        report = json.loads(path.read_text())
        report.update(pngSha256=digest(image), pixelSha256=digest(modified))
        path.write_text(json.dumps(report))
        with self.assertRaisesRegex(ValueError, "process death"):
            qualification.verify_receipts(self.root, attempt)

    def test_png_and_claimed_raw_pixels_must_match_independently(self):
        attempt, raw = self.fixture()
        image = png(raw[:4] + b"\xff\x00\x22\x44" + raw[8:])
        (self.root / "saved.png").write_bytes(image)
        path = self.root / "saved-receipt.json"
        report = json.loads(path.read_text())
        report["pngSha256"] = digest(image)
        path.write_text(json.dumps(report))
        with self.assertRaisesRegex(ValueError, "decoded receipt pixels"):
            qualification.verify_receipts(self.root, attempt)

    def test_failure_receipt_and_missing_screen_cannot_be_qualified(self):
        attempt, _ = self.fixture()
        error = self.root / "saved-error.txt"
        error.write_text("Permission denied opening actual content URI")
        with self.assertRaisesRegex(ValueError, "share failure"):
            qualification.verify_receipts(self.root, attempt)
        error.unlink()
        (self.root / "painted.png").unlink()
        with self.assertRaises(OSError):
            qualification.verify_receipts(self.root, attempt)

    def test_existing_or_retained_packages_fail_before_device_mutations(self):
        responses = {
            "device-state": "device", "device-serial": "emulator-5554", "device-user": "0", "device-page-size": "4096",
            "device-properties": "[ro.kernel.qemu]: [1]\n[ro.build.version.sdk]: [36]\n[ro.build.fingerprint]: [fixture/fingerprint]\n",
        }
        commands = mock.Mock()
        commands.text.side_effect = lambda label, argv, **kwargs: responses.get(label, "")
        device = qualification.Device(commands, "adb", "emulator-5554")
        self.assertTrue(device.fresh()["freshPackagePreconditions"])
        responses["registered-" + qualification.APP] = "package:" + qualification.APP
        with self.assertRaisesRegex(ValueError, "already registered"):
            device.fresh()
        self.assertTrue(all("install" not in call.args[1] and "uninstall" not in call.args[1] for call in commands.text.call_args_list))

    def test_reused_attempt_keeps_original_failure_and_never_contacts_device(self):
        self.write_json("prepared.json", {"revision": "1" * 40, "apks": {"signed": {"sha256": "a" * 64}, "driver": {"sha256": "b" * 64}}})
        original = b'{"token":"original-failed-attempt"}\n'
        (self.root / "attempt.json").write_bytes(original)
        with mock.patch.object(qualification, "Device") as device:
            with self.assertRaises(FileExistsError):
                qualification.run(self.root, "adb", "emulator-5554", True)
            device.assert_not_called()
        self.assertEqual((self.root / "attempt.json").read_bytes(), original)

    def test_failed_command_preserves_first_stdout_stderr_and_status(self):
        commands = qualification.Commands(self.root / "commands")
        with self.assertRaisesRegex(ValueError, "original stdout/stderr"):
            commands.run("failure", [sys.executable, "-c", "import sys; print('first failure'); print('diagnostic', file=sys.stderr); sys.exit(7)"])
        record = json.loads((commands.root / "001-failure.json").read_text())
        self.assertEqual(record["exitCode"], 7)
        self.assertEqual(Path(record["stdout"]).read_text(), "first failure\n")
        self.assertEqual(Path(record["stderr"]).read_text(), "diagnostic\n")

    def test_changed_or_untracked_source_rejected(self):
        with mock.patch.object(qualification, "git", side_effect=["1" * 40, " M app/build.gradle.kts"]):
            with self.assertRaisesRegex(ValueError, "clean"):
                qualification.clean_source(self.root, "1" * 40)
        with mock.patch.object(qualification, "git", return_value="2" * 40):
            with self.assertRaisesRegex(ValueError, "revision changed"):
                qualification.clean_source(self.root, "1" * 40)

    def test_missing_run_evidence_produces_failed_nonpublisher_report(self):
        result = qualification.verify(self.root)
        self.assertFalse(result["success"])
        self.assertFalse(result["publisherQualified"])
        self.assertFalse(result["physicalDeviceQualified"])
        self.assertTrue(result["problems"])
        self.assertTrue((self.root / "verification.md").is_file())


if __name__ == "__main__":
    unittest.main()
