#!/usr/bin/env python3
"""Resolve candidate IPA resource filenames for the pinned REA brush corpus.

Static, read-only, bounded analysis. No image bytes, decryption or native code
are written. An exact suffix match is NOT proof of an asset's runtime use.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import zipfile

EXPECTED_HASH = "7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e"
MAX_MEMBERS = 150_000
MAX_EXPANDED = 32 * 1024**3


def source_hash(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def valid_path(value: str) -> bool:
    return (
        bool(value) and not value.startswith("/") and "\\" not in value
        and "\x00" not in value
        and all(p not in ("", ".", "..") for p in value.rstrip("/").split("/"))
    )


def reference_paths(corpus: dict, counts=(394, 534)) -> tuple[Counter, Counter]:
    if corpus.get("reference_sha256") != EXPECTED_HASH:
        raise ValueError("Pinned REA brush evidence digest mismatch")
    brushes = corpus.get("brushes")
    if not isinstance(brushes, list):
        raise ValueError("REA brush evidence unavailable")
    shape = Counter()
    grain = Counter()
    seen = set()
    components = 0
    for brush in brushes:
        path = brush.get("path")
        if not isinstance(path, str) or not valid_path(path) or path in seen:
            raise ValueError("Invalid or duplicate logical brush")
        seen.add(path)
        archives = brush.get("archives")
        if not isinstance(archives, list) or [a.get("path") for a in archives] not in (
            ["Brush.archive"], ["Brush.archive", "Sub01/Brush.archive"]
        ):
            raise ValueError("Invalid primary/secondary component grouping")
        for archive in archives:
            components += 1
            root = archive.get("top", {}).get("root")
            if not isinstance(root, dict):
                raise ValueError("Unrecognized brush settings root")
            for field, accumulator in (
                ("bundledShapePath", shape), ("bundledGrainPath", grain)
            ):
                value = root.get(field)
                if value in (None, "$null", ""):
                    continue
                if not isinstance(value, str) or not valid_path(value):
                    raise ValueError("Invalid reference asset path")
                accumulator[value] += 1
    if (len(brushes), components) != counts:
        raise ValueError("REA corpus cardinality mismatch")
    return shape, grain


def read_inventory(path: Path, expected_hash=EXPECTED_HASH) -> tuple[set[str], str]:
    sha = source_hash(path)
    if expected_hash is not None and sha != expected_hash:
        raise ValueError("Original IPA SHA-256 mismatch")
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        if len(entries) > MAX_MEMBERS:
            raise ValueError("IPA member count exceeds limit")
        names = set()
        folded = set()
        total = 0
        for entry in entries:
            name = entry.filename
            if not valid_path(name) or entry.flag_bits & 1:
                raise ValueError("Unsafe or ZIP-encrypted member")
            if name.casefold() in folded:
                raise ValueError("Duplicate or case-ambiguous ZIP member")
            folded.add(name.casefold())
            total += entry.file_size
            if total > MAX_EXPANDED:
                raise ValueError("Expanded-size budget exceeded")
            if not entry.is_dir():
                names.add(name)
    return names, sha


def resolve(refs: Counter, names: set[str]) -> dict:
    """Never guess from substrings; suffix matching respects path segments."""
    outcome = {}
    for resource, uses in sorted(refs.items()):
        if not valid_path(resource):
            raise ValueError("Unsafe resource name")
        candidates = sorted(name for name in names if name == resource or name.endswith("/" + resource))
        status = (
            "no-loose-file-candidate" if not candidates else
            "unique-path-candidate" if len(candidates) == 1 else
            "ambiguous-path-candidates"
        )
        outcome[resource] = {
            "component_uses": uses,
            "status": status,
            "candidate_count": len(candidates),
            "candidate_paths": candidates[:25],
        }
    return outcome


def report(corpus: dict, names: set[str], sha: str, counts=(394, 534)) -> dict:
    shape, grain = reference_paths(corpus, counts)
    records = {"shape": resolve(shape, names), "grain": resolve(grain, names)}
    summary = {}
    for kind, values in records.items():
        by_path = Counter(row["status"] for row in values.values())
        by_use = Counter()
        for row in values.values():
            by_use[row["status"]] += row["component_uses"]
        summary[kind] = {
            "distinct_paths": len(values),
            "component_uses": sum(by_use.values()),
            "path_statuses": dict(sorted(by_path.items())),
            "use_statuses": dict(sorted(by_use.items())),
        }
    return {
        "reference_sha256": sha,
        "verified_corpus_sha256": EXPECTED_HASH,
        "scope": "Literal path candidates only; source code and asset contents are not recovered",
        "resource_inventory_count": len(names),
        "opaque_asset_catalogs": len([n for n in names if n.lower().endswith(".car")]),
        "summary": summary,
        "references": records,
        "limitations": [
            "An Asset.car catalog or other opaque resource archive can contain a resource not present as a loose file.",
            "Path candidates are not evidence of which resource the native renderer loads.",
            "No proprietary texture data or decrypted executable bytes have been extracted.",
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpus", type=Path)
    parser.add_argument("ipa", type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    names, sha = read_inventory(args.ipa)
    result = report(json.loads(args.corpus.read_text(encoding="utf-8")), names, sha)
    args.out.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(result["summary"], sort_keys=True))


if __name__ == "__main__":
    main()
