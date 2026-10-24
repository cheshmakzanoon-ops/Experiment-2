#!/usr/bin/env python3
"""Verify selected layer-blend static anchors in the pinned reference, never execute it.

Requires capstone==5.0.6 and llvmlite==0.44.0. Only a small derived JSON report
is emitted. No executable, shader bitcode, IR, artwork or profile is extracted.
"""
from __future__ import annotations

import argparse
from fractions import Fraction as F
import hashlib
import json
from pathlib import Path
import struct
import sys
import zipfile


REFERENCE_SHA256 = '7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e'
WRAPPER_OFFSET = 0xc639
BITCODE_LENGTH = 32512
BITCODE_SHA256 = '7057e66b46f286d2496a07b75f93fd59f4b50132128d953c96fa282812a130a8'


def shader_bitcode(library: bytes) -> bytes:
    """Validate the pinned bounded module before passing bytes to the AIR parser."""
    start = WRAPPER_OFFSET + 20
    if len(library) < start:
        raise ValueError('Truncated AIR wrapper')
    if struct.unpack_from('<5I', library, WRAPPER_OFFSET)[:4] != (0x0b17c0de, 0, 20, BITCODE_LENGTH):
        raise ValueError('AIR wrapper differs')
    end = start + BITCODE_LENGTH
    if len(library) < end:
        raise ValueError('Truncated AIR bitcode')
    bitcode = library[start:end]
    if hashlib.sha256(bitcode).hexdigest() != BITCODE_SHA256:
        raise ValueError('AIR hash differs')
    return bitcode


def check_fixtures(report: dict, path: Path) -> None:
    expected = json.loads(path.read_text())
    if expected.get('schemaVersion') != 1 or expected.get('referenceSha256') != report['ipaSha256']:
        raise ValueError('Fixture provenance differs')
    if expected.get('fixtures') != report['derivedFixtures']:
        raise ValueError('Derived layer-blend fixtures differ')


def inspect(ipa: Path, tools: Path) -> dict:
    sys.path.insert(0, str(tools))
    from ipa_inventory import EXPECTED_SHA, digest
    from ipa_native_contracts import NativeImage, find_method, require
    from ipa_brush_blend_contracts import SOURCE_TO_GPU, verify_native
    import capstone
    import llvmlite
    from llvmlite import binding as llvm

    require(capstone.__version__ == '5.0.6', 'Use capstone==5.0.6')
    require(llvmlite.__version__ == '0.44.0', 'Use llvmlite==0.44.0')
    require(ipa.stat().st_size == 777430382 and digest(ipa) == EXPECTED_SHA, 'Wrong reference IPA')
    specs = (
        ('Payload/Procreate.app/Procreate', 19119424, '76d3ff1249a987b8f03ac3ecc64c54e48f618bac467e9fbb1f238b91fab1487b'),
        ('Payload/Procreate.app/ValkyrieMetal.metallib', 1370648, '30ed6b20d7592fe45b4b94c4a2e7e820a1871f705e32ab280fcd0bde7709c6de'),
    )
    data = []
    with zipfile.ZipFile(ipa) as archive:
        for name, size, sha in specs:
            require(archive.getinfo(name).file_size == size, 'Wrong member size')
            content = archive.read(name)
            require(hashlib.sha256(content).hexdigest() == sha, 'Wrong member hash')
            data.append(content)
    binary, library = data
    image = NativeImage(binary)
    classes = image.classes()
    # Reuse the already published verifier for all 35 archive-to-GPU translations.
    translation = verify_native(binary)
    require(SOURCE_TO_GPU[19] == 35 and SOURCE_TO_GPU[34] == 47, 'Wrong selected translations')
    methods = (
        ('ValkyrieLayer', 'initWithCoder:', 'instance', 0x100b2c274),
        ('ValkyrieLayer', 'encodeWithCoder:', 'instance', 0x100b2c894),
        ('ValkyrieLayer', 'blend', 'instance', 0x100b351a4),
        ('ValkyrieCompositeAction', 'blendLayer:source:predicted:', 'class', 0x100a5fda8),
        ('ValkyrieCompositeAction', 'blend', 'instance', 0x100a5ff94),
        ('ValkyrieCompositeResolvedAction', 'actionWithAction:', 'class', 0x100a60174),
        ('ValkyrieComposite', 'executeBlendAction:encoder:', 'instance', 0x100a5b18c),
        ('ValkyrieShaderBlitter', 'blitterMatchedToEncoder:label:mode:options:blend:baseSwizzle:sampleSwizzle:resultSwizzle:sourceAttachment:remapSource:remapPrimaryMask:secondaryMask_attachment:remapSecondaryMask:baseAttachment:targets:writeMask:', 'class', 0x100b603fc),
        ('ValkyrieFragmentBlend', 'initOnDevice:blend:transformed:bicubic:liquify:liquifyLayerMask:sourceMode:remapSource:sourceAttachment:remapPrimaryMask:maskingMode:remapSecondaryMask:secondaryMask_attachment:baseAttachment:needColorConversion:baseSwizzle:sampleSwizzle:resultSwizzle:', 'instance', 0x100b4adc8),
    )
    for owner, selector, kind, address in methods:
        require(find_method(classes, owner, selector, kind) == address, f'Method differs: {owner}.{selector}')
    keys = {
        0x100b2c3e8: (21, 'extendedBlend2'), 0x100b2c400: (21, 'extendedBlend'),
        0x100b2c414: (8, 'blend'), 0x100b4afa8: (22, 'ValkyrieFragmentBlend'),
        0x100b2ca04: (3, 'extendedBlend2'), 0x100b2ca28: (3, 'extendedBlend'),
        0x100b2ca5c: (3, 'blend'),
    }
    for address, (register, expected) in keys.items():
        require(image.cfstring_pair(address, register) == expected, f'String differs at {address:#x}')
    selectors = {
        0x100b2c3f8: 'containsValueForKey:', 0x100b2c410: 'containsValueForKey:',
        0x100b2c42c: 'decodeIntegerForKey:', 0x100a5fe24: 'opacity',
        0x100a5fe30: 'blend', 0x100a601cc: 'blend', 0x100a601d8: 'fragmentBlendMode:',
        0x100a601fc: 'opacity', 0x100a60208: 'parameters',
        0x100a5b3d0: methods[7][1], 0x100b60634: methods[8][1],
        0x100b60698: 'function', 0x100b606c4: 'pipeline:matching:targets:writeMask:vertex:fragment:alphaBlending:',
        0x100b4ae40: 'setConstantValue:type:atIndex:', 0x100b4afc0: 'newFunctionWithName:constantValues:error:',
        0x100b2ca10: 'encodeInteger:forKey:', 0x100b2ca34: 'encodeInteger:forKey:',
        0x100b2ca68: 'encodeInteger:forKey:',
    }
    for address, expected in selectors.items():
        require(image.called_selector(address) == expected, f'Selector differs at {address:#x}')
    anchors = {
        0x100b2c3fc: ('tbnz', 'w0, #0, #0x100b2c424'),
        0x100b2c420: ('csel', 'x21, x21, x8, ne'),
        0x100b2c428: ('mov', 'x2, x21'), 0x100b2c430: ('str', 'x0, [x20, #0x48]'),
        0x100b351a4: ('ldr', 'x0, [x0, #0x48]'),
        0x100a5fe28: ('str', 's0, [x20, #0x10]'),
        0x100a5fe34: ('str', 'x0, [x20, #0x28]'),
        0x100a5ff94: ('ldr', 'x0, [x0, #0x28]'),
        0x100a601c4: ('ldr', 'x21, [x23, #0x5e8]'),
        0x100a601d0: ('mov', 'x2, x0'), 0x100a601d4: ('mov', 'x0, x21'),
        0x100a601dc: ('str', 'w0, [x20, #0x18]'),
        0x100a6020c: ('str', 's8, [x0, #0x40]'),
        0x100a5b1a8: ('bl', '#0x100bb5f6c'), 0x100a5b1ac: ('mov', 'x20, x0'),
        0x100a5b228: ('stp', 'x8, x9, [sp, #0x18]'),
        0x100a5b22c: ('stp', 'x22, x20, [sp, #0x28]'),
        0x100a5b2d0: ('ldr', 'x9, [x20, #0x28]'),
        0x100a5b2e0: ('add', 'x8, x8, #0x478'),
        0x100a5b2e8: ('ldr', 'w6, [x9, x8]'),
        0x100b60420: ('mov', 'x25, x6'),
        0x100b604ac: ('str', 'w25, [sp, #0x88]'),
        0x100b605dc: ('ldr', 'x0, [x8, #0xdb8]'),
        0x100b605ec: ('ldp', 'w3, w8, [sp, #0x88]'),
        0x100b606a4: ('strb', 'wzr, [sp]'),
        0x100b606c0: ('mov', 'x7, x25'),
        0x100b4adf4: ('stur', 'w3, [x29, #-0x34]'),
        0x100b4ae34: ('sub', 'x2, x29, #0x34'),
        0x100b4ae38: ('mov', 'w3, #0x21'), 0x100b4ae3c: ('mov', 'x4, #0'),
        0x100b2ca00: ('mov', 'x2, x0'), 0x100b2ca1c: ('cmp', 'x0, #0x22'),
        0x100b2ca20: ('mov', 'w8, #0x13'), 0x100b2ca24: ('csel', 'x2, x8, x0, eq'),
        0x100b2ca44: ('sub', 'x8, x0, #0x14'), 0x100b2ca48: ('cmp', 'x8, #0xe'),
        0x100b2ca54: ('add', 'x9, x9, #0x908'), 0x100b2ca58: ('ldr', 'x2, [x9, x8, lsl #3]'),
    }
    decoder = capstone.Cs(capstone.CS_ARCH_ARM64, capstone.CS_MODE_LITTLE_ENDIAN)
    for address, expected in anchors.items():
        offset = image.offset(address, 4)
        instruction = next(decoder.disasm(binary[offset:offset + 4], address))
        require((instruction.mnemonic, instruction.op_str) == expected, f'Instruction differs at {address:#x}')
    refs = {0x1010325e8: 'ValkyrieLayer', 0x101033db8: 'ValkyrieFragmentBlend', 0x101032630: 'ValkyrieShaderBlitter'}
    for address, expected in refs.items():
        cls = image.pointer(address)
        ro = image.pointer(cls + 32) & ~7
        require(image.string(image.pointer(ro + 24)) == expected, 'Class reference differs')
    require(image.read('<I', 0x101036478)[0] == 24, 'Resolved blend ivar offset differs')
    require(image.read('<Q', 0x100c7f908 + (34 - 20) * 8)[0] == 19, 'Legacy blend fallback differs')
    # Resolve the external retain helper by its chained-import name, never dereference or execute it.
    cursor = 32
    retain_name = None
    for _ in range(image.raw('<I', 16)[0]):
        command, size = image.raw('<II', cursor)
        if command == 0x80000034:
            offset, amount = image.raw('<II', cursor + 8)
            _, _, imports, symbols, count, form, symbol_form = image.raw('<7I', offset)
            require(form == 1 and symbol_form == 0, 'Unexpected import format')
            bound = image.read('<Q', 0x100ec08d8)[0]
            ordinal = bound & 0xffffff
            require(bound >> 63 == 1 and ordinal < count, 'Invalid helper import')
            name_offset = image.raw('<I', offset + imports + ordinal * 4)[0] >> 9
            start = offset + symbols + name_offset
            require(offset <= start < offset + amount, 'Import name outside payload')
            end = binary.find(b'\0', start, min(start + 256, offset + amount))
            require(end >= start, 'Unterminated import name')
            retain_name = binary[start:end].decode('ascii')
        cursor += size
    require(retain_name == '_objc_retain_x2', 'Resolved-action retain helper differs')

    wrapper, length, shader_sha = WRAPPER_OFFSET, BITCODE_LENGTH, BITCODE_SHA256
    bitcode = shader_bitcode(library)
    module = llvm.parse_bitcode(bitcode)
    require(module.triple == 'air64-apple-ios16.3.0', 'Unexpected AIR target')
    inline = str(module.get_function('_ZL12inline_blendjDv4_DhS_S_'))
    safely = str(module.get_function('_Z11safelyBlendDv4_DhS_DhS_'))
    fragment = str(module.get_function('ValkyrieFragmentBlend'))
    init = str(module.get_function('_GLOBAL__sub_I_ValkyrieFragmentBlend.metal'))
    mixer = str(module.get_function('_Z5mixerDv4_DhS_Dv3_Dh'))
    patterns = [
        (inline, 'i32 35, label %288'), (inline, 'i32 47, label %296'),
        (inline, '%289 = extractelement <4 x half> %2, i64 3'),
        (inline, '%290 = fsub fast half 0xH3C00, %289'),
        (inline, '%293 = fmul fast <4 x half> %292, %1'),
        (inline, '%294 = fadd fast <4 x half> %293, %2'),
        (inline, '@air.fmin.v4f16(<4 x half> %1, <4 x half> %294)'),
        (inline, '@_Z13unpremultiplyDv4_Dh(<4 x half> %1)'),
        (inline, '@air.fmin.v3f16(<3 x half> %297, <3 x half> %298)'),
        (inline, '@_Z5mixerDv4_DhS_Dv3_Dh(<4 x half> %1, <4 x half> %2, <3 x half> %299)'),
        (safely, '@air.saturate.v4f16(<4 x half> %0)'),
        (safely, '@air.mix.v4f16(<4 x half> %27, <4 x half> %44, <4 x half> %46)'),
        (safely, '%39 = shufflevector <4 x half> %38, <4 x half> %27, <4 x i32> <i32 0, i32 1, i32 2, i32 7>'),
        (fragment, '%173 = fmul fast <4 x half> %172, %167'),
        (fragment, '@_Z7swizzle28ValkyrieFragmentBlendSwizzleDv4_Dh(i32 %19, <4 x half> %17)'),
        (fragment, '@_Z11safelyBlendDv4_DhS_DhS_(<4 x half> %201, <4 x half> %200, half %143, <4 x half> %204)'),
        (init, '@_Z10blend_mode.MTL_FC_INIT_0_j'),
        (init, '%28 = lshr i32 %2, 13'),
        (init, 'store i8 %30, i8 addrspace(2)* @_ZL20preserve_target_mode'),
        (mixer, '%4 = extractelement <4 x half> %1, i64 3'),
        (mixer, '%5 = fsub fast half 0xH3C00, %4'),
        (mixer, '%8 = fmul fast <4 x half> %7, %0'),
        (mixer, '@air.mix.v3f16(<3 x half> %9, <3 x half> %11, <3 x half> %12)'),
        (mixer, '%15 = shufflevector <4 x half> %14, <4 x half> %1, <4 x i32> <i32 0, i32 1, i32 2, i32 7>'),
        (mixer, '%16 = fadd fast <4 x half> %15, %8'),
    ]
    for text, pattern in patterns:
        require(pattern in text, f'AIR dataflow anchor differs: {pattern}')
    return {
        'ipaSha256': EXPECTED_SHA,
        'members': [{'path': name, 'bytes': size, 'sha256': sha} for name, size, sha in specs],
        'selectedSourceToGpu': {'19': 35, '34': 47},
        'allArchiveTranslationsVerified': len(translation['source_to_gpu']),
        'methods': [{'owner': owner, 'selector': selector, 'kind': kind, 'address': hex(address)} for owner, selector, kind, address in methods],
        'verifiedAnchors': {'strings': len(keys), 'selectors': len(selectors), 'instructions': len(anchors), 'classReferences': len(refs), 'airDataflow': len(patterns)},
        'retainHelper': retain_name,
        'shader': {'entrypoint': 'ValkyrieFragmentBlend', 'wrapperOffset': hex(wrapper), 'bitcodeBytes': length, 'sha256': shader_sha},
        'sourcePrecedence': ['extendedBlend2', 'extendedBlend', 'blend'],
        'nativeExportFields': {'source19': {'extendedBlend2': 19, 'extendedBlend': 19, 'blend': 19}, 'source34': {'extendedBlend2': 34, 'extendedBlend': 19, 'blend': 19}},
        'ordinaryEquation': 'R = componentwise_min(B, S + (1 - S.a) * B), including alpha; B and S are premultiplied',
        'maskEquation': 'mix(B, R, maskCoverage), after source RGBA has been scaled by layer opacity',
        'source34Equation': 'R.rgb=(1-S.a)*B.rgb+(1-B.a)*S.rgb+B.a*S.a*min(unpremultiply(B),unpremultiply(S)); R.a=S.a+(1-S.a)*B.a',
        'guardScope': 'Finite normalized premultiplied RGBA with identity channel swizzles; the ordinary branch has preserve_target_mode disabled. Its enabled branch separately restores original destination alpha.',
        'exactConsequence': 'For transparent zero B and finite nonnegative S, ordinary output is zero; preserve-target output also has zero alpha.',
        'limits': ['No target code or GPU shader was executed.', 'AIR uses half precision and fast arithmetic; real-arithmetic fixtures do not prove all GPU rounding or sampling.', 'This verifies one pinned 5.4.4 binary, not the unknown authoring binaries of the genuine files.', 'Host sampling, complete group/clipping routing, nonidentity swizzles and all other blend modes are outside this contract.'],
        'derivedFixtures': fixtures(),
    }


def fixtures() -> list[dict]:
    def ordinary(b, s):
        return tuple(min(x, y + (1 - s[3]) * x) for x, y in zip(b, s))

    def preserve(b, s):
        if b[3] == 0:
            return (F(0),) * 4
        full = tuple(x / b[3] for x in b[:3]) + (F(1),)
        r = ordinary(full, s)
        return tuple(x / r[3] * b[3] for x in r[:3]) + (b[3],)

    rows = [
        ('transparent-base', (0, 0, 0, 0), ('1/8', '1/4', '3/8', '1/2'), '1', '1', False),
        ('opaque-base', ('3/4', '1/4', '1/2', 1), ('1/8', '1/4', '1/8', '1/2'), '1', '1', False),
        ('partial-base', ('1/4', '1/8', '3/8', '1/2'), ('1/8', '1/4', '1/16', '1/2'), '1', '1', False),
        ('half-layer-opacity', ('1/4', '1/8', '3/8', '1/2'), ('1/8', '1/4', '1/16', '1/2'), '1/2', '1', False),
        ('half-mask', ('1/4', '1/8', '3/8', '1/2'), ('1/8', '1/4', '1/16', '1/2'), '1', '1/2', False),
        ('preserve-target-branch', ('1/4', '1/8', '3/8', '1/2'), ('1/8', '1/4', '1/16', '1/2'), '1', '1', True),
        ('preserve-target-half-mask', ('1/4', '1/8', '3/8', '1/2'), ('1/8', '1/4', '1/16', '1/2'), '1', '1/2', True),
    ]
    result = []
    for name, base, source, opacity, mask, keep in rows:
        b, s, op, m = tuple(map(F, base)), tuple(map(F, source)), F(opacity), F(mask)
        scaled = tuple(x * op for x in s)
        blended = preserve(b, scaled) if keep else ordinary(b, scaled)
        output = tuple(x + m * (y - x) for x, y in zip(b, blended))
        result.append({'name': name, 'contract': 'source19/gpu35', 'base': list(map(str, b)), 'sourceBeforeOpacity': list(map(str, s)), 'opacity': str(op), 'maskCoverage': str(m), 'preserveTarget': keep, 'output': list(map(str, output)), 'oracle': 'Exact rational evaluation of recovered scalar dataflow; not native execution'})
    for name, base in [('modern-transparent-base', (0, 0, 0, 0)), ('modern-partial-base', ('1/4', '1/8', '3/8', '1/2'))]:
        b, s = tuple(map(F, base)), tuple(map(F, ('1/8', '1/4', '1/16', '1/2')))
        rgb = tuple((1 - s[3]) * x + (1 - b[3]) * y + b[3] * s[3] * min(x / b[3] if b[3] else 0, y / s[3]) for x, y in zip(b[:3], s[:3]))
        output = rgb + (s[3] + (1 - s[3]) * b[3],)
        result.append({'name': name, 'contract': 'source34/gpu47', 'base': list(map(str, b)), 'sourceBeforeOpacity': list(map(str, s)), 'opacity': '1', 'maskCoverage': '1', 'preserveTarget': False, 'output': list(map(str, output)), 'oracle': 'Exact rational evaluation of recovered scalar dataflow; not native execution'})
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('ipa', type=Path)
    parser.add_argument('--tools', type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument('--check', type=Path, help='Require the committed exact-rational fixture set')
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    report = inspect(args.ipa, args.tools)
    if args.check is not None:
        check_fixtures(report, args.check)
    args.out.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'status': 'pass', 'output': str(args.out), 'anchors': report['verifiedAnchors'], 'derivedFixtures': len(report['derivedFixtures'])}))


if __name__ == '__main__':
    main()
