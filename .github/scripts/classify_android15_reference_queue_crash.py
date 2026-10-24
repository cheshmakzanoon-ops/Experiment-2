#!/usr/bin/env python3
"""Recognize proven Android 15/16 KB runtime crashes without masking app failures."""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET

REFERENCE_QUEUE_MARKERS = (
    "java.lang.NullPointerException",
    "ReferenceQueue.enqueuePending",
    "Daemons$ReferenceQueueDaemon",
)
ART_SIGSEGV_MARKERS = (
    "google/sdk_gphone16k_x86_64/emu64xa16k:15/",
    "Page size: 16384 bytes",
    "Cmdline: com.artflow.studio",
    "signal 11 (SIGSEGV)",
    "#00",
    "/apex/com.android.art/lib64/libart.so",
)


def _contains_reference_queue_crash(text: str) -> bool:
    return all(marker in text for marker in REFERENCE_QUEUE_MARKERS)


def _is_reference_queue_failure(text: str) -> bool:
    # A matching framework stack can appear as the cause of an application/test
    # failure. A wrapper assertion or application frame still makes that outcome
    # ineligible for infrastructure recovery.
    unrelated = ("AssertionError", "AssertionFailedError", "ComparisonFailure", "at com.artflow.")
    return _contains_reference_queue_crash(text) and not any(marker in text for marker in unrelated)


def _contains_art_sigsegv(text: str) -> bool:
    if not all(marker in text for marker in ART_SIGSEGV_MARKERS):
        return False
    frame_zero = next((line for line in text.splitlines() if "#00" in line), "")
    return "/apex/com.android.art/lib64/libart.so" in frame_zero


def _failure_texts(results_root: Path) -> list[str] | None:
    failures: list[str] = []
    found_xml = False
    for path in results_root.rglob("TEST-*.xml"):
        found_xml = True
        try:
            root = ET.parse(path).getroot()
        except (ET.ParseError, OSError):
            return None
        # Some runners report a suite error only in the counter. Missing detail is
        # not permission to retry; inconsistent or invalid evidence fails closed.
        for suite in root.iter():
            if suite.tag in ("testsuite", "testsuites"):
                try:
                    if int(suite.get("errors", "0")) != 0:
                        return None
                except ValueError:
                    return None
        # An <error> is a separate JUnit outcome, not an empty native-process failure.
        # Do not discard it merely because another testcase has a recognized crash.
        if any(True for _ in root.iter("error")):
            return None
        for failure in root.iter("failure"):
            # JUnit writers may put the entire exception in message/type and leave the
            # body empty. Those attributes are evidence too; ignoring them can turn
            # an application assertion into an apparently empty, retryable crash.
            failure_type = failure.get("type", "").strip()
            if failure_type not in ("", "java.lang.NullPointerException"):
                return None
            failures.append("\n".join(filter(None, (
                failure_type,
                failure.get("message", ""),
                "".join(failure.itertext()),
            ))))
    return failures if found_xml else None


def is_retryable(results_root: Path) -> bool:
    if not results_root.is_dir():
        return False

    failures = _failure_texts(results_root)
    if failures is None or not failures:
        return False

    logs = []
    for path in results_root.rglob("*.txt"):
        try:
            logs.append(path.read_text(errors="replace"))
        except OSError:
            return False

    reference_queue = any(_contains_reference_queue_crash(text) for text in logs)
    if reference_queue:
        # When system_server itself dies, the app's interrupted test has an empty failure element.
        return all(not text.strip() or _is_reference_queue_failure(text) for text in failures)

    art_sigsegv = any(_contains_art_sigsegv(text) for text in logs)
    if not art_sigsegv:
        return False

    # Native process crashes generate empty JUnit failure elements. Any Java/Kotlin assertion or
    # exception text means this is not the narrow emulator-runtime case and must remain red.
    return all(not text.strip() for text in failures)


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: classify_android15_reference_queue_crash.py <android-test-results>", file=sys.stderr)
        return 2
    root = Path(argv[1])
    if is_retryable(root):
        print("retryable Android 15/16 KB framework runtime crash")
        return 0
    print("not a retryable framework-only crash", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
