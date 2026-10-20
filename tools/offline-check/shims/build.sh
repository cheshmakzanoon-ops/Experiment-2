#!/bin/bash
# Compile the harness shims (androidx.* APIs that live on Google Maven, unreachable from this sandbox) into libs/shims.jar.
# usage: build.sh   (rebuilds only when a shim source is newer than the jar)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AC_ROOT="$(dirname "$HERE")"
# shellcheck source=../tools/env.sh
source "$AC_ROOT/tools/env.sh"
OUT="$AC_ROOT/libs/shims.jar"
if [ -f "$OUT" ] && [ -z "$(find "$HERE/src" -name '*.kt' -newer "$OUT" -print -quit)" ] && [ "$OUT" -nt "$AC_ROOT/tools/env.sh" ]; then
  exit 0
fi
echo ">> building shims.jar"
find "$HERE/src" -name '*.kt' | sort > "$OUT_ROOT/shims.list"
rm -rf "$OUT_ROOT/shims" && mkdir -p "$OUT_ROOT/shims"
kotlinc -module-name harness_shims -cp "$(base_classpath)" \
  -Xplugin="$COMPOSE_PLUGIN" -d "$OUT_ROOT/shims" @"$OUT_ROOT/shims.list" 2>&1 | grep -v -e '^Picked up' | head -80
[ "${PIPESTATUS[0]}" = 0 ] || { echo "SHIM BUILD FAILED"; exit 2; }
( cd "$OUT_ROOT/shims" && jar cf "$OUT.tmp" . ) 2> >(grep -v "^Picked up" >&2) && mv "$OUT.tmp" "$OUT"
