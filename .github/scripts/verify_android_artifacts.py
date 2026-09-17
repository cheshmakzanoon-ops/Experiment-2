#!/usr/bin/env python3
"""Check APK/AAB integrity and packaged native alignment, without third-party modules.

This is a binary preflight, not a substitute for testing on a 16 KB device.
See https://developer.android.com/guide/practices/page-sizes .
"""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import struct
import sys
import zipfile

PAGE_SIZE = 16384
MAX_LIBRARY_BYTES = 256 * 1024 * 1024


def verify_elf(data: bytes, require_64: bool) -> list[str]:
    """Validate ELF load segments and 64-bit LOAD/RELRO alignment."""
    if len(data) < 64 or data[:4] != b'\x7fELF':
        return ['not a complete ELF header']
    if data[4] not in (1, 2) or data[5] not in (1, 2):
        return ['unsupported ELF class or byte order']
    endian = '<' if data[5] == 1 else '>'
    is_64 = data[4] == 2
    if require_64 and not is_64:
        return ['32-bit ELF in a 64-bit ABI directory']
    if is_64:
        phoff = struct.unpack_from(endian + 'Q', data, 32)[0]
        entsize, count = struct.unpack_from(endian + 'HH', data, 54)
        fmt = endian + 'IIQQQQQQ'
    else:
        phoff = struct.unpack_from(endian + 'I', data, 28)[0]
        entsize, count = struct.unpack_from(endian + 'HH', data, 42)
        fmt = endian + 'IIIIIIII'
    if not count or entsize < struct.calcsize(fmt) or phoff + entsize * count > len(data):
        return ['invalid program header table']
    errors = []
    loads = 0
    for index in range(count):
        values = struct.unpack_from(fmt, data, phoff + index * entsize)
        if is_64:
            kind, _, offset, vaddr, _, filesz, memsz, align = values
        else:
            kind, offset, vaddr, _, filesz, memsz, _, align = values
        if kind == 1:  # PT_LOAD
            loads += 1
            if filesz > memsz or offset + filesz > len(data):
                errors.append(f'LOAD {index}: invalid size or file offset')
            if is_64 and (align < PAGE_SIZE or align & (align - 1) or (vaddr - offset) % PAGE_SIZE):
                errors.append(f'LOAD {index}: not 16 KB aligned (alignment {align})')
        if kind == 0x6474E552 and is_64 and (vaddr + memsz) % PAGE_SIZE:  # PT_GNU_RELRO
            errors.append(f'RELRO {index}: protection end not 16 KB aligned')
    if not loads:
        errors.append('ELF has no LOAD segments')
    return errors


def verify_archive(path: Path) -> tuple[list[str], list[str]]:
    errors: list[str] = []
    inventory: list[str] = []
    if path.suffix.lower() not in {'.apk', '.aab'}:
        return ['expected an APK or AAB'], inventory
    with zipfile.ZipFile(path) as archive, path.open('rb') as raw:
        bad = archive.testzip()
        if bad:
            errors.append(f'CRC failure: {bad}')
        names = archive.namelist()
        if len(names) != len(set(names)):
            errors.append('duplicate archive paths')
        manifest = 'AndroidManifest.xml' if path.suffix.lower() == '.apk' else 'base/manifest/AndroidManifest.xml'
        if manifest not in names:
            errors.append(f'missing {manifest}')
        abis = set()
        for entry in archive.infolist():
            if not entry.filename.endswith('.so'):
                continue
            parts = entry.filename.split('/')
            abi = parts[-2] if len(parts) >= 2 else ''
            abis.add(abi)
            if entry.file_size > MAX_LIBRARY_BYTES:
                errors.append(f'{entry.filename}: unexpectedly large library')
                continue
            data = archive.read(entry)
            problems = verify_elf(data, abi in {'arm64-v8a', 'x86_64'})
            errors.extend(f'{entry.filename}: {item}' for item in problems)
            inventory.append(f'{entry.filename} {len(data)} bytes sha256={hashlib.sha256(data).hexdigest()}')
            if path.suffix.lower() == '.apk' and entry.compress_type == zipfile.ZIP_STORED:
                raw.seek(entry.header_offset)
                header = raw.read(30)
                if len(header) != 30 or header[:4] != b'PK\x03\x04':
                    errors.append(f'{entry.filename}: invalid ZIP local header')
                    continue
                name_len, extra_len = struct.unpack_from('<HH', header, 26)
                data_offset = entry.header_offset + 30 + name_len + extra_len
                if data_offset % PAGE_SIZE:
                    errors.append(f'{entry.filename}: uncompressed APK entry is not 16 KB aligned')
        for abi32, abi64 in [('armeabi-v7a', 'arm64-v8a'), ('x86', 'x86_64')]:
            if abi32 in abis and abi64 not in abis:
                errors.append(f'{abi32} libraries have no corresponding {abi64} ABI')
    return errors, inventory


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('artifacts', nargs='+', type=Path)
    args = parser.parse_args()
    failed = False
    for path in args.artifacts:
        try:
            errors, inventory = verify_archive(path)
        except (OSError, ValueError, zipfile.BadZipFile, struct.error) as error:
            errors, inventory = [str(error)], []
        print(f'{path}: {"FAIL" if errors else "PASS"}')
        for item in inventory:
            print('  ' + item)
        if not inventory:
            print('  No native libraries found')
        for error in errors:
            print('  ERROR: ' + error)
        failed |= bool(errors)
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
