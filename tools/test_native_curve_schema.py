"""Independently authored, relocated ARM64 data protects the bounded native curve schema."""
import struct
import unittest

from ipa_native_contracts import NativeImage, verify_curve_schema
from test_ipa_native_contracts import BASE, fixture


KEYS = (
    'dynamicsPressurePlotJitterCurve', 'dynamicsPressureStampCountCurve', 'dynamicsPressureStampCountJitterCurve',
    'dynamicsPressureOpacityCurve', 'dynamicsPressureOpacityJitterCurve', 'dynamicsPressureSizeCurve',
    'dynamicsPressureSizeJitterCurve', 'dynamicsPressureShapeRoundnessCurve', 'dynamicsPressureBleedCurve',
    'dynamicsPressureCharge', 'dynamicsPressureTransferModulationCurve', 'dynamicsPressureShapeScatterCurve',
    'dynamicsPressureGrainMovementCurve', 'dynamicsPressureGrainScaleCurve', 'dynamicsPressureGrainZoomCurve',
    'dynamicsPressureGrainDepthCurve', 'dynamicsPressureGrainDepthJitterCurve', 'dynamicsPressureDilutionCurve',
    'dynamicsPressureAttackCurve', 'dynamicsPressureWetPullCurve', 'dynamicsPressureSmudgePullCurve',
    'dynamicsPressureGradeCurve', 'dynamicsPressureBlurCurve', 'dynamicsPressureBlurJitterCurve',
    'dynamicsPressureWetnessCurve', 'dynamicsPressureHueCurve', 'dynamicsPressureSaturationCurve',
    'dynamicsPressureBrightnessCurve', 'dynamicsPressureSecondaryColorCurve', 'dynamicsPressureHueJitterCurve',
    'dynamicsPressureSaturationJitterCurve', 'dynamicsPressureLightnessJitterCurve', 'dynamicsPressureDarknessJitterCurve',
    'dynamicsPressureSecondaryColorJitterCurve', 'dynamicsPressureShapeRoundessVerticalJitterCurve',
    'dynamicsPressureShapeRoundessHorizontalJitterCurve', 'dynamicsPressurePlotSpacingJitter',
    'dynamicsPressurePlotJitterLongitudinalCurve', 'dynamicsPressureOverall',
)


def curve_schema_fixture():
    data = fixture()
    data.extend(bytes(0xB000 - len(data)))
    struct.pack_into('<Q', data, 64, len(data))
    struct.pack_into('<Q', data, 80, len(data))

    def word(at, value):
        struct.pack_into('<I', data, at, value)

    def adrp(at, target, register):
        immediate = (((target & ~4095) - (at & ~4095)) // 4096) & ((1 << 21) - 1)
        word(at, 0x90000000 | ((immediate & 3) << 29) | ((immediate >> 2) << 5) | register)

    def address(at, target, register):
        adrp(at, target, register)
        word(at + 4, 0x91000000 | ((target & 4095) << 10) | (register << 5) | register)

    def text(at, value):
        encoded = value.encode() + b'\0'
        data[at:at + len(encoded)] = encoded

    keys, decode, encode = 0x1000, 0x2000, 0x4000
    instructions = {
        keys: {0x0: 0xD1000448, 0x4: 0xF100991F, 0x8: 0x54000F28,
               0x14: 0x1000008A, 0x18: 0x3868692B, 0x1C: 0x8B0B094A, 0x20: 0xD61F0140},
        decode: {0xB60: 0xD2800015, 0xB7C: 0xAA1503E2, 0xB90: 0xAA1603E2,
                 0xB94: 0xAA1703E3, 0xBA0: 0x8B150E96, 0xBA8: 0xF9000EC0,
                 0xBE4: 0x910006B5, 0xBE8: 0xF1009EBF, 0xBEC: 0x54FFFC01},
        encode: {0xC28: 0x52800076, 0xC30: 0xF8767A74, 0xC34: 0xB4000174,
                 0xC38: 0xD1000EC2, 0xC50: 0xAA1403E2, 0xC54: 0xAA1503E3,
                 0xC60: 0x910006D6, 0xC64: 0xF100AADF, 0xC68: 0x54FFFE41},
    }
    for start, words in instructions.items():
        for offset, instruction in words.items():
            word(start + offset, instruction)
    address(keys + 0xC, 0x6000, 9)
    for index, key in enumerate(KEYS):
        cf, string_at = 0x7000 + index * 0x20, 0x8000 + index * 0x40
        struct.pack_into('<QQ', data, cf + 16, BASE + string_at, len(key))
        text(string_at, key)
        arm = keys + 0x1EC if index == 0 else keys + 0x24 + (index - 1) * 12
        address(arm, cf, 0)
        word(arm + 8, 0xD65F03C0)
        if index:
            data[0x6000 + index - 1] = (index - 1) * 3
    selectors = ['serializedKeyForCurve:', 'decodeObjectOfClass:forKey:', 'encodeObject:forKey:']
    stubs = {}
    for index, selector in enumerate(selectors):
        stub, pointer, string_at = 0xA000 + index * 0x20, 0xA100 + index * 8, 0xA200 + index * 0x40
        stubs[selector] = stub
        adrp(stub, pointer, 1)
        word(stub + 4, 0xF9400021 | ((pointer & 4095) // 8 << 10))
        struct.pack_into('<Q', data, pointer, BASE + string_at)
        text(string_at, selector)
    for at, selector in [(decode + 0xB80, selectors[0]), (decode + 0xB98, selectors[1]),
                         (encode + 0xC40, selectors[0]), (encode + 0xC58, selectors[2])]:
        word(at, 0x94000000 | (((stubs[selector] - at) // 4) & 0x03FFFFFF))
    classes = [
        {'name': 'ValkyrieBrush', 'kind': 'class',
         'methods': [{'name': 'serializedKeyForCurve:', 'address': BASE + keys}]},
        {'name': 'ValkyrieBrush', 'kind': 'instance', 'methods': [
            {'name': 'initWithCoder:', 'address': BASE + decode},
            {'name': 'encodeWithCoder:', 'address': BASE + encode},
        ]},
    ]
    return data, classes


class NativeCurveSchemaTest(unittest.TestCase):
    def test_all_thirty_nine_channels_share_decoder_and_encoder_slots(self):
        data, classes = curve_schema_fixture()
        result = verify_curve_schema(NativeImage(bytes(data)), classes)
        self.assertEqual(list(KEYS), [row['archive_key'] for row in result['channels']])
        self.assertEqual(list(range(39)), [row['index'] for row in result['channels']])
        self.assertEqual(35, result['artflow_auxiliary_channel_count'])
        self.assertEqual([0, 38], result['decode_channel_range'])
        self.assertEqual([3, 41], result['encode_slot_range'])
        self.assertEqual(25, result['instruction_anchors'])
        self.assertEqual(39, result['key_return_anchors'])
        self.assertEqual(4, result['selector_anchors'])

    def test_a_changed_previously_missing_key_is_rejected(self):
        for index in (23, 24, 30, 31, 32, 33):
            with self.subTest(channel=index):
                data, classes = curve_schema_fixture()
                data[0x8000 + index * 0x40] = ord('x')
                with self.assertRaisesRegex(ValueError, 'Curve schema archive key differs'):
                    verify_curve_schema(NativeImage(bytes(data)), classes)

    def test_wrong_method_kind_missing_or_duplicate_method_is_rejected(self):
        for mode in ('instance', 'missing', 'duplicate'):
            with self.subTest(mode=mode):
                data, classes = curve_schema_fixture()
                if mode == 'instance':
                    classes[0]['kind'] = 'instance'
                elif mode == 'missing':
                    classes[0]['methods'].clear()
                else:
                    classes.append(classes[0])
                with self.assertRaisesRegex(ValueError, 'Ambiguous or absent method'):
                    verify_curve_schema(NativeImage(bytes(data)), classes)

    def test_changed_range_slot_or_null_branch_is_rejected(self):
        for at, replacement in [(0x2BE8, 0xF1009ABF), (0x4C64, 0xF100A6DF),
                                (0x2BA8, 0xF90012C0), (0x4C38, 0xD10012C2),
                                (0x4C34, 0xD503201F), (0x2BEC, 0xD503201F)]:
            with self.subTest(address=at):
                data, classes = curve_schema_fixture()
                struct.pack_into('<I', data, at, replacement)
                with self.assertRaisesRegex(ValueError, 'Curve schema instruction differs'):
                    verify_curve_schema(NativeImage(bytes(data)), classes)

    def test_changed_table_target_or_nonreturning_arm_is_rejected(self):
        data, classes = curve_schema_fixture()
        data[0x6000 + 22] = data[0x6000 + 23]
        with self.assertRaisesRegex(ValueError, 'Curve schema archive key differs'):
            verify_curve_schema(NativeImage(bytes(data)), classes)
        data, classes = curve_schema_fixture()
        struct.pack_into('<I', data, 0x1000 + 0x24 + 22 * 12 + 8, 0xD503201F)
        with self.assertRaisesRegex(ValueError, 'Curve schema key arm does not return'):
            verify_curve_schema(NativeImage(bytes(data)), classes)

    def test_object_decode_and_encode_selectors_are_required(self):
        for at in (0xA240, 0xA280):
            with self.subTest(selector_address=at):
                data, classes = curve_schema_fixture()
                data[at] = ord('x')
                with self.assertRaisesRegex(ValueError, 'Curve schema selector differs'):
                    verify_curve_schema(NativeImage(bytes(data)), classes)


if __name__ == '__main__':
    unittest.main()
