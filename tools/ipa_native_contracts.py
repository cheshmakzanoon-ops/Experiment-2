#!/usr/bin/env python3
"""Verify narrow native contracts in the user-selected Procreate 5.4.4 IPA.

Read-only, standard-library-only analysis. Never loads target code, extracts artwork,
or emits native instruction bodies. This deliberately supports only little-endian
ARM64 Mach-O, chained pointer format 6, and ordinary/relative Objective-C methods.
Unexpected formats fail closed rather than silently manufacturing evidence.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile

from ipa_inventory import EXPECTED_SHA, digest

EXECUTABLE = 'Payload/Procreate.app/Procreate'
MAX_BINARY = 64 * 1024 * 1024
MAX_POINTERS = 500_000


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def signed(value: int, bits: int) -> int:
    return value - (1 << bits) if value & (1 << (bits - 1)) else value


class NativeImage:
    """A bounded file-backed view; external binds are never dereferenced."""

    def __init__(self, data: bytes):
        require(32 <= len(data) <= MAX_BINARY, 'Native binary outside size budget')
        self.data = data
        magic, cpu, _, _, count, command_bytes, _, _ = self.raw('<8I', 0)
        require(magic == 0xFEEDFACF and cpu == 0x0100000C, 'Expected ARM64 Mach-O')
        require(count <= 4096 and command_bytes <= 8 * 1024 * 1024,
                'Excessive load commands')
        require(32 + command_bytes <= len(data), 'Truncated load commands')
        self.segments = []
        self.sections = {}
        self.pointers = {}
        self.encrypted = False
        fixups = None
        cursor = 32
        for _ in range(count):
            command, size = self.raw('<II', cursor)
            require(size >= 8 and cursor + size <= 32 + command_bytes,
                    'Malformed load command')
            if command == 0x19:
                require(size >= 72, 'Truncated segment')
                name = self.raw('16s', cursor + 8)[0].split(b'\0')[0].decode('ascii')
                va, vsize, file_offset, file_size = self.raw('<4Q', cursor + 24)
                nsects = self.raw('<I', cursor + 64)[0]
                require(nsects <= 4096 and 72 + nsects * 80 <= size,
                        'Truncated section table')
                require(file_offset + file_size <= len(data), 'Segment outside file')
                self.segments.append((name, va, vsize, file_offset, file_size))
                for index in range(nsects):
                    at = cursor + 72 + index * 80
                    section = self.raw('16s', at)[0].split(b'\0')[0].decode('ascii')
                    addr, amount = self.raw('<QQ', at + 32)
                    key = (name, section)
                    require(key not in self.sections, 'Duplicate section')
                    require(va <= addr and addr + amount <= va + vsize,
                            'Section outside segment')
                    self.sections[key] = (addr, amount)
            elif command == 0x80000034:
                require(size >= 16 and fixups is None, 'Invalid fixup command')
                fixups = self.raw('<II', cursor + 8)
            elif command == 0x2C:
                require(size >= 24, 'Truncated encryption command')
                self.encrypted |= self.raw('<I', cursor + 16)[0] != 0
            cursor += size
        require(cursor == 32 + command_bytes, 'Load command size mismatch')
        bases = [va for _, va, _, off, amount in self.segments if off == 0 and amount]
        require(len(bases) == 1, 'Ambiguous image base')
        self.base = bases[0]
        require(not self.encrypted, 'Encrypted native code cannot prove these contracts')
        if fixups:
            self._fixups(*fixups)

    def raw(self, fmt: str, at: int):
        size = struct.calcsize(fmt)
        require(0 <= at <= len(self.data) - size, 'Read outside file')
        return struct.unpack_from(fmt, self.data, at)

    def offset(self, address: int, size: int = 1) -> int:
        require(size >= 0, 'Negative read length')
        matches = [off + address - va for _, va, _, off, amount in self.segments
                   if va <= address and address + size <= va + amount]
        require(len(matches) == 1, 'Address not uniquely file-backed')
        return matches[0]

    def read(self, fmt: str, address: int):
        return self.raw(fmt, self.offset(address, struct.calcsize(fmt)))

    def word(self, address: int) -> int:
        return self.read('<I', address)[0]

    def pointer(self, address: int) -> int:
        if address in self.pointers:
            value = self.pointers[address]
            require(value is not None, 'External bind is not a local pointer')
            return value
        return self.read('<Q', address)[0]

    def string(self, address: int) -> str:
        at = self.offset(address)
        end = self.data.find(b'\0', at, min(at + 2048, len(self.data)))
        require(end >= at, 'Unterminated string')
        self.offset(address, end - at + 1)
        return self.data[at:end].decode('utf-8', errors='strict')

    def _fixups(self, at: int, amount: int) -> None:
        require(28 <= amount <= 4 * 1024 * 1024 and at + amount <= len(self.data),
                'Invalid fixup payload')
        version, starts, _, _, _, _, _ = self.raw('<7I', at)
        require(version == 0 and 28 <= starts <= amount - 4, 'Unsupported fixup header')
        start = at + starts
        count = self.raw('<I', start)[0]
        require(count == len(self.segments) and count <= 32, 'Fixup segment count mismatch')
        require(start + 4 + count * 4 <= at + amount, 'Truncated segment starts')
        for index in range(count):
            relative = self.raw('<I', start + 4 + index * 4)[0]
            if not relative:
                continue
            info = start + relative
            require(start + 4 + count * 4 <= info <= at + amount - 22,
                    'Segment starts outside fixup payload')
            size, page_size, form, segment_offset, _, pages = self.raw('<IHHQIH', info)
            require(form == 6, 'Unsupported chained pointer format')
            require(page_size in (4096, 16384), 'Unsupported fixup page size')
            require(22 + pages * 2 <= size and info + size <= at + amount,
                    'Truncated page starts')
            segment = self.segments[index]
            require(segment_offset == segment[1] - self.base, 'Fixup segment offset mismatch')
            for page in range(pages):
                first = self.raw('<H', info + 22 + page * 2)[0]
                if first == 0xFFFF:
                    continue
                require(first & 0x8000 == 0, 'Multiple-start pages are unsupported')
                require(first < page_size, 'Pointer starts outside page')
                page_base = segment[1] + page * page_size
                address = page_base + first
                while True:
                    require(address not in self.pointers, 'Repeated chained pointer')
                    require(len(self.pointers) < MAX_POINTERS, 'Excessive chained pointers')
                    require(page_base <= address and address + 8 <= page_base + page_size,
                            'Chained pointer crosses page boundary')
                    raw = self.read('<Q', address)[0]
                    step = (raw >> 51) & 0xFFF
                    if raw >> 63:
                        self.pointers[address] = None
                    else:
                        require((raw >> 44) & 0x7F == 0, 'Reserved rebase bits set')
                        target = (raw & ((1 << 36) - 1)) | (((raw >> 36) & 0xFF) << 56)
                        self.pointers[address] = self.base + target
                    if step == 0:
                        break
                    address += step * 4

    def method_list(self, address: int) -> list[dict]:
        if not address:
            return []
        flags, count = self.read('<II', address)
        size = flags & 0xFFFF
        small = bool(flags & 0x80000000)
        direct = bool(flags & 0x40000000)
        require(count <= 10_000 and size == (12 if small else 24), 'Unsupported method list')
        self.offset(address, 8 + count * size)
        result = []
        for index in range(count):
            entry = address + 8 + size * index
            if small:
                name_rel, types_rel, imp_rel = self.read('<iii', entry)
                name = entry + name_rel
                name = name if direct else self.pointer(name)
                types = entry + 4 + types_rel
                imp = entry + 8 + imp_rel
            else:
                name, types, imp = (self.pointer(entry + field * 8) for field in range(3))
            self.string(types)
            self.offset(imp, 4)
            result.append({'name': self.string(name), 'address': imp})
        return result

    def classes(self) -> list[dict]:
        output = []
        for (_, section), (address, size) in self.sections.items():
            if section != '__objc_classlist':
                continue
            require(size % 8 == 0 and size <= 100_000, 'Invalid class list')
            for index in range(size // 8):
                ordinary = self.pointer(address + 8 * index)
                for kind, cls in [('instance', ordinary), ('class', self.pointer(ordinary))]:
                    ro = self.pointer(cls + 32) & ~7
                    output.append({'name': self.string(self.pointer(ro + 24)), 'kind': kind,
                                   'methods': self.method_list(self.pointer(ro + 32))})
        require(bool(output), 'No Objective-C classes found')
        return output

    def adrp(self, address: int, register: int) -> int:
        word = self.word(address)
        require(word & 0x9F00001F == 0x90000000 | register, 'Expected ADRP instruction')
        immediate = ((word >> 29) & 3) | (((word >> 5) & 0x7FFFF) << 2)
        return (address & ~4095) + (signed(immediate, 21) << 12)

    def address_pair(self, address: int, register: int) -> int:
        page = self.adrp(address, register)
        word = self.word(address + 4)
        require(word & 0xFFC003FF == 0x91000000 | register << 5 | register,
                'Expected unshifted ADD immediate')
        return page + ((word >> 10) & 0xFFF)

    def cfstring_pair(self, address: int, register: int = 2) -> str:
        cf = self.address_pair(address, register)
        value = self.string(self.pointer(cf + 16))
        require(len(value.encode('utf-8')) == self.read('<Q', cf + 24)[0],
                'CFString length mismatch')
        return value

    def called_selector(self, address: int) -> str:
        word = self.word(address)
        require(word & 0xFC000000 == 0x94000000, 'Expected direct BL instruction')
        target = address + (signed(word & 0x3FFFFFF, 26) << 2)
        page = self.adrp(target, 1)
        load = self.word(target + 4)
        require(load & 0xFFC003FF == 0xF9400021, 'Expected selector stub pointer load')
        return self.string(self.pointer(page + ((load >> 10) & 0xFFF) * 8))

    def float_ivar(self, address: int, *, store: bool, base: int) -> int:
        word = self.word(address)
        opcode = 0xBD000000 if store else 0xBD400000
        require(word & 0xFFC003FF == opcode | base << 5, 'Expected scalar float ivar access')
        return ((word >> 10) & 0xFFF) * 4


# Instruction anchors are deliberately pinned to the source hash, not portability claims.
# Each row validates CFString -> decodeFloatForKey: -> float store -> named getter.
SCALARS = [
    ('dynamicsPressureOpacity', 'dynamicsPressureFlow', 0xA3FF3C, 0xA3FF48, 0xA3FF4C),
    ('dynamicsPressureOpacityTransfer', 'dynamicsPressureOpacity', 0xA40894, 0xA408A0, 0xA408A4),
    ('dynamicsMix', 'dynamicsDilution', 0xA40480, 0xA4048C, 0xA40490),
    ('dynamicsMixSoftening', 'dynamicsGrade', 0xA40494, 0xA404A0, 0xA404A4),
    ('dynamicsLoad', 'dynamicsLoad', 0xA404A8, 0xA404B4, 0xA404B8),
    ('dynamicsPressureMix', 'dynamicsAttack', 0xA40044, 0xA40050, 0xA40054),
    ('dynamicsPressureBleed', 'dynamicsPressureBleed', 0xA40058, 0xA40064, 0xA4006C),
    ('dynamicsWetAccumulation', 'dynamicsWetPull', 0xA40938, 0xA40944, 0xA40950),
    ('dynamicsBlur', 'dynamicsBlur', 0xA408BC, 0xA408C8, 0xA408CC),
    ('dynamicsBlurJitter', 'dynamicsBlurJitter', 0xA408D0, 0xA408DC, 0xA408E0),
    ('shapeCount', 'shapeCount', 0xA3F9FC, 0xA3FA08, 0xA3FA0C),
    ('shapeCountJitter', 'shapeCountJitter', 0xA3FA24, 0xA3FA30, 0xA3FA34),
]


def find_method(classes: list[dict], owner: str, selector: str, kind='instance') -> int:
    found = [m['address'] for c in classes if c['name'] == owner and c['kind'] == kind
             for m in c['methods'] if m['name'] == selector]
    require(len(found) == 1, f'Ambiguous or absent method: {owner}.{selector}')
    return found[0]


def verify_scalar(image: NativeImage, classes: list[dict], row: tuple) -> dict:
    key, prop, key_pc, call_pc, store_pc = row
    key_pc, call_pc, store_pc = (image.base + value for value in (key_pc, call_pc, store_pc))
    require(image.cfstring_pair(key_pc) == key, 'Archive key differs from contract')
    require(image.called_selector(call_pc) == 'decodeFloatForKey:', 'Wrong scalar decoding call')
    getter = find_method(classes, 'ValkyrieBrush', prop)
    require(image.word(getter + 4) == 0xD65F03C0, 'Getter is not a bounded direct load')
    stored = image.float_ivar(store_pc, store=True, base=20)
    loaded = image.float_ivar(getter, store=False, base=0)
    require(stored == loaded, 'Decoded archive value and native getter use different ivars')
    return {'archive_key': key, 'native_property': prop, 'key_reference': hex(key_pc),
            'decode_call': hex(call_pc), 'store_instruction': hex(store_pc),
            'getter': hex(getter), 'ivar_offset': hex(stored)}


def verify_curve(image: NativeImage, *, index: int, key: str, bridge: int,
                 property_selector: str, index_instruction: int) -> dict:
    # The real compiled bridge selects a getter and a curve-table index together.
    selector_pointer = image.address_pair(image.base + bridge, 1)
    require(image.string(image.pointer(selector_pointer)) == property_selector,
            'Curve bridge property changed')
    require(image.word(image.base + index_instruction) == 0x52800003 | index << 5,
            'Curve bridge index changed')
    table = image.base + 0xC7E500
    target = image.base + 0xA3F298 + image.read('<B', table + index - 1)[0] * 4
    require(image.cfstring_pair(target, 0) == key, 'Serialized curve key differs')
    require(image.word(target + 8) == 0xD65F03C0, 'Curve switch arm does not return')
    return {'native_property': property_selector, 'curve_index': index, 'archive_key': key,
            'bridge': hex(image.base + bridge), 'switch_arm': hex(target)}


def verify_shape_count(image: NativeImage, classes: list[dict]) -> dict:
    """Pin Count conversion and the final bounded stamp loop, not jitter distributions."""
    getter = find_method(classes, 'ValkyrieStrokeOptions', 'shapeCount')
    plot = find_method(classes, 'ValkyrieStrokePlot', 'plotCurve:previousPlot:initialAngle:')
    # Complete arithmetic in the getter: read brush, multiply binary32 by 15,
    # truncate to an unsigned integer. Its caller later adds the mandatory first stamp.
    getter_words = {
        0x00: 0xA9BF7BFD, 0x04: 0x910003FD, 0x08: 0xF9402800,
        0x10: 0x1E25D001, 0x14: 0x1E210800, 0x18: 0x9E390000,
        0x1C: 0xA8C17BFD, 0x20: 0xD65F03C0,
    }
    # Selected caller anchors establish the integer return type, upper/lower cap,
    # truncation, +1 and bounded loop. Source hashing pins the surrounding modifier paths.
    plot_words = {
        0x898: 0xF9417E80, 0x8A0: 0x9E230000,
        0x8C0: 0xF9417E80, 0x8E0: 0x1E25D000,
        0x8E4: 0x1E202020, 0x8E8: 0x54000285,
        0x8EC: 0xF9417E80, 0x90C: 0x1E2703E0,
        0x910: 0x1E202028, 0x914: 0x5400012D,
        0x918: 0xF9417E80, 0x938: 0x1E390009,
        0x944: 0x5280001B, 0x94C: 0x1100053A,
        0x9CC: 0x91000508, 0x9D0: 0xF9018A88,
        0x9D4: 0x1100077B, 0x9D8: 0x6B1B035F, 0x9DC: 0x54FFFBA1,
    }
    for start, words in ((getter, getter_words), (plot, plot_words)):
        for offset, instruction in words.items():
            require(image.word(start + offset) == instruction,
                    f'Shape Count instruction differs at {start + offset:#x}')
    calls = [getter + 0x0C, *(plot + offset for offset in (0x89C, 0x8C4, 0x8F0, 0x91C))]
    for call in calls:
        require(image.called_selector(call) == 'shapeCount', 'Shape Count getter call changed')
    return {
        'archive_key': 'shapeCount', 'options_getter': hex(getter), 'plot_method': hex(plot),
        'instruction_anchors': len(getter_words) + len(plot_words), 'selector_anchors': len(calls),
        'normalized_scale': 15.0, 'arithmetic': 'binary32 multiplication then unsigned truncation toward zero',
        'minimum_stamps': 1, 'maximum_stamps': 16,
        'neutral_count_formula': '1 + trunc_to_uint(float32(float32(shapeCount) * 15.0))',
        'scope': 'Finite normalized controls with neutral count modifiers; plot capacity can reduce output.',
        'limitations': ['Count jitter distribution, pressure/tilt/roll modifiers and rendered pixels are not evaluated.'],
    }


def verify_spacing_version(image: NativeImage, classes: list[dict]) -> dict:
    """Pin spacing-version interchange, without claiming a complete placement model."""
    decode = find_method(classes, 'ValkyrieBrush', 'initWithCoder:')
    encode = find_method(classes, 'ValkyrieBrush', 'encodeWithCoder:')
    setter = find_method(classes, 'ValkyrieBrush', 'setPlotSpacing:')
    getter = find_method(classes, 'ValkyrieBrush', 'plotSpacingVersion')
    cache = find_method(classes, 'ValkyrieStrokePlot', 'cacheOptions:size:')
    plot = find_method(classes, 'ValkyrieStrokePlot', 'plotCurve:previousPlot:initialAngle:')
    # Decoder: explicit integer wins; otherwise invert presence of the legacy dual key.
    # Setter: version=1, then store the new spacing. Both use the getter's same ivar.
    anchors = [
        (decode, {0x75C: 0xAA1303E0, 0x764: 0x340000C0, 0x770: 0xAA1303E0,
                  0x778: 0x14000006, 0x784: 0xAA1303E0, 0x78C: 0x52000000,
                  0x790: 0xF9024E80}),
        (setter, {0x0: 0x52800028, 0x4: 0xF9024C08, 0x8: 0xBD01C000, 0xC: 0xD65F03C0}),
        (getter, {0x0: 0xF9424C00, 0x4: 0xD65F03C0}),
        (encode, {0x76C: 0xAA1303E0, 0x774: 0xAA0003E2, 0x780: 0xF94007E0}),
        (cache, {0x40: 0xAA0003F4, 0x48: 0xF100001F, 0x4C: 0x1A9F17E8, 0x50: 0x39056268}),
        # The cached version==0 bit selects 1 rather than the binary32 sqrt(2) constant.
        # Both the previous-size and candidate-size paths have the same decision.
        (plot, {0x178: 0x39456289, 0x188: 0x7100013F, 0x194: 0x1E2E1002,
                0x198: 0xBD0033E1, 0x19C: 0x1E211C41, 0x1A0: 0x1E200821,
                0x298: 0xBD4033FA, 0x2AC: 0x39456289, 0x2B0: 0x7100013F,
                0x2B4: 0x1E2E1002, 0x2B8: 0x1E3A1C40}),
    ]
    for start, words in anchors:
        for offset, word in words.items():
            require(image.word(start + offset) == word,
                    f'Spacing version instruction differs at {start + offset:#x}')
    keys = [(decode + 0x754, 2, 'plotSpacingVersion'),
            (decode + 0x768, 2, 'plotSpacingVersion'),
            (decode + 0x77C, 2, 'dualBlendMode'),
            (encode + 0x778, 3, 'plotSpacingVersion')]
    for address, register, key in keys:
        require(image.cfstring_pair(address, register) == key, 'Spacing version archive key differs')
    calls = [(decode + 0x760, 'containsValueForKey:'),
             (decode + 0x774, 'decodeIntegerForKey:'),
             (decode + 0x788, 'containsValueForKey:'),
             (encode + 0x770, 'plotSpacingVersion'),
             (encode + 0x784, 'encodeInteger:forKey:'),
             (cache + 0x44, 'plotSpacingVersion')]
    for address, selector in calls:
        require(image.called_selector(address) == selector, 'Spacing version selector differs')
    constant_page = image.adrp(plot + 0x18C, 9)
    load = image.word(plot + 0x190)
    require(load & 0xFFC003FF == 0xBD400121, 'Spacing version factor load differs')
    constant = constant_page + ((load >> 10) & 0xFFF) * 4
    require(image.word(constant) == 0x3FB504F3, 'Spacing version sqrt-two constant differs')
    return {
        'archive_key': 'plotSpacingVersion', 'decode_method': hex(decode),
        'encode_method': hex(encode), 'spacing_setter': hex(setter), 'version_getter': hex(getter),
        'cache_method': hex(cache), 'plot_method': hex(plot), 'version_ivar_offset': '0x498',
        'instruction_anchors': sum(len(words) for _, words in anchors) + 2,
        'selector_anchors': len(calls), 'archive_key_anchors': len(keys),
        'explicit_version': 'decodeIntegerForKey: when the key is present',
        'implicit_version': '0 if legacy dualBlendMode is present, otherwise 1; extended-only does not select 0',
        'spacing_edit_version': 1,
        'size_factor': {'version_zero': 1.0, 'other_versions_float_bits': '3fb504f3',
                        'constant_address': hex(constant)},
        'scope': 'Archive version selection, setter/encoder linkage and two cached size-factor decisions.',
        'limitations': ['Native pressure preprocessing, placement, random sequence and rendered pixels are not evaluated.'],
    }


def contracts(image: NativeImage) -> dict:
    classes = image.classes()
    scalars = [verify_scalar(image, classes, row) for row in SCALARS]
    curves = [
        verify_curve(image, index=3, key='dynamicsPressureOpacityCurve', bridge=0x159080,
                     property_selector='dynamicsPressureFlow', index_instruction=0x159090),
        verify_curve(image, index=10, key='dynamicsPressureTransferModulationCurve', bridge=0x1590B0,
                     property_selector='dynamicsPressureOpacity', index_instruction=0x1590C0),
    ]
    selected = {
        'SiPSDDataSourceLayer': ['maskVisible', 'maskRect', 'maskdata'],
        'SiPSDRecordLayer': ['record', 'dataForMask', 'channelCount', 'maskChannelLength'],
        'ValkyrieComposite': ['onionSkinPrimaryMixed', 'onionSkinPreviousFrameColor', 'onionSkinNextFrameColor'],
        'ValkyrieDocument': ['animationItemsForBackground', 'animationItemsForForeground',
                            'visibleAnimationItemsForBackground', 'visibleAnimationItemsForForeground'],
        'ValkyrieGroup': ['animationHeldLength', 'isAnimationBackground'],
        'ValkyrieLayer': ['animationHeldLength', 'isAnimationBackground'],
    }
    declarations = {owner: {name: hex(find_method(classes, owner, name)) for name in names}
                    for owner, names in selected.items()}
    mask_calls = {0xB71D34: 'maskChannelLength', 0xB720C4: 'maskVisible',
                  0xB72788: 'maskdata', 0xB72800: 'compactChannel:data:forRect:channels:invertValues:',
                  0xB72818: 'setMaskChannelLength:'}
    for at, selector in mask_calls.items():
        require(image.called_selector(image.base + at) == selector, 'PSD mask call changed')
    # MOV W8,#0xfeff; STRH W8,[X0] produces bytes FF FE, PSD channel id -2.
    require(image.word(image.base + 0xB71D28) == 0x529FDFE8, 'PSD user-mask id changed')
    require(image.word(image.base + 0xB71D2C) == 0x79000008, 'PSD user-mask id store changed')
    require(image.word(image.base + 0xB727F8) == 0x52800024, 'PSD mask is not single-channel')
    require(image.word(image.base + 0xB727FC) == 0x52800005, 'PSD mask inversion argument changed')
    return {
        'scope': 'Static archive-key/property correspondences, Count/spacing-version contracts and selected PSD/animation declarations',
        'architecture': 'arm64', 'encrypted': image.encrypted,
        'rebased_pointers': sum(value is not None for value in image.pointers.values()),
        'external_bind_pointers': sum(value is None for value in image.pointers.values()),
        'class_count': len(classes) // 2, 'method_count': sum(len(c['methods']) for c in classes),
        'brush_scalar_contracts': scalars, 'brush_curve_contracts': curves,
        'brush_count_contract': verify_shape_count(image, classes),
        'brush_spacing_version_contract': verify_spacing_version(image, classes),
        'selected_native_methods': declarations,
        'psd_mask_contract': {'channel_id': -2, 'channel_count_argument': 1, 'invert_argument': False,
                             'calls': {hex(image.base + at): name for at, name in mask_calls.items()}},
        'limitations': [
            'No target code was executed. Declared methods are not runtime observations.',
            'Complete brush rendering, GPU shading, brush feel and pixel parity are outside this inspector.',
            'Curve bridge/key correspondence does not establish the original curve interpolation algorithm.',
            'PSD independent-mask semantics are verified narrowly; complete PSD interoperability is not established.',
            'Only the pinned ARM64 build and supported metadata encodings are accepted.',
        ],
    }


def inspect(path: Path) -> dict:
    source_sha = digest(path)
    require(source_sha == EXPECTED_SHA, 'Source SHA-256 differs from pinned reference')
    with zipfile.ZipFile(path) as archive:
        matches = [entry for entry in archive.infolist() if entry.filename == EXECUTABLE]
        require(len(matches) == 1, 'Missing or duplicate main executable')
        entry = matches[0]
        require(not entry.flag_bits & 1 and 32 <= entry.file_size <= MAX_BINARY,
                'Encrypted ZIP member or executable exceeds budget')
        with archive.open(entry) as stream:
            binary = stream.read(MAX_BINARY + 1)
        require(len(binary) == entry.file_size, 'Native member size differs from declaration')
    report = contracts(NativeImage(binary))
    return {'source_sha256': source_sha, 'source_bytes': path.stat().st_size,
            'executable_path': EXECUTABLE, 'executable_sha256': hashlib.sha256(binary).hexdigest(),
            'executable_bytes': len(binary), **report}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('ipa', type=Path)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    report = inspect(args.ipa)
    args.out.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
    print(json.dumps({'source_sha256': report['source_sha256'],
                      'scalar_contracts': len(report['brush_scalar_contracts']),
                      'curve_contracts': len(report['brush_curve_contracts']),
                      'count_limit': report['brush_count_contract']['maximum_stamps'],
                      'count_rounding': 'toward_zero',
                      'spacing_edit_version': report['brush_spacing_version_contract']['spacing_edit_version'],
                      'methods': report['method_count']}))


if __name__ == '__main__':
    main()
