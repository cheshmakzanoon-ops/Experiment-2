#!/usr/bin/env python3
"""Verify selected native 2D document archive fields; never execute reference code.

Only a bounded derived JSON report is emitted. This is not an exported-file acceptance
test in Procreate, an ICC engine proof, or a complete document-format specification.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import zipfile

from ipa_inventory import EXPECTED_SHA, digest
from ipa_native_contracts import NativeImage, find_method, require, signed

EXECUTABLE = "Payload/Procreate.app/Procreate"
EXECUTABLE_BYTES = 19119424
EXECUTABLE_SHA = "76d3ff1249a987b8f03ac3ecc64c54e48f618bac467e9fbb1f238b91fab1487b"


def verify_mask_types(image: NativeImage, classes: list[dict]) -> dict:
    """Follow the archived type through the mask owner, backing allocation and channel.

    Relative instruction anchors permit independently authored synthetic fixtures without
    Capstone or the IPA. The entry point additionally pins the real executable and methods.
    No reference code, graphics API or exported document is executed here.
    """
    names = ["initWithCoder:", "encodeWithCoder:", "type", "reloadWithAllocator:", "maskChannel"]
    decode, encode, getter, reload, channel = [find_method(classes, "ValkyrieLayer", name) for name in names]
    fields = [(decode + 0x58, 2, "type"), (decode + 0xF8, 3, "mask"), (encode + 0x60, 3, "type")]
    for pc, register, key in fields:
        require(image.cfstring_pair(pc, register) == key, "Native mask archive key differs")
    calls = {
        decode + 0x64: "decodeIntegerForKey:",
        decode + 0x104: "decodeObjectOfClass:forKey:",
        decode + 0x11C: "type",
        decode + 0x130: "setType:",
        encode + 0x58: "type",
        encode + 0x6C: "encodeInteger:forKey:",
        reload + 0x6C: "type",
        reload + 0x12C: "backingOfKind:size:format:channels:flatColors:label:reservation:",
    }
    for pc, selector in calls.items():
        require(image.called_selector(pc) == selector, "Native mask source selector differs")

    call = reload + 0x70
    branch = image.word(call)
    require(branch & 0xFC000000 == 0x94000000, "Native backing helper is not a direct call")
    helper = call + signed(branch & 0x3FFFFFF, 26) * 4
    # The decoded type, getter and mask-channel load all use the same self+0xa0 field.
    # The owner upgrades only mask type2 to3; the unequal branch skips the setter.
    anchors = {
        decode: {0x60: 0xAA1303E0, 0x68: 0xF9005280, 0x110: 0xF9005E80,
                 0x118: 0xF9405E80, 0x120: 0xF100081F, 0x124: 0x54000081,
                 0x128: 0xF9405E80, 0x12C: 0x52800062},
        encode: {0x54: 0xAA1503E0, 0x5C: 0xAA0003E2, 0x68: 0xAA1303E0},
        getter: {0: 0xF9405000, 4: 0xD65F03C0},
        reload: {0x68: 0xAA1303E0, 0x74: 0xF9000BE0, 0x78: 0xAA0103F5,
                 0x10C: 0x2A1503E5, 0x120: 0xF9400BE4},
        helper: {0: 0xF100241F, 4: 0x54000128, 8: 0xAA0003E8,
                 0x14: 0xF8607920, 0x20: 0xF8687921, 0x24: 0xD65F03C0},
        channel: {0: 0xF9405008, 4: 0xD1000908, 8: 0xF1001D1F, 0xC: 0x540000A8,
                  0x18: 0xF8687920, 0x1C: 0xD65F03C0, 0x20: 0x52800060, 0x24: 0xD65F03C0},
    }
    for start, words in anchors.items():
        for offset, expected in words.items():
            require(image.word(start + offset) == expected, "Native mask dataflow anchor differs")

    format_table = image.address_pair(helper + 0xC, 9)
    channels_table = image.address_pair(helper + 0x18, 9)
    mask_table = image.address_pair(channel + 0x10, 9)
    formats = image.read("<10Q", format_table)
    channels = image.read("<10Q", channels_table)
    mask_channels = image.read("<8Q", mask_table)
    for layer_type, expected_channels in [(0, 4), (2, 1), (3, 1)]:
        require(formats[layer_type] == 0 and channels[layer_type] == expected_channels,
                "Native mask backing format or channel count differs")
    require(mask_channels[:2] == (0, 0), "Native single-channel mask selector differs")
    return {
        "methods": [{"owner": "ValkyrieLayer", "selector": name, "address": hex(pc)}
                    for name, pc in zip(names, [decode, encode, getter, reload, channel])],
        "archive_keys": [key for _, _, key in fields],
        "owner_upgrade": {"from_type": 2, "to_type": 3},
        "backing_helper": hex(helper),
        "format_table": hex(format_table),
        "channels_table": hex(channels_table),
        "mask_channel_table": hex(mask_table),
        "backing_types": {str(t): {"format": formats[t], "channels": channels[t]} for t in (0, 2, 3)},
        "mask_channels": {"type2": mask_channels[0], "type3": mask_channels[1], "type0_default": 3},
        "verified_instruction_anchors": 1 + sum(len(words) for words in anchors.values()),
        "verified_source_selectors": len(calls),
        "reference_runtime_executed": False,
        "limits": [
            "Selected ordinary native mask paths only; no generic layer-type or old-version specification.",
            "Backing channel counts do not execute tile decompression, the compositor or Procreate file opening.",
        ],
    }


def verify_mask_visibility(image: NativeImage, classes: list[dict]) -> dict:
    """Verify the ordinary action builder omits a hidden attached mask's texture."""
    decode = find_method(classes, "ValkyrieLayer", "initWithCoder:")
    encode = find_method(classes, "ValkyrieLayer", "encodeWithCoder:")
    getter = find_method(classes, "ValkyrieLayer", "isHidden")
    build = find_method(classes, "ValkyrieComposite", "buildActionsWithQuads:predictedQuads:")
    for pc, register in [(decode + 0x1C0, 2), (encode + 0x1E4, 3)]:
        require(image.cfstring_pair(pc, register) == "hidden", "Native mask visibility archive key differs")
    calls = {
        decode + 0x1CC: "decodeBoolForKey:",
        encode + 0x1DC: "isHidden",
        encode + 0x1F0: "encodeBool:forKey:",
        build + 0x6BC: "mask",
        build + 0x6D0: "mask",
        build + 0x6DC: "isHidden",
        build + 0x748: "setMaskTexture:",
    }
    for pc, selector in calls.items():
        require(image.called_selector(pc) == selector, "Native mask visibility selector differs")
    # Both archive storage and isHidden use self+0x24. The action's mask is the
    # receiver of isHidden; true bypasses the binding and its loaded-backing fallback.
    anchors = {
        decode: {0x1C8: 0xAA1303E0, 0x1D0: 0x39009280},
        encode: {0x1D8: 0xAA1503E0, 0x1E0: 0xAA0003E2, 0x1EC: 0xAA1303E0},
        getter: {0: 0x39409000, 4: 0xD65F03C0},
        build: {0x6B8: 0xAA1C03E0, 0x6C4: 0xB4000440, 0x6CC: 0xAA1C03E0,
                0x6D8: 0xAA0003F6, 0x6E0: 0x36000080, 0x6EC: 0x14000018,
                0x740: 0xB4000CC2, 0x744: 0xAA1C03E0},
    }
    for start, words in anchors.items():
        for offset, expected in words.items():
            require(image.word(start + offset) == expected, "Native mask visibility dataflow differs")
    return {
        "archive_key": "hidden",
        "hidden_true_disables_attached_mask": True,
        "hidden_getter": hex(getter),
        "action_builder": hex(build),
        "mask_hidden_call": hex(build + 0x6DC),
        "hidden_bypass_target": hex(build + 0x74C),
        "verified_instruction_anchors": sum(len(words) for words in anchors.values()),
        "verified_source_selectors": len(calls),
        "reference_runtime_executed": False,
        "limits": [
            "The ordinary action-builder gate only; visible masks still obey other existing selection/effect gates.",
            "No native application execution, legacy-version acceptance or exported-file opening is established.",
        ],
    }


def verify_native(binary: bytes) -> dict:
    import capstone
    require(capstone.__version__ == "5.0.6", "Use capstone==5.0.6")
    require(len(binary) == EXECUTABLE_BYTES and hashlib.sha256(binary).hexdigest() == EXECUTABLE_SHA,
            "Wrong native executable")
    image = NativeImage(binary)
    classes = image.classes()
    methods = [
        ("ValkyrieDocument", "encodeWithCoder:", 0x100b1a144),
        ("ValkyrieDocument", "unwrappedLayers", 0x100b24d30),
        ("ValkyrieDocument", "flattenedLayers", 0x100b1425c),
        ("ValkyrieDocument", "colorProfile", 0x100b24f2c),
        ("ValkyrieColorProfile", "encodeWithCoder:", 0x100b0e588),
    ]
    for owner, selector, address in methods:
        require(find_method(classes, owner, selector) == address, "Native method identity differs")
    mask_methods = [
        ("ValkyrieLayer", "initWithCoder:", 0x100b2c274),
        ("ValkyrieLayer", "encodeWithCoder:", 0x100b2c894),
        ("ValkyrieLayer", "type", 0x100b35268),
        ("ValkyrieLayer", "reloadWithAllocator:", 0x100b2fd40),
        ("ValkyrieLayer", "maskChannel", 0x100b2bca4),
        ("ValkyrieLayer", "isHidden", 0x100b352a0),
        ("ValkyrieComposite", "buildActionsWithQuads:predictedQuads:", 0x100a58ca4),
    ]
    for owner, selector, address in mask_methods:
        require(find_method(classes, owner, selector) == address, "Native mask method identity differs")
    mask_types = verify_mask_types(image, classes)
    mask_visibility = verify_mask_visibility(image, classes)
    fields = [
        (0x100b1a43c, 3, "unwrappedLayers", 0x100b1a448, "encodeObject:forKey:"),
        (0x100b1a50c, 3, "layers", 0x100b1a51c, "encodeObject:forKey:"),
        (0x100b1a678, 3, "colorProfile", 0x100b1a684, "encodeObject:forKey:"),
        (0x100b0e5d4, 4, "SiColorProfileArchiveICCDataKey", 0x100b0e5e4, "encodeBytes:length:forKey:"),
        (0x100b0e604, 3, "SiColorProfileArchiveICCNameKey", 0x100b0e614, "encodeObject:forKey:"),
    ]
    for pc, register, name, call, selector in fields:
        require(image.cfstring_pair(pc, register) == name, "Native archive key differs")
        require(image.called_selector(call) == selector, "Native field encoder differs")
    selectors = {
        0x100b1a4c8: "flattenedLayers",
        0x100b0e5c0: "bytes",
        0x100b0e5cc: "length",
        0x100b0e5ec: "meta",
        0x100b0e5f8: "name",
    }
    for pc, selector in selectors.items():
        require(image.called_selector(pc) == selector, "Native source selector differs")
    anchors = {
        0x100b24d30: ("ldr", "x0, [x0, #0x120]"),
        0x100b24d34: ("ret", ""),
        0x100b1a438: ("ldr", "x2, [x19, #0x120]"),
        0x100b1a444: ("mov", "x0, x20"),
        0x100b1a4c4: ("mov", "x0, x19"),
        0x100b1a4d0: ("mov", "x21, x0"),
        0x100b1a514: ("mov", "x0, x20"),
        0x100b1a518: ("mov", "x2, x21"),
        0x100b24f2c: ("ldr", "x0, [x0, #0x1d0]"),
        0x100b24f30: ("ret", ""),
        0x100b1a674: ("ldr", "x2, [x19, #0x1d0]"),
        0x100b1a680: ("mov", "x0, x20"),
        0x100b0e5c4: ("mov", "x20, x0"),
        0x100b0e5c8: ("ldr", "x0, [sp, #8]"),
        0x100b0e5d0: ("mov", "x3, x0"),
        0x100b0e5dc: ("mov", "x0, x21"),
        0x100b0e5e0: ("mov", "x2, x20"),
        0x100b0e600: ("mov", "x20, x0"),
        0x100b0e60c: ("mov", "x0, x21"),
        0x100b0e610: ("mov", "x2, x20"),
    }
    decoder = capstone.Cs(capstone.CS_ARCH_ARM64, capstone.CS_MODE_LITTLE_ENDIAN)
    for pc, expected in anchors.items():
        offset = image.offset(pc, 4)
        instruction = next(decoder.disasm(binary[offset:offset + 4], pc))
        require((instruction.mnemonic, instruction.op_str) == expected, "Native dataflow anchor differs")
    return {
        "source_sha256": EXPECTED_SHA,
        "executable_sha256": EXECUTABLE_SHA,
        "methods": [{"owner": owner, "selector": selector, "address": hex(pc)} for owner, selector, pc in methods],
        "archive_fields": [{"key": name, "key_address": hex(pc), "encoder": selector, "call_address": hex(call)}
                           for pc, _, name, call, selector in fields],
        "verified_instruction_anchors": (len(anchors) + mask_types["verified_instruction_anchors"]
                                         + mask_visibility["verified_instruction_anchors"]),
        "verified_source_selectors": (len(selectors) + mask_types["verified_source_selectors"]
                                      + mask_visibility["verified_source_selectors"]),
        "tree_field": "unwrappedLayers",
        "flat_pixel_index_field": "layers",
        "profile_field": "colorProfile",
        "profile_icc_field": "SiColorProfileArchiveICCDataKey",
        "mask_type_contract": mask_types,
        "mask_visibility_contract": mask_visibility,
        "reference_runtime_executed": False,
        "export_opened_in_reference_app": False,
        "limits": [
            "Selected 2D encoding branch only; not a complete archive specification.",
            "Flat leaf identity/order and group isHidden are additionally checked against the pinned genuine archives.",
            "ICC bytes originate from the profile object; this does not execute the native ICC engine.",
            "No native app, GPU shader, group blend/mask, animation, text or 3D export acceptance is implied.",
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("ipa", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    require(args.ipa.stat().st_size == 777430382 and digest(args.ipa) == EXPECTED_SHA, "Wrong reference IPA")
    with zipfile.ZipFile(args.ipa) as archive:
        require(archive.getinfo(EXECUTABLE).file_size == EXECUTABLE_BYTES, "Wrong executable size")
        report = verify_native(archive.read(EXECUTABLE))
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": "pass", "fields": len(report["archive_fields"]),
                      "instruction_anchors": report["verified_instruction_anchors"],
                      "reference_runtime_executed": False}))


if __name__ == "__main__":
    main()
