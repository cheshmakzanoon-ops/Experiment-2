"""Focused Compose CI result-inventory regression tests."""
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

from verify_compose_repetition import EXPECTED, check


def write_report(root: Path, names: list[str], issue: str | None = None) -> None:
    suite = ET.Element("testsuite")
    for name in names:
        class_name, method = name.split("#", 1)
        case = ET.SubElement(suite, "testcase", classname=class_name, name=method)
        if issue == name:
            ET.SubElement(case, "failure", message="simulated failure").text = "stack"
    ET.ElementTree(suite).write(root / "TEST-compose.xml", encoding="utf-8")


class FocusedComposeVerifierTest(unittest.TestCase):
    def test_exact_inventory_passes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_report(root, sorted(EXPECTED))
            self.assertTrue(check(root)["pass"])

    def test_missing_case_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_report(root, sorted(EXPECTED)[:-1])
            report = check(root)
            self.assertFalse(report["pass"])
            self.assertEqual(1, len(report["missing"]))

    def test_unexpected_case_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_report(root, sorted(EXPECTED) + ["extra.Test#unexpected"])
            self.assertFalse(check(root)["pass"])

    def test_failure_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            identity = sorted(EXPECTED)[0]
            write_report(root, sorted(EXPECTED), issue=identity)
            report = check(root)
            self.assertFalse(report["pass"])
            self.assertIn(identity, report["failed_or_skipped"])

    def test_absent_results_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            self.assertFalse(check(Path(directory))["pass"])

    def test_duplicate_case_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_report(root, sorted(EXPECTED))
            root.joinpath("TEST-compose.xml").rename(root / "TEST-compose-a.xml")
            write_report(root, sorted(EXPECTED))
            with self.assertRaises(ValueError):
                check(root)


if __name__ == "__main__":
    unittest.main()
