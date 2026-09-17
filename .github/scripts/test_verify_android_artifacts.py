"""Positive and negative controls for the binary release preflight."""
import struct
import tempfile
import unittest
from pathlib import Path
import zipfile
from verify_android_artifacts import verify_elf, verify_archive


def elf(alignment=16384, relro_end=16384):
    data = bytearray(512)
    data[:7] = b'\x7fELF\x02\x01\x01'
    struct.pack_into('<Q', data, 32, 64)
    struct.pack_into('<HH', data, 54, 56, 2)
    struct.pack_into('<IIQQQQQQ', data, 64, 1, 5, 0, 0, 0, 512, 512, alignment)
    struct.pack_into('<IIQQQQQQ', data, 120, 0x6474E552, 4, 0, 0, 0, 0, relro_end, 1)
    return bytes(data)


class PreflightTest(unittest.TestCase):
    def test_aligned_elf(self):
        self.assertEqual([], verify_elf(elf(), True))

    def test_old_page_alignment(self):
        self.assertTrue(any('LOAD' in e for e in verify_elf(elf(alignment=4096), True)))

    def test_unaligned_relro(self):
        self.assertTrue(any('RELRO' in e for e in verify_elf(elf(relro_end=4096), True)))

    def test_truncated_headers(self):
        self.assertTrue(verify_elf(elf()[:80], True))
        self.assertTrue(verify_elf(b'not ELF', True))

    def test_bad_segment_size(self):
        data = bytearray(elf())
        struct.pack_into('<Q', data, 64 + 32, 8192)
        self.assertTrue(verify_elf(bytes(data), True))

    def test_bundle_inventory_and_missing_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'app.aab'
            with zipfile.ZipFile(path, 'w') as z:
                z.writestr('base/manifest/AndroidManifest.xml', b'manifest')
                z.writestr('base/lib/arm64-v8a/check.so', elf())
            errors, inventory = verify_archive(path)
            self.assertEqual([], errors)
            self.assertEqual(1, len(inventory))
            empty = Path(directory) / 'empty.apk'
            with zipfile.ZipFile(empty, 'w'):
                pass
            self.assertTrue(verify_archive(empty)[0])

    def test_unaligned_uncompressed_apk_entry(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'app.apk'
            with zipfile.ZipFile(path, 'w') as z:
                z.writestr('AndroidManifest.xml', b'manifest')
                z.writestr('lib/arm64-v8a/check.so', elf())
            self.assertTrue(any('APK entry' in e for e in verify_archive(path)[0]))


if __name__ == '__main__':
    unittest.main()
