import tempfile
from pathlib import Path
import unittest

from classify_android15_reference_queue_crash import is_retryable

FRAMEWORK_FAILURE = """java.lang.NullPointerException: runtime failure
at java.lang.ref.ReferenceQueue.enqueuePending(ReferenceQueue.java:239)
at java.lang.Daemons$ReferenceQueueDaemon.runInternal(Daemons.java:260)
"""


class ReferenceQueueClassifierTest(unittest.TestCase):
    def fixture(self, failure=FRAMEWORK_FAILURE, crash=FRAMEWORK_FAILURE):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        report = root / "device" / "logcat-com.artflow.studio-crash-report.txt"
        report.parent.mkdir(parents=True)
        report.write_text(crash)
        xml = root / "TEST-emulator.xml"
        xml.write_text(
            "<testsuite><testcase><failure>"
            + failure.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            + "</failure></testcase></testsuite>"
        )
        return root

    def test_exact_framework_crash_is_retryable(self):
        self.assertTrue(is_retryable(self.fixture()))

    def test_app_assertion_is_never_retryable_even_with_framework_crash_report(self):
        self.assertFalse(is_retryable(self.fixture(failure="java.lang.AssertionError: pixels changed")))

    def test_unrelated_runtime_crash_is_not_retryable(self):
        self.assertFalse(
            is_retryable(
                self.fixture(
                    failure="java.lang.NullPointerException at com.artflow.studio.Editor",
                    crash="java.lang.NullPointerException at com.artflow.studio.Editor",
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
