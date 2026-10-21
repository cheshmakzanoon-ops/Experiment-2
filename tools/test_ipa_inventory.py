import importlib.util
import plistlib
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location('ipa_inventory', Path(__file__).with_name('ipa_inventory.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def mach64(cryptid=0, name='@rpath/Test.framework/Test'):
    raw = name.encode() + b'\x00'
    load_size = (24 + len(raw) + 3) & ~3
    load = struct.pack('<6I', 0xC, load_size, 24, 0, 0, 0) + raw.ljust(load_size - 24, b'\x00')
    encrypt = struct.pack('<6I', 0x2C, 24, 0x1000, 4096, cryptid, 0)
    signature = struct.pack('<4I', 0x1D, 16, 5000, 256)
    uuid = struct.pack('<2I', 0x1B, 24) + bytes(range(16))
    build = struct.pack('<6I', 0x32, 24, 2, 0x10000, 0x12000, 0)
    commands = [load, encrypt, signature, uuid, build]
    header = struct.pack('<8I', 0xFEEDFACF, 0x0100000C, 0, 2, len(commands), sum(map(len, commands)), 0, 0)
    return header + b''.join(commands)


class InventoryTests(unittest.TestCase):
    def test_macho_encryption_dylib_uuid_and_signature(self):
        parsed = module.parse_macho(mach64(cryptid=1))
        self.assertEqual('parsed', parsed['status'])
        self.assertEqual('arm64', parsed['architecture'])
        self.assertTrue(parsed['contains_encrypted_region'])
        self.assertEqual(1, parsed['encryption'][0]['cryptid'])
        self.assertEqual('@rpath/Test.framework/Test', parsed['linked_libraries'][0]['name'])
        self.assertEqual(bytes(range(16)).hex(), parsed['uuid'][0])
        self.assertEqual(5000, parsed['code_signatures'][0]['offset'])
        self.assertEqual(2, parsed['platforms'][0]['platform_id'])

    def test_fat_archives_report_both_slices(self):
        a = mach64(0)
        b = mach64(1)
        hdr = b'\xca\xfe\xba\xbe' + struct.pack('>I', 2)
        arches = struct.pack('>5I', 0x0100000C, 0, 64, len(a), 2)
        arches += struct.pack('>5I', 0x0100000C, 0, 512, len(b), 2)
        bundle = (hdr + arches).ljust(64, b'\x00') + a
        bundle = bundle.ljust(512, b'\x00') + b
        found = module.parse_macho(bundle)
        self.assertEqual('fat-container', found['status'])
        self.assertEqual([False, True], [s['header']['contains_encrypted_region'] for s in found['slices']])

    def test_full_ipa_inspection_and_hash_pin(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'fixture.ipa'
            with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_DEFLATED) as z:
                z.writestr('Payload/Painter.app/Info.plist', plistlib.dumps({
                    'CFBundleIdentifier': 'local.fixture', 'CFBundleExecutable': 'Painter',
                    'CFBundleVersion': '12', 'MinimumOSVersion': '17.0'}))
                z.writestr('Payload/Painter.app/Painter', mach64(1))
                z.writestr('Payload/Painter.app/Brush-Presets/Sample.brush', b'PK\x03\x04')
                z.writestr('Payload/Painter.app/Frameworks/Sim.framework/Info.plist',
                           plistlib.dumps({'CFBundleIdentifier':'fixture.sim'}))
            record = module.inspect(path, expected_sha=None)
            self.assertEqual(['Payload/Painter.app'], record['app_roots'])
            self.assertEqual(4, record['zip_members'])
            self.assertTrue(record['declared_executables']['Payload/Painter.app/Painter']['binary']['contains_encrypted_region'])
            self.assertIn('Sim.framework', record['frameworks'])
            self.assertEqual('12', record['bundle_plists']['Payload/Painter.app/Info.plist']['CFBundleVersion'])
            with self.assertRaisesRegex(ValueError, 'SHA-256'):
                module.inspect(path)

    def test_unsafe_path_rejected(self):
        for bad in ('../evil', 'Payload/App.app/../evil', '/absolute', 'Payload\\evil'):
            with self.subTest(path=bad), tempfile.TemporaryDirectory() as td:
                p = Path(td) / 'bad.ipa'
                with zipfile.ZipFile(p, 'w') as z:
                    z.writestr(bad, b'no')
                with self.assertRaisesRegex(ValueError, 'Unsafe'):
                    module.inspect(p, expected_sha=None)

    def test_malformed_load_commands_fail_closed(self):
        invalid = struct.pack('<8I', 0xFEEDFACF, 0x0100000C, 0, 2, 1, 8, 0, 0) + struct.pack('<2I', 0xC, 0)
        self.assertEqual('malformed-load-command-size', module.parse_macho(invalid)['status'])
        self.assertEqual('not-thin-mach-o', module.parse_macho(b'NO\x00\x00' + b'0' * 100)['status'])


if __name__ == '__main__':
    unittest.main()
