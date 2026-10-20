#!/bin/bash
# inject_lint.sh -- prove that lint.sh catches ktlint and detekt violations.  Works on a copy of the repository
# (app/src, .editorconfig, config/, build files); the repository itself is never touched.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AC_ROOT="$(dirname "$HERE")"
REPO="${ARTFLOW_REPO:-/home/user/Experiment-2}"
W="$AC_ROOT/work/inject-lint"
rm -rf "$W" && mkdir -p "$W/repo/app"
cp -r "$REPO/.editorconfig" "$REPO/config" "$REPO/build.gradle.kts" "$REPO/settings.gradle.kts" "$W/repo/"
cp -r "$REPO/app/src" "$REPO/app/build.gradle.kts" "$W/repo/app/"

F=app/src/main/java/com/artflow/studio/presentation/ui/screens/help/HelpScreen.kt
python3 -I - "$W/repo/$F" > "$W/expected.txt" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
lines = s.split("\n")
# header: after the package line add an unused import (ktlint no-unused-imports)
pkg = next(i for i, l in enumerate(lines) if l.startswith("package "))
lines.insert(pkg + 2, "import java.util.concurrent.ConcurrentHashMap")
exp = [(pkg + 3, "ktlint no-unused-imports")]
# end of file: a too-long line (ktlint max-line-length), a FIXME comment and a 13-parameter function (detekt)
base = len(lines)
extra = [
    "",
    "val harnessLongLine = \"" + "x" * 150 + "\"",
    "// FIXME: injected by the harness test",
    "fun harnessTooManyParameters(a1: Int, a2: Int, a3: Int, a4: Int, a5: Int, a6: Int, a7: Int, a8: Int, a9: Int, a10: Int, a11: Int, a12: Int, a13: Int) = a1",
]
lines += extra
exp += [(base + 2, "ktlint max-line-length"), (base + 3, "detekt ForbiddenComment"), (base + 4, "detekt LongParameterList")]
open(p, "w", encoding="utf-8").write("\n".join(lines))
for ln, what in exp:
    print(f"HelpScreen.kt:{ln}\t{what}")
PY
echo "injected:"; cat "$W/expected.txt"
ARTFLOW_REPO="$W/repo" bash "$AC_ROOT/lint.sh" > "$W/result.txt" 2>&1
echo "lint.sh exit status: $? (expected 1)"
grep -E "HelpScreen" "$W/result.txt" | sed 's|^app/src/main/java/com/artflow/studio/presentation/ui/screens/help/||'
echo "---- verification"
status=0
while IFS=$'\t' read -r loc label; do
  if grep -q -F -- "$loc:" "$W/result.txt"; then echo "FOUND   $loc  ($label)"; else echo "MISSING $loc  ($label)"; status=1; fi
done < "$W/expected.txt"
tail -3 "$W/result.txt"
exit $status
