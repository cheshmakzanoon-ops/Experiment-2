#!/usr/bin/env python3
"""Compare a hash-pinned REA brush corpus with literal fields used by the ArtFlow reader.

Read-only inventory of setting names, member names and reference counts.
This cannot establish rendering parity or native executable recovery.
"""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import re

REFERENCE_SHA256 = "7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e"
READER_CALL = re.compile(
    r'\b(?:number|unit|signed|flag)\(\s*"([A-Za-z][A-Za-z0-9_]*)"\s*\)'
    r'|\bbrush\[\s*"([A-Za-z][A-Za-z0-9_]*)"\s*\]'
)


def referenced_keys(reader_source: str) -> set[str]:
    """Directly interpreted fields (not proof of rendering equivalence)."""
    return {left or right for left, right in READER_CALL.findall(reader_source)}


def auxiliary_curve_keys(reader_source: str) -> set[str]:
    """Explicitly whitelisted keyed curves retained for interchange, not applied to strokes."""
    block = re.search(
        r'\bAUXILIARY_CURVE_KEYS\s*:\s*Set<String>\s*=\s*setOf\((.*?)\)',
        reader_source,
        re.DOTALL,
    )
    return set(re.findall(r'"([A-Za-z][A-Za-z0-9_]*)"', block.group(1))) if block else set()


def audit(evidence: dict, reader_source: str, counts=(394, 534)) -> dict:
    if evidence.get("reference_sha256") != REFERENCE_SHA256:
        raise ValueError("Reference IPA SHA-256 mismatch")
    corpus = evidence.get("brushes")
    if not isinstance(corpus, list):
        raise ValueError("Missing REA brush containers")
    keys = Counter()
    members = Counter()
    shapes = Counter()
    grains = Counter()
    known_paths = set()
    components = compound = 0

    for brush in corpus:
        path = brush.get("path")
        if not isinstance(path, str) or path in known_paths:
            raise ValueError("Invalid or duplicate logical brush path")
        known_paths.add(path)
        archives = brush.get("archives")
        if not isinstance(archives, list):
            raise ValueError("Missing per-brush archive inventory")
        expected = ["Brush.archive"] + (["Sub01/Brush.archive"] if len(archives) == 2 else [])
        if [item.get("path") for item in archives] != expected:
            raise ValueError("Malformed primary/secondary brush arrangement")
        compound += len(archives) == 2
        for name in brush.get("members", []):
            if not isinstance(name, str):
                raise ValueError("Invalid brush member")
            members[name] += 1
        for part in archives:
            root = part.get("top", {}).get("root")
            if not isinstance(root, dict):
                raise ValueError("Missing keyed settings root")
            keys.update(root.keys())
            components += 1
            for key, accumulator in (
                ("bundledShapePath", shapes), ("bundledGrainPath", grains)
            ):
                value = root.get(key)
                if isinstance(value, str) and value not in ("", "$null"):
                    accumulator[value] += 1

    if (len(corpus), components) != counts:
        raise ValueError("Reference corpus size differs from expected counts")
    observed = set(keys)
    direct = referenced_keys(reader_source)
    auxiliary = auxiliary_curve_keys(reader_source)
    used = direct | auxiliary
    referenced = used & observed
    prefixes = ("dynamicsPressure", "dynamicsTilt", "dynamicsSpeed",
                "shape", "texture", "grain", "plot")
    families = {
        prefix: {
            "observed": len([name for name in observed if name.startswith(prefix)]),
            "referenced": sorted(name for name in referenced if name.startswith(prefix)),
            "unreferenced": sorted(name for name in observed - used if name.startswith(prefix)),
        }
        for prefix in prefixes
    }
    image_member_names = sorted(
        name for name in members
        if name.lower().endswith((".png", ".jpg", ".jpeg"))
        and not name.startswith(("QuickLook/", "AuthorPicture/", "Signature/"))
    )
    return {
        "source_sha256": REFERENCE_SHA256,
        "scope": "Static REA brush dictionary key/member inventory and reader source-key cross-reference",
        "logical_brushes": len(corpus),
        "compound_brushes": compound,
        "settings_components": components,
        "observed_keys": len(observed),
        "direct_reader_keys": len(direct & observed),
        "preserved_auxiliary_curve_keys": len(auxiliary & observed),
        "reader_referenced_keys": len(referenced),
        "reader_unreferenced_keys": len(observed - used),
        "reader_referenced_names": sorted(referenced),
        "preserved_auxiliary_curve_names": sorted(auxiliary & observed),
        "reader_unreferenced_names": sorted(observed - used),
        "reader_keys_absent_from_corpus": sorted(used - observed),
        "families": families,
        "embedded_thumbnails": members["QuickLook/Thumbnail.png"],
        "separate_image_member_count": sum(members[name] for name in image_member_names),
        "bundled_shape_component_references": sum(shapes.values()),
        "distinct_shape_references": len(shapes),
        "bundled_grain_component_references": sum(grains.values()),
        "distinct_grain_references": len(grains),
        "limitations": [
            "A literal reader field reference is not evidence the field is applied or rendered correctly.",
            "Resource paths may resolve through opaque archives not represented in the brush containers.",
            "No native binary code, original images or proprietary source is reconstructed or redistributed.",
        ],
    }


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("corpus", type=Path)
    p.add_argument("reader", type=Path)
    p.add_argument("--out", required=True, type=Path)
    args = p.parse_args()
    report = audit(json.loads(args.corpus.read_text(encoding="utf-8")),
                   args.reader.read_text(encoding="utf-8"))
    args.out.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({key: report[key] for key in (
        "logical_brushes", "compound_brushes", "settings_components", "observed_keys",
        "direct_reader_keys", "preserved_auxiliary_curve_keys",
        "reader_referenced_keys", "reader_unreferenced_keys", "distinct_shape_references",
        "distinct_grain_references"
    )}, sort_keys=True))


if __name__ == "__main__":
    main()
