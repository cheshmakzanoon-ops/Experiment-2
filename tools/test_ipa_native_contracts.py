"""Synthetic ARM64 fixtures exercise metadata parsing without redistributing the IPA."""
import struct
import unittest

from ipa_native_contracts import NativeImage, find_method, signed, verify_scalar, verify_shape_count, verify_spacing_version

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


def count_fixture():
    """Synthetic getter and stamp-loop anchors, using a normal local selector stub."""
    data = fixture()
    getter, plot = 0x800, 0x1000
    # STP; frame setup; LDR brush; FMOV 15; FMUL; FCVTZU; restore; RET.
    words = {
        0x00: 0xA9BF7BFD, 0x04: 0x910003FD, 0x08: 0xF9402800,
        0x10: 0x1E25D001, 0x14: 0x1E210800, 0x18: 0x9E390000,
        0x1C: 0xA8C17BFD, 0x20: 0xD65F03C0,
    }
    for offset, word in words.items():
        struct.pack_into('<I', data, getter + offset, word)
    # Independently specified cap/conversion/iteration instructions; these never execute.
    words = {
        0x898: 0xF9417E80, 0x8A0: 0x9E230000,
        0x8C0: 0xF9417E80, 0x8E0: 0x1E25D000, 0x8E4: 0x1E202020, 0x8E8: 0x54000285,
        0x8EC: 0xF9417E80, 0x90C: 0x1E2703E0, 0x910: 0x1E202028, 0x914: 0x5400012D,
        0x918: 0xF9417E80, 0x938: 0x1E390009, 0x944: 0x5280001B, 0x94C: 0x1100053A,
        0x9CC: 0x91000508, 0x9D0: 0xF9018A88, 0x9D4: 0x1100077B,
        0x9D8: 0x6B1B035F, 0x9DC: 0x54FFFBA1,
    }
    for offset, word in words.items():
        struct.pack_into('<I', data, plot + offset, word)
    calls = [getter + 0x0C, *(plot + offset for offset in (0x89C, 0x8C4, 0x8F0, 0x91C))]
    for call in calls:
        struct.pack_into('<I', data, call, 0x94000000 | (((0xB00 - call) // 4) & 0x03FFFFFF))
    data[0xBA0:0xBAB] = b'shapeCount\0'
    classes = [
        {'name': 'ValkyrieStrokeOptions', 'kind': 'instance',
         'methods': [{'name': 'shapeCount', 'address': BASE + getter}]},
        {'name': 'ValkyrieStrokePlot', 'kind': 'instance',
         'methods': [{'name': 'plotCurve:previousPlot:initialAngle:', 'address': BASE + plot}]},
    ]
    return data, classes


def spacing_fixture():
    """Self-authored metadata/control-flow fixture; no executable is included or run."""
    data = fixture()
    data.extend(bytes(0x8000 - len(data)))
    struct.pack_into('<Q', data, 64, len(data))
    struct.pack_into('<Q', data, 80, len(data))

    def word(at, value):
        struct.pack_into('<I', data, at, value)

    def adrp(at, target, register):
        delta = ((target & ~4095) - (at & ~4095)) // 4096
        immediate = delta & ((1 << 21) - 1)
        word(at, 0x90000000 | ((immediate & 3) << 29) | ((immediate >> 2) << 5) | register)

    def address(at, target, register):
        adrp(at, target, register)
        word(at + 4, 0x91000000 | ((target & 4095) << 10) | (register << 5) | register)

    def text(at, value):
        encoded = value.encode() + b'\0'
        data[at:at + len(encoded)] = encoded

    # Explicit test layout, unrelated to the source executable's virtual addresses.
    decode, setter, getter, encode, cache, plot = 0x1000, 0x2000, 0x2020, 0x3000, 0x4000, 0x5000
    instructions = {
        decode: {0x75C: 0xAA1303E0, 0x764: 0x340000C0, 0x770: 0xAA1303E0,
                 0x778: 0x14000006, 0x784: 0xAA1303E0, 0x78C: 0x52000000, 0x790: 0xF9024E80},
        setter: {0x00: 0x52800028, 0x04: 0xF9024C08, 0x08: 0xBD01C000, 0x0C: 0xD65F03C0},
        getter: {0x00: 0xF9424C00, 0x04: 0xD65F03C0},
        encode: {0x76C: 0xAA1303E0, 0x774: 0xAA0003E2, 0x780: 0xF94007E0},
        cache: {0x40: 0xAA0003F4, 0x48: 0xF100001F, 0x4C: 0x1A9F17E8, 0x50: 0x39056268},
        plot: {0x178: 0x39456289, 0x188: 0x7100013F, 0x194: 0x1E2E1002,
               0x198: 0xBD0033E1, 0x19C: 0x1E211C41, 0x1A0: 0x1E200821,
               0x298: 0xBD4033FA, 0x2AC: 0x39456289, 0x2B0: 0x7100013F,
               0x2B4: 0x1E2E1002, 0x2B8: 0x1E3A1C40},
    }
    for start, words in instructions.items():
        for offset, value in words.items():
            word(start + offset, value)
    for cf, string_at, key in [(0x7000, 0x7200, 'plotSpacingVersion'), (0x7040, 0x7240, 'dualBlendMode')]:
        struct.pack_into('<QQ', data, cf + 16, BASE + string_at, len(key))
        text(string_at, key)
    for at, cf, register in [(decode + 0x754, 0x7000, 2), (decode + 0x768, 0x7000, 2),
                             (decode + 0x77C, 0x7040, 2), (encode + 0x778, 0x7000, 3)]:
        address(at, cf, register)
    selectors = ['containsValueForKey:', 'decodeIntegerForKey:', 'plotSpacingVersion', 'encodeInteger:forKey:']
    stubs = {}
    for index, selector in enumerate(selectors):
        stub, pointer, string_at = 0x7400 + index * 0x20, 0x7500 + index * 8, 0x7600 + index * 0x40
        stubs[selector] = stub
        adrp(stub, pointer, 1)
        word(stub + 4, 0xF9400021 | ((pointer & 4095) // 8 << 10))
        struct.pack_into('<Q', data, pointer, BASE + string_at)
        text(string_at, selector)
    for at, selector in [(decode + 0x760, selectors[0]), (decode + 0x774, selectors[1]),
                         (decode + 0x788, selectors[0]), (encode + 0x770, selectors[2]),
                         (encode + 0x784, selectors[3]), (cache + 0x44, selectors[2])]:
        word(at, 0x94000000 | (((stubs[selector] - at) // 4) & 0x03FFFFFF))
    adrp(plot + 0x18C, 0x7800, 9)
    word(plot + 0x190, 0xBD400121 | (0x800 // 4 << 10))
    word(0x7800, 0x3FB504F3)
    classes = [
        {'name': 'ValkyrieBrush', 'kind': 'instance', 'methods': [
            {'name': 'initWithCoder:', 'address': BASE + decode},
            {'name': 'encodeWithCoder:', 'address': BASE + encode},
            {'name': 'setPlotSpacing:', 'address': BASE + setter},
            {'name': 'plotSpacingVersion', 'address': BASE + getter},
        ]},
        {'name': 'ValkyrieStrokePlot', 'kind': 'instance', 'methods': [
            {'name': 'cacheOptions:size:', 'address': BASE + cache},
            {'name': 'plotCurve:previousPlot:initialAngle:', 'address': BASE + plot},
        ]},
    ]
    return data, classes


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

    def test_shape_count_contract_accepts_the_bounded_native_structure(self):
        data, classes = count_fixture()
        result = verify_shape_count(NativeImage(bytes(data)), classes)
        self.assertEqual(15.0, result['normalized_scale'])
        self.assertEqual(1, result['minimum_stamps'])
        self.assertEqual(16, result['maximum_stamps'])
        self.assertEqual(27, result['instruction_anchors'])
        self.assertEqual(5, result['selector_anchors'])
        self.assertIn('unsigned truncation toward zero', result['arithmetic'])

    def test_shape_count_arithmetic_and_loop_changes_are_rejected(self):
        changes = {
            'wrong scale': (0x810, 0x1E27F001),  # FMOV S1,#31
            'round-to-nearest': (0x818, 0x9E210000),  # FCVTNU X0,S0
            'wrong cap': (0x18E0, 0x1E27F000),  # FMOV S0,#31
            'wrong mandatory stamp': (0x194C, 0x1100093A),  # ADD W26,W9,#2
            'missing loop bound': (0x19DC, 0xD503201F),  # NOP
        }
        for label, (offset, instruction) in changes.items():
            with self.subTest(change=label):
                data, classes = count_fixture()
                struct.pack_into('<I', data, offset, instruction)
                with self.assertRaisesRegex(ValueError, 'Shape Count instruction differs'):
                    verify_shape_count(NativeImage(bytes(data)), classes)

    def test_shape_count_requires_the_named_getter_calls(self):
        data, classes = count_fixture()
        data[0xBA0:0xBAB] = b'wrongCount\0'
        with self.assertRaisesRegex(ValueError, 'Shape Count getter call changed'):
            verify_shape_count(NativeImage(bytes(data)), classes)


class SpacingVersionContractTest(unittest.TestCase):
    def test_explicit_implicit_edit_and_cached_factor_contracts_are_linked(self):
        data, classes = spacing_fixture()
        result = verify_spacing_version(NativeImage(bytes(data)), classes)
        self.assertEqual(1, result['spacing_edit_version'])
        self.assertEqual('0x498', result['version_ivar_offset'])
        self.assertEqual(33, result['instruction_anchors'])
        self.assertEqual(6, result['selector_anchors'])
        self.assertEqual(4, result['archive_key_anchors'])
        self.assertEqual('3fb504f3', result['size_factor']['other_versions_float_bits'])
        self.assertIn('extended-only does not select 0', result['implicit_version'])

    def test_changed_presence_branch_or_inversion_cannot_pass(self):
        for at, replacement in [(0x1764, 0x350000C0), (0x1778, 0x14000007), (0x178C, 0xD503201F)]:
            with self.subTest(address=at):
                data, classes = spacing_fixture()
                struct.pack_into('<I', data, at, replacement)
                with self.assertRaisesRegex(ValueError, 'Spacing version instruction differs'):
                    verify_spacing_version(NativeImage(bytes(data)), classes)

    def test_extended_key_is_not_accepted_as_the_implicit_legacy_selector(self):
        data, classes = spacing_fixture()
        key = b'dualBlendModeExtended'
        data[0x7280:0x7280 + len(key) + 1] = key + b'\0'
        struct.pack_into('<QQ', data, 0x7050, BASE + 0x7280, len(key))
        with self.assertRaisesRegex(ValueError, 'Spacing version archive key differs'):
            verify_spacing_version(NativeImage(bytes(data)), classes)

    def test_setter_version_and_shared_getter_ivar_are_required(self):
        for at, replacement in [(0x2000, 0x52800008), (0x2004, 0xF9025008), (0x2020, 0xF9425000)]:
            with self.subTest(address=at):
                data, classes = spacing_fixture()
                struct.pack_into('<I', data, at, replacement)
                with self.assertRaisesRegex(ValueError, 'Spacing version instruction differs'):
                    verify_spacing_version(NativeImage(bytes(data)), classes)

    def test_integer_archive_selector_is_required(self):
        data, classes = spacing_fixture()
        changed = b'encodeFloat:forKey:\0'
        data[0x76C0:0x76C0 + len(changed)] = changed
        with self.assertRaisesRegex(ValueError, 'Spacing version selector differs'):
            verify_spacing_version(NativeImage(bytes(data)), classes)

    def test_cached_zero_predicate_and_both_factor_paths_cannot_drift(self):
        for at, replacement, message in [
            (0x404C, 0x1A9F07E8, 'instruction'),
            (0x519C, 0x1E210C41, 'instruction'),
            (0x52B8, 0x1E3A0C40, 'instruction'),
            (0x5190, 0xBD400101, 'factor load'),
            (0x7800, 0x3F800000, 'sqrt-two constant'),
        ]:
            with self.subTest(address=at):
                data, classes = spacing_fixture()
                struct.pack_into('<I', data, at, replacement)
                with self.assertRaisesRegex(ValueError, f'Spacing version {message} differs'):
                    verify_spacing_version(NativeImage(bytes(data)), classes)


if __name__ == '__main__':
    unittest.main()
