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
from ipa_native_contracts import NativeImage, find_method, require

EXECUTABLE = "Payload/Procreate.app/Procreate"
EXECUTABLE_BYTES = 19119424
EXECUTABLE_SHA = "76d3ff1249a987b8f03ac3ecc64c54e48f618bac467e9fbb1f238b91fab1487b"


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
        "verified_instruction_anchors": len(anchors),
        "verified_source_selectors": len(selectors),
        "tree_field": "unwrappedLayers",
        "flat_pixel_index_field": "layers",
        "profile_field": "colorProfile",
        "profile_icc_field": "SiColorProfileArchiveICCDataKey",
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
