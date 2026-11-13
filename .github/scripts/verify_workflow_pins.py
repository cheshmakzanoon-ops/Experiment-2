#!/usr/bin/env python3
"""Verify that every third-party GitHub Action in .github/workflows is pinned to a 40-char commit SHA."""

from __future__ import annotations

import re
import sys
from pathlib import Path

USES_LINE = re.compile(r"^\s*(?:-\s*)?uses:\s*([^\s#]+(?:\s+#.*)?)$")
PINNED_SHA = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_./-]+)?@[0-9a-f]{40}$")


def find_unpinned_uses(workflow_dir: Path) -> list[str]:
    violations: list[str] = []
    if not workflow_dir.is_dir():
        return [f"Workflow directory does not exist: {workflow_dir}"]

    for path in sorted(list(workflow_dir.glob("*.yml")) + list(workflow_dir.glob("*.yaml"))):
        for lineno, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            stripped = raw_line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            match = USES_LINE.match(raw_line)
            if not match:
                continue
            target = match.group(1).split("#", 1)[0].strip().strip("\"'")
            if target.startswith("./"):
                continue
            if not PINNED_SHA.match(target):
                violations.append(f"{path}:{lineno}: unpinned action reference '{target}' (expected 40-char lowercase commit SHA)")
    return violations


def main(argv: list[str]) -> int:
    workflow_dir = Path(argv[1]) if len(argv) > 1 else Path(".github/workflows")
    violations = find_unpinned_uses(workflow_dir)
    if violations:
        for item in violations:
            print(item, file=sys.stderr)
        return 1
    count = len(list(workflow_dir.glob("*.yml")) + list(workflow_dir.glob("*.yaml")))
    print(f"OK: All third-party action references across {count} workflow files are pinned to 40-char commit SHAs.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
