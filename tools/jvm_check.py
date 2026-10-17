#!/usr/bin/env python3
"""Compile and test the Android-free part of ArtFlow with a bare Kotlin compiler.

The Gradle build needs the Android SDK and Google's Maven repository, which a sandbox or a
quick review checkout often lacks. Most of ``core/`` and ``domain/model`` is plain Kotlin, so this
script downloads kotlinc plus JUnit (once), compiles every main source file that does not import
Android, Compose, Hilt or Room, then compiles the unit tests that still resolve against that set
and runs them with JUnitCore. Files that cannot compile without Android are dropped from the run
(and listed), so the result covers the pixel, render, export, colour, canvas and brush logic and
nothing else. It does not replace ``./gradlew testDebugUnitTest``; CI remains the authority.

    python3 tools/jvm_check.py setup              # fetch kotlinc and jars into the cache
    python3 tools/jvm_check.py                    # compile everything, run every test
    python3 tools/jvm_check.py --test PixelBufferTest --test StrokeRasterizerTest
    python3 tools/jvm_check.py --compile-only     # just check that main sources compile
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CACHE = Path(os.environ.get("ARTFLOW_JVM_TOOLS", Path.home() / ".cache" / "artflow-jvm"))
KOTLIN_VERSION = "2.0.21"
KOTLIN_ZIP = f"https://github.com/JetBrains/kotlin/releases/download/v{KOTLIN_VERSION}/kotlin-compiler-{KOTLIN_VERSION}.zip"
MAVEN = "https://repo.maven.apache.org/maven2"
JARS = [
    "org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/1.7.3/kotlinx-serialization-json-jvm-1.7.3.jar",
    "org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.7.3/kotlinx-serialization-core-jvm-1.7.3.jar",
    "org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.10.2/kotlinx-coroutines-core-jvm-1.10.2.jar",
    "org/jetbrains/kotlinx/kotlinx-coroutines-test-jvm/1.10.2/kotlinx-coroutines-test-jvm-1.10.2.jar",
    "junit/junit/4.13.2/junit-4.13.2.jar",
    "org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar",
]
# Imports that need the Android SDK or an annotation processor, which this runner does not have.
ANDROID_IMPORT = re.compile(
    r"^import (android(?!\.graphics\.Color$)|androidx|dagger|javax\.inject|timber|coil|com\.google|org\.mockito|app\.cash"
    r"|kotlinx\.coroutines\.android)\b",
    re.M,
)
ERROR_LINE = re.compile(r"^(?P<file>[^\s:][^:]*\.kt):\d+:\d+: error:", re.M)

# The only Android classes the pure sources touch are Color.BLACK in Stroke.kt and the Compose
# Color constructor named by its full path in domain/model/Color.kt.
ANDROID_STUBS = """package android.graphics

object Color {
    const val BLACK: Int = -0x1000000
    const val WHITE: Int = -1
    const val TRANSPARENT: Int = 0
}
"""
COMPOSE_STUBS = """package androidx.compose.ui.graphics

class Color(val value: Long)

@Suppress("FunctionName")
fun Color(red: Int, green: Int, blue: Int, alpha: Int = 0xFF): Color = Color(0L)
"""


def download(url: str, target: Path) -> None:
    for attempt in range(5):
        try:
            with urllib.request.urlopen(url, timeout=180) as response, open(target, "wb") as out:
                shutil.copyfileobj(response, out)
            return
        except Exception as error:  # noqa: BLE001 - retry any transient network failure
            if attempt == 4:
                raise
            print(f"  retrying {url} ({error})", file=sys.stderr)
            time.sleep(3 * (attempt + 1))


def setup() -> None:
    CACHE.mkdir(parents=True, exist_ok=True)
    kotlinc = CACHE / "kotlinc" / "bin" / "kotlinc"
    if not kotlinc.exists():
        archive = CACHE / "kotlin.zip"
        print(f"downloading kotlinc {KOTLIN_VERSION}")
        download(KOTLIN_ZIP, archive)
        with zipfile.ZipFile(archive) as bundle:
            bundle.extractall(CACHE)
        for entry in (CACHE / "kotlinc" / "bin").iterdir():
            entry.chmod(0o755)
        archive.unlink()
    libs = CACHE / "libs"
    libs.mkdir(exist_ok=True)
    for path in JARS:
        jar = libs / path.rsplit("/", 1)[1]
        if not jar.exists():
            print(f"downloading {jar.name}")
            download(f"{MAVEN}/{path}", jar)
    print(f"ready in {CACHE}")


def classpath() -> str:
    jars = sorted((CACHE / "libs").glob("*.jar")) + [CACHE / "kotlinc" / "lib" / "kotlin-stdlib.jar"]
    return ":".join(str(jar) for jar in jars)


def kotlinc(sources: list[Path], out: Path, extra_cp: str = "") -> tuple[bool, str]:
    out.mkdir(parents=True, exist_ok=True)
    listing = out.parent / (out.name + ".args")
    listing.write_text("\n".join(str(source) for source in sources))
    plugin = CACHE / "kotlinc" / "lib" / "kotlinx-serialization-compiler-plugin.jar"
    command = [
        str(CACHE / "kotlinc" / "bin" / "kotlinc"),
        "-jvm-target", "17",
        "-nowarn",
        f"-Xplugin={plugin}",
        "-cp", classpath() + (":" + extra_cp if extra_cp else ""),
        "-d", str(out),
        f"@{listing}",
    ]
    result = subprocess.run(command, capture_output=True, text=True, cwd=ROOT)
    return result.returncode == 0, result.stdout + result.stderr


def compile_pruning(sources: list[Path], out: Path, extra_cp: str, label: str) -> tuple[list[Path], list[Path]]:
    """Compile, dropping files with errors until what remains compiles. Returns (kept, dropped)."""
    kept = list(sources)
    dropped: list[Path] = []
    for _ in range(40):
        ok, log = kotlinc(kept, out, extra_cp)
        if ok:
            return kept, dropped
        bad = {(ROOT / match.group("file")).resolve() for match in ERROR_LINE.finditer(log)}
        bad &= set(kept)
        if not bad:
            print(log)
            sys.exit(f"{label}: compiler failed without a source error")
        kept = [source for source in kept if source not in bad]
        dropped.extend(sorted(bad))
    sys.exit(f"{label}: gave up pruning")


def tracked_kotlin(base: str) -> list[Path]:
    root = ROOT / base
    return sorted(root.rglob("*.kt"))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", nargs="?", choices=["setup", "run"], default="run")
    parser.add_argument("--test", action="append", default=[], help="simple name of a test class to run (repeatable)")
    parser.add_argument("--compile-only", action="store_true", help="stop after compiling main sources")
    parser.add_argument("--strict", action="store_true",
                        help="fail when a main file that does not import Android still fails to compile")
    arguments = parser.parse_args()
    if arguments.command == "setup":
        setup()
        return
    if not (CACHE / "kotlinc" / "bin" / "kotlinc").exists():
        setup()

    work = Path(tempfile.mkdtemp(prefix="artflow-jvm-"))
    try:
        stub = work / "AndroidColor.kt"
        stub.write_text(ANDROID_STUBS)
        compose_stub = work / "ComposeColor.kt"
        compose_stub.write_text(COMPOSE_STUBS)
        main_all = tracked_kotlin("app/src/main/java")
        main_pure = [path for path in main_all if not ANDROID_IMPORT.search(path.read_text())]
        print(f"main: {len(main_pure)} of {len(main_all)} files import no Android")
        kept, dropped = compile_pruning(main_pure + [stub, compose_stub], work / "main", "", "main")
        kept = [path for path in kept if path not in (stub, compose_stub)]
        for path in dropped:
            print(f"  dropped (needs a dropped dependency): {path.relative_to(ROOT)}")
        print(f"main: {len(kept)} files compiled")
        if arguments.strict and dropped:
            sys.exit("--strict: pure sources failed to compile")
        if arguments.compile_only:
            return

        tests_all = tracked_kotlin("app/src/test/java")
        tests_pure = [path for path in tests_all if not ANDROID_IMPORT.search(path.read_text())]
        if arguments.test:
            wanted = set(arguments.test)
            tests_pure = [path for path in tests_pure if path.stem in wanted]
        print(f"tests: {len(tests_pure)} of {len(tests_all)} files import no Android")
        kept_tests, dropped_tests = compile_pruning(tests_pure, work / "test", str(work / "main"), "tests")
        for path in dropped_tests:
            print(f"  dropped test: {path.relative_to(ROOT)}")
        classes = []
        for path in kept_tests:
            text = path.read_text()
            if "@Test" not in text:
                continue
            package = re.search(r"^package ([\w.]+)", text, re.M)
            classes.append(f"{package.group(1)}.{path.stem}" if package else path.stem)
        print(f"tests: running {len(classes)} classes")
        command = [
            "java", "-cp", f"{work / 'test'}:{work / 'main'}:{ROOT / 'app/src/test/resources'}:{ROOT / 'app/src/main/resources'}:{classpath()}",
            "org.junit.runner.JUnitCore", *classes,
        ]
        result = subprocess.run(command)
        sys.exit(result.returncode)
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
