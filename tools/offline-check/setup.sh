#!/bin/bash
# setup.sh -- download and prepare every third-party artifact the harness needs (idempotent; nothing is fetched
# when libs/ is already complete).  All downloads come from Maven Central (repo.maven.apache.org) and are SHA-1
# verified against Central's own checksum files; the JetBrains Compose / test jars are resolved by a scratch Gradle
# build (resolve/) that only talks to Maven Central.  Nothing is fetched from Google Maven (dl.google.com is blocked).
#
# usage: setup.sh [compile] [lint] [unit]     (default: compile lint)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export AC_ROOT="$HERE"
# shellcheck source=tools/env.sh
source "$HERE/tools/env.sh"
what=("$@"); [ "${#what[@]}" -eq 0 ] && what=(compile lint)
mkdir -p "$LIBS"/{jvm,aar,test,plugins} "$HERE/dl"

mvn() { bash "$HERE/tools/mvn.sh" "$@"; }
log() { echo "setup: $*" >&2; }

# --- pinned versions ----------------------------------------------------------------------------------------------
ANDROID_ALL=16-robolectric-13921718        # Android 16 / API 36 framework classes (compileSdk 36)
HILT=2.54; COIL=2.5.0; TIMBER=5.0.1; OKHTTP=4.12.0; OKIO=3.6.0
KTLINT=1.3.1; DETEKT=1.23.6

setup_compile() {
  [ -e "$LIBS/.compile-ready" ] && return 0
  log "android-all $ANDROID_ALL (≈190 MB)"
  local aa; aa="$(mvn org.robolectric:android-all:$ANDROID_ALL)" || return 1
  ln -sf "$aa" "$LIBS/android-all.jar"

  log "compiler plugins"
  for p in kotlin-compose-compiler-plugin-embeddable kotlin-serialization-compiler-plugin-embeddable; do
    cp "$(mvn org.jetbrains.kotlin:$p:$KOTLIN_VERSION)" "$LIBS/plugins/" || return 1
  done

  log "AARs (Hilt, Coil, Timber) -> classes.jar"
  local c f d
  for c in com.google.dagger:hilt-android:$HILT io.coil-kt:coil-compose:$COIL io.coil-kt:coil-compose-base:$COIL \
           io.coil-kt:coil-base:$COIL io.coil-kt:coil:$COIL com.jakewharton.timber:timber:$TIMBER; do
    f="$(mvn "$c:aar")" || return 1
    d="$(dirname "$f")/aar-unzipped"; rm -rf "$d"; mkdir -p "$d"
    unzip -q -o "$f" classes.jar -d "$d" || return 1
    cp "$d/classes.jar" "$LIBS/aar/$(basename "$f" .aar).jar"
  done

  log "plain jars (Dagger/Hilt core, javax.inject, OkHttp/Okio for Coil)"
  for c in com.google.dagger:hilt-core:$HILT com.google.dagger:dagger:$HILT javax.inject:javax.inject:1 \
           com.squareup.okhttp3:okhttp:$OKHTTP com.squareup.okio:okio-jvm:$OKIO; do
    cp "$(mvn "$c")" "$LIBS/jvm/" || return 1
  done

  log "Compose Multiplatform 1.7.3 desktop jars + coroutines + serialization via Gradle (Maven Central only)"
  resolve_with_gradle || return 1
  touch "$LIBS/.compile-ready"
}

# Resolves the JVM flavour of the libraries with a throw-away Gradle build; GRADLE_USER_HOME stays inside the workspace.
resolve_with_gradle() {
  [ -s "$HERE/dl/resolved.txt" ] && grep -q '^mainApi|' "$HERE/dl/resolved.txt" && { copy_resolved; return 0; }
  export GRADLE_USER_HOME="$HERE/gradle-home"; mkdir -p "$GRADLE_USER_HOME"
  local i
  for i in 1 2 3 4 5 6; do
    if /opt/gradle/bin/gradle --no-daemon -q -p "$HERE/resolve" printResolved -PoutFile="$HERE/dl/resolved.txt" \
         > "$HERE/dl/resolve.out" 2> "$HERE/dl/resolve.err"; then copy_resolved; return 0; fi
    log "gradle resolution attempt $i failed (Maven Central rate limit?); retrying"; sleep $((i * 15))
  done
  grep -v '^Picked up' "$HERE/dl/resolve.err" | head -20 >&2
  return 1
}

copy_resolved() {
  local cfg path
  while IFS='|' read -r cfg path; do
    case "$(basename "$path")" in kotlin-stdlib-*|annotations-*) continue ;; esac
    case "$cfg" in
      mainApi) cp -f "$path" "$LIBS/jvm/" ;;
      testRuntime) case "$(basename "$path")" in kotlinx-coroutines-core*) ;; *) cp -f "$path" "$LIBS/test/" ;; esac ;;
    esac
  done < <(grep -v '^#' "$HERE/dl/resolved.txt")
}

setup_lint() {
  [ -e "$LIBS/ktlint.jar" ] && [ -e "$LIBS/detekt.jar" ] && return 0
  log "ktlint $KTLINT and detekt $DETEKT (shaded CLI jars)"
  ln -sf "$(mvn com.pinterest.ktlint:ktlint-cli:$KTLINT:jar:all)" "$LIBS/ktlint.jar" || return 1
  ln -sf "$(mvn io.gitlab.arturbosch.detekt:detekt-cli:$DETEKT:jar:all)" "$LIBS/detekt.jar" || return 1
}

setup_unit() {
  setup_compile || return 1
  [ -e "$LIBS/.unit-ready" ] && return 0
  resolve_with_gradle || return 1
  touch "$LIBS/.unit-ready"
}

for w in "${what[@]}"; do
  case "$w" in
    compile) setup_compile || { log "FAILED: compile"; exit 1; } ;;
    lint) setup_lint || { log "FAILED: lint"; exit 1; } ;;
    unit) setup_unit || { log "FAILED: unit"; exit 1; } ;;
    *) echo "unknown target $w" >&2; exit 2 ;;
  esac
done
