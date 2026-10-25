"""Self-authored ARM64 fixtures validate mask linkage without Capstone or native assets."""
import struct
import unittest

from ipa_native_contracts import NativeImage
from ipa_native_document_contracts import verify_mask_types, verify_mask_visibility
from test_ipa_native_contracts import BASE, fixture


def mask_fixture():
    data = fixture()
    data.extend(bytes(0xA000 - len(data)))
    struct.pack_into("<Q", data, 64, len(data))
    struct.pack_into("<Q", data, 80, len(data))

    def word(at, value):
        struct.pack_into("<I", data, at, value)

    def adrp(at, target, register):
        delta = ((target & ~4095) - (at & ~4095)) // 4096
        immediate = delta & ((1 << 21) - 1)
        word(at, 0x90000000 | ((immediate & 3) << 29) | ((immediate >> 2) << 5) | register)

    def address(at, target, register):
        adrp(at, target, register)
        word(at + 4, 0x91000000 | ((target & 4095) << 10) | (register << 5) | register)

    def text(at, value):
        encoded = value.encode() + b"\0"
        data[at:at + len(encoded)] = encoded

    def call(at, target):
        word(at, 0x94000000 | (((target - at) // 4) & 0x03FFFFFF))

    decode, encode, getter, reload, channel, helper = 0x1000, 0x2000, 0x3000, 0x4000, 0x5000, 0x6000
    hidden, build = 0x3020, 0x8000
    # Authored stores/loads, the owner type2 -> type3 branch and allocation arguments.
    instructions = {
        decode: {0x60: 0xAA1303E0, 0x68: 0xF9005280, 0x110: 0xF9005E80,
                 0x118: 0xF9405E80, 0x120: 0xF100081F, 0x124: 0x54000081,
                 0x128: 0xF9405E80, 0x12C: 0x52800062,
                 0x1C8: 0xAA1303E0, 0x1D0: 0x39009280},
        encode: {0x54: 0xAA1503E0, 0x5C: 0xAA0003E2, 0x68: 0xAA1303E0,
                 0x1D8: 0xAA1503E0, 0x1E0: 0xAA0003E2, 0x1EC: 0xAA1303E0},
        getter: {0: 0xF9405000, 4: 0xD65F03C0},
        reload: {0x68: 0xAA1303E0, 0x74: 0xF9000BE0, 0x78: 0xAA0103F5,
                 0x10C: 0x2A1503E5, 0x120: 0xF9400BE4},
        helper: {0: 0xF100241F, 4: 0x54000128, 8: 0xAA0003E8,
                 0x14: 0xF8607920, 0x20: 0xF8687921, 0x24: 0xD65F03C0},
        channel: {0: 0xF9405008, 4: 0xD1000908, 8: 0xF1001D1F, 0xC: 0x540000A8,
                  0x18: 0xF8687920, 0x1C: 0xD65F03C0, 0x20: 0x52800060, 0x24: 0xD65F03C0},
        hidden: {0: 0x39409000, 4: 0xD65F03C0},
        build: {0x6B8: 0xAA1C03E0, 0x6C4: 0xB4000440, 0x6CC: 0xAA1C03E0,
                0x6D8: 0xAA0003F6, 0x6E0: 0x36000080, 0x6EC: 0x14000018,
                0x740: 0xB4000CC2, 0x744: 0xAA1C03E0},
    }
    for start, words in instructions.items():
        for offset, value in words.items():
            word(start + offset, value)

    # Synthetic table locations and values; the verifier must follow the pointers.
    address(helper + 0xC, 0x6200, 9)
    address(helper + 0x18, 0x6280, 9)
    address(channel + 0x10, 0x6300, 9)
    struct.pack_into("<10Q", data, 0x6200, 0, 0, 0, 0, 4, 0, 0, 0, 0, 0)
    struct.pack_into("<10Q", data, 0x6280, 4, 4, 1, 1, 2, 4, 4, 4, 2, 1)
    struct.pack_into("<8Q", data, 0x6300, 0, 0, 3, 3, 3, 3, 1, 1)
    call(reload + 0x70, helper)

    for at, string_at, key in [(0x7000, 0x7200, "type"), (0x7040, 0x7240, "mask"), (0x7080, 0x7280, "hidden")]:
        struct.pack_into("<QQ", data, at + 16, BASE + string_at, len(key))
        text(string_at, key)
    for at, target, register in [(decode + 0x58, 0x7000, 2), (decode + 0xF8, 0x7040, 3),
                                 (encode + 0x60, 0x7000, 3), (decode + 0x1C0, 0x7080, 2),
                                 (encode + 0x1E4, 0x7080, 3)]:
        address(at, target, register)

    selectors = ["decodeIntegerForKey:", "decodeObjectOfClass:forKey:", "type", "setType:",
                 "encodeInteger:forKey:", "backingOfKind:size:format:channels:flatColors:label:reservation:",
                 "decodeBoolForKey:", "isHidden", "encodeBool:forKey:", "mask", "setMaskTexture:"]
    stubs = {}
    for index, selector in enumerate(selectors):
        stub, pointer, string_at = 0x7400 + index * 0x20, 0x7D00 + index * 8, 0x7600 + index * 0x80
        stubs[selector] = stub
        adrp(stub, pointer, 1)
        word(stub + 4, 0xF9400021 | ((pointer & 4095) // 8 << 10))
        struct.pack_into("<Q", data, pointer, BASE + string_at)
        text(string_at, selector)
    for at, selector in [(decode + 0x64, selectors[0]), (decode + 0x104, selectors[1]),
                         (decode + 0x11C, selectors[2]), (decode + 0x130, selectors[3]),
                         (encode + 0x58, selectors[2]), (encode + 0x6C, selectors[4]),
                         (reload + 0x6C, selectors[2]), (reload + 0x12C, selectors[5]),
                         (decode + 0x1CC, selectors[6]), (encode + 0x1DC, selectors[7]),
                         (encode + 0x1F0, selectors[8]), (build + 0x6BC, selectors[9]),
                         (build + 0x6D0, selectors[9]), (build + 0x6DC, selectors[7]),
                         (build + 0x748, selectors[10])]:
        call(at, stubs[selector])
    classes = [{"name": "ValkyrieLayer", "kind": "instance", "methods": [
        {"name": name, "address": BASE + at} for name, at in
        [("initWithCoder:", decode), ("encodeWithCoder:", encode), ("type", getter),
         ("reloadWithAllocator:", reload), ("maskChannel", channel), ("isHidden", hidden)]
    ]}, {"name": "ValkyrieComposite", "kind": "instance", "methods": [
        {"name": "buildActionsWithQuads:predictedQuads:", "address": BASE + build}
    ]}]
    return data, classes


class NativeMaskTypeContractTest(unittest.TestCase):
    def check(self, data=None, classes=None):
        if data is None:
            data, classes = mask_fixture()
        return verify_mask_types(NativeImage(bytes(data)), classes)

    def test_follows_archived_type_into_backing_and_mask_channel(self):
        report = self.check()
        self.assertEqual({"from_type": 2, "to_type": 3}, report["owner_upgrade"])
        self.assertEqual({"format": 0, "channels": 1}, report["backing_types"]["3"])
        self.assertEqual({"format": 0, "channels": 4}, report["backing_types"]["0"])
        self.assertEqual({"type2": 0, "type3": 0, "type0_default": 3}, report["mask_channels"])
        self.assertEqual(hex(BASE + 0x6000), report["backing_helper"])
        self.assertFalse(report["reference_runtime_executed"])

    def test_rejects_wrong_archive_key(self):
        data, classes = mask_fixture()
        data[0x7200:0x7204] = b"typo"
        with self.assertRaisesRegex(ValueError, "archive key"):
            self.check(data, classes)

    def test_rejects_a_type_store_that_disagrees_with_the_getter(self):
        data, classes = mask_fixture()
        struct.pack_into("<I", data, 0x1068, 0xF9005680)
        with self.assertRaisesRegex(ValueError, "dataflow anchor"):
            self.check(data, classes)

    def test_rejects_an_owner_that_upgrades_the_wrong_mask_type(self):
        for at, word in [(0x1120, 0xF100001F), (0x112C, 0x52800082), (0x1124, 0x54000080)]:
            with self.subTest(instruction=hex(at)):
                data, classes = mask_fixture()
                struct.pack_into("<I", data, at, word)
                with self.assertRaisesRegex(ValueError, "dataflow anchor"):
                    self.check(data, classes)

    def test_rejects_an_encoder_that_does_not_read_the_type(self):
        data, classes = mask_fixture()
        data[0x7700:0x7704] = b"size"
        with self.assertRaisesRegex(ValueError, "source selector"):
            self.check(data, classes)

    def test_rejects_a_missing_backing_helper_call(self):
        data, classes = mask_fixture()
        struct.pack_into("<I", data, 0x4070, 0xD503201F)
        with self.assertRaisesRegex(ValueError, "direct call"):
            self.check(data, classes)

    def test_rejects_wrong_backing_channels_format_or_argument(self):
        for at, fmt, value in [(0x6280 + 3 * 8, "<Q", 4), (0x6200 + 3 * 8, "<Q", 1),
                               (0x410C, "<I", 0x2A1403E5)]:
            with self.subTest(location=hex(at)):
                data, classes = mask_fixture()
                struct.pack_into(fmt, data, at, value)
                with self.assertRaises(ValueError):
                    self.check(data, classes)

    def test_rejects_an_alpha_channel_mask_or_a_changed_default(self):
        for at, fmt, value in [(0x6308, "<Q", 3), (0x5020, "<I", 0x52800000)]:
            with self.subTest(location=hex(at)):
                data, classes = mask_fixture()
                struct.pack_into(fmt, data, at, value)
                with self.assertRaises(ValueError):
                    self.check(data, classes)

    def test_rejects_wrong_method_ownership(self):
        data, classes = mask_fixture()
        classes[0]["name"] = "UnrelatedLayer"
        with self.assertRaises(ValueError):
            self.check(data, classes)


class NativeMaskVisibilityContractTest(unittest.TestCase):
    def check(self, data=None, classes=None):
        if data is None:
            data, classes = mask_fixture()
        return verify_mask_visibility(NativeImage(bytes(data)), classes)

    def test_hidden_archive_flag_reaches_the_mask_texture_gate(self):
        report = self.check()
        self.assertEqual("hidden", report["archive_key"])
        self.assertTrue(report["hidden_true_disables_attached_mask"])
        self.assertEqual(hex(BASE + 0x874C), report["hidden_bypass_target"])
        self.assertFalse(report["reference_runtime_executed"])

    def test_rejects_visibility_key_or_storage_substitution(self):
        for kind in ("key", "store", "getter"):
            with self.subTest(kind=kind):
                data, classes = mask_fixture()
                if kind == "key":
                    data[0x7280:0x7286] = b"locked"
                else:
                    struct.pack_into("<I", data, 0x11D0 if kind == "store" else 0x3020,
                                     0x39009680 if kind == "store" else 0x39409400)
                with self.assertRaises(ValueError):
                    self.check(data, classes)

    def test_rejects_inverted_gate_or_hidden_fallthrough(self):
        for at, value in [(0x86E0, 0x37000080), (0x86EC, 0x14000001)]:
            with self.subTest(instruction=hex(at)):
                data, classes = mask_fixture()
                struct.pack_into("<I", data, at, value)
                with self.assertRaisesRegex(ValueError, "visibility dataflow"):
                    self.check(data, classes)

    def test_rejects_an_unrelated_mask_binding_selector(self):
        data, classes = mask_fixture()
        data[0x7B00:0x7B0F] = b"setBaseTexture:"
        with self.assertRaisesRegex(ValueError, "visibility selector"):
            self.check(data, classes)


if __name__ == "__main__":
    unittest.main()
