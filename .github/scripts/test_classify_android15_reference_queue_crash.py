import tempfile
from pathlib import Path
import unittest

from classify_android15_reference_queue_crash import is_retryable

REFERENCE_QUEUE_FAILURE = """java.lang.NullPointerException: runtime failure
at java.lang.ref.ReferenceQueue.enqueuePending(ReferenceQueue.java:239)
at java.lang.Daemons$ReferenceQueueDaemon.runInternal(Daemons.java:260)
"""

ART_SIGSEGV = """F DEBUG   : Build fingerprint: 'google/sdk_gphone16k_x86_64/emu64xa16k:15/AE3A.240806.043/12960925:userdebug/dev-keys'
F DEBUG   : Page size: 16384 bytes
F DEBUG   : Cmdline: com.artflow.studio
F DEBUG   : signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x8
F DEBUG   : backtrace:
F DEBUG   :       #00 pc 00000000008519ea  /apex/com.android.art/lib64/libart.so (art::mirror::Class::IsInSamePackage+154)
"""


class Android15RuntimeClassifierTest(unittest.TestCase):
    def fixture(self, failure=REFERENCE_QUEUE_FAILURE, log=REFERENCE_QUEUE_FAILURE):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        report = root / "device" / "runtime-log.txt"
        report.parent.mkdir(parents=True)
        report.write_text(log)
        xml = root / "TEST-emulator.xml"
        escaped = failure.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        xml.write_text("<testsuite><testcase><failure>" + escaped + "</failure></testcase></testsuite>")
        return root

    def test_reference_queue_framework_crash_is_retryable(self):
        self.assertTrue(is_retryable(self.fixture()))

    def test_android15_16k_libart_sigsegv_with_empty_process_failure_is_retryable(self):
        self.assertTrue(is_retryable(self.fixture(failure="", log=ART_SIGSEGV)))

    def test_app_assertion_is_never_retryable_even_with_matching_art_tombstone(self):
        self.assertFalse(is_retryable(self.fixture(failure="java.lang.AssertionError: pixels changed", log=ART_SIGSEGV)))

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

    def test_missing_or_malformed_evidence_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertFalse(is_retryable(Path(directory)))
        root = self.fixture()
        (root / "TEST-emulator.xml").write_text("<testsuite><failure>")
        self.assertFalse(is_retryable(root))


if __name__ == "__main__":
    unittest.main()
