#!/usr/bin/env python3
"""Recognize one known Android 15 framework crash without masking app failures."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

CRASH_MARKERS = (
    "java.lang.NullPointerException",
    "ReferenceQueue.enqueuePending",
    "Daemons$ReferenceQueueDaemon",
)


def _contains_reference_queue_crash(text: str) -> bool:
    return all(marker in text for marker in CRASH_MARKERS)


def is_retryable(results_root: Path) -> bool:
    if not results_root.is_dir():
        return False

    crash_reports = list(results_root.rglob("logcat-com.artflow.studio-crash-report.txt"))
    if not crash_reports:
        return False
    if not any(_contains_reference_queue_crash(path.read_text(errors="replace")) for path in crash_reports):
        return False

    failures = []
    for path in results_root.rglob("TEST-*.xml"):
        try:
            root = ET.parse(path).getroot()
        except (ET.ParseError, OSError):
            return False
        for failure in root.iter("failure"):
            failures.append("".join(failure.itertext()))

    if not failures:
        return False
    return all(_contains_reference_queue_crash(failure) for failure in failures)


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: classify_android15_reference_queue_crash.py <android-test-results>", file=sys.stderr)
        return 2
    root = Path(argv[1])
    if is_retryable(root):
        print("retryable Android 15 ReferenceQueueDaemon framework crash")
        return 0
    print("not a retryable framework-only crash", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
