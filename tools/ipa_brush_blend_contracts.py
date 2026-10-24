#!/usr/bin/env python3
"""Verify bounded, static compound-brush contracts in the pinned reference IPA.

Requires capstone==5.0.6 and llvmlite==0.44.0. Reads only two ZIP members; never
loads/executes reference code, creates a JIT, or writes binary/IR assets. The
numeric examples are analyst-derived real-valued cases, not reference captures
or an emulation of Metal's half/float arithmetic and fast-math behavior.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile

from ipa_inventory import EXPECTED_SHA, digest
from ipa_native_contracts import NativeImage, find_method, require

EXECUTABLE = 'Payload/Procreate.app/Procreate'
LIBRARY = 'Payload/Procreate.app/ValkyrieMetal.metallib'
BINARY_SHA = '76d3ff1249a987b8f03ac3ecc64c54e48f618bac467e9fbb1f238b91fab1487b'
LIBRARY_SHA = '30ed6b20d7592fe45b4b94c4a2e7e820a1871f705e32ab280fcd0bde7709c6de'
SOURCE_TO_GPU = (11, 12, 13, 14, 15, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30,
                 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 16,
                 17, 18, 19, 20, 47)
SCALAR_MODES = (0, 1, 2, 3, 4, 6, 7, 8, 9, 10, 11, 19, 20, 26, 28, 34)
MODULES = (
    ('ValkyrieFragmentStrokeSynthesize', 0x14559, 47436,
     '2d40970aac7e87b2009cd07b6f2dbe79f51eef3cdfd177aa23efdc390a28744c'),
    ('ValkyrieFragmentStrokeSynthesizePatch', 0x86a89, 31460,
     '82220b546293004530b9355c49d3cc51b288844de8212f6606b139646906fe66'),
    ('ValkyrieFragmentParticles', 0x24a69, 29564,
     'd6ec40a200e3e6ce882d9d8322dc7a19f8322df535cde9934b2daf293249cc5d'),
    ('ValkyrieVertexParticles', 0xd77a9, 10036,
     '45a16953635357414cc71b52e4c9fa7b67f34995c50a6f64455d6d2d881912fa'),
)
HELPERS = ('_Z19blendSecondaryBrushjDhDh', '_Z12blendChanneljDhDh',
           '_Z25channelblend_linearheightDhDh', '_Z20channelblend_overlayDhDh',
           '_Z23channelblend_vividlightDhDh')
EPSILON = struct.unpack('<e', bytes.fromhex('0004'))[0]
HEIGHT_THRESHOLD = struct.unpack('<e', bytes.fromhex('6632'))[0]


def saturate(value: float) -> float:
    return max(0.0, min(1.0, value))


def vivid_light(a: float, b: float) -> float:
    if b < 0.5:
        return 1 - saturate((1 - a) / max(2 * b, EPSILON))
    return saturate(a / max(2 * (1 - b), EPSILON))


def scalar(mode: int, a: float, b: float) -> float:
    """Real-valued summary for finite [0,1] operands; not native execution."""
    require(0 <= a <= 1 and 0 <= b <= 1, 'Scalar operands outside [0,1]')
    if mode in (0, 2):
        return b + (1 - b) * a
    if mode == 1:
        return saturate(a * b)
    if mode == 3:
        return saturate(a + b)
    if mode == 4:
        return saturate(max(a, b))
    if mode == 6:
        return saturate(abs(a - b))
    if mode == 7:
        return saturate(a - b)
    if mode == 8:
        return saturate(a + b - 1)
    if mode == 9:
        return saturate(a / max(1 - b, EPSILON))
    if mode == 10:
        return 1 - saturate((1 - a) / max(b, EPSILON))
    if mode == 11:
        return saturate(2 * a * b if a < 0.5 else 1 - 2 * (1 - a) * (1 - b))
    if mode == 20:
        return float(vivid_light(a, b) >= 0.5)
    if mode == 26:
        return saturate(a / max(b, EPSILON))
    if mode == 28:
        shape = a ** 6 + (2 * (0.5 - abs(a - 0.5))) ** 6
        amount = 40 * max(0, HEIGHT_THRESHOLD - b) * shape
        return saturate(max(amount + 0.5 * a, 2 * a + (amount - a) * b))
    if mode in (19, 34):
        return saturate(min(a, b))
    raise ValueError('Mode outside this bounded scalar reconstruction')


def amount_after_dual(mode: int, p: float, a: float, b: float,
                      cap_to_primary: bool = False) -> float:
    """Enabled-secondary stage; a already includes primary edge/grain work.

    Thresholding, colors and final opacity are deliberately outside this helper.
    This is real arithmetic, not a simulation of intermediate half rounding.
    """
    result = scalar(mode, a, b)
    if cap_to_primary:
        result = min(result, a)
    if mode != 0 and result > p:
        result = p + min(1, 63.75 * p) * saturate(result - p)
    return result


def accumulate(old: float, coverage: float, transfer: float,
               maximum: bool, modulated: bool) -> float:
    """Dry amount-channel algebra; does not implement albedo/wetness mixing."""
    if maximum:
        candidate = old + (transfer - old) * coverage if modulated else coverage * transfer
    else:
        deposited = coverage * (1 - old if modulated else 1) * transfer
        candidate = (1 - deposited) * old + deposited
    return max(old, candidate)


class IrView:
    """Small dataflow reader over LLVM values; no IR evaluation or execution."""

    def __init__(self, function):
        self.arguments = list(function.arguments)
        self.instructions = [value for block in function.blocks for value in block.instructions]
        self.nodes = {value: value for value in self.instructions}

    def opcode(self, value) -> str | None:
        return self.nodes[value].opcode if value in self.nodes else None

    def operands(self, value) -> list:
        require(value in self.nodes, 'Expected an instruction operand')
        return list(self.nodes[value].operands)

    def calls(self, name: str) -> list:
        return [value for value in self.instructions if value.opcode == 'call'
                and list(value.operands)[-1].name == name]

    def unary(self, value, opcode: str, source) -> bool:
        return self.opcode(value) == opcode and self.operands(value) == [source]

    def parameter_load(self, value, parameter, field: int) -> bool:
        if self.opcode(value) != 'load':
            return False
        pointer = self.operands(value)[0]
        if self.opcode(pointer) != 'getelementptr':
            return False
        parts = self.operands(pointer)
        return len(parts) == 3 and parts[0] == parameter and constant(parts[1], 0) and constant(parts[2], field)


def constant(value, expected) -> bool:
    return value.is_constant and value.get_constant_value() == expected


def exactly_one(values: list, description: str):
    require(len(values) == 1, f'Expected one {description}, got {len(values)}')
    return values[0]


def verify_native(binary: bytes) -> dict:
    import capstone
    require(capstone.__version__ == '5.0.6', 'Use pinned capstone==5.0.6')
    image = NativeImage(binary)
    classes = image.classes()
    require(find_method(classes, 'ValkyrieLayer', 'fragmentBlendMode:', 'class') == 0x100b35048,
            'Blend translation method differs')
    require(find_method(classes, 'ValkyrieStrokeOptions', 'featureSet') == 0x100acf4a0,
            'Feature-set method differs')
    require(find_method(classes, 'ValkyrieBrush', 'dualBlendMode') == 0x100a4695c,
            'Dual getter differs')
    keys = {
        0x100a414a4: (21, 'dualBlendModeExtended'), 0x100a414bc: (21, 'dualBlendMode'),
        0x100a40728: (2, 'renderingRecursiveMixing'), 0x100a4073c: (2, 'renderingMaxTransfer'),
        0x100a40750: (2, 'renderingModulatedTransfer'), 0x100a411ac: (2, 'wetEdgesAmount'),
        0x100a41210: (2, 'alphaThreshold'), 0x100a41224: (2, 'alphaThresholdAmount'),
    }
    for pc, (register, key) in keys.items():
        require(image.cfstring_pair(pc, register) == key, f'Source key differs at {pc:#x}')
    selectors = {
        0x100a414b4: 'containsValueForKey:', 0x100a414cc: 'containsValueForKey:',
        0x100a414dc: 'decodeIntegerForKey:', 0x100ac1a94: 'dualBlendMode',
        0x100ac1aa0: 'fragmentBlendMode:', 0x100ac3188: 'dualBlendMode',
        0x100ac3194: 'fragmentBlendMode:', 0x100b5cca8: 'setConstantValue:type:atIndex:',
        0x100acf4c8: 'renderingRecursiveMixing', 0x100acf4d4: 'renderingMaxTransfer',
        0x100acf4e8: 'renderingModulatedTransfer', 0x100acf5e8: 'alphaThreshold',
        0x100ac19f8: 'strokeOpacity', 0x100ac1c34: 'parameters',
        0x100ac1c44: 'wetEdgesAmount', 0x100ac1c50: 'parameters',
        0x100ac1cd0: 'alphaThresholdAmount', 0x100ac1ce8: 'parameters',
        0x100ac1d00: 'secondaryBrush', 0x100ac1d0c: 'alphaThresholdAmount',
        0x100ac1d24: 'parameters', 0x100ac72f4: 'dynamicsPressureOpacity',
    }
    for pc, selector in selectors.items():
        require(image.called_selector(pc) == selector, f'Selector differs at {pc:#x}')
    anchors = {
        0x100a414b8: ('tbnz', 'w0, #0, #0x100a414d4'),
        0x100a414d0: ('cbz', 'w0, #0x100a414e4'),
        0x100a414e4: ('mov', 'x0, #0'),
        0x100a414e8: ('str', 'x0, [x20, #0x520]'),
        0x100a4695c: ('ldr', 'x0, [x0, #0x520]'),
        0x100ac1a98: ('mov', 'x2, x0'),
        0x100ac1aa4: ('str', 'w0, [sp, #0x18c]'),
        0x100b35048: ('sub', 'x8, x2, #1'),
        0x100b3504c: ('cmp', 'x8, #0x22'),
        0x100b35050: ('b.hi', '#0x100b3517c'),
        0x100b3517c: ('mov', 'w0, #0xb'),
        0x100b5cc98: ('add', 'x2, x29, #0x30'),
        0x100b5cca4: ('mov', 'w4, #9'),
        0x100a40738: ('strb', 'w0, [x20, #0x169]'),
        0x100a4074c: ('strb', 'w0, [x20, #0x16a]'),
        0x100a40760: ('strb', 'w0, [x20, #0x16b]'),
        0x100a411bc: ('str', 's0, [x20, #0x3a0]'),
        0x100a41220: ('strb', 'w0, [x20, #0x167]'),
        0x100a41234: ('str', 's0, [x20, #0x204]'),
        0x100a45e5c: ('ldrb', 'w0, [x0, #0x167]'),
        0x100a45e6c: ('ldr', 's0, [x0, #0x204]'),
        0x100a45e8c: ('ldrb', 'w0, [x0, #0x169]'),
        0x100a45e9c: ('ldrb', 'w0, [x0, #0x16a]'),
        0x100a45eac: ('ldrb', 'w0, [x0, #0x16b]'),
        0x100a4680c: ('ldr', 's0, [x0, #0x3a0]'),
        0x100acf548: ('ldr', 'x8, [x19, #0x60]'),
        0x100acf54c: ('cmp', 'x8, #3'),
        0x100acf550: ('b.ne', '#0x100acf568'),
        0x100acf554: ('ldrb', 'w8, [x19, #0xb]'),
        0x100acf558: ('orr', 'w9, w20, #0x400'),
        0x100acf55c: ('cmp', 'w8, #0'),
        0x100acf560: ('csel', 'w8, w20, w9, ne'),
        0x100acf5ec: ('orr', 'w8, w20, #0x200000'),
        0x100acfb90: ('ldr', 'x0, [x0, #0x60]'),
        0x100acfc40: ('ldrb', 'w0, [x0, #0xb]'),
        0x100ac1c38: ('str', 's8, [x0, #0x1c]'),
        0x100ac1c54: ('str', 's8, [x0, #0x20]'),
        0x100ac1cd4: ('fcvt', 'd0, s0'),
        0x100ac1cd8: ('ldr', 'd1, [x20, #0xd68]'),
        0x100ac1cdc: ('fmul', 'd0, d0, d1'),
        0x100ac1ce0: ('fcvt', 's8, d0'),
        0x100ac1cec: ('str', 's8, [x0, #0x34]'),
        0x100ac1d10: ('fcvt', 'd0, s0'),
        0x100ac1d14: ('ldr', 'd1, [x20, #0xd68]'),
        0x100ac1d18: ('fmul', 'd0, d0, d1'),
        0x100ac1d1c: ('fcvt', 's8, d0'),
        0x100ac1d28: ('str', 's8, [x0, #0x38]'),
        0x100ac72f8: ('str', 's0, [sp, #0x3a4]'),
    }
    decoder = capstone.Cs(capstone.CS_ARCH_ARM64, capstone.CS_MODE_LITTLE_ENDIAN)
    for pc, expected in anchors.items():
        offset = image.offset(pc, 4)
        instruction = next(decoder.disasm(binary[offset:offset + 4], pc))
        require((instruction.mnemonic, instruction.op_str) == expected, f'Instruction differs at {pc:#x}')
    for mode in range(1, 35):
        target = 0x100b3506c + image.read('<B', 0x100c7f804 + mode - 1)[0] * 4
        require(image.word(target) == (0x52800000 | SOURCE_TO_GPU[mode] << 5)
                and image.word(target + 4) == 0xd65f03c0, f'Mode translation differs for {mode}')
    require(image.read('<Q', 0x100c75d68)[0] == 0x3fefdfdfdfdfdfe0, 'Threshold constant differs')
    return {'instruction_anchors': len(anchors), 'source_key_anchors': len(keys),
            'selector_anchors': len(selectors), 'source_to_gpu': dict(enumerate(SOURCE_TO_GPU)),
            'threshold_scale_binary64': image.read('<d', 0x100c75d68)[0]}


def verify_air(library: bytes) -> dict:
    import llvmlite
    from llvmlite import binding as llvm
    require(llvmlite.__version__ == '0.44.0', 'Use pinned llvmlite==0.44.0')
    parsed = []
    for entry, wrapper, size, expected_hash in MODULES:
        header = struct.unpack_from('<5I', library, wrapper)
        require(header[:4] == (0x0b17c0de, 0, 20, size), f'Wrapper differs for {entry}')
        bitcode = library[wrapper + 20:wrapper + 20 + size]
        require(hashlib.sha256(bitcode).hexdigest() == expected_hash, f'Bitcode differs for {entry}')
        module = llvm.parse_bitcode(bitcode)
        require(module.triple == 'air64-apple-ios16.3.0', 'Unexpected AIR target')
        functions = {function.name: function for function in module.functions if not function.is_declaration}
        require(entry in functions, f'Missing AIR entry {entry}')
        parsed.append((module, functions))
    for helper in HELPERS:
        bodies = [re.sub(r'#\d+', '#ATTR', str(functions[helper])) for _, functions in parsed[:2]]
        require(bodies[0] == bodies[1], f'Main/patch helper mismatch: {helper}')

    checks = []

    def check(name: str, condition: bool) -> None:
        require(condition, f'AIR dataflow mismatch: {name}')
        checks.append(name)

    module, functions = parsed[0]
    text = str(module)
    for offset, name in ((28, 'alpha'), (32, 'wetEdgesAmount'), (52, 'alphaThreshold'),
                         (56, 'secondaryAlphaThreshold')):
        check(f'parameter field {name}@{offset}',
              f'i32 {offset}, i32 4, i32 0, !"float", !"{name}"' in text)
    for index, name in ((3, 'amountTexture'), (4, 'secondaryAmountTexture')):
        check(f'texture binding {name}@{index}', any(
            f'!"air.location_index", i32 {index}, i32 1' in line
            and f'!"air.arg_name", !"{name}"' in line for line in text.splitlines()))
    check('secondary mode uses function constant 9', 'secondary_blend_mode.MTL_FC_INIT_9_j' in text)
    check('patch secondary mode uses function constant 8',
          'secondary_blend_mode.MTL_FC_INIT_8_j' in str(parsed[1][0]))

    body = IrView(exactly_one([f for name, f in functions.items()
                              if name.startswith('_ZL16synthesizeStroke')], 'synthesis helper'))
    blend = exactly_one(body.calls(HELPERS[0]), 'secondary blend call')
    _, primary, secondary, _ = body.operands(blend)
    p = body.arguments[11]
    s = body.arguments[12]
    parameter = body.arguments[1]
    check('secondary threshold select retains unthresholded input',
          body.opcode(secondary) == 'select' and body.operands(secondary)[1] == s)
    threshold = body.operands(secondary)[2]
    compare, on, off = body.operands(threshold)
    left, right = body.operands(compare)
    check('secondary threshold is strict greater-than with field10',
          body.opcode(threshold) == 'select' and constant(on, 1) and constant(off, 0)
          and 'fcmp fast olt' in str(compare) and body.parameter_load(left, parameter, 10)
          and body.unary(right, 'fpext', s))
    check('primary input chooses wet-edge or per-stroke-grain result', body.opcode(primary) == 'phi')
    candidates = body.operands(primary)
    wet_mix = exactly_one([v for v in candidates if body.opcode(v) == 'call'
                          and body.operands(v)[-1].name == 'air.mix.f16'], 'wet edge mix')
    mix_args = body.operands(wet_mix)
    check('wet-edge interpolation begins with original primary amount', mix_args[0] == p)
    wet_load = body.operands(mix_args[2])[0]
    check('wet-edge interpolation uses field4', body.parameter_load(wet_load, parameter, 4))
    grain_call = exactly_one([v for v in candidates if v != wet_mix], 'per-stroke grain call')
    check('per-stroke grain uses wet-edge-adjusted primary',
          body.opcode(grain_call) == 'call' and body.operands(grain_call)[-1].name == '_Z10blendGrainjDhDhDhDhDh'
          and body.operands(grain_call)[1] == wet_mix)
    check('additive-only path caps dual result to adjusted primary', any(
        body.operands(call)[:2] == [blend, primary] for call in body.calls('air.fmin.f16')))
    check('primary-edge guard uses original primary times exact half63.75', any(
        value.opcode == 'fmul' and body.operands(value)[0] == p
        and constant(body.operands(value)[1], 63.75) for value in body.instructions))
    opacity = exactly_one([value for value in body.instructions
                           if body.parameter_load(value, parameter, 3)], 'global opacity load')
    mask_product = exactly_one([value for value in body.instructions if value.opcode == 'fmul'
                               and body.operands(value)[0] == opacity
                               and body.unary(body.operands(value)[1], 'fpext', body.arguments[13])],
                              'global opacity times mask')
    check('global opacity times mask is applied to synthesized amount', any(
        value.opcode == 'fmul' and body.operands(value)[0] == mask_product
        and body.opcode(body.operands(value)[1]) == 'fpext' for value in body.instructions))

    entry = IrView(functions[MODULES[0][0]])
    synthesis_calls = entry.calls(exactly_one([name for name in functions
                                             if name.startswith('_ZL16synthesizeStroke')], 'synthesis name'))
    ordinary = exactly_one([value for value in synthesis_calls
                            if entry.opcode(entry.operands(value)[11]) == 'phi'], 'ordinary texture synthesis call')
    for texture_argument, helper_argument, label in ((13, 11, 'primary'), (14, 12, 'secondary')):
        sample = exactly_one([call for call in entry.calls('air.sample_texture_2d.v4f16')
                              if entry.operands(call)[0] == entry.arguments[texture_argument]], f'{label} amount sample')
        channel = exactly_one([value for value in entry.instructions if value.opcode == 'extractelement'
                               and constant(entry.operands(value)[1], 0)
                               and entry.opcode(entry.operands(value)[0]) == 'extractvalue'
                               and entry.operands(entry.operands(value)[0]) == [sample]], f'{label} R channel')
        check(f'{label} helper operand is sampled amount R',
              channel in entry.operands(entry.operands(ordinary)[helper_argument]))

    particle = IrView(parsed[2][1][MODULES[2][0]])
    alpha, transfer, old = particle.arguments[6], particle.arguments[7], particle.arguments[28]
    check('particle alpha multiplies shaped coverage before accumulation', any(
        value.opcode == 'fmul' and particle.operands(value)[1] == alpha
        for value in particle.instructions))
    transfer_float = exactly_one([value for value in particle.instructions
                                 if particle.unary(value, 'fpext', transfer)], 'transfer extension')
    maximum_mix = exactly_one([call for call in particle.calls('air.mix.f32')
                              if particle.operands(call)[:2] == [old, transfer_float]], 'modulated maximum candidate')
    coverage = particle.operands(maximum_mix)[2]
    check('unmodulated transfer also has coverage times transfer candidate', any(
        value.opcode == 'fmul' and particle.operands(value) == [coverage, transfer_float]
        for value in particle.instructions))
    check('amount accumulation has distinct maximum and over branches', len([
        call for call in particle.calls('air.fast_fmax.f32') if particle.operands(call)[0] == old]) == 2)

    vertex_text = str(parsed[3][0])
    check('vertex instance has separate alpha and transfer fields',
          'i32 80, i32 4, i32 0, !"float", !"alpha"' in vertex_text
          and 'i32 92, i32 4, i32 0, !"float", !"transfer"' in vertex_text)
    check('vertex transferModulation is field at244',
          'i32 244, i32 4, i32 0, !"float", !"transferModulation"' in vertex_text)
    return {'parsed_modules': [{'entry': e, 'wrapper_offset': w, 'bitcode_size': s, 'sha256': h}
                               for e, w, s, h in MODULES],
            'equal_main_patch_helpers': list(HELPERS), 'dataflow_checks': checks,
            'llvm_version': list(llvm.llvm_version_info)}


def derived_cases() -> dict:
    require(scalar(1, 0.5, 0.5) == 0.25 and scalar(3, 0.5, 0.5) == 1,
            'Invalid basic scalar summary')
    require(scalar(7, 0.75, 0.25) == 0.5 and scalar(7, 0.25, 0.75) == 0,
            'Invalid subtract operand order')
    require(scalar(28, 0.25, 0.75) == 0.3125, 'Invalid height summary')
    return {
        'interpretation': 'Analyst-derived real arithmetic, not measured outputs or half/float emulation.',
        'scalar': [{'source_mode': mode, 'gpu_mode': SOURCE_TO_GPU[mode], 'a': a, 'b': b,
                    'idealized_result': scalar(mode, a, b)} for mode in SCALAR_MODES
                   for a, b in ((0, 0), (0.5, 0.5), (0.75, 0.25), (0.25, 0.75), (1, 0), (0, 1))],
        'edge_guard': [{'source_mode': mode, 'original_primary': p, 'adjusted_primary': p,
                        'secondary': 0.5, 'cap_to_primary': cap,
                        'idealized_result': amount_after_dual(mode, p, p, 0.5, cap)}
                       for mode in (0, 3) for p in (0, 1 / 255, 0.5) for cap in (False, True)],
        'dry_accumulation': [{'old': 0.5, 'coverage': 0.5, 'transfer': 0.75,
                              'maximum': maximum, 'modulated': modulated,
                              'idealized_result': accumulate(0.5, 0.5, 0.75, maximum, modulated)}
                             for maximum in (False, True) for modulated in (False, True)],
    }


def inspect(path: Path) -> dict:
    require(digest(path) == EXPECTED_SHA, 'IPA SHA-256 differs from pinned reference')
    with zipfile.ZipFile(path) as archive:
        require(archive.getinfo(EXECUTABLE).file_size == 19_119_424, 'Executable size differs')
        require(archive.getinfo(LIBRARY).file_size == 1_370_648, 'Shader library size differs')
        binary = archive.read(EXECUTABLE)
        library = archive.read(LIBRARY)
    require(hashlib.sha256(binary).hexdigest() == BINARY_SHA, 'Executable hash differs')
    require(hashlib.sha256(library).hexdigest() == LIBRARY_SHA, 'Shader library hash differs')
    return {'schema': 1, 'reference_code_executed': False, 'ipa_sha256': EXPECTED_SHA,
            'executable_sha256': BINARY_SHA, 'library_sha256': LIBRARY_SHA,
            'native': verify_native(binary), 'air': verify_air(library), 'derived_cases': derived_cases(),
            'limits': [
                'Static inspection proves these operations, not empirical Procreate rendering or feel.',
                'Source-to-shader entry/exit anchors are checked; not every pipeline/cache path is reconstructed.',
                'Vertex alpha/transfer producers, geometry, sampling and texture storage formats need further tracing.',
                'Threshold, edge and accumulation summaries omit exact half/float rounding and fast-math execution.',
                'Complete color, wet/recursive, legacy normal, prediction, remapping and material paths are outside this evaluator.',
                'Source mode35 takes a different switch arm and is excluded from this mapping summary.',
            ]}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('ipa', type=Path)
    parser.add_argument('--out', '--output', dest='output', type=Path,
                        help='Optional derived evidence JSON; no reference assets are emitted')
    args = parser.parse_args()
    result = inspect(args.ipa)
    encoded = json.dumps(result, indent=2) + '\n'
    if args.output:
        args.output.write_text(encoded, encoding='utf-8')
        print(json.dumps({'verified': True, 'native_instruction_anchors': result['native']['instruction_anchors'],
                          'air_dataflow_checks': len(result['air']['dataflow_checks']),
                          'source_mode_translations': len(SOURCE_TO_GPU),
                          'derived_scalar_cases': len(result['derived_cases']['scalar'])}))
    else:
        print(encoded, end='')


if __name__ == '__main__':
    main()
