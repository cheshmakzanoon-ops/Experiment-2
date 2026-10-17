"""Turn a raw kotlinc log into a compact error list (run as: python3 -I report.py LOG REPO_DIR [BASELINE]).

* prints `path:line:col: error: message` (path relative to the repository) plus the offending source line;
* prints `n errors` / exits 1 when there are errors, 0 otherwise;
* errors whose (file, message) pair is listed in BASELINE are known harness limitations and reported separately.
"""
import os
import re
import sys

ERR = re.compile(r"^(/.*?\.(?:kt|java|kts)):(\d+):(\d+): (error|exception): (.*)$")
PLAIN = re.compile(r"^(error|exception): (.*)$")

# Imports of these packages come from libraries that are only available as hand-written shims in this sandbox.
SHIMMED = (
    "androidx.activity", "androidx.core", "androidx.lifecycle", "androidx.navigation", "androidx.hilt",
    "androidx.room", "androidx.sqlite", "androidx.media3", "androidx.annotation",
)


def main():
    log, repo = sys.argv[1], sys.argv[2]
    baseline = set()
    if len(sys.argv) > 3 and os.path.exists(sys.argv[3]):
        for line in open(sys.argv[3], encoding="utf-8"):
            line = line.rstrip("\n")
            if line and not line.startswith("#"):
                baseline.add(line)
    lines = open(log, encoding="utf-8", errors="replace").read().splitlines()
    errors, other = [], []
    i = 0
    while i < len(lines):
        m = ERR.match(lines[i])
        if m:
            path, ln, col, _kind, msg = m.groups()
            block = []
            j = i + 1
            while j < len(lines) and not ERR.match(lines[j]) and not lines[j].startswith("Picked up"):
                block.append(lines[j])
                j += 1
            # kotlinc prints: message line(s), the source line, then a line of ^^^ markers.  Split accordingly.
            caret = max((k for k, b in enumerate(block) if re.match(r"^\s*\^+\s*$", b)), default=-1)
            if caret >= 1:
                msg = msg + "".join("\n    " + b.strip() for b in block[: caret - 1])
                snippet = [block[caret - 1]]
            else:
                snippet = block[:2]
            errors.append((path, int(ln), int(col), msg, snippet))
            i = j
            continue
        p = PLAIN.match(lines[i])
        if p:
            other.append(lines[i])
        elif lines[i].startswith(("exception:", "java.lang.", "Caused by", "\tat ")) and not lines[i].startswith("Picked up"):
            other.append(lines[i])
        i += 1

    shown, known = [], []
    for path, ln, col, msg, snippet in errors:
        rel = os.path.relpath(path, repo) if path.startswith(repo) else path
        key = f"{rel}|{msg.strip()}"
        (known if key in baseline else shown).append((rel, ln, col, msg, snippet))

    def emit(item):
        rel, ln, col, msg, snippet = item
        print(f"{rel}:{ln}:{col}: error: {msg}")
        src = [s for s in snippet if s.strip()]
        if src:
            print(f"    > {src[0].strip()[:200]}")
        if "unresolved reference" in msg:
            for s in src:
                m = re.match(r"\s*import\s+([A-Za-z0-9_.]+)", s)
                if m and m.group(1).startswith(SHIMMED):
                    print("    note: this package is provided by a hand-written shim (shims/src); if the symbol exists in the real "
                          "library, extend the shim")
                    break

    for item in shown:
        emit(item)
    if other and not shown:
        print("compiler problems without a source location:")
        for line in other[:40]:
            print("  " + line)
    if known:
        print(f"({len(known)} known harness limitation(s) from the baseline suppressed)")
    total = len(shown) + (1 if other and not shown else 0)
    print(f"{len(shown)} error(s)")
    sys.exit(1 if total else 0)


main()
