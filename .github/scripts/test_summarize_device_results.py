import importlib.util
import tempfile
import unittest
from pathlib import Path


SPEC = importlib.util.spec_from_file_location("summary", Path(__file__).with_name("summarize_device_results.py"))
summary = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(summary)


class DeviceSummaryTest(unittest.TestCase):
    def test_missing_and_stale_tests_cannot_make_a_partial_run_green(self):
        errors, _ = summary.evaluate({"a#first", "a#second"}, {"a#first": "passed", "a#old": "passed"}, 36)
        self.assertTrue(any("Missing test: a#second" in error for error in errors))
        self.assertTrue(any("Unexpected test" in error for error in errors))

    def test_only_documented_skips_below_the_required_api_are_accepted(self):
        for case, (minimum, _) in summary.API_LIMITED_TESTS.items():
            with self.subTest(case=case):
                errors, skips = summary.evaluate({case}, {case: "skipped"}, minimum - 1)
                self.assertEqual([], errors)
                self.assertEqual(case, skips[0]["test"])
                self.assertTrue(summary.evaluate({case}, {case: "skipped"}, minimum)[0])
        self.assertTrue(summary.evaluate({"a#unknown"}, {"a#unknown": "skipped"}, 26)[0])

    def test_message_only_failure_and_error_are_never_passes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "TEST-device.xml").write_text(
                '<testsuite tests="2" failures="1" errors="1" skipped="0">'
                '<testcase classname="a" name="first"><failure message="pixels differ"/></testcase>'
                '<testcase classname="a" name="second"><error type="AssertionError"/></testcase>'
                '</testsuite>'
            )
            results = summary.read_results(root)
            self.assertEqual({"a#first": "failure", "a#second": "error"}, results)
            self.assertEqual(2, len(summary.evaluate(set(results), results, 36)[0]))

    def test_duplicate_reports_cannot_inflate_test_counts(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            content = '<testsuite><testcase classname="a" name="same"/></testsuite>'
            (root / "TEST-one.xml").write_text(content)
            (root / "TEST-two.xml").write_text(content)
            with self.assertRaisesRegex(ValueError, "Duplicate result"):
                summary.read_results(root)

    def test_empty_malformed_and_inconsistent_reports_fail(self):
        samples = (
            '<testsuite tests="0"/>',
            '<testsuite tests="2"><testcase classname="a" name="one"/></testsuite>',
            '<testsuite failures="0"><testcase classname="a" name="one"><failure/></testcase></testsuite>',
            '<testsuite><testcase classname="a"/></testsuite>',
            '<testsuite><testcase classname="a" name="one"><skipped/><error/></testcase></testsuite>',
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaises(ValueError):
                summary.read_results(root)
            for sample in samples:
                with self.subTest(sample=sample):
                    (root / "TEST-device.xml").write_text(sample)
                    with self.assertRaises(ValueError):
                        summary.read_results(root)

    def test_suite_level_runner_failure_cannot_hide_behind_passed_cases(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "TEST-passed.xml").write_text('<testsuite><testcase classname="a" name="one"/></testsuite>')
            for failed in (
                '<testsuite tests="0" errors="1"><error message="Runner crashed"/></testsuite>',
                '<testsuite tests="0" errors="1"/>',
                '<testsuites><error message="Runner crashed"/></testsuites>',
            ):
                with self.subTest(failed=failed):
                    (root / "TEST-runner.xml").write_text(failed)
                    with self.assertRaises(ValueError):
                        summary.read_results(root)

    def test_inventory_uses_declarations_and_ignores_comments(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "EditorTest.kt").write_text(
                'package com.artflow.studio\n'
                '/** An example: @Test fun example() */\n'
                'class EditorTest {\n'
                '// @Test fun commentedOut() {}\n'
                '@Test\nfun first() {}\n'
                '@Test fun second() = runBlocking {}\n}\n'
            )
            self.assertEqual(
                {"com.artflow.studio.EditorTest#first", "com.artflow.studio.EditorTest#second"},
                summary.source_inventory(root),
            )

    def test_new_declaration_syntax_requires_an_explicit_parser_update(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaises(ValueError):
                summary.source_inventory(root)
            (root / "EditorTest.kt").write_text(
                'package com.artflow.studio\nclass EditorTest {\n@Test(expected = Exception::class) fun fails() {}\n}'
            )
            with self.assertRaisesRegex(ValueError, "Unsupported test declarations"):
                summary.source_inventory(root)
            (root / "EditorTest.kt").write_text(
                'package com.artflow.studio\nclass EditorTest {\n'
                '@Test fun first() {}\n@org.junit.Test fun second() {}\n}'
            )
            with self.assertRaisesRegex(ValueError, "Unsupported test declarations"):
                summary.source_inventory(root)

    def test_runtime_evidence_must_be_present_complete_and_unambiguous(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaises(ValueError):
                summary.read_runtime(root)
            (root / "runtime-environment.txt").write_text("sdk=36\npageSize=16384\n")
            with self.assertRaisesRegex(ValueError, "Incomplete"):
                summary.read_runtime(root)
            (root / "runtime-environment.txt").write_text(
                "sdk=36\npageSize=16384\nabis=x86_64\nfingerprint=android/emulator\n"
            )
            self.assertEqual("16384", summary.read_runtime(root)["pageSize"])
            (root / "stale").mkdir()
            (root / "stale/runtime-environment.txt").write_text("sdk=26\n")
            with self.assertRaisesRegex(ValueError, "found 2"):
                summary.read_runtime(root)


if __name__ == "__main__":
    unittest.main()
