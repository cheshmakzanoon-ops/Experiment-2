#!/usr/bin/env python3
"""Run the real affine transform kernels without downloading Gradle or pretending to run Android.

Requires Python 3.10+, an installed Kotlin compiler, and Java 17+. This compiles the
unchanged production pixel sources and the same checks called by LayerTransformTest.
It does not execute the JUnit runner, repository integration, Android, ktlint, or Detekt.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time

PIXELS = "app/src/main/java/com/artflow/studio/core/pixels"
PRODUCTION = [
    f"{PIXELS}/{name}.kt"
    for name in (
        "PixelBuffer", "SelectionMask", "ImageFilters", "BlendModes",
        "ColorSelection", "SelectionCoverage", "SelectionGeometry",
    )
]
PRODUCTION.append("app/src/main/java/com/artflow/studio/core/canvas/LayerTransform.kt")
ENUM_SOURCE = "app/src/main/java/com/artflow/studio/domain/model/layer/Layer.kt"
CHECKS = "app/src/test/java/com/artflow/studio/core/canvas/LayerTransformChecks.kt"


def enum_adapter(text: str) -> str:
    """Use the real enum, without bringing Android/serialization metadata into this probe."""
    marker = "enum class BlendMode("
    if text.count(marker) != 1:
        raise ValueError("Cannot identify the single production BlendMode enum")
    enum = text[text.index(marker):].strip()
    # BlendMode is the final declaration today. Fail instead of guessing after restructuring.
    if enum.count("enum class ") != 1 or not enum.endswith("}"):
        raise ValueError("BlendMode layout changed; review the standalone compilation adapter")
    if enum.count("{") != enum.count("}"):
        raise ValueError("BlendMode source is incomplete")
    return "package com.artflow.studio.domain.model.layer\n\n" + enum + "\n"


def execute(command: list[str], log: Path, timeout: float) -> str:
    """Preserve stdout/stderr and never turn a compiler or runtime failure into success."""
    with log.open("w", encoding="utf-8") as stream:
        result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, timeout=timeout, check=False)
    output = log.read_text(encoding="utf-8", errors="replace")
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}); see {log}:\n{output[-4000:]}")
    return output


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=root / "build/transform-kernel-checks")
    parser.add_argument("--heap-mb", type=int, default=96)
    args = parser.parse_args()
    if not 32 <= args.heap_mb <= 4096:
        parser.error("--heap-mb must be between 32 and 4096")
    compiler = shutil.which("kotlinc")
    java = shutil.which("java")
    if not compiler or not java:
        parser.error("Install Kotlin and a JDK first; this script never downloads tools")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    sources = PRODUCTION + [ENUM_SOURCE, CHECKS]
    report = {
        "status": "not-run",
        "scope": "Standalone JVM affine transform kernels; NOT a Gradle, JUnit, Android, lint, signing, or release result",
        "heap_mb": args.heap_mb,
        "compiler": compiler,
        "java": java,
        "sha256": {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in sources},
    }
    report_path = output / "transform-kernel-report.json"
    try:
        report["kotlin_version"] = execute([compiler, "-version"], output / "kotlin-version.txt", 30).strip()
        report["java_version"] = execute([java, "-version"], output / "java-version.txt", 30).strip()
        with tempfile.TemporaryDirectory(prefix="artflow-transform-") as temporary:
            temp = Path(temporary)
            adapter = enum_adapter((root / ENUM_SOURCE).read_text(encoding="utf-8"))
            enum_file = temp / "BlendMode.kt"
            enum_file.write_text(adapter, encoding="utf-8")
            # Preserve the exact adaptation for an independent reviewer.
            (output / "BlendMode-adapter.kt").write_text(adapter, encoding="utf-8")
            report["adapter_sha256"] = hashlib.sha256(adapter.encode()).hexdigest()
            jar = temp / "transform-checks.jar"
            compile_command = [
                compiler, *[str(root / name) for name in PRODUCTION],
                str(enum_file), str(root / CHECKS), "-jvm-target", "17", "-include-runtime", "-d", str(jar),
            ]
            report["compile_command"] = compile_command
            execute(compile_command, output / "compile.txt", 120)
            run_command = [
                java, f"-Xmx{args.heap_mb}m", "-cp", str(jar),
                "com.artflow.studio.core.canvas.LayerTransformChecks",
            ]
            report["run_command"] = run_command
            result = execute(run_command, output / "results.txt", 45)
            match = re.search(r"^PASS transform-kernel-groups=(\d+) checks=(\d+)$", result, re.MULTILINE)
            if not match:
                raise RuntimeError("The check runner did not report its completed groups")
            report["groups"] = int(match.group(1))
            report["comparisons_and_boundary_checks"] = int(match.group(2))
            report["status"] = "passed"
            print(result, end="")
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        report["status"] = "failed"
        report["error"] = str(error)
        print(f"FAIL: {error}")
    finally:
        report["duration_seconds"] = round(time.monotonic() - started, 3)
        report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(f"Evidence: {report_path}")
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
