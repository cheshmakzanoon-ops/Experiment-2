"""Synthetic ARM64 fixtures exercise metadata parsing without redistributing the IPA."""
import struct
import unittest

from ipa_native_contracts import NativeImage, find_method, signed, verify_scalar

BASE = 0x100000000


def fixture(*, small=True, direct=False, fixups=False):
    data = bytearray(0x2000)
    commands = 152 + (16 if fixups else 0)
    struct.pack_into('<8I', data, 0, 0xFEEDFACF, 0x0100000C, 0, 2,
                     2 if fixups else 1, commands, 0, 0)
    struct.pack_into('<II16s4Q4I', data, 32, 0x19, 152, b'__TEXT',
                     BASE, len(data), 0, len(data), 7, 5, 1, 0)
    struct.pack_into('<16s16sQQ8I', data, 104, b'__objc_classlist', b'__TEXT',
                     BASE + 0x300, 8, 0x300, 3, 0, 0, 0, 0, 0, 0)
    # A synthetic class and metaclass with one ordinary or relative method.
    struct.pack_into('<Q', data, 0x300, BASE + 0x400)
    struct.pack_into('<5Q', data, 0x400, BASE + 0x428, 0, 0, 0, BASE + 0x500)
    struct.pack_into('<5Q', data, 0x428, BASE + 0x428, 0, 0, 0, BASE + 0x548)
    struct.pack_into('<Q', data, 0x500 + 24, BASE + 0x600)
    struct.pack_into('<Q', data, 0x500 + 32, BASE + 0x700)
    struct.pack_into('<Q', data, 0x548 + 24, BASE + 0x600)
    data[0x600:0x60E] = b'ValkyrieBrush\0'
    data[0x650:0x658] = b'f16@0:8\0'
    data[0x780:0x78C] = b'nativeFlow\0\0'
    if small:
        flags = 0x8000000C | (0x40000000 if direct else 0)
        struct.pack_into('<IIiii', data, 0x700, flags, 1,
                         (0x780 if direct else 0x760) - 0x708, 0x650 - 0x70C, 0x800 - 0x710)
        struct.pack_into('<Q', data, 0x760, BASE + 0x780)
    else:
        struct.pack_into('<IIQQQ', data, 0x700, 24, 1,
                         BASE + 0x780, BASE + 0x650, BASE + 0x800)
    struct.pack_into('<II', data, 0x800, 0xBD401000, 0xD65F03C0)  # LDR S0,[X0,#16]; RET
    # ADRP X2, containing page; ADD X2,X2,#0xa00 -> synthetic CFString.
    struct.pack_into('<II', data, 0x900, 0x90000002, 0x91280042)
    struct.pack_into('<QQ', data, 0xA10, BASE + 0xA40, 5)
    data[0xA40:0xA46] = b'field\0'
    # A direct BL reaches a standard selector stub, which refers to decodeFloatForKey:.
    struct.pack_into('<II', data, 0x920, 0x94000078, 0xBD001280)
    struct.pack_into('<II', data, 0xB00, 0x90000001, 0xF945C021)
    struct.pack_into('<Q', data, 0xB80, BASE + 0xBA0)
    data[0xBA0:0xBB3] = b'decodeFloatForKey:\0'
    if fixups:
        struct.pack_into('<4I', data, 184, 0x80000034, 16, 0x1000, 68)
        struct.pack_into('<7I', data, 0x1000, 0, 32, 0, 0, 0, 1, 0)
        struct.pack_into('<3I', data, 0x1020, 1, 12, 0)
        struct.pack_into('<IHHQIHH', data, 0x102C, 24, 4096, 6, 0, 0, 1, 0x300)
        struct.pack_into('<Q', data, 0x300, 0x400)
    return data


class NativeMetadataTest(unittest.TestCase):
    def test_relative_indirect_methods_and_class_names(self):
        image = NativeImage(bytes(fixture()))
        classes = image.classes()
        self.assertEqual(2, len(classes))
        self.assertEqual(BASE + 0x800, find_method(classes, 'ValkyrieBrush', 'nativeFlow'))

    def test_relative_direct_selector_variant(self):
        classes = NativeImage(bytes(fixture(direct=True))).classes()
        self.assertEqual('nativeFlow', classes[0]['methods'][0]['name'])

    def test_absolute_method_variant(self):
        classes = NativeImage(bytes(fixture(small=False))).classes()
        self.assertEqual(BASE + 0x800, classes[0]['methods'][0]['address'])

    def test_chained_rebase_resolves_to_local_class(self):
        image = NativeImage(bytes(fixture(fixups=True)))
        self.assertEqual(BASE + 0x400, image.pointer(BASE + 0x300))
        self.assertEqual(2, len(image.classes()))

    def test_external_bind_cannot_be_dereferenced(self):
        data = fixture(fixups=True)
        struct.pack_into('<Q', data, 0x300, 1 << 63)
        image = NativeImage(bytes(data))
        with self.assertRaisesRegex(ValueError, 'External bind'):
            image.classes()

    def test_chain_crossing_page_is_rejected(self):
        data = fixture(fixups=True)
        struct.pack_into('<H', data, 0x1042, 0xFFC)
        with self.assertRaisesRegex(ValueError, 'page boundary'):
            NativeImage(bytes(data))

    def test_unknown_chained_format_is_rejected(self):
        data = fixture(fixups=True)
        struct.pack_into('<H', data, 0x1032, 7)
        with self.assertRaisesRegex(ValueError, 'pointer format'):
            NativeImage(bytes(data))

    def test_multi_start_page_requires_explicit_support(self):
        data = fixture(fixups=True)
        struct.pack_into('<H', data, 0x1042, 0x8000)
        with self.assertRaisesRegex(ValueError, 'Multiple-start'):
            NativeImage(bytes(data))

    def test_rebase_reserved_bits_are_rejected(self):
        data = fixture(fixups=True)
        struct.pack_into('<Q', data, 0x300, 0x400 | (1 << 44))
        with self.assertRaisesRegex(ValueError, 'Reserved rebase'):
            NativeImage(bytes(data))

    def test_truncated_file_and_invalid_command_are_rejected(self):
        with self.assertRaises(ValueError):
            NativeImage(b'\0' * 16)
        data = fixture()
        struct.pack_into('<I', data, 36, 4)
        with self.assertRaisesRegex(ValueError, 'Malformed load command'):
            NativeImage(bytes(data))

    def test_impossible_method_count_is_rejected(self):
        data = fixture()
        struct.pack_into('<I', data, 0x704, 10001)
        with self.assertRaisesRegex(ValueError, 'method list'):
            NativeImage(bytes(data)).classes()

    def test_missing_or_duplicate_method_is_not_a_match(self):
        classes = NativeImage(bytes(fixture())).classes()
        with self.assertRaises(ValueError):
            find_method(classes, 'ValkyrieBrush', 'absent')
        with self.assertRaises(ValueError):
            find_method(classes + classes, 'ValkyrieBrush', 'nativeFlow')


class InstructionContractTest(unittest.TestCase):
    def test_archive_decoding_and_getter_share_the_same_ivar(self):
        image = NativeImage(bytes(fixture()))
        result = verify_scalar(image, image.classes(), ('field', 'nativeFlow', 0x900, 0x920, 0x924))
        self.assertEqual('0x10', result['ivar_offset'])
        self.assertEqual('field', result['archive_key'])

    def test_changed_ivar_cannot_pass_contract(self):
        data = fixture()
        struct.pack_into('<I', data, 0x800, 0xBD401400)  # LDR from a different field.
        image = NativeImage(bytes(data))
        with self.assertRaisesRegex(ValueError, 'different ivars'):
            verify_scalar(image, image.classes(), ('field', 'nativeFlow', 0x900, 0x920, 0x924))

    def test_changed_key_cannot_pass_contract(self):
        image = NativeImage(bytes(fixture()))
        with self.assertRaisesRegex(ValueError, 'Archive key differs'):
            verify_scalar(image, image.classes(), ('wrong', 'nativeFlow', 0x900, 0x920, 0x924))

    def test_wrong_instruction_and_string_length_are_rejected(self):
        data = fixture()
        struct.pack_into('<I', data, 0x900, 0xD65F03C0)
        with self.assertRaisesRegex(ValueError, 'ADRP'):
            NativeImage(bytes(data)).cfstring_pair(BASE + 0x900)
        data = fixture()
        struct.pack_into('<Q', data, 0xA18, 6)
        with self.assertRaisesRegex(ValueError, 'CFString length'):
            NativeImage(bytes(data)).cfstring_pair(BASE + 0x900)

    def test_sign_extension_for_negative_branches_and_adrp(self):
        self.assertEqual(-1, signed((1 << 21) - 1, 21))
        self.assertEqual(-8, signed((1 << 26) - 8, 26))
        self.assertEqual(17, signed(17, 21))


if __name__ == '__main__':
    unittest.main()
