"""Exercise the release driver's Java accessibility helpers using retained failure evidence.

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
            str(ROOT / "release-ui/src/main/java/com/artflow/qualification/AccessibleScroll.java"),
            str(ROOT / "release-ui/src/main/java/com/artflow/qualification/AccessibleObservation.java"),
            str(FIXTURES / "AccessibleToggleRegression.java"),
            str(FIXTURES / "AccessibleScrollRegression.java"),
            str(FIXTURES / "AccessibleObservationRegression.java"),
        ], check=True, text=True, capture_output=True, timeout=30)

    def run_case(self, name, regression="AccessibleToggleRegression", fixture="settings-checkable-rows.xml", extra=()):
        result = subprocess.run([
            self.java, "-cp", str(self.classes), "com.artflow.qualification." + regression,
            name, str(FIXTURES / fixture), *extra,
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

    def scroll_case(self, name):
        self.run_case(name, "AccessibleScrollRegression", "settings-scroll-with-slider.xml",
                      (str(FIXTURES / "settings-scroll-original-log.txt"),))

    def test_retained_swipe_hits_slider_but_scroll_action_targets_the_container_once(self):
        self.scroll_case("scroll-retained-slider")

    def test_backward_scroll_action_keeps_its_direction_and_container(self):
        self.scroll_case("scroll-backward")

    def test_unadvertised_scroll_action_is_never_sent(self):
        self.scroll_case("scroll-unadvertised")

    def test_rejected_scroll_action_is_not_retried_or_redirected(self):
        self.scroll_case("scroll-rejected")

    def test_short_horizontal_strip_cannot_capture_vertical_navigation(self):
        self.scroll_case("scroll-short")

    def test_missing_scroll_container_causes_no_action(self):
        self.scroll_case("scroll-empty")

    def observation_case(self, name):
        self.run_case(name, "AccessibleObservationRegression", "export-result-stale-original-log.txt")

    def test_current_visible_node_is_observed_once(self):
        self.observation_case("observation-visible")

    def test_missing_node_is_not_observed_or_inspected(self):
        self.observation_case("observation-missing")

    def test_hidden_node_is_not_accepted_as_visible(self):
        self.observation_case("observation-hidden")

    def test_detached_lookup_remains_unobserved_without_repeating_the_read(self):
        self.observation_case("observation-detached-lookup")

    def test_detached_export_result_bounds_require_a_new_observation(self):
        self.observation_case("observation-detached-bounds")

    def test_unrelated_accessibility_failures_propagate(self):
        self.observation_case("observation-unrelated-failure")

    def test_qualification_assertions_propagate(self):
        self.observation_case("observation-assertion-failure")


if __name__ == "__main__":
    unittest.main()
