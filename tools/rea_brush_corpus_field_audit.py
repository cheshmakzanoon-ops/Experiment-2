#!/usr/bin/env python3
"""Aggregate pinned REA brush settings without redistributing proprietary preset data."""
import argparse
from collections import Counter, defaultdict
import json
import math
from pathlib import Path
import re

POINT = re.compile(r'^\{\s*([-+0-9.eE]+)\s*,\s*([-+0-9.eE]+)\s*\}$')
EXPECTED_SHA256 = '7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e'


def analyze(artifact):
    if artifact.get('reference_sha256') != EXPECTED_SHA256:
        raise ValueError('Unexpected reference identity')
    brushes = artifact.get('brushes')
    if not isinstance(brushes, list):
        raise ValueError('Missing brush containers')
    fields = defaultdict(list)
    seen_paths = set()
    component_count = 0
    for brush in brushes:
        path = brush['path']
        if path in seen_paths:
            raise ValueError('Duplicate primary path')
        seen_paths.add(path)
        for archive in brush['archives']:
            root = archive.get('top', {}).get('root')
            if not isinstance(root, dict):
                raise ValueError('Unrecognized settings root')
            component_count += 1
            for name, value in root.items():
                fields[name].append(value)
    if len(brushes) != 394 or component_count != 534:
        raise ValueError('Unexpected corpus cardinality')
    report = {
        'reference_sha256': artifact['reference_sha256'],
        'method': 'REA key-archive static corpus; statistics only; no iOS executable run',
        'brush_containers': len(brushes),
        'components': component_count,
        'field_count': len(fields),
        'fields': {},
    }
    for name, values in sorted(fields.items()):
        types = Counter(type(v).__name__ for v in values)
        row = {'present': len(values), 'missing': component_count - len(values),
               'types': dict(sorted(types.items()))}
        nums = [float(v) for v in values if type(v) in (int, float) and math.isfinite(float(v))]
        if nums:
            ordered = sorted(nums)
            row['numeric'] = {
                'count': len(nums), 'min': round(ordered[0], 8),
                'max': round(ordered[-1], 8),
                'median': round(ordered[len(ordered) // 2], 8),
                'zeros': sum(v == 0 for v in nums),
                'negative': sum(v < 0 for v in nums),
                'positive': sum(v > 0 for v in nums),
                'unique': len(set(nums)),
            }
        if types['bool']:
            row['boolean_true'] = sum(v is True for v in values)
        if types['str']:
            strings = [v for v in values if isinstance(v, str)]
            row['strings'] = {'unique': len(set(strings)),
                              'longest_length': max(map(len, strings))}
        if types['dict']:
            knots = []
            descending = 0
            unordered_x = 0
            for value in values:
                if not isinstance(value, dict):
                    continue
                coords = value.get('points', {}).get('NS.objects', []) if isinstance(value.get('points'), dict) else []
                points = []
                for s in coords:
                    match = POINT.fullmatch(s) if isinstance(s, str) else None
                    if match:
                        xy = (float(match[1]), float(match[2]))
                        if all(math.isfinite(x) for x in xy):
                            points.append(xy)
                if points:
                    knots.append(len(points))
                    unordered_x += any(points[i][0] > points[i+1][0] for i in range(len(points)-1))
                    sorted_points = sorted(points)
                    descending += any(sorted_points[i][1] > sorted_points[i+1][1] for i in range(len(points)-1))
            if knots:
                row['curves'] = {
                    'parsed': len(knots), 'min_knots': min(knots),
                    'max_knots': max(knots), 'descending_y': descending,
                    'unordered_input_x': unordered_x,
                }
        report['fields'][name] = row
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    report = analyze(json.loads(args.source.read_text()))
    args.out.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
    print(json.dumps({
        'containers': report['brush_containers'],
        'components': report['components'],
        'fields': report['field_count'],
        'numeric': sum('numeric' in f for f in report['fields'].values()),
        'curves': sum('curves' in f for f in report['fields'].values()),
    }))


if __name__ == '__main__':
    main()
