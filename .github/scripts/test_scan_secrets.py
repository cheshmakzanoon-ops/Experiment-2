import tempfile
import unittest
from pathlib import Path

from scan_secrets import scan_directory


class ScanSecretsTest(unittest.TestCase):
    def test_detects_private_key_and_tracked_keystore(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / ".gitignore").write_text("keystore.properties\n*.keystore\n*.jks\n.env\n", encoding="utf-8")
            (root / "leak.txt").write_text("-----BEGIN " + "PRIVATE KEY-----\nabc\n", encoding="utf-8")
            (root / "release.jks").write_bytes(b"\x00\x01")
            findings = scan_directory(root)
            self.assertEqual(len(findings), 2)

    def test_clean_repo_passes(self) -> None:
        repo_root = Path(__file__).resolve().parents[2]
        self.assertEqual(scan_directory(repo_root), [])


if __name__ == "__main__":
    unittest.main()
