import tempfile
import unittest
from pathlib import Path

from verify_workflow_pins import find_unpinned_uses


class VerifyWorkflowPinsTest(unittest.TestCase):
    def test_accepts_40_char_sha_with_tag_comment_and_local_actions(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            wf = Path(tmp) / "ci.yml"
            wf.write_text(
                "\n".join(
                    [
                        "jobs:",
                        "  build:",
                        "    steps:",
                        "      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4",
                        "      - uses: gradle/actions/setup-gradle@ed408507eac070d1f99cc633dbcf757c94c7933a # v4.4.3",
                        "      - uses: ./local/action",
                    ]
                ),
                encoding="utf-8",
            )
            self.assertEqual(find_unpinned_uses(Path(tmp)), [])

    def test_rejects_tag_and_branch_references(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            wf = Path(tmp) / "bad.yml"
            wf.write_text(
                "\n".join(
                    [
                        "steps:",
                        "  - uses: actions/checkout@v4",
                        "  - uses: reactivecircus/android-emulator-runner@main",
                    ]
                ),
                encoding="utf-8",
            )
            violations = find_unpinned_uses(Path(tmp))
            self.assertEqual(len(violations), 2)

    def test_repo_workflows_are_all_pinned(self) -> None:
        repo_wf = Path(__file__).resolve().parents[2] / ".github" / "workflows"
        self.assertEqual(find_unpinned_uses(repo_wf), [])


if __name__ == "__main__":
    unittest.main()
