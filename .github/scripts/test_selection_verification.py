"""Negative controls for the standalone selection-verification harness."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import verify_selection_kernels as verifier


class SelectionVerifierTest(unittest.TestCase):
    def test_adapter_keeps_the_actual_enum_body(self):
        source = "package example\n@Serializable\nenum class BlendMode(val label: String) { NORMAL(\"Normal\") }\n"
        adapted = verifier.enum_adapter(source)
        self.assertEqual(
            "package com.artflow.studio.domain.model.layer\n\nenum class BlendMode(val label: String) { NORMAL(\"Normal\") }\n",
            adapted,
        )
        self.assertNotIn("@Serializable", adapted)

    def test_missing_enum_is_an_error(self):
        with self.assertRaises(ValueError):
            verifier.enum_adapter("enum class SomethingElse { NORMAL }")

    def test_duplicate_enum_is_an_error(self):
        with self.assertRaises(ValueError):
            verifier.enum_adapter("enum class BlendMode() {}\nenum class BlendMode() {}")

    def test_incomplete_enum_is_an_error(self):
        with self.assertRaises(ValueError):
            verifier.enum_adapter("enum class BlendMode() { NORMAL;")

    def test_successful_command_preserves_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / "result.txt"
            result = verifier.execute([sys.executable, "-c", "print('real command output')"], log, 10)
            self.assertEqual("real command output\n", result)
            self.assertEqual(result, log.read_text(encoding="utf-8"))

    def test_nonzero_exit_cannot_be_reported_as_success(self):
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / "failed.txt"
            with self.assertRaisesRegex(RuntimeError, "Command failed \\(7\\)"):
                verifier.execute([sys.executable, "-c", "print('compiler failed'); raise SystemExit(7)"], log, 10)
            self.assertIn("compiler failed", log.read_text(encoding="utf-8"))

    def test_timeout_cannot_be_reported_as_success(self):
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / "timed-out.txt"
            with self.assertRaises(subprocess.TimeoutExpired):
                verifier.execute([sys.executable, "-c", "import time; time.sleep(60)"], log, 0.02)

    def test_production_inputs_exist_and_are_not_generated_fakes(self):
        root = Path(__file__).resolve().parents[2]
        self.assertEqual(7, len(verifier.PRODUCTION))
        for path in verifier.PRODUCTION:
            self.assertTrue(path.startswith("app/src/main/java/"))
            self.assertTrue((root / path).is_file(), path)
        self.assertIn("enum class BlendMode", verifier.enum_adapter((root / verifier.ENUM_SOURCE).read_text(encoding="utf-8")))
        self.assertIn("SelectionKernelChecks", (root / verifier.CHECKS).read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
