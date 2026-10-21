#!/usr/bin/env python3
"""Compare REA archive settings/asset references with the actual ArtFlow reader.

This is a structural completeness test, not a claim of identical brush rendering.
The output contains key names and counts only: no brush names or source textures.
"""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import re

EXPECTED_SHA = '7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e'
READER_KEY = re.compile(r'\b(?:number|unit|signed|flag)\(\s*"([\w]+)"\s*\)|\bbrush\[\s*"([\w]+)"\s*\]')


def reader_keys(text: str) -> set[str]:
    return {a or b for a, b in READER_KEY.findall(text)}


def analyze(evidence: dict, reader_source: str, expected_counts=(394, 534)) -> dict:
    if evidence.get('reference_sha256') != EXPECTED_SHA:
        raise ValueError('Reference IPA hash mismatch')
    brushes = evidence.get('brushes')
    if not isinstance(brushes, list):
        raise ValueError('Missing brush corpus')
    keys = Counter()
    archive_count = 0
    members = Counter()
    bundled_shape = Counter()
    bundled_grain = Counter()
    compound = 0
    logical_paths = set()
    for brush in brushes:
        logical_path = brush['path']
        if logical_path in logical_paths:
            raise ValueError('Duplicate brush container')
        logical_paths.add(logical_path)
        archives = brush['archives']
        if not isinstance(archives, list) or len(archives) not in (1, 2):
            raise ValueError('Invalid primary/secondary pairing')
        expected_paths = ['Brush.archive'] if len(archives) == 1 else ['Brush.archive', 'Sub01/Brush.archive']
        if [a['path'] for a in archives] != expected_paths:
            raise ValueError('Malformed primary/secondary structure')
        compound += len(archives) == 2
        for member in brush['members']:
            members[member] += 1
        for archive in archives:
            root = archive.get('top', {}).get('root')
            if not isinstance(root, dict):
                raise ValueError('Missing settings dictionary')
            archive_count += 1
            keys.update(root.keys())
            shape = root.get('bundledShapePath')
            grain = root.get('bundledGrainPath')
            if isinstance(shape, str) and shape != '$null':
                bundled_shape[shape] += 1
            if isinstance(grain, str) and grain != '$null':
                bundled_grain[grain] += 1
    if (len(brushes), archive_count) != expected_counts:
        raise ValueError('Corpus cardinality differs from pinned reference')
    parser = reader_keys(reader_source)
    observed = set(keys)
    consumed = observed & parser
    prefixes = ('dynamicsPressure', 'dynamicsTilt', 'dynamicsSpeed', 'shape', 'texture', 'grain', 'plot')
    by_family = {}
    for family in prefixes:
        available = sorted(k for k in observed if k.startswith(family))
        used = sorted(k for k in consumed if k.startswith(family))
        by_family[family] = {'observed_count': len(available), 'reader_keys': used,
                             'unread_keys': [k for k in available if k not in consumed]}
    local_textures = sorted(k for k in members if k.lower().endswith(('.png', '.jpg', '.jpeg'))
                            and not k.startswith(('QuickLook/', 'AuthorPicture/', 'Signature/')))
    return {
        'reference_sha256': EXPECTED_SHA,
        'scope': 'REA-derived settings and member names versus ArtFlow Kotlin reader key references',
        'logical_brushes': len(brushes), 'settings_components': archive_count,
        'compound_brushes': compound,
        'observed_settings_fields': len(observed),
        'read_fields': len(consumed), 'unread_fields': len(observed - parser),
        'read_field_names': sorted(consumed),
        'unread_field_names': sorted(observed - parser),
        'reader_keys_unseen_in_corpus': sorted(parser - observed),
        'field_families': by_family,
        'embedded_preview_thumbnails': members['QuickLook/Thumbnail.png'],
        'embedded_signature_images': members['Signature/SignaturePicture.png'],
        'embedded_author_images': members['AuthorPicture/AuthorPicture.png'],
        'separate_brush_asset_member_count': sum(members[k] for k in local_textures),
        'separate_brush_asset_member_names': local_textures,
        'bundled_shape_references': sum(bundled_shape.values()),
        'distinct_bundled_shape_paths': len(bundled_shape),
        'bundled_grain_references': sum(bundled_grain.values()),
        'distinct_bundled_grain_paths': len(bundled_grain),
        'unresolved_claims': [
            'The corpus does not reveal whether referenced bundled resources are present elsewhere in the IPA.',
            'A field being read does not establish that it is rendered like the reference application.',
            'Unencrypted source or native implementation has not been reconstructed.',
        ],
    }


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('corpus', type=Path)
    p.add_argument('reader', type=Path)
    p.add_argument('--out', required=True, type=Path)
    a = p.parse_args()
    report = analyze(json.loads(a.corpus.read_text()), a.reader.read_text())
    a.out.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
    print(json.dumps({key: report[key] for key in (
        'logical_brushes', 'settings_components', 'compound_brushes', 'observed_settings_fields',
        'read_fields', 'unread_fields', 'embedded_preview_thumbnails',
        'separate_brush_asset_member_count', 'distinct_bundled_shape_paths', 'distinct_bundled_grain_paths')}))


if __name__ == '__main__':
    main()
