#!/bin/bash
# compile.sh -- offline stand-in for `./gradlew compileDebugKotlin`.
#
# Compiles every Kotlin source of the app's debug variant (src/main + src/debug, plus generated R / BuildConfig) with
# the Kotlin 2.0.21 K2 compiler from the Gradle distribution, the Compose and kotlinx.serialization compiler plugins,
# and a classpath assembled from Maven Central artifacts + hand-written shims (see README.md for what that means).
# A quick aapt2-style resource sanity check (duplicates, apostrophes, formats, dangling @references) runs first.
#
# usage: compile.sh            print errors as path:line:col: error: message, exit 1 on error (0 = compiles), 2 = harness problem
#                              compiled classes are left in out/classes (unit.sh reuses them)
# environment: ARTFLOW_REPO (checkout, default /home/user/Experiment-2), AC_OUT (output dir), KC_XMX (default 3g),
#              KC_EXTRA (extra kotlinc flags), AC_NO_CACHE=1 (ignore the result cache)
#
# Several agents may call this at the same time: runs are serialised per output directory (a lock), and a run whose sources
# are byte-identical to an earlier run just replays that run's output.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export AC_ROOT="$HERE"
# shellcheck source=tools/env.sh
source "$HERE/tools/env.sh"
start=$(date +%s)

# One run at a time per output directory (unit.sh holds the lock itself and sets AC_LOCK_HELD for the nested call).
if [ -z "${AC_LOCK_HELD:-}" ]; then
  exec 9> "$OUT_ROOT/.lock"
  flock -n 9 || { echo "compile.sh: another harness run is using $OUT_ROOT; waiting..." >&2; flock 9; }
  export AC_LOCK_HELD=1
fi

bash "$HERE/setup.sh" compile || exit 2
bash "$HERE/shims/build.sh" || exit 2

# --- result cache: key = content of everything that can influence the result ------------------------------------------------
CACHE="$OUT_ROOT/cache"; mkdir -p "$CACHE"
key_inputs() {
  {
    for d in "$APP/src/main/java" "$APP/src/main/kotlin" "$APP/src/debug/java" "$APP/src/debug/kotlin" "$APP/src/main/res" "$APP/src/debug/res" \
             "$HERE/shims/src"; do
      [ -d "$d" ] && find "$d" -type f
    done
    ls "$APP"/src/main/AndroidManifest.xml "$APP"/src/debug/AndroidManifest.xml "$APP"/build.gradle.kts "$HERE"/tools/{env.sh,gen_r.py,check_res.py,report.py} \
       "$HERE"/baseline.txt "$HERE"/compile.sh 2>/dev/null
  } | LC_ALL=C sort | xargs -d '\n' sha256sum | sha256sum | cut -c1-40
}
KEY="$(key_inputs)-$(printf '%s' "${KC_EXTRA:-}" | sha256sum | cut -c1-8)"
if [ -z "${AC_NO_CACHE:-}" ] && [ -s "$CACHE/$KEY.out" ]; then
  if [ -z "${AC_NEED_CLASSES:-}" ] || [ "$(cat "$OUT_ROOT/classes.key" 2>/dev/null)" = "$KEY" ]; then
    cat "$CACHE/$KEY.out"
    echo "compile.sh: replayed the result of an earlier run on identical sources (AC_NO_CACHE=1 to recompile)"
    exit "$(cat "$CACHE/$KEY.status")"
  fi
fi

# --- the actual run ---------------------------------------------------------------------------------------------------------
RESULT="$(mktemp "$OUT_ROOT/result.XXXXXX")"
{
  GEN="$OUT_ROOT/gen"
  rm -rf "$GEN"
  python3 -I "$HERE/tools/gen_r.py" "$APP" "$GEN" debug > /dev/null || { echo "gen_r.py failed"; exit 2; }
  python3 -I "$HERE/tools/check_res.py" "$APP" debug
  res_status=$?

  LIST="$OUT_ROOT/main.list"
  {
    for d in "$APP/src/main/java" "$APP/src/main/kotlin" "$APP/src/debug/java" "$APP/src/debug/kotlin"; do
      [ -d "$d" ] && find "$d" \( -name '*.kt' -o -name '*.java' \)
    done
    find "$GEN" -name '*.kt'
  } | sort > "$LIST"

  CLASSES="$OUT_ROOT/classes"
  rm -rf "$CLASSES" "$OUT_ROOT/classes.key" && mkdir -p "$CLASSES"
  LOG="$OUT_ROOT/compile.log"
  kotlinc -module-name app_debug \
    -Xplugin="$COMPOSE_PLUGIN" -Xplugin="$SERIALIZATION_PLUGIN" \
    -cp "$(compile_classpath)" -d "$CLASSES" @"$LIST" > "$LOG" 2>&1
  kc_status=$?

  python3 -I "$HERE/tools/report.py" "$LOG" "$REPO" "$HERE/baseline.txt"
  report_status=$?
  if [ "$kc_status" -ne 0 ] && [ "$report_status" -eq 0 ]; then
    echo "kotlinc exited with status $kc_status but no error was parsed; see $LOG" >&2
    report_status=1
  fi
  [ "$res_status" -ne 0 ] && report_status=1
  [ "$kc_status" -eq 0 ] && echo "$KEY" > "$OUT_ROOT/classes.key"
  elapsed=$(( $(date +%s) - start ))
  echo "compile.sh: $(wc -l < "$LIST") files, ${elapsed}s, $([ "$report_status" -eq 0 ] && echo OK || echo FAILED)"
  exit "$report_status"
} 2>&1 | tee "$RESULT"
status=${PIPESTATUS[0]}
if [ "$status" -le 1 ]; then
  cp "$RESULT" "$CACHE/$KEY.out"; echo "$status" > "$CACHE/$KEY.status"
  ls -1t "$CACHE"/*.out 2>/dev/null | tail -n +25 | while read -r f; do rm -f "$f" "${f%.out}.status"; done
fi
rm -f "$RESULT"
exit "$status"
