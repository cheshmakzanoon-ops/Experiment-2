#!/bin/bash
# unit.sh -- offline stand-in for `./gradlew testDebugUnitTest` (JVM unit tests, JUnit 4).
#
# Compiles the app (compile.sh), compiles src/test with the same compiler + plugins, and runs the tests with JUnitCore on a
# classpath that mimics AGP's: test classes, main classes, test resources, a "mockable android.jar" (built from android-all with
# every method returning 0/false/null, like `unitTests.isReturnDefaultValues = true`) and the library jars.
# Limits (see README): the Compose runtime cannot run (androidx.collection is only on Google Maven), Hilt/Room generated code
# does not exist, and androidx.* APIs come from hand-written shims.
#
# usage: unit.sh [--no-compile] [-v] [-k SUBSTRING]... [fully.qualified.TestClass[#method]]...
#   no selection      every test class (a class counts as a test when it has @Test methods)
#   -k data/          (substring of the fully qualified name, e.g. -k presentation. -k CanvasMetadata) keeps matching classes
#   --no-compile      reuse out/classes from the last compile.sh run instead of compiling the main sources again
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export AC_ROOT="$HERE"
# shellcheck source=tools/env.sh
source "$HERE/tools/env.sh"

nocompile=0; runner_args=()
while [ $# -gt 0 ]; do
  case "$1" in
    --no-compile) nocompile=1 ;;
    -v) runner_args+=("-v") ;;
    -k) runner_args+=("-k" "$2"); shift ;;
    -h|--help) sed -n 2,17p "${BASH_SOURCE[0]}"; exit 0 ;;
    *) runner_args+=("$1") ;;
  esac
  shift
done

exec 9> "$OUT_ROOT/.lock"
flock -n 9 || { echo "unit.sh: another harness run is using $OUT_ROOT; waiting..." >&2; flock 9; }
export AC_LOCK_HELD=1

bash "$HERE/setup.sh" compile unit || exit 2
CLASSES="$OUT_ROOT/classes"
if [ "$nocompile" = 0 ] || [ ! -d "$CLASSES" ]; then
  AC_NEED_CLASSES=1 bash "$HERE/compile.sh" || { echo "unit.sh: main sources do not compile; fix those errors first" >&2; exit 1; }
fi

# --- mockable android jar (once) -------------------------------------------------------------------------------------
MOCKABLE="$LIBS/mockable-android.jar"
if [ ! -s "$MOCKABLE" ]; then
  echo ">> building mockable-android.jar from android-all (one-off, ~20 s)"
  java -Xmx2g -cp "$GRADLE_LIB/asm-9.7.1.jar:$GRADLE_LIB/asm-tree-9.7.1.jar" "$HERE/tools/Mockable.java" "$ANDROID_JAR" "$MOCKABLE.tmp" 2>&1 \
    | grep -v '^Picked up' | tail -3
  mv "$MOCKABLE.tmp" "$MOCKABLE" || exit 2
fi

# --- launcher ----------------------------------------------------------------------------------------------------------
TESTLIBS=""; for j in "$LIBS"/test/*.jar; do TESTLIBS="$TESTLIBS:$j"; done
RUNNER="$OUT_ROOT/runner"
if [ ! -f "$RUNNER/RunTests.class" ] || [ "$HERE/tools/RunTests.java" -nt "$RUNNER/RunTests.class" ]; then
  mkdir -p "$RUNNER"
  javac -nowarn -cp "$LIBS/test/junit-4.13.2.jar" -d "$RUNNER" "$HERE/tools/RunTests.java" 2>&1 | grep -v '^Picked up'
fi

# --- test sources ------------------------------------------------------------------------------------------------------
TCLASSES="$OUT_ROOT/test-classes"
TLIST="$OUT_ROOT/test.list"
{ for d in "$APP/src/test/java" "$APP/src/test/kotlin"; do [ -d "$d" ] && find "$d" -name '*.kt'; done; } | sort > "$TLIST"
rm -rf "$TCLASSES" && mkdir -p "$TCLASSES"
TLOG="$OUT_ROOT/test-compile.log"
echo ">> compiling $(wc -l < "$TLIST") test files"
kotlinc -module-name app_debugUnitTest -Xfriend-paths="$CLASSES" \
  -Xplugin="$COMPOSE_PLUGIN" -Xplugin="$SERIALIZATION_PLUGIN" \
  -cp "$(compile_classpath)$TESTLIBS:$CLASSES" -d "$TCLASSES" @"$TLIST" > "$TLOG" 2>&1
kc=$?
python3 -I "$HERE/tools/report.py" "$TLOG" "$REPO" "$HERE/baseline.txt"
rs=$?
if [ "$kc" -ne 0 ] || [ "$rs" -ne 0 ]; then echo "unit.sh: test sources do not compile" >&2; exit 1; fi

# --- run ----------------------------------------------------------------------------------------------------------------
RUNCP="$TCLASSES:$CLASSES:$APP/src/test/resources:$MOCKABLE:$LIBS/shims.jar"
for j in "$LIBS"/jvm/*.jar "$LIBS"/aar/*.jar; do RUNCP="$RUNCP:$j"; done
RUNCP="$RUNCP$TESTLIBS:$STDLIB:$RUNNER"
WORK="$OUT_ROOT/unit-cwd"; rm -rf "$WORK"; mkdir -p "$WORK"
cd "$WORK" || exit 2
java -Xmx3g -XX:+EnableDynamicAgentLoading -Djava.awt.headless=true -Djava.io.tmpdir="$WORK" \
  -cp "$RUNCP" RunTests "$TCLASSES" "${runner_args[@]+"${runner_args[@]}"}" 2>&1 | grep -v -e '^Picked up' -e '^WARNING: A Java agent' -e '^WARNING: Dynamic loading' \
    -e '^WARNING: If a serviceability' -e '^WARNING: Mockito' -e 'Mockito is currently self-attaching' -e '^WARNING: We recommend' \
    -e 'Sharing is only supported' -e '^OpenJDK 64-Bit Server VM warning'
exit "${PIPESTATUS[0]}"
