#!/usr/bin/env python3
"""Read-only, bounded IPA ZIP, bundle metadata and Mach-O header inventory.

Never runs an executable, decrypts content, or extracts reference assets.
Requires full source archive locally and stores only derived metadata.
"""
from __future__ import annotations

from collections import Counter
import argparse
import hashlib
import json
import pathlib
import plistlib
import struct
import zipfile

EXPECTED_SHA = '7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e'
HEADER_BUDGET = 8 * 1024 * 1024
PLIST_BUDGET = 2 * 1024 * 1024
MAX_MEMBERS = 150_000
MAX_EXPANDED = 32 * 1024**3
PLIST_FIELDS = ('CFBundleIdentifier', 'CFBundleName', 'CFBundleDisplayName',
                'CFBundleExecutable', 'CFBundleVersion', 'CFBundleShortVersionString',
                'MinimumOSVersion', 'CFBundleSupportedPlatforms', 'UIDeviceFamily')
CPU = {0x0100000C: 'arm64', 0x01000007: 'x86_64', 12: 'arm', 7: 'x86'}
LOAD_NAMES = {0xC: 'load', 0xD: 'id', 0x80000018: 'weak',
              0x8000001F: 'reexport', 0x20: 'lazy', 0x23: 'weak',
              0x80000023: 'upward'}


def digest(path: pathlib.Path) -> str:
    sha = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            sha.update(block)
    return sha.hexdigest()


def safe_name(name: str) -> bool:
    return (bool(name) and not name.startswith('/') and '\\' not in name and '\x00' not in name
            and all(c not in ('', '.', '..') for c in name.rstrip('/').split('/')))


def parse_slice(data: bytes, base: int = 0, limit: int | None = None) -> dict:
    """Parse only load-command facts from a thin Mach-O slice; no disassembly."""
    limit = len(data) if limit is None else min(limit, len(data))
    if base < 0 or base + 32 > limit:
        return {'status': 'header-outside-read-budget'}
    tag = data[base:base + 4]
    spec = {b'\xfe\xed\xfa\xce': ('>', False), b'\xce\xfa\xed\xfe': ('<', False),
            b'\xfe\xed\xfa\xcf': ('>', True), b'\xcf\xfa\xed\xfe': ('<', True)}
    if tag not in spec:
        return {'status': 'not-thin-mach-o'}
    endian, is64 = spec[tag]
    head_size = 32 if is64 else 28
    if base + head_size > limit:
        return {'status': 'incomplete-header'}
    values = struct.unpack_from(endian + '7I', data, base)
    _, cpu_type, cpu_subtype, kind, commands, cmd_bytes, flags = values
    if commands > 65536 or cmd_bytes > HEADER_BUDGET or base + head_size + cmd_bytes > limit:
        return {'status': 'load-commands-outside-read-budget', 'architecture': CPU.get(cpu_type, hex(cpu_type)),
                'declared_load_commands': commands}
    output = {'status': 'parsed', 'architecture': CPU.get(cpu_type, hex(cpu_type)),
              'cpu_subtype': cpu_subtype, 'kind': kind, 'is_64_bit': is64,
              'commands': commands, 'flags': flags, 'encryption': [], 'linked_libraries': [],
              'rpaths': [], 'uuid': [], 'code_signatures': [], 'platforms': []}
    cursor = base + head_size
    command_end = cursor + cmd_bytes
    for _ in range(commands):
        if cursor + 8 > command_end:
            return {'status': 'malformed-load-command-table', 'architecture': output['architecture']}
        cmd, size = struct.unpack_from(endian + 'II', data, cursor)
        if size < 8 or cursor + size > command_end:
            return {'status': 'malformed-load-command-size', 'architecture': output['architecture']}
        if cmd in (0x21, 0x2C) and size >= (24 if cmd == 0x2C else 20):
            cryptoff, cryptsize, cryptid = struct.unpack_from(endian + 'III', data, cursor + 8)
            output['encryption'].append({'cryptoff': cryptoff, 'cryptsize': cryptsize, 'cryptid': cryptid})
        if cmd in LOAD_NAMES and size >= 24:
            offset = struct.unpack_from(endian + 'I', data, cursor + 8)[0]
            if 24 <= offset < size:
                raw = data[cursor + offset:cursor + size]
                if b'\0' in raw:
                    name = raw.split(b'\0', 1)[0].decode('utf-8', errors='replace')
                    output['linked_libraries'].append({'kind': LOAD_NAMES[cmd], 'name': name[:1024]})
        if cmd == 0x8000001C and size >= 12:
            offset = struct.unpack_from(endian + 'I', data, cursor + 8)[0]
            if 12 <= offset < size:
                raw = data[cursor + offset:cursor + size]
                if b'\0' in raw:
                    output['rpaths'].append(raw.split(b'\0', 1)[0].decode('utf-8', errors='replace')[:1024])
        if cmd == 0x1B and size >= 24:
            output['uuid'].append(data[cursor + 8:cursor + 24].hex())
        if cmd == 0x1D and size >= 16:
            offset, amount = struct.unpack_from(endian + 'II', data, cursor + 8)
            output['code_signatures'].append({'offset': offset, 'size': amount})
        if cmd == 0x32 and size >= 24:
            platform, version, sdk, tools = struct.unpack_from(endian + '4I', data, cursor + 8)
            output['platforms'].append({'platform_id': platform, 'minimum_os_encoded': version,
                                        'sdk_encoded': sdk, 'tool_count': tools})
        cursor += size
    if cursor != command_end:
        output['unused_load_command_bytes'] = command_end - cursor
    output['contains_encrypted_region'] = any(x['cryptid'] != 0 for x in output['encryption'])
    return output


def parse_macho(data: bytes) -> dict:
    """FAT32/FAT64 slice enumeration with strict bounds; pass thin inputs through."""
    if len(data) < 8:
        return {'status': 'insufficient-bytes'}
    magic = data[:4]
    specs = {b'\xca\xfe\xba\xbe': ('>', False), b'\xbe\xba\xfe\xca': ('<', False),
             b'\xca\xfe\xba\xbf': ('>', True), b'\xbf\xba\xfe\xca': ('<', True)}
    if magic not in specs:
        return parse_slice(data)
    endian, fat64 = specs[magic]
    count = struct.unpack_from(endian + 'I', data, 4)[0]
    size = 32 if fat64 else 20
    if count == 0 or count > 32 or 8 + count * size > len(data):
        return {'status': 'malformed-fat-header', 'declared_slices': count}
    slices = []
    for i in range(count):
        pos = 8 + i * size
        if fat64:
            cpu_type, cpu_subtype, offset, length, alignment, reserved = struct.unpack_from(endian + 'IIQQII', data, pos)
        else:
            cpu_type, cpu_subtype, offset, length, alignment = struct.unpack_from(endian + '5I', data, pos)
        details = parse_slice(data, offset, offset + length)
        slices.append({'declared_architecture': CPU.get(cpu_type, hex(cpu_type)),
                       'offset': offset, 'size': length, 'header': details})
    return {'status': 'fat-container', 'slices': slices}


def inspect(path: pathlib.Path, expected_sha: str | None = EXPECTED_SHA) -> dict:
    source_hash = digest(path)
    if expected_sha is not None and source_hash.lower() != expected_sha.lower():
        raise ValueError('Source SHA-256 differs from pinned reference; refusing to mix evidence')
    report = {'sha256': source_hash, 'source_bytes': path.stat().st_size,
              'scope': 'read-only static ZIP, bundle plist and Mach-O header evidence',
              'limitations': ['Native functions and encrypted application code are not recovered.',
                              'Executable metadata is not evidence of observed runtime behavior.',
                              'No application code or unpacked proprietary resources redistributed.']}
    with zipfile.ZipFile(path, 'r') as z:
        infos = z.infolist()
        if len(infos) > MAX_MEMBERS:
            raise ValueError('Excessive archive members')
        entries = {}
        names_folded = set()
        total = 0
        suffixes = Counter()
        frameworks = set()
        roots = set()
        for item in infos:
            name = item.filename
            folded = name.casefold()
            if not safe_name(name) or folded in names_folded or item.flag_bits & 1:
                raise ValueError('Unsafe, duplicate or encrypted archive ZIP member')
            names_folded.add(folded)
            total += item.file_size
            if total > MAX_EXPANDED:
                raise ValueError('Declared expanded bytes exceed analysis budget')
            entries[name] = item
            suffixes[pathlib.PurePosixPath(name.rstrip('/')).suffix.lower() or '[none]'] += 1
            parts = name.split('/')
            if len(parts) > 1 and parts[0] == 'Payload' and parts[1].endswith('.app'):
                roots.add('/'.join(parts[:2]))
            frameworks.update(p for p in parts if p.endswith('.framework'))
        report.update(zip_members=len(infos), declared_expanded_bytes=total,
                      extensions=dict(suffixes.most_common(75)), app_roots=sorted(roots),
                      frameworks=sorted(frameworks), bundle_plists={}, declared_executables={})
        for name, item in entries.items():
            if not name.endswith('/Info.plist') or item.file_size > PLIST_BUDGET:
                continue
            try:
                with z.open(item) as stream:
                    val = plistlib.loads(stream.read(PLIST_BUDGET + 1))
                if not isinstance(val, dict):
                    raise ValueError('Root is not a dictionary')
                fields = {}
                for field in PLIST_FIELDS:
                    v = val.get(field)
                    if isinstance(v, (str, bool, int)) and len(str(v)) <= 1024:
                        fields[field] = v
                    elif isinstance(v, list) and len(v) <= 12 and all(isinstance(x, (str, int)) for x in v):
                        fields[field] = v
                report['bundle_plists'][name] = fields
                exe = fields.get('CFBundleExecutable')
                if not isinstance(exe, str):
                    continue
                exe_path = name.rsplit('/', 1)[0] + '/' + exe
                entry = entries.get(exe_path)
                if entry is not None and not entry.is_dir():
                    with z.open(entry) as stream:
                        binary = stream.read(min(entry.file_size, HEADER_BUDGET))
                    report['declared_executables'][exe_path] = {
                        'declared_bytes': entry.file_size, 'binary': parse_macho(binary)}
            except (OSError, ValueError, plistlib.InvalidFileException, zipfile.BadZipFile) as e:
                report['bundle_plists'][name] = {'parse_error_type': type(e).__name__}
    return report


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('ipa', type=pathlib.Path)
    ap.add_argument('--expected-sha256', default=EXPECTED_SHA)
    ap.add_argument('--no-pin', action='store_true', help='For synthetic unit-test fixtures only')
    ap.add_argument('--out', required=True, type=pathlib.Path)
    args = ap.parse_args()
    record = inspect(args.ipa, expected_sha=None if args.no_pin else args.expected_sha256)
    args.out.write_text(json.dumps(record, indent=2, sort_keys=True) + '\n')
    print(json.dumps({'source_sha256': record['sha256'], 'zip_members': record['zip_members'],
                      'app_roots': record['app_roots'],
                      'executable_count': len(record['declared_executables'])}))


if __name__ == '__main__':
    main()
