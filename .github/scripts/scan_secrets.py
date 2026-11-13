#!/usr/bin/env python3
"""Scan repository tracked text files for leaked secrets, private keys, and keystore config hygiene."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path
import re

SECRET_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("PEM private key", re.compile(r"-----BEGIN (?:RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----")),
    ("AWS access key ID", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("GitHub classic token", re.compile(r"\bgh[pousr]_[A-Za-z0-9_]{36,}\b")),
    ("GitHub fine-grained PAT", re.compile(r"\bgithub_pat_[A-Za-z0-9_]{20,}\b")),
    ("Slack token", re.compile(r"\bxox[baprs]-[0-9A-Za-z-]{10,}\b")),
    ("Google API key", re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b")),
    ("Stripe live secret key", re.compile(r"\bsk_live_[0-9A-Za-z]{16,}\b")),
)

REQUIRED_GITIGNORE_ENTRIES = (
    "keystore.properties",
    "*.keystore",
    "*.jks",
    ".env",
)

TEXT_SUFFIX_SKIP = {".png", ".jpg", ".jpeg", ".webp", ".gif", ".jar", ".apk", ".aab", ".ttf", ".otf", ".zip", ".psd"}
PATH_SKIP_PREFIXES = (".git/", "app/build/", ".gradle/", "build/")


def list_target_files(root: Path) -> list[Path]:
    git_dir = root / ".git"
    if git_dir.exists():
        res = subprocess.run(
            ["git", "-C", str(root), "ls-files", "-z"],
            capture_output=True,
            check=False,
        )
        if res.returncode == 0:
            files = []
            for raw in res.stdout.split(b"\x00"):
                if not raw:
                    continue
                rel = raw.decode("utf-8", errors="replace")
                candidate = root / rel
                if candidate.is_file():
                    files.append(candidate)
            return files
    return [p for p in root.rglob("*") if p.is_file()]


def scan_directory(root: Path, check_gitignore: bool = True) -> list[str]:
    findings: list[str] = []
    if check_gitignore:
        gitignore_path = root / ".gitignore"
        if not gitignore_path.is_file():
            findings.append(".gitignore is missing")
        else:
            lines = {line.strip() for line in gitignore_path.read_text(encoding="utf-8").splitlines() if line.strip() and not line.strip().startswith("#")}
            for req in REQUIRED_GITIGNORE_ENTRIES:
                if req not in lines:
                    findings.append(f".gitignore missing required credential pattern: {req}")

    for path in list_target_files(root):
        try:
            rel = path.relative_to(root).as_posix()
        except ValueError:
            rel = path.name
        if any(rel.startswith(prefix) for prefix in PATH_SKIP_PREFIXES):
            continue
        if path.suffix.lower() in TEXT_SUFFIX_SKIP:
            continue
        # Do not flag this scanner script's own literal regex definitions.
        if rel == ".github/scripts/scan_secrets.py" or rel == ".github/scripts/test_scan_secrets.py":
            continue
        if rel == "keystore.properties" or path.suffix.lower() in {".keystore", ".jks", ".p12", ".pem"}:
            findings.append(f"{rel}: tracked credential/keystore artifact must never be committed")
            continue
        try:
            content = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        for lineno, line in enumerate(content.splitlines(), start=1):
            for label, pattern in SECRET_PATTERNS:
                if pattern.search(line):
                    findings.append(f"{rel}:{lineno}: potential {label} detected")
    return findings


def main(argv: list[str]) -> int:
    root = Path(argv[1]) if len(argv) > 1 else Path(".")
    findings = scan_directory(root.resolve())
    if findings:
        for item in findings:
            print(item, file=sys.stderr)
        return 1
    print(f"OK: No secrets or tracked signing artifacts found under {root.resolve()}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
