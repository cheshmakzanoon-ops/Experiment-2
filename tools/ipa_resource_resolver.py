#!/usr/bin/env python3
"""Cross-check pinned IPA brush asset references with ZIP member names.

Reads keyed-archive settings and central-directory metadata, never executes iOS
binaries, decrypts protected material, or exports copyrighted image contents.
A matching filename is a *candidate*, not proof of runtime resource resolution.
"""
from __future__ import annotations

from collections import Counter
import argparse
import hashlib
import io
import json
from pathlib import Path
import plistlib
import zipfile

PINNED_SHA = '7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e'
MAX_BRUSHES = 1000
MAX_BRUSH_ZIP = 16 * 1024 * 1024
MAX_SETTINGS = 2 * 1024 * 1024
MAX_INNER_ENTRIES = 1024
MAX_APP_ENTRIES = 150000


def hash_source(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def decode_archive(raw: bytes) -> dict:
    """Resolve just the two NSString-like paths from NSKeyedArchiver root."""
    root_plist = plistlib.loads(raw)
    if not isinstance(root_plist, dict):
        raise ValueError('Unknown archive format')
    objects = root_plist.get('$objects')
    top = root_plist.get('$top')
    if not isinstance(objects, list) or not isinstance(top, dict):
        raise ValueError('Missing keyed archive object table')

    def deref(value, depth=0):
        if depth > 12:
            raise ValueError('Keyed archive reference depth exceeded')
        if isinstance(value, plistlib.UID):
            if value.data >= len(objects):
                raise ValueError('Invalid keyed archive object index')
            return deref(objects[value.data], depth + 1)
        if isinstance(value, dict) and 'NS.string' in value:
            return deref(value['NS.string'], depth + 1)
        return value

    fields = deref(top.get('root'))
    if not isinstance(fields, dict):
        raise ValueError('Keyed root is not a dictionary')
    result = {}
    for name in ('bundledShapePath', 'bundledGrainPath'):
        val = deref(fields.get(name))
        if val is None or val == '$null':
            result[name] = None
        elif isinstance(val, str) and 0 < len(val) <= 1024:
            result[name] = val
        else:
            raise ValueError('Unexpected bundled image path representation')
    return result


def resolve_paths(paths: Counter, inventory: set[str]) -> dict:
    """Exact suffixes only: basename collision remains explicitly ambiguous."""
    out = {}
    for target, uses in sorted(paths.items()):
        if target.startswith('/') or '..' in target.split('/') or '\\' in target or '\x00' in target:
            raise ValueError('Unsafe image reference')
        matches = sorted(p for p in inventory if p == target or p.endswith('/' + target))
        status = 'absent-by-path' if not matches else 'unique-suffix-candidate' if len(matches) == 1 else 'ambiguous-suffix-candidates'
        out[target] = {'uses': uses, 'status': status, 'candidate_paths': matches[:50],
                       'candidate_count': len(matches)}
    return out


def inspect(path: Path, expected_sha: str | None = PINNED_SHA, expect_counts=(394, 534)) -> dict:
    sha = hash_source(path)
    if expected_sha is not None and sha.lower() != expected_sha.lower():
        raise ValueError('Source hash does not match pinned reference')
    with zipfile.ZipFile(path) as outer:
        entries = outer.infolist()
        if len(entries) > MAX_APP_ENTRIES:
            raise ValueError('Too many IPA ZIP members')
        names = {x.filename for x in entries}
        shapes = Counter()
        grains = Counter()
        containers = components = 0
        for entry in entries:
            if not entry.filename.endswith('.brush'):
                continue
            containers += 1
            if containers > MAX_BRUSHES or entry.file_size > MAX_BRUSH_ZIP:
                raise ValueError('Brush corpus resource scan exceeded bounds')
            with outer.open(entry) as stream:
                data = stream.read(MAX_BRUSH_ZIP + 1)
            if len(data) > MAX_BRUSH_ZIP:
                raise ValueError('Oversized nested brush package')
            with zipfile.ZipFile(io.BytesIO(data)) as nested:
                members = nested.infolist()
                if len(members) > MAX_INNER_ENTRIES:
                    raise ValueError('Excessive nested ZIP entries')
                archives = [m for m in members if m.filename.endswith('.archive')]
                if [m.filename for m in archives] not in (['Brush.archive'], ['Brush.archive', 'Sub01/Brush.archive']):
                    raise ValueError('Unexpected brush archive components')
                for part in archives:
                    components += 1
                    if part.file_size > MAX_SETTINGS:
                        raise ValueError('Oversized keyed archive')
                    with nested.open(part) as fp:
                        fields = decode_archive(fp.read(MAX_SETTINGS + 1))
                    if fields['bundledShapePath']:
                        shapes[fields['bundledShapePath']] += 1
                    if fields['bundledGrainPath']:
                        grains[fields['bundledGrainPath']] += 1
        if (containers, components) != expect_counts:
            raise ValueError('Unexpected number of brush containers/components')
        shape_matches = resolve_paths(shapes, names)
        grain_matches = resolve_paths(grains, names)
    def summary(rows):
        status = Counter(v['status'] for v in rows.values())
        uses = Counter()
        for v in rows.values():
            uses[v['status']] += v['uses']
        return {'distinct_references': len(rows), 'total_uses': sum(uses.values()),
                'path_statuses': dict(status), 'component_uses_by_status': dict(uses)}
    return {'reference_sha256': sha, 'source_bytes': path.stat().st_size,
            'scope': 'static member suffix candidates, not observed runtime loading',
            'brush_containers': containers, 'settings_components': components,
            'shape_summary': summary(shape_matches), 'grain_summary': summary(grain_matches),
            'shape_references': shape_matches, 'grain_references': grain_matches,
            'limitations': [
                'An absent filename may be stored in Assets.car or another resource archive.',
                'One suffix match is a candidate, not proof of the app loading that asset.',
                'No referenced images, native functions, or executable instructions were copied.',
            ]}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('ipa', type=Path)
    parser.add_argument('--out', required=True, type=Path)
    parser.add_argument('--no-pin', action='store_true', help='Synthetic fixture mode only')
    args = parser.parse_args()
    report = inspect(args.ipa, expected_sha=None if args.no_pin else PINNED_SHA)
    args.out.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
    print(json.dumps({k: report[k] for k in ('brush_containers', 'settings_components', 'shape_summary', 'grain_summary')}))


if __name__ == '__main__':
    main()
