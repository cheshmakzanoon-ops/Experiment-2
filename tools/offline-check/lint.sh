#!/bin/bash
# lint.sh -- offline stand-in for `./gradlew ktlintCheck detekt`.
#
#   ktlint 1.3.1   standard rule set, configured by <repo>/.editorconfig (the Gradle plugin's `android.set(true)` has no effect
#                  with ktlint 1.x: the code style stays ktlint_official unless .editorconfig says otherwise, so no flag is passed)
#   detekt 1.23.6  <repo>/config/detekt/detekt.yml + built-in defaults (buildUponDefaultConfig) + <repo>/config/detekt/baseline.xml,
#                  without type resolution -- exactly what the plain Gradle `detekt` task does
#
# usage: lint.sh [--ktlint | --detekt] [--format] [FILE.kt ...]
#   no FILE            whole tree, as CI does:  ktlint -> app/src/**/*.kt + build scripts;  detekt -> app/src/{main,test}/{java,kotlin}
#   FILE.kt ...        only those files (detekt skips files outside app/src/main and app/src/test, as the Gradle task would)
#   --format           ktlint only: auto-correct the given files IN PLACE (the only mode that writes into the repository)
# output: path:line:col: message (rule)   -- exit status 1 when anything is reported, 2 on a harness problem
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export AC_ROOT="$HERE"
# shellcheck source=tools/env.sh
source "$HERE/tools/env.sh"
CALLER_PWD="$PWD"

run_k=1; run_d=1; format=0; files=()
for a in "$@"; do
  case "$a" in
    --ktlint) run_d=0 ;;
    --detekt) run_k=0 ;;
    --format) format=1 ;;
    -h|--help) sed -n 2,16p "${BASH_SOURCE[0]}"; exit 0 ;;
    -*) echo "lint.sh: unknown option $a" >&2; exit 2 ;;
    *) files+=("$a") ;;
  esac
done
bash "$HERE/setup.sh" lint || exit 2

# resolve file arguments to absolute paths (relative ones: against the caller's directory, then the repository root)
abs=()
for f in "${files[@]+"${files[@]}"}"; do
  if [ -e "$f" ]; then p="$(realpath "$f")"
  elif [ -e "$CALLER_PWD/$f" ]; then p="$(realpath "$CALLER_PWD/$f")"
  elif [ -e "$REPO/$f" ]; then p="$(realpath "$REPO/$f")"
  else echo "lint.sh: no such file: $f" >&2; exit 2; fi
  abs+=("$p")
done

cd "$REPO" || exit 2
status=0
TMP="$(mktemp -d "$OUT_ROOT/lint.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

# ---------------------------------------------------------------------------------------------------------------- ktlint
if [ "$run_k" = 1 ]; then
  # NB: ktlint adds its default patterns (= the whole tree) as soon as one '!negated' pattern is present, so files are
  # enumerated here and 'generated/' directories (excluded by the Gradle plugin) are filtered out by hand.
  kt_args=()
  if [ "${#abs[@]}" -gt 0 ]; then
    for p in "${abs[@]}"; do case "$p" in */generated/*) ;; *.kt|*.kts) kt_args+=("$p") ;; esac; done
  else
    while IFS= read -r p; do kt_args+=("$p"); done < <({ find app/src -name '*.kt' -not -path '*/generated/*'; ls ./*.kts app/*.kts 2>/dev/null; } | sort)
  fi
  if [ "${#kt_args[@]}" -gt 0 ]; then
    fmt=(); [ "$format" = 1 ] && fmt=(--format)
    java -jar "$LIBS/ktlint.jar" --relative --log-level=error "${fmt[@]+"${fmt[@]}"}" "${kt_args[@]}" > "$TMP/ktlint.txt" 2>&1
    k_status=$?
    # findings are `path:line:col: message (rule)`; ktlint's own summary/log lines are dropped unless nothing else explains the failure
    out="$(grep -E '\.kts?:[0-9]+:[0-9]+: ' "$TMP/ktlint.txt")"
    [ -n "$out" ] && echo "$out"
    n=$(printf '%s\n' "$out" | grep -c -E '\.kts?:[0-9]+:[0-9]+: ')
    echo "ktlint: $n finding(s)"
    if [ "$k_status" -ne 0 ]; then
      status=1
      if [ "$n" -eq 0 ]; then echo "ktlint exited with status $k_status but reported no finding; raw output:"; grep -v '^Picked up' "$TMP/ktlint.txt" | head -40; fi
    fi
  else
    echo "ktlint: no .kt/.kts files given"
  fi
fi

# --------------------------------------------------------------------------------------------------------------- detekt
if [ "$run_d" = 1 ] && [ "$format" = 0 ]; then
  inputs=()
  if [ "${#abs[@]}" -gt 0 ]; then
    for p in "${abs[@]}"; do
      case "$p" in
        "$APP"/src/main/*.kt|"$APP"/src/test/*.kt) inputs+=("$p") ;;
        *.kt) echo "detekt: skipping $(realpath --relative-to="$REPO" "$p") (the Gradle task only analyses src/main and src/test)" ;;
      esac
    done
  else
    for d in app/src/main/java app/src/main/kotlin app/src/test/java app/src/test/kotlin; do [ -d "$REPO/$d" ] && inputs+=("$d"); done
  fi
  if [ "${#inputs[@]}" -gt 0 ]; then
    input_csv="$(IFS=,; echo "${inputs[*]}")"
    java -jar "$LIBS/detekt.jar" --input "$input_csv" --config config/detekt/detekt.yml --build-upon-default-config \
      --baseline config/detekt/baseline.xml --parallel > "$TMP/detekt.txt" 2>&1
    d_status=$?
    raw="$(grep -v '^Picked up' "$TMP/detekt.txt")"
    # keep only the "path:line:col: message [Rule]" lines and print them repo-relative
    out="$(printf '%s\n' "$raw" | grep -E '^/.*\.kt:[0-9]+:[0-9]+: ' | sed -e "s|^$REPO/||" | sort -u)"
    [ -n "$out" ] && echo "$out"
    n=$(printf '%s\n' "$out" | grep -c -E '\.kt:[0-9]+:[0-9]+:')
    # anything detekt says that is not a finding (config errors, crashes) must not be swallowed
    other="$(printf '%s\n' "$raw" | grep -v -E '^/.*\.kt:[0-9]+:[0-9]+: |^\s*$|^\s+.*\[.*\] at /|Overall debt|Analysis failed|^[A-Za-z]+ - |^[a-z-]+ - |detekt finished' | head -20)"
    echo "detekt: $n finding(s)"
    if [ "$d_status" -ne 0 ]; then
      status=1
      if [ "$n" -eq 0 ]; then echo "detekt exited with status $d_status but reported no finding; raw output:"; printf '%s\n' "$raw" | head -40; fi
    fi
    [ -n "$other" ] && [ "$n" -eq 0 ] && [ "$d_status" -eq 0 ] && { echo "detekt output:"; echo "$other"; }
  fi
fi
exit "$status"
