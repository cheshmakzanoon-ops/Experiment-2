#!/usr/bin/env python3
"""Recognize proven Android 15/16 KB runtime crashes without masking app failures."""

from pathlib import Path
from dataclasses import dataclass
import re
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


def _failed_case(results_root: Path) -> tuple[str, str, str] | None:
    failures: list[tuple[str, str, str]] = []
    identities: set[tuple[str, str]] = set()
    found_xml = False
    for path in results_root.rglob("TEST-*.xml"):
        found_xml = True
        try:
            root = ET.parse(path).getroot()
        except (ET.ParseError, OSError):
            return None
        if root.tag not in ("testsuite", "testsuites"):
            return None
        # Some runners report a suite error only in the counter. Missing detail is
        # not permission to retry; inconsistent or invalid evidence fails closed.
        for suite in root.iter():
            if suite.tag in ("testsuite", "testsuites"):
                try:
                    if int(suite.get("errors", "0")) != 0:
                        return None
                    for attribute, tag in (("tests", "testcase"), ("failures", "failure"), ("skipped", "skipped")):
                        if attribute in suite.attrib and int(suite.get(attribute)) != len(list(suite.iter(tag))):
                            return None
                except ValueError:
                    return None
        # An <error> is a separate JUnit outcome, not an empty native-process failure.
        # Do not discard it merely because another testcase has a recognized crash.
        if any(True for _ in root.iter("error")):
            return None
        attached_failures = 0
        for case in root.iter("testcase"):
            classname, name = case.get("classname", ""), case.get("name", "")
            # These identifiers also select the exact UTP per-case log. Unsupported names,
            # duplicate cases/devices and missing identities cannot establish attribution.
            if not re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_.$]*", classname) or not re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*", name):
                return None
            if (classname, name) in identities:
                return None
            identities.add((classname, name))
            case_failures = case.findall("failure")
            if len(case_failures) > 1:
                return None
            if not case_failures:
                continue
            if case.find("skipped") is not None:
                return None
            attached_failures += 1
            failure = case_failures[0]
            # JUnit writers may put the entire exception in message/type and leave the
            # body empty. Those attributes are evidence too; ignoring them can turn
            # an application assertion into an apparently empty, retryable crash.
            failure_type = failure.get("type", "").strip()
            if failure_type not in ("", "java.lang.NullPointerException"):
                return None
            text = "\n".join(filter(None, (
                failure_type,
                failure.get("message", ""),
                "".join(failure.itertext()),
            )))
            failures.append((classname, name, text))
        if attached_failures != len(list(root.iter("failure"))):
            return None
    # One native/system interruption cannot explain multiple failed test identities.
    return failures[0] if found_xml and len(failures) == 1 else None


@dataclass(frozen=True)
class LogLine:
    index: int
    stamp: str
    pid: int
    tid: int
    tag: str
    message: str


LOG_LINE = re.compile(
    r"^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+[VDIWEFS]\s+([^:]+?)\s*:\s?(.*)$"
)
SYSTEM_FATAL = "*** FATAL EXCEPTION IN SYSTEM PROCESS:"


def _fatal_exception(line: LogLine) -> bool:
    return line.tag == "AndroidRuntime" and line.message.startswith(("FATAL EXCEPTION:", SYSTEM_FATAL))


def _case_window(text: str, classname: str, name: str) -> tuple[int, list[LogLine]] | None:
    lines = []
    for index, raw in enumerate(text.splitlines()):
        match = LOG_LINE.fullmatch(raw)
        if match:
            stamp, pid, tid, tag, message = match.groups()
            lines.append(LogLine(index, stamp, int(pid), int(tid), tag.strip(), message))
        elif "Fatal signal" in raw or "FATAL EXCEPTION" in raw:
            return None
    starts = [line for line in lines if line.tag == "TestRunner" and line.message.startswith("started:")]
    identity = f"{name}({classname})"
    if len(starts) != 1 or starts[0].message != "started: " + identity:
        return None
    start = starts[0]
    ends = [line for line in lines if line.tag == "TestRunner" and
            line.message in ("finished: " + identity, "failed: " + identity)]
    if any(line.pid != start.pid or line.index <= start.index or line.stamp < start.stamp for line in ends):
        return None
    end = min(ends, key=lambda line: line.index) if ends else None
    window = [line for line in lines if line.index > start.index and line.stamp >= start.stamp and
              (end is None or (line.index < end.index and line.stamp <= end.stamp))]
    return start.pid, window


def _exception_block(lines: list[LogLine], fatal: LogLine) -> list[LogLine]:
    block = []
    for line in lines:
        if line.index < fatal.index or (line.pid, line.tid, line.tag) != (fatal.pid, fatal.tid, "AndroidRuntime"):
            continue
        if line.index > fatal.index and _fatal_exception(line):
            break
        block.append(line)
    return block


def _reference_queue_event(lines: list[LogLine], fatal: LogLine, app_pid: int) -> bool:
    block = _exception_block(lines, fatal)
    system = fatal.message.startswith(SYSTEM_FATAL)
    if not fatal.message.endswith("ReferenceQueueDaemon") or (system and fatal.pid == app_pid):
        return False
    process = [line.message for line in block if line.message.startswith("Process:")]
    expected = f"Process: {'system_server' if system else 'com.artflow.studio'}, PID: {fatal.pid}"
    if system:
        if process and process != [expected]:
            return False
    elif fatal.pid != app_pid or process != [expected]:
        return False
    return _is_reference_queue_failure("\n".join(line.message for line in block))


def _app_art_event(lines: list[LogLine], fatal: LogLine, app_pid: int) -> bool:
    if fatal.pid != app_pid or "Fatal signal 11 (SIGSEGV)" not in fatal.message:
        return False
    declared_pid = re.search(r"\bpid (\d+) \(", fatal.message)
    declared_tid = re.search(r"\bin tid (\d+) \(", fatal.message)
    if (declared_pid is None or int(declared_pid.group(1)) != app_pid or
            declared_tid is None or int(declared_tid.group(1)) != fatal.tid):
        return False
    dumps: dict[tuple[int, int], list[str]] = {}
    for line in lines:
        if line.index > fatal.index and line.stamp >= fatal.stamp and line.tag == "DEBUG":
            dumps.setdefault((line.pid, line.tid), []).append(line.message)
    matching = []
    for messages in dumps.values():
        cmdlines = [message for message in messages if message.startswith("Cmdline:")]
        pids = [re.match(r"pid: (\d+), tid: (\d+),", message) for message in messages if message.startswith("pid:")]
        zero = [message for message in messages if re.match(r"\s*#00\s", message)]
        if (cmdlines == ["Cmdline: com.artflow.studio"] and len(pids) == 1 and pids[0] is not None and
                int(pids[0].group(1)) == app_pid and int(pids[0].group(2)) == fatal.tid and len(zero) == 1):
            matching.append("\n".join(messages))
    return len(matching) == 1 and _contains_art_sigsegv(matching[0])


def _interrupted_crash(text: str, classname: str, name: str, failure: str) -> bool:
    context = _case_window(text, classname, name)
    if context is None:
        return False
    app_pid, lines = context
    for line in lines:
        # Consider the first fatal in the app/system process. A later matching crash
        # must not disguise a different interruption, including a framework fatal.
        if _fatal_exception(line) and (line.pid == app_pid or line.message.startswith(SYSTEM_FATAL)):
            return _reference_queue_event(lines, line, app_pid) and (not failure.strip() or _is_reference_queue_failure(failure))
        if line.tag == "libc" and "Fatal signal" in line.message:
            if line.pid == app_pid or re.search(r"\bpid \d+ \(system_server\)", line.message):
                return not failure.strip() and _app_art_event(lines, line, app_pid)
    return False


def is_retryable(results_root: Path) -> bool:
    if not results_root.is_dir():
        return False

    case = _failed_case(results_root)
    if case is None:
        return False
    classname, name, failure = case
    logs = list(results_root.rglob(f"logcat-{classname}-{name}.txt"))
    if len(logs) != 1:
        return False
    try:
        text = logs[0].read_text(errors="replace")
    except OSError:
        return False
    return _interrupted_crash(text, classname, name, failure)


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
