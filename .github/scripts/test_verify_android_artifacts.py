"""Positive and adversarial controls for the binary release preflight."""
import struct
import tempfile
import unittest
from pathlib import Path
import zipfile
from verify_android_artifacts import verify_elf, verify_archive


def elf(alignment=16384, relro_start=0x4000, relro_end=0x8000,
        writable_start=0x8000, relro_load_end=None, executable_flags=5):
    data = bytearray(512)
    data[:7] = b'\x7fELF\x02\x01\x01'
    struct.pack_into('<Q', data, 32, 64)
    struct.pack_into('<HH', data, 54, 56, 4)
    segments = [
        (1, executable_flags, 0, 0, 0, 512, 512, alignment),
        (1, 6, 0, 0x4000, 0x4000, 0,
         (relro_end if relro_load_end is None else relro_load_end) - 0x4000, alignment),
        (1, 6, 0, writable_start, writable_start, 0, 16, alignment),
        (0x6474E552, 4, 0, relro_start, relro_start, 0, relro_end - relro_start, 1),
    ]
    for index, segment in enumerate(segments):
        struct.pack_into('<IIQQQQQQ', data, 64 + index * 56, *segment)
    return bytes(data)


class PreflightTest(unittest.TestCase):
    def test_aligned_elf(self):
        self.assertEqual([], verify_elf(elf(), True))

    def test_old_page_alignment(self):
        self.assertTrue(any('LOAD' in e for e in verify_elf(elf(alignment=4096), True)))

    def test_raw_relro_end_in_padding_is_safe(self):
        self.assertEqual([], verify_elf(elf(relro_end=0x6000), True))

    def test_unaligned_relro_end_cannot_protect_writable_tail(self):
        errors = verify_elf(elf(relro_end=0x6000, relro_load_end=0x7000), True)
        self.assertTrue(any('RELRO' in e and 'writable' in e for e in errors))

    def test_aligned_relro_end_does_not_hide_unsafe_prefix(self):
        errors = verify_elf(elf(relro_start=0x5000), True)
        self.assertTrue(any('RELRO' in e and 'writable' in e for e in errors))

    def test_relro_cannot_change_another_loads_write_permission(self):
        errors = verify_elf(elf(relro_end=0x5000, writable_start=0x6000), True)
        self.assertTrue(any('RELRO' in e and 'writable' in e for e in errors))

    def test_relro_cannot_remove_execute_permission(self):
        data = bytearray(elf())
        struct.pack_into('<QQ', data, 64 + 3 * 56 + 16, 0, 0)
        errors = verify_elf(bytes(data), True)
        self.assertTrue(any('executable' in e for e in errors))

    def test_relro_cannot_protect_unmapped_pages(self):
        data = bytearray(elf())
        struct.pack_into('<Q', data, 64 + 3 * 56 + 16, 0x10000)
        self.assertTrue(any('unmapped' in e for e in verify_elf(bytes(data), True)))

    def test_actual_androidx_graphics_path_program_headers_have_safe_padding(self):
        # The four relevant program headers in the packaged 1.1.0 arm64 and x86_64
        # libraries. These are numeric layout fixtures, not copied native binaries.
        for size, text_end, relro_files, relro_memory, bss in (
            (9952, 0x1b40, 0x398, 0x4c0, 0x9ed8),
            (10104, 0x1bc0, 0x3b0, 0x440, 0x9f70),
        ):
            with self.subTest(size=size):
                data = bytearray(size)
                data[:7] = b'\x7fELF\x02\x01\x01'
                struct.pack_into('<Q', data, 32, 64)
                struct.pack_into('<HH', data, 54, 56, 4)
                base = text_end + 0x4000
                segments = [
                    (1, 5, 0, 0, 0, text_end, text_end, 0x4000),
                    (1, 6, text_end, base, base, relro_files, relro_memory, 0x4000),
                    (1, 6, bss - 0x8000, bss, bss, 0, 16, 0x4000),
                    (0x6474E552, 4, text_end, base, base, relro_files, relro_memory, 1),
                ]
                for i, segment in enumerate(segments):
                    struct.pack_into('<IIQQQQQQ', data, 64 + i * 56, *segment)
                self.assertEqual([], verify_elf(bytes(data), True))
                # Extend live RW data into exactly the formerly harmless padding.
                struct.pack_into('<Q', data, 64 + 56 + 40, relro_memory + 16)
                self.assertTrue(any('writable' in e for e in verify_elf(bytes(data), True)))

    def test_truncated_headers(self):
        self.assertTrue(verify_elf(elf()[:80], True))
        self.assertTrue(verify_elf(b'not ELF', True))

    def test_bad_segment_size(self):
        data = bytearray(elf())
        struct.pack_into('<Q', data, 64 + 32, 8192)
        self.assertTrue(verify_elf(bytes(data), True))

    def test_overflowing_virtual_address_is_rejected(self):
        data = bytearray(elf())
        struct.pack_into('<Q', data, 64 + 16, 2**64 - 16384)
        struct.pack_into('<Q', data, 64 + 40, 32768)
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

    def test_64_bit_counterpart_must_exist_for_each_library(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'app.aab'
            with zipfile.ZipFile(path, 'w') as z:
                z.writestr('base/manifest/AndroidManifest.xml', b'manifest')
                z.writestr('base/lib/armeabi-v7a/missing.so', elf())
                z.writestr('base/lib/arm64-v8a/unrelated.so', elf())
            self.assertTrue(any('missing.so' in e and 'corresponding' in e for e in verify_archive(path)[0]))

    def test_unaligned_uncompressed_apk_entry(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'app.apk'
            with zipfile.ZipFile(path, 'w') as z:
                z.writestr('AndroidManifest.xml', b'manifest')
                z.writestr('lib/arm64-v8a/check.so', elf())
            self.assertTrue(any('APK entry' in e for e in verify_archive(path)[0]))


if __name__ == '__main__':
    unittest.main()
