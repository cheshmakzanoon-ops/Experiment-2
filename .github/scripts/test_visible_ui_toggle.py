"""Exercise the release driver's real Java resolver using retained Settings hierarchy rows.

Requires the JDK 17 already provisioned by Android CI. No Android SDK, emulator,
Gradle, network resolution, substituted driver implementation, or retry is used.
"""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
FIXTURES = Path(__file__).resolve().parent / "fixtures/release-ui"


def java_tool(name):
    home = os.environ.get("JAVA_HOME")
    candidate = Path(home) / "bin" / name if home else None
    executable = str(candidate) if candidate and candidate.is_file() else shutil.which(name)
    if executable is None:
        raise RuntimeError("A JDK 17 installation is required for the visible UI regression")
    return executable


class VisibleUiToggleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="artflow-visible-toggle-")
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.classes = Path(cls.temporary.name)
        cls.java = java_tool("java")
        subprocess.run([
            java_tool("javac"), "--release", "17", "-d", str(cls.classes),
            str(ROOT / "release-ui/src/main/java/com/artflow/qualification/AccessibleToggle.java"),
            str(FIXTURES / "AccessibleToggleRegression.java"),
        ], check=True, text=True, capture_output=True, timeout=30)

    def run_case(self, name):
        result = subprocess.run([
            self.java, "-cp", str(self.classes), "com.artflow.qualification.AccessibleToggleRegression",
            name, str(FIXTURES / "settings-checkable-rows.xml"),
        ], text=True, capture_output=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(result.stdout.strip(), "PASS " + name)

    def test_retained_settings_labels_resolve_their_own_checkable_rows(self):
        self.run_case("retained-rows")

    def test_direct_content_description_keeps_the_actual_checked_control(self):
        self.run_case("direct-description")

    def test_multiple_label_nodes_in_one_row_do_not_look_like_two_controls(self):
        self.run_case("repeated-label-in-one-row")

    def test_separate_labeled_controls_are_rejected_as_ambiguous(self):
        self.run_case("ambiguous-controls")

    def test_unrelated_sibling_toggle_is_never_selected(self):
        self.run_case("unrelated-sibling")

    def test_ancestors_outside_the_application_are_not_selected(self):
        self.run_case("package-boundary")

    def test_hidden_label_cannot_select_a_control(self):
        self.run_case("hidden-label")

    def test_missing_label_cannot_select_a_control(self):
        self.run_case("missing-label")


if __name__ == "__main__":
    unittest.main()
