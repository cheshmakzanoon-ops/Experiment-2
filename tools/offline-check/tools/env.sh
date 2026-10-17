# env.sh -- sourced by compile.sh / lint.sh / unit.sh / shims/build.sh.  Defines paths and the kotlinc wrapper.
# Everything is overridable from the environment:
#   ARTFLOW_REPO   repository checkout (read-only for the harness; default /home/user/Experiment-2)
#   GRADLE_LIB     lib/ directory of a Gradle 8.14.x distribution (supplies the embeddable Kotlin 2.0.21 compiler)
#   KC_XMX         heap for the Kotlin compiler JVM (default 3g)
#   KC_EXTRA       extra kotlinc flags (word-split), e.g. -Xbackend-threads=0
HERE_ENV="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AC_ROOT="${AC_ROOT:-$(dirname "$HERE_ENV")}"
REPO="${ARTFLOW_REPO:-/home/user/Experiment-2}"
APP="$REPO/app"
LIBS="$AC_ROOT/libs"
OUT_ROOT="${AC_OUT:-$AC_ROOT/out}"
mkdir -p "$OUT_ROOT"

if [ -z "${GRADLE_LIB:-}" ]; then
  gbin="$(readlink -f "$(command -v gradle 2>/dev/null || echo /opt/gradle/bin/gradle)")"
  GRADLE_LIB="$(dirname "$(dirname "$gbin")")/lib"
fi
KOTLIN_VERSION=2.0.21
KC_JARS=(
  "$GRADLE_LIB/kotlin-compiler-embeddable-$KOTLIN_VERSION.jar"
  "$GRADLE_LIB/kotlin-stdlib-$KOTLIN_VERSION.jar"
  "$GRADLE_LIB/kotlin-reflect-$KOTLIN_VERSION.jar"
  "$GRADLE_LIB/kotlin-script-runtime-$KOTLIN_VERSION.jar"
  "$GRADLE_LIB/kotlin-daemon-embeddable-$KOTLIN_VERSION.jar"
  "$GRADLE_LIB/trove4j-1.0.20200330.jar"
  "$GRADLE_LIB/annotations-24.0.1.jar"
  "$GRADLE_LIB/kotlinx-coroutines-core-jvm-1.6.4.jar"
)
KC_CP="$(IFS=:; echo "${KC_JARS[*]}")"
STDLIB="$GRADLE_LIB/kotlin-stdlib-$KOTLIN_VERSION.jar"
COMPOSE_PLUGIN="$LIBS/plugins/kotlin-compose-compiler-plugin-embeddable-$KOTLIN_VERSION.jar"
SERIALIZATION_PLUGIN="$LIBS/plugins/kotlin-serialization-compiler-plugin-embeddable-$KOTLIN_VERSION.jar"
ANDROID_JAR="$LIBS/android-all.jar"

# kotlinc ARGS...   (K2 compiler, JDK 17 API surface like the CI toolchain, warnings off)
kotlinc() {
  java -Xmx"${KC_XMX:-3g}" -Xss8m -cp "$KC_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -nowarn -jvm-target 17 -Xjdk-release=17 ${KC_EXTRA:-} "$@"
}

# base_classpath: everything except the shims (used to build them)
base_classpath() {
  local cp="$STDLIB" j
  for j in "$LIBS"/jvm/*.jar "$LIBS"/aar/*.jar; do cp="$cp:$j"; done
  echo "$cp:$ANDROID_JAR"
}

# compile_classpath: what the app's main sources are compiled against
compile_classpath() {
  local cp="$STDLIB" j
  for j in "$LIBS"/jvm/*.jar "$LIBS"/aar/*.jar; do cp="$cp:$j"; done
  [ -f "$LIBS/shims.jar" ] && cp="$cp:$LIBS/shims.jar"
  echo "$cp:$ANDROID_JAR"
}
