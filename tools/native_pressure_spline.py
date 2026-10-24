"""Derived curve mathematics and fixtures; never executes reference binary code.

Only the independently written equations below execute. Optional --binary
verification opens the reference as data and requires Capstone 5.0.6. Fixture
generation and ordinary unit tests use only the standard library.
"""
from __future__ import annotations

import argparse
from fractions import Fraction
from pathlib import Path
import hashlib
import json
import math
import struct
import zipfile

BINARY_SHA = '76d3ff1249a987b8f03ac3ecc64c54e48f618bac467e9fbb1f238b91fab1487b'
RESOLUTION = 1024


def f32(value: float | int) -> float:
    return struct.unpack('<f', struct.pack('<f', value))[0]


def bits(value: float) -> str:
    return struct.pack('>f', value).hex()


def nearest_even(numerator: int, denominator: int) -> int:
    whole, remainder = divmod(numerator, denominator)
    twice = remainder * 2
    return whole + int(twice > denominator or (twice == denominator and whole & 1))


def rounded(value: Fraction) -> float:
    """IEEE binary32 round-to-nearest-even directly from an exact rational."""
    if value == 0:
        return 0.0
    sign = int(value < 0) << 31
    value = abs(value)
    n, d = value.numerator, value.denominator
    exponent = n.bit_length() - d.bit_length()
    if exponent >= 0:
        exponent -= int(n < d << exponent)
    else:
        exponent -= int(n << -exponent < d)
    if exponent < -126:
        encoded = nearest_even(n << 149, d)
    else:
        shift = 23 - exponent
        mantissa = nearest_even(n << shift, d) if shift >= 0 else nearest_even(n, d << -shift)
        if mantissa == 1 << 24:
            exponent += 1
            mantissa >>= 1
        encoded = 0x7f800000 if exponent > 127 else ((exponent + 127) << 23) | (mantissa - (1 << 23))
    return struct.unpack('<f', struct.pack('<I', sign | encoded))[0]


def add(a: float, b: float) -> float:
    return rounded(Fraction(a) + Fraction(b))


def sub(a: float, b: float) -> float:
    return rounded(Fraction(a) - Fraction(b))


def mul(a: float, b: float) -> float:
    return rounded(Fraction(a) * Fraction(b))


def div(a: float, b: float) -> float:
    return rounded(Fraction(a) / Fraction(b))


def fma(a: float, b: float, c: float) -> float:
    return rounded(Fraction(a) * Fraction(b) + Fraction(c))


def sorted_points(points: list[list[float]]) -> list[tuple[float, float]]:
    converted = [(f32(x), f32(y)) for x, y in points]
    # This finite, in-range domain is also the existing ArtFlow import domain.
    # Native sort runs before its coordinate clamp; unsupported NaNs/interior
    # duplicate inputs do not have a reliable useful interpolation contract.
    assert all(0 <= x <= 1 and 0 <= y <= 1 for x, y in converted)
    result = []
    seen_endpoints = set()
    for x, y in sorted(converted):
        if x in (0.0, 1.0):
            if x in seen_endpoints:
                continue
            seen_endpoints.add(x)
        result.append((x, y))
    assert all(a[0] < b[0] for a, b in zip(result, result[1:])), 'Interior duplicate x is unsupported'
    return result


def second_derivatives(points: list[tuple[float, float]]) -> list[float]:
    count = len(points)
    assert count >= 2
    lower, diagonal, upper, rhs = ([0.0] * count for _ in range(4))
    diagonal[0] = diagonal[-1] = 1.0
    for i in range(1, count - 1):
        x0, y0 = points[i - 1]
        x1, y1 = points[i]
        x2, y2 = points[i + 1]
        h0, h1 = sub(x1, x0), sub(x2, x1)
        lower[i] = div(h0, 6.0)
        diagonal[i] = div(sub(x2, x0), 3.0)
        upper[i] = div(h1, 6.0)
        rhs[i] = sub(div(sub(y2, y1), h1), div(sub(y1, y0), h0))
    for i in range(1, count):
        factor = div(-lower[i], diagonal[i - 1])
        diagonal[i] = fma(factor, upper[i - 1], diagonal[i])
        lower[i] = 0.0
        rhs[i] = fma(factor, rhs[i - 1], rhs[i])
    for i in range(count - 2, -1, -1):
        factor = div(-upper[i], diagonal[i + 1])
        diagonal[i] = fma(factor, lower[i + 1], diagonal[i])
        upper[i] = 0.0
        rhs[i] = fma(factor, rhs[i + 1], rhs[i])
    return [div(value, coefficient) for value, coefficient in zip(rhs, diagonal)]


def prepare(points: list[list[float]] | None, resolution: int = RESOLUTION) -> list[float]:
    assert resolution >= 2
    if points is None:
        return [1.0] * resolution
    nodes = sorted_points(points)
    if len(nodes) < 2:
        return [div(float(i), float(resolution - 1)) for i in range(resolution)]
    # The two scales intentionally differ: the pinned native code uses N for
    # coordinates and N-1 for output normalization, rather than N-1 for both.
    scaled = [(mul(x, float(resolution)), mul(y, float(resolution))) for x, y in nodes]
    derivatives = second_derivatives(scaled)
    values = [0.0] * resolution
    for i, ((x0, y0), (x1, y1)) in enumerate(zip(scaled, scaled[1:])):
        h = sub(x1, x0)
        scale = div(mul(h, h), 6.0)
        for sample in range(math.trunc(x0), math.trunc(x1)):
            b = div(sub(float(sample), x0), h)
            a = sub(1.0, b)
            linear = fma(a, y0, mul(y1, b))
            acubic = fma(mul(a, a), a, -a)
            bcubic = fma(mul(b, b), b, -b)
            curvature = fma(acubic, derivatives[i], mul(derivatives[i + 1], bcubic))
            values[sample] = fma(scale, curvature, linear)
    first_x, first_y = scaled[0]
    for sample in range(math.ceil(first_x)):
        values[sample] = first_y
    last_x, last_y = scaled[-1]
    for sample in range(math.trunc(last_x), resolution):
        values[sample] = last_y
    return [max(0.0, min(1.0, div(value, float(resolution - 1)))) for value in values]


def lookup(table: list[float], pressure: float) -> float:
    assert math.isfinite(pressure), 'Fixture model limits pressure to finite inputs'
    pressure = max(0.0, min(1.0, f32(pressure)))
    coordinate = mul(pressure, float(len(table) - 1))
    low, high = math.floor(coordinate), math.ceil(coordinate)
    fraction = sub(coordinate, float(low))
    return fma(table[low], sub(1.0, fraction), mul(table[high], fraction))


def linear_mapping(points: list[list[float]], pressure: float) -> float:
    nodes = sorted_points(points)
    if pressure <= nodes[0][0]:
        return nodes[0][1]
    for (x0, y0), (x1, y1) in zip(nodes, nodes[1:]):
        if pressure <= x1:
            return y0 + (pressure - x0) / (x1 - x0) * (y1 - y0)
    return nodes[-1][1]


EXPECTED_METHODS = {
    ('ValkyrieMagnitudinalCurve', 'class', 'getPreparedSplineCurve:count:stride:buffer:'): 0x100b8000c,
    ('ValkyrieMagnitudinalCurve', 'class', 'sortPoints:'): 0x100b802cc,
    ('ValkyrieMagnitudinalCurve', 'class', 'splineCurve:pointsCount:count:buffer:'): 0x100b80504,
    ('ValkyrieMagnitudinalCurve', 'class', 'secondDerivative:numberOfPoints:outputBuffer:'): 0x100b806a0,
    ('ValkyrieMagnitudinalCurveInterleavedTable', 'class', 'tableForCurves:curveCount:resolution:'): 0x100b80934,
    ('ValkyrieMagnitudinalCurveInterleavedTable', 'instance', 'lookup:atIndex:'): 0x100b80aec,
}

INSTRUCTION_ANCHORS = {
    0x100a3df2c: ('mov', 'w3, #0x27'),
    0x100a3df30: ('mov', 'w4, #0x400'),
    0x100b80054: ('ucvtf', 's7, x3'),
    0x100b80140: ('stur', 'q7, [x29, #-0x90]'),
    0x100b801c8: ('ldur', 'q0, [x29, #-0x90]'),
    0x100b801cc: ('fmul', 'v0.2s, v10.2s, v0.s[0]'),
    0x100b80228: ('subs', 'x8, x20, #1'),
    0x100b80248: ('fdiv', 's3, s3, s0'),
    0x100b805d8: ('fnmsub', 's19, s21, s19, s19'),
    0x100b805e0: ('fnmsub', 's18, s21, s18, s18'),
    0x100b80b00: ('sub', 'w8, w8, #1'),
    0x100b80b40: ('fmadd', 's0, s0, s1, s2'),
}


def provenance() -> dict:
    return {
        'binary_sha256': BINARY_SHA,
        'static_verification_capstone_version': '5.0.6',
        'reference_code_executed': False,
        'channel_count': 39,
        'resolution': RESOLUTION,
        'interleaved_stride_floats': 40,
        'evidence': 'Mathematical reconstruction from static native instructions; not measured reference-app outputs.',
        'arithmetic': 'binary32 round-to-nearest-even with exact fused multiply-add; finite in-range controls only',
        'derivation': 'docs/NATIVE_PRESSURE_SPLINE.md',
        'methods': [{'class': cls, 'kind': kind, 'selector': selector, 'address': hex(address)}
                    for (cls, kind, selector), address in EXPECTED_METHODS.items()],
        'anchor_count': len(INSTRUCTION_ANCHORS),
    }


def verify_native_bytes(data: bytes) -> dict:
    # These optional dependencies are never imported during ordinary fixture generation.
    from ipa_native_contracts import NativeImage, require
    import capstone
    require(capstone.__version__ == '5.0.6', 'Static verification requires Capstone 5.0.6')
    require(hashlib.sha256(data).hexdigest() == BINARY_SHA, 'Reference executable hash does not match')
    image = NativeImage(data)
    methods = {(c['name'], c['kind'], m['name']): m['address'] for c in image.classes() for m in c['methods']}
    for key, address in EXPECTED_METHODS.items():
        require(methods.get(key) == address, f'Unexpected native method address: {key}')
    require(image.read('<2f', 0x100c3b318) == (0.0, 1.0), 'Unexpected natural endpoint coefficients')
    require(image.read('<2f', 0x100c80628) == (6.0, 3.0), 'Unexpected natural spline coefficients')
    md = capstone.Cs(capstone.CS_ARCH_ARM64, capstone.CS_MODE_LITTLE_ENDIAN)
    for address, expected_instruction in INSTRUCTION_ANCHORS.items():
        offset = image.offset(address, 4)
        instruction = next(md.disasm(data[offset:offset + 4], address))
        require((instruction.mnemonic, instruction.op_str) == expected_instruction,
                f'Unexpected instruction anchor at {address:#x}')
    return {'binary_sha256': BINARY_SHA, 'method_count': len(EXPECTED_METHODS),
            'instruction_anchor_count': len(INSTRUCTION_ANCHORS), 'reference_code_executed': False}


def verify_native(path: Path) -> dict:
    from ipa_native_contracts import MAX_BINARY, require
    require(path.stat().st_size <= MAX_BINARY, 'Reference binary exceeds the analysis budget')
    with path.open('rb') as stream:
        return verify_native_bytes(stream.read(MAX_BINARY + 1))


def verify_ipa(path: Path) -> dict:
    from ipa_inventory import EXPECTED_SHA, digest
    from ipa_native_contracts import EXECUTABLE, MAX_BINARY, require
    source_sha = digest(path)
    require(source_sha == EXPECTED_SHA, 'Source IPA SHA-256 differs from pinned reference')
    with zipfile.ZipFile(path) as archive:
        matches = [entry for entry in archive.infolist() if entry.filename == EXECUTABLE]
        require(len(matches) == 1, 'Missing or duplicate reference executable')
        entry = matches[0]
        require(not entry.flag_bits & 1 and 32 <= entry.file_size <= MAX_BINARY,
                'Encrypted ZIP member or reference executable exceeds budget')
        with archive.open(entry) as stream:
            data = stream.read(MAX_BINARY + 1)
        require(len(data) == entry.file_size, 'Native member size differs from declaration')
    return {'source_sha256': source_sha, **verify_native_bytes(data)}


def fixture(name: str, points: list[list[float]] | None) -> dict:
    table = prepare(points)
    sample_indices = [0, 1, 127, 255, 256, 341, 511, 512, 767, 1022, 1023]
    pressures = [-0.1, 0.0, 1 / 1023, 0.1, 0.2, 0.25, 0.333, 0.5, 0.666, 0.75, 0.9, 1.0, 1.1]
    def value(v):
        return {'value': v, 'binary32': bits(v)}
    result = {
        'name': name,
        'points': points,
        'resolution': RESOLUTION,
        'table_sha256_le_float32': hashlib.sha256(struct.pack('<1024f', *table)).hexdigest(),
        'table_samples': [{'index': i, **value(table[i])} for i in sample_indices],
        'lookup_samples': [{'pressure': p, **value(lookup(table, p))} for p in pressures],
    }
    if points is not None and len(sorted_points(points)) >= 2:
        comparisons = [(abs(lookup(table, p / 4096) - linear_mapping(points, p / 4096)), p / 4096) for p in range(4097)]
        error, pressure = max(comparisons)
        result['max_difference_from_linear_4097_probes'] = {'difference': error, 'pressure': pressure}
    return result


def validate_model() -> dict:
    # Independent exact identities catch N/N-1 mistakes and cubic-term sign errors.
    half = prepare([[0, 0.5], [1, 0.5]])
    assert all(value == f32(512 / 1023) for value in half)
    descending = prepare([[0, 1], [1, 0]])
    assert descending[0] == descending[1] == 1.0
    assert descending[-1] == f32(1 / 1023)
    identity = prepare([[0, 0], [1, 1]])
    assert identity == [f32(i / 1023) for i in range(1024)]
    # Natural spline through (0,0),(.5,.25),(1,1) has normalized M1=3.
    # At normalized x=.25, y=.078125. Native entry256 scales it by1024/1023.
    bend = prepare([[0, 0], [0.5, 0.25], [1, 1]])
    assert bend[256] == f32(80 / 1023)
    # Exact IEEE ties and FMA with cancellation; no host libm dependency.
    assert rounded(Fraction(1) + Fraction(1, 1 << 24)) == 1.0
    assert rounded(Fraction(1) + Fraction(3, 1 << 24)) == f32(1 + 2 ** -22)
    assert fma(f32(1 + 2 ** -23), f32(1 - 2 ** -23), -1.0) == -(2 ** -46)
    return {'identity_all_1024': True, 'constant_half_all_1024': True, 'descending_boundaries': True,
            'analytic_natural_spline_sample': True, 'binary32_ties_and_fma': True}


def generate_document() -> dict:
    evidence = provenance()
    validation = validate_model()
    cases = {
        'nil_channel': None,
        'empty_curve_fallback': [],
        'single_point_fallback': [[0.3, 0.7]],
        'identity': [[0, 0], [1, 1]],
        'descending': [[0, 1], [1, 0]],
        'constant_half': [[0, 0.5], [1, 0.5]],
        'natural_bend': [[0, 0], [0.5, 0.25], [1, 1]],
        'hump': [[0, 0], [0.5, 1], [1, 0]],
        'nonuniform': [[0, 0.05], [0.2, 0.9], [1, 0.4]],
        'plateau_clipping': [[0, 0], [0.3, 1], [0.6, 1], [1, 0]],
        'interior_endpoints': [[0.2, 0.3], [0.8, 0.7]],
        'noninteger_knots': [[0, 0], [0.333, 0.8], [0.669, 0.2], [1, 1]],
        'duplicate_endpoints_sorted': [[1, 0.8], [0, 0.6], [0.5, 0.4], [0, 0.2], [1, 0.7]],
    }
    return {'provenance': evidence, 'model_checks': validation, 'fixtures': [fixture(name, points) for name, points in cases.items()]}


def check_document(path: Path, expected: dict) -> str:
    if path.stat().st_size > 2 * 1024 * 1024:
        raise ValueError('Fixture JSON exceeds the analysis budget')
    data = path.read_bytes()
    if json.loads(data) != expected:
        raise ValueError('Committed native pressure fixtures differ from the reconstructed model')
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    native = parser.add_mutually_exclusive_group()
    native.add_argument('--binary', type=Path, help='Optional pinned executable for read-only static verification')
    native.add_argument('--ipa', type=Path, help='Optional pinned IPA for read-only static verification in memory')
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument('--output', type=Path, help='Explicit output path for regenerating derived fixtures')
    action.add_argument('--check', type=Path, help='Verify existing fixtures without modifying them')
    parser.add_argument('--report', type=Path, help='Optional small JSON verification report; no source assets')
    args = parser.parse_args()
    report = {'reference_code_executed': False}
    if args.binary:
        report['static_verification'] = verify_native(args.binary)
    elif args.ipa:
        report['static_verification'] = verify_ipa(args.ipa)
    result = generate_document()
    report['fixtures'] = len(result['fixtures'])
    report['model_checks'] = result['model_checks']
    if args.check:
        report['fixture_sha256'] = check_document(args.check, result)
        report['fixtures_match'] = True
    else:
        data = (json.dumps(result, indent=2) + '\n').encode('utf-8')
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(data)
        report['fixture_sha256'] = hashlib.sha256(data).hexdigest()
        report['fixtures_written'] = True
    text = json.dumps(report, indent=2, sort_keys=True) + '\n'
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(text)
    print(text, end='')


if __name__ == '__main__':
    main()
