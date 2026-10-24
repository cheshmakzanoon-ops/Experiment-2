import tempfile
from pathlib import Path
import unittest

from classify_android15_reference_queue_crash import is_retryable

CLASSNAME = "com.artflow.studio.RuntimeTest"
CASE = "interrupted"
CASE_OPEN = '<testcase classname="' + CLASSNAME + '" name="' + CASE + '">'
START = f"10-24 10:00:00.000  101  102 I TestRunner: started: {CASE}({CLASSNAME})\n"
FINISH = f"10-24 10:00:00.050  101  102 I TestRunner: finished: {CASE}({CLASSNAME})\n"

REFERENCE_QUEUE_FAILURE = """java.lang.NullPointerException: runtime failure
at java.lang.ref.ReferenceQueue.enqueuePending(ReferenceQueue.java:239)
at java.lang.Daemons$ReferenceQueueDaemon.runInternal(Daemons.java:260)
"""


def records(messages, pid=101, tid=110, tag="AndroidRuntime", time="10:00:00.100"):
    return "".join(f"10-24 {time}  {pid}  {tid} E {tag}: {message}\n" for message in messages.splitlines())


REFERENCE_QUEUE_LOG = records(
    "FATAL EXCEPTION: ReferenceQueueDaemon\nProcess: com.artflow.studio, PID: 101\n" + REFERENCE_QUEUE_FAILURE
)
SYSTEM_REFERENCE_QUEUE_LOG = records(
    "*** FATAL EXCEPTION IN SYSTEM PROCESS: ReferenceQueueDaemon\n" + REFERENCE_QUEUE_FAILURE, pid=201, tid=210
)
ART_SIGNAL = records(
    "Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x8 in tid 101 (.artflow.studio), pid 101 (.artflow.studio)",
    pid=101, tid=101, tag="libc",
)
ART_DUMP = records("""Build fingerprint: 'google/sdk_gphone16k_x86_64/emu64xa16k:15/AE3A.240806.043/12960925:userdebug/dev-keys'
Page size: 16384 bytes
Cmdline: com.artflow.studio
pid: 101, tid: 101, name: .artflow.studio  >>> com.artflow.studio <<<
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x8
backtrace:
      #00 pc 00000000008519ea  /apex/com.android.art/lib64/libart.so (art::mirror::Class::IsInSamePackage+154)
""", pid=303, tid=303, tag="DEBUG", time="10:00:00.200")
ART_SIGSEGV = ART_SIGNAL + ART_DUMP


class Android15RuntimeClassifierTest(unittest.TestCase):
    def fixture(self, failure=REFERENCE_QUEUE_FAILURE, log=REFERENCE_QUEUE_LOG):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        report = self.log_path(root)
        report.parent.mkdir(parents=True)
        report.write_text(START + log)
        xml = root / "TEST-emulator.xml"
        escaped = failure.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        xml.write_text("<testsuite>" + CASE_OPEN + "<failure>" + escaped + "</failure></testcase></testsuite>")
        return root

    def log_path(self, root):
        return root / "device" / f"logcat-{CLASSNAME}-{CASE}.txt"

    def test_reference_queue_framework_crash_is_retryable(self):
        self.assertTrue(is_retryable(self.fixture()))

    def test_reference_queue_system_crash_with_empty_process_failure_is_retryable(self):
        self.assertTrue(is_retryable(self.fixture(failure="", log=SYSTEM_REFERENCE_QUEUE_LOG)))

    def test_app_assertion_is_never_retryable_even_with_reference_queue_crash(self):
        self.assertFalse(is_retryable(self.fixture(failure="java.lang.AssertionError: pixels changed")))

    def test_android15_16k_libart_sigsegv_with_empty_process_failure_is_retryable(self):
        self.assertTrue(is_retryable(self.fixture(failure="", log=ART_SIGSEGV)))

    def test_app_assertion_is_never_retryable_even_with_matching_art_tombstone(self):
        self.assertFalse(is_retryable(self.fixture(failure="java.lang.AssertionError: pixels changed", log=ART_SIGSEGV)))

    def test_message_only_assertion_is_not_an_empty_native_failure(self):
        root = self.fixture(failure="", log=ART_SIGSEGV)
        (root / "TEST-emulator.xml").write_text(
            '<testsuite>' + CASE_OPEN + '<failure message="java.lang.AssertionError: pixels changed"/></testcase></testsuite>'
        )
        self.assertFalse(is_retryable(root))

    def test_failure_type_cannot_hide_an_assertion(self):
        root = self.fixture(failure="", log=ART_SIGSEGV)
        (root / "TEST-emulator.xml").write_text(
            '<testsuite>' + CASE_OPEN + '<failure type="java.lang.AssertionError"/></testcase></testsuite>'
        )
        self.assertFalse(is_retryable(root))

    def test_reference_queue_text_cannot_override_an_assertion_attribute(self):
        root = self.fixture()
        (root / "TEST-emulator.xml").write_text(
            '<testsuite>' + CASE_OPEN + '<failure type="java.lang.AssertionError" message="unexpected crash">'
            + REFERENCE_QUEUE_FAILURE + '</failure></testcase></testsuite>'
        )
        self.assertFalse(is_retryable(root))

    def test_reference_queue_cause_does_not_hide_an_application_failure(self):
        for extra in (
            'java.lang.AssertionError: artwork changed\n',
            'junit.framework.AssertionFailedError: artwork changed\n',
            'org.junit.ComparisonFailure: artwork changed\n',
            'at com.artflow.studio.Editor.apply(Editor.kt:42)\n',
        ):
            with self.subTest(extra=extra):
                root = self.fixture(failure=extra + REFERENCE_QUEUE_FAILURE)
                self.assertFalse(is_retryable(root))

    def test_error_alongside_empty_native_failure_is_never_retryable(self):
        for error in (
            '<error>java.lang.AssertionError: artwork changed</error>',
            '<error message="java.lang.IllegalStateException: document unavailable"/>',
            '<error/>',
        ):
            with self.subTest(error=error):
                root = self.fixture(failure="", log=ART_SIGSEGV)
                (root / "TEST-emulator.xml").write_text(
                    '<testsuite>' + CASE_OPEN + '<failure/></testcase><testcase>' + error + '</testcase></testsuite>'
                )
                self.assertFalse(is_retryable(root))

    def test_failure_in_another_report_cannot_be_ignored(self):
        root = self.fixture(failure="", log=ART_SIGSEGV)
        (root / "device" / "TEST-app.xml").write_text(
            '<testsuite><testcase classname="' + CLASSNAME + '" name="pixelAssertion">'
            + '<failure message="wrong exported pixels"/></testcase></testsuite>'
        )
        self.assertFalse(is_retryable(root))

    def test_suite_error_count_cannot_be_hidden_by_an_empty_native_failure(self):
        for count in ("1", "-1", "not-a-number"):
            with self.subTest(count=count):
                root = self.fixture(failure="", log=ART_SIGSEGV)
                (root / "TEST-emulator.xml").write_text(
                    '<testsuites errors="' + count + '"><testsuite tests="1" failures="1">'
                    + CASE_OPEN + '<failure/></testcase></testsuite></testsuites>'
                )
                self.assertFalse(is_retryable(root))

    def test_reference_queue_exception_attributes_are_kept_as_evidence(self):
        root = self.fixture()
        (root / "TEST-emulator.xml").write_text(
            '<testsuite>' + CASE_OPEN + '<failure type="java.lang.NullPointerException" message="runtime failure">'
            + REFERENCE_QUEUE_FAILURE + '</failure></testcase></testsuite>'
        )
        self.assertTrue(is_retryable(root))

    def test_non_art_top_frame_is_not_retryable(self):
        log = ART_SIGSEGV.replace(
            "#00 pc 00000000008519ea  /apex/com.android.art/lib64/libart.so",
            "#00 pc 0000000000001234  /data/app/lib/arm64/libartflow.so",
        )
        self.assertFalse(is_retryable(self.fixture(failure="", log=log)))

    def test_wrong_runtime_or_page_size_is_not_retryable(self):
        self.assertFalse(is_retryable(self.fixture(failure="", log=ART_SIGSEGV.replace("Page size: 16384 bytes", "Page size: 4096 bytes"))))
        self.assertFalse(is_retryable(self.fixture(failure="", log=ART_SIGSEGV.replace(":15/", ":16/"))))

    def test_unrelated_runtime_crash_is_not_retryable(self):
        self.assertFalse(
            is_retryable(
                self.fixture(
                    failure="java.lang.NullPointerException at com.artflow.studio.Editor",
                    log="java.lang.NullPointerException at com.artflow.studio.Editor",
                )
            )
        )

    def test_reference_queue_in_an_earlier_passing_case_cannot_allow_retry(self):
        root = self.fixture(failure="", log="")
        passed = root / "device" / f"logcat-{CLASSNAME}-earlierPassed.txt"
        passed.write_text(START.replace(CASE, "earlierPassed") + REFERENCE_QUEUE_LOG)
        xml = root / "TEST-emulator.xml"
        xml.write_text(xml.read_text().replace(
            "</testsuite>", f'<testcase classname="{CLASSNAME}" name="earlierPassed"/></testsuite>'
        ))
        self.assertFalse(is_retryable(root))

    def test_foreign_reference_queue_in_the_failed_case_window_cannot_allow_retry(self):
        for process in ("com.google.android.gms.persistent", "com.google.android.googlequicksearchbox"):
            with self.subTest(process=process):
                log = records(
                    f"FATAL EXCEPTION: ReferenceQueueDaemon\nProcess: {process}, PID: 201\n" + REFERENCE_QUEUE_FAILURE,
                    pid=201, tid=210,
                )
                self.assertFalse(is_retryable(self.fixture(failure="", log=log)))

    def test_reference_queue_markers_must_share_a_process_thread_and_fatal_block(self):
        missing = REFERENCE_QUEUE_LOG.splitlines(keepends=True)
        tail = missing.pop()
        for replacement in (tail.replace(" 101  110 ", " 201  110 "), tail.replace(" 101  110 ", " 101  111 ")):
            with self.subTest(replacement=replacement):
                self.assertFalse(is_retryable(self.fixture(failure="", log="".join(missing) + replacement)))
        app_frame = records("at com.artflow.studio.Editor.apply(Editor.kt:42)")
        self.assertFalse(is_retryable(self.fixture(failure="", log=REFERENCE_QUEUE_LOG + app_frame)))

    def test_crashes_outside_the_interrupted_case_window_are_rejected(self):
        for log in (
            REFERENCE_QUEUE_LOG + START,
            START + REFERENCE_QUEUE_LOG.replace("10:00:00.100", "09:59:59.900"),
            START + FINISH + REFERENCE_QUEUE_LOG,
            ART_SIGNAL + START + ART_DUMP,
        ):
            with self.subTest(log=log):
                root = self.fixture(failure="")
                self.log_path(root).write_text(log)
                self.assertFalse(is_retryable(root))

    def test_missing_conflicting_or_duplicate_runner_start_fails_closed(self):
        for start in ("", START.replace(CASE, "anotherCase"), START.replace(" 101  102 ", " 999  102 "), START + START):
            with self.subTest(start=start):
                root = self.fixture(failure="")
                self.log_path(root).write_text(start + REFERENCE_QUEUE_LOG)
                self.assertFalse(is_retryable(root))
        root = self.fixture(failure="")
        self.log_path(root).write_text(START + REFERENCE_QUEUE_LOG + FINISH.replace(" 101  102 ", " 999  102 ").replace(".050", ".300"))
        self.assertFalse(is_retryable(root))

    def test_one_failed_identity_and_one_exact_per_case_log_are_required(self):
        for mutation in ("missing_log", "duplicate_log", "missing_identity", "duplicate_identity", "skipped_failure", "two_failures"):
            with self.subTest(mutation=mutation):
                root = self.fixture(failure="")
                log = self.log_path(root)
                xml = root / "TEST-emulator.xml"
                if mutation == "missing_log":
                    log.rename(log.parent / "runtime-log.txt")
                elif mutation == "duplicate_log":
                    duplicate = root / "another-device" / log.name
                    duplicate.parent.mkdir()
                    duplicate.write_text(log.read_text())
                elif mutation == "missing_identity":
                    xml.write_text(xml.read_text().replace(f' classname="{CLASSNAME}"', ""))
                elif mutation == "duplicate_identity":
                    xml.write_text(xml.read_text().replace("</testsuite>", CASE_OPEN + "</testcase></testsuite>"))
                elif mutation == "skipped_failure":
                    xml.write_text(xml.read_text().replace("<failure>", "<skipped/><failure>"))
                else:
                    second = CASE_OPEN.replace(CASE, "anotherFailure") + "<failure/></testcase>"
                    xml.write_text(xml.read_text().replace("</testsuite>", second + "</testsuite>"))
                    (log.parent / f"logcat-{CLASSNAME}-anotherFailure.txt").write_text(
                        START.replace(CASE, "anotherFailure") + REFERENCE_QUEUE_LOG
                    )
                self.assertFalse(is_retryable(root))

    def test_reference_queue_needs_consistent_process_identity(self):
        for log in (
            REFERENCE_QUEUE_LOG.replace("Process: com.artflow.studio, PID: 101", "Process: com.artflow.studio, PID: 202"),
            REFERENCE_QUEUE_LOG.replace("Process: com.artflow.studio, PID: 101", "Process: com.artflow.studio.remote, PID: 101"),
            "\n".join(line for line in REFERENCE_QUEUE_LOG.splitlines() if "Process:" not in line),
            SYSTEM_REFERENCE_QUEUE_LOG + records("Process: unrelated, PID: 201", pid=201, tid=210),
        ):
            with self.subTest(log=log):
                self.assertFalse(is_retryable(self.fixture(failure="", log=log)))

    def test_declared_case_counts_cannot_hide_missing_failure_evidence(self):
        for attribute, value in (("tests", "2"), ("failures", "2"), ("failures", "-1"), ("skipped", "1"), ("tests", "invalid")):
            with self.subTest(attribute=attribute, value=value):
                root = self.fixture(failure="")
                xml = root / "TEST-emulator.xml"
                xml.write_text(xml.read_text().replace("<testsuite>", f'<testsuite {attribute}="{value}">'))
                self.assertFalse(is_retryable(root))

    def test_native_signal_and_tombstone_must_match_the_interrupted_app_pid(self):
        for log in (
            ART_SIGNAL.replace("pid 101 (", "pid 202 (") + ART_DUMP,
            ART_SIGNAL.replace("in tid 101 (", "in tid 202 (") + ART_DUMP,
            ART_SIGNAL + ART_DUMP.replace("pid: 101,", "pid: 202,"),
            ART_SIGNAL + ART_DUMP.replace("tid: 101,", "tid: 202,"),
            ART_SIGNAL + ART_DUMP.replace("Cmdline: com.artflow.studio", "Cmdline: com.artflow.studio.remote"),
            ART_DUMP,
            ART_SIGNAL,
        ):
            with self.subTest(log=log):
                self.assertFalse(is_retryable(self.fixture(failure="", log=log)))

    def test_an_earlier_nonmatching_app_or_system_fatal_blocks_a_later_match(self):
        app_error = records(
            "FATAL EXCEPTION: main\nProcess: com.artflow.studio, PID: 101\njava.lang.IllegalStateException: bad app state",
            time="10:00:00.050",
        )
        system_error = records(
            "Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x20 in tid 210 (binder:201_1), pid 201 (system_server)",
            pid=201, tid=210, tag="libc", time="10:00:00.050",
        )
        self.assertFalse(is_retryable(self.fixture(failure="", log=app_error + REFERENCE_QUEUE_LOG)))
        self.assertFalse(is_retryable(self.fixture(failure="", log=system_error + ART_SIGSEGV)))

    def test_native_fields_cannot_be_borrowed_from_other_or_duplicate_dumps(self):
        lines = ART_DUMP.splitlines(keepends=True)
        split = "".join(line.replace(" 303  303 ", " 404  404 ") if "#00" in line else line for line in lines)
        for dump in (split, ART_DUMP + ART_DUMP, ART_DUMP + ART_DUMP.replace(" 303  303 ", " 404  404 ")):
            with self.subTest(dump=dump):
                self.assertFalse(is_retryable(self.fixture(failure="", log=ART_SIGNAL + dump)))

    def test_missing_or_malformed_evidence_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertFalse(is_retryable(Path(directory)))
        root = self.fixture()
        (root / "TEST-emulator.xml").write_text("<testsuite><failure>")
        self.assertFalse(is_retryable(root))
        root = self.fixture()
        xml = root / "TEST-emulator.xml"
        xml.write_text(xml.read_text().replace("testsuite", "unrecognized-root"))
        self.assertFalse(is_retryable(root))


if __name__ == "__main__":
    unittest.main()
