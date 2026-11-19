import hashlib
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import prepare_emulator_kernel as kernel


class PrepareKernelTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.release = "6.6.66-android15-8-gd0c43a640eab-ab13812146"
        data = bytearray(1024)
        data[0x202:0x206] = b"HdrS"
        struct.pack_into("<H", data, 0x20E, 0x70)
        version = (self.release + " (test-builder)").encode()
        data[0x270:0x270 + len(version)] = version
        self.kernel_bytes = bytes(data)
        self.archive = self.make_archive(self.kernel_bytes)
        self.pin = {
            "package": kernel.EXPECTED_PACKAGE,
            "url": "https://dl.google.com/android/repository/sys-img/google_apis/pinned.zip",
            "archive_bytes": len(self.archive),
            "archive_sha1": hashlib.sha1(self.archive).hexdigest(),
            "member": "x86_64/kernel-ranchu",
            "kernel_bytes": len(self.kernel_bytes),
            "kernel_sha256": hashlib.sha256(self.kernel_bytes).hexdigest(),
            "kernel_release": self.release,
            "expected_api": 35,
            "expected_fingerprint": "google/test/test:15/BUILD/123:userdebug/dev-keys",
        }

    def make_archive(self, data):
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("x86_64/kernel-ranchu", data)
            archive.writestr("x86_64/system.img", b"must-not-be-extracted")
            archive.writestr("../outside", b"must-not-be-extracted")
        return output.getvalue()

    def write_pin(self):
        path = self.root / "pin.json"
        path.write_text(json.dumps(self.pin))
        return path

    def test_verified_download_extracts_only_kernel_and_records_identity(self):
        with patch.object(kernel, "urlopen", return_value=io.BytesIO(self.archive)):
            report = kernel.prepare_kernel(self.write_pin(), self.root / "out")
        self.assertEqual(report["kernel_release"], self.release)
        self.assertEqual(report["archive_sha256"], hashlib.sha256(self.archive).hexdigest())
        self.assertEqual(report["status"], "PASS")
        self.assertEqual({p.name for p in (self.root / "out").iterdir()}, {"kernel-ranchu", "kernel-source.json"})
        self.assertFalse((self.root / "outside").exists())

    def test_corrupt_archive_does_not_publish_kernel_or_success_manifest(self):
        self.pin["archive_sha1"] = "0" * 40
        with patch.object(kernel, "urlopen", return_value=io.BytesIO(self.archive)):
            with self.assertRaisesRegex(ValueError, "archive size/SHA-1"):
                kernel.prepare_kernel(self.write_pin(), self.root / "out")
        self.assertEqual(list((self.root / "out").iterdir()), [])

    def test_optional_archive_sha256_pin_is_enforced_before_extraction(self):
        self.pin["archive_sha256"] = hashlib.sha256(self.archive).hexdigest()
        with patch.object(kernel, "urlopen", return_value=io.BytesIO(self.archive)):
            report = kernel.prepare_kernel(self.write_pin(), self.root / "valid")
        self.assertEqual(report["archive_sha256"], self.pin["archive_sha256"])
        self.assertEqual(report["status"], "PASS")

        self.pin["archive_sha256"] = "0" * 64
        with patch.object(kernel, "urlopen", return_value=io.BytesIO(self.archive)):
            with self.assertRaisesRegex(ValueError, "archive SHA-256"):
                kernel.prepare_kernel(self.write_pin(), self.root / "wrong-digest")
        self.assertEqual(list((self.root / "wrong-digest").iterdir()), [])

    def test_valid_archive_with_wrong_kernel_is_rejected(self):
        self.pin["kernel_sha256"] = "0" * 64
        with patch.object(kernel, "urlopen", return_value=io.BytesIO(self.archive)):
            with self.assertRaisesRegex(ValueError, "Kernel SHA-256"):
                kernel.prepare_kernel(self.write_pin(), self.root / "out")
        self.assertEqual(list((self.root / "out").iterdir()), [])

    def test_archive_length_is_enforced(self):
        for size in (len(self.archive) - 1, len(self.archive) + 1):
            with self.subTest(size=size):
                self.pin["archive_bytes"] = size
                with patch.object(kernel, "urlopen", return_value=io.BytesIO(self.archive)):
                    with self.assertRaises(ValueError):
                        kernel.prepare_kernel(self.write_pin(), self.root / "out")
                self.assertEqual(list((self.root / "out").iterdir()), [])

    def test_cache_reuse_revalidates_bytes_and_version(self):
        output = self.root / "out"
        output.mkdir()
        (output / "kernel-ranchu").write_bytes(self.kernel_bytes)
        with patch.object(kernel, "urlopen", side_effect=AssertionError("cache must avoid download")):
            report = kernel.prepare_kernel(self.write_pin(), output)
        self.assertEqual(report["source"], "verified-kernel-cache")
        (output / "kernel-ranchu").write_bytes(b"bad cache")
        with self.assertRaisesRegex(ValueError, "size/type"):
            kernel.prepare_kernel(self.write_pin(), output)
        self.assertFalse((output / "kernel-source.json").exists())

    def test_wrong_kernel_family_or_actual_version_is_rejected(self):
        bad = dict(self.pin, kernel_release="6.12.1-android16-8-gdeadbeef-ab123")
        with self.assertRaisesRegex(ValueError, "KMI"):
            kernel.validate_pin(bad)
        self.pin["kernel_release"] = "6.6.50-android15-8-gdeadbeef-ab123"
        with patch.object(kernel, "urlopen", return_value=io.BytesIO(self.archive)):
            with self.assertRaisesRegex(ValueError, "release"):
                kernel.prepare_kernel(self.write_pin(), self.root / "out")

    def test_source_platform_and_nonofficial_sources_fail_closed(self):
        for changes in (
            {"expected_api": 36},
            {"expected_fingerprint": "google/test/test:16/BUILD/123:userdebug/dev-keys"},
            {"url": "https://example.com/pinned.zip"},
            {"member": "x86_64/system.img"},
        ):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                kernel.validate_pin(dict(self.pin, **changes))


if __name__ == "__main__":
    unittest.main()
