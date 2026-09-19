"""Prevent the in-app disclosure and published policy from drifting apart."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


class PrivacyPolicyTest(unittest.TestCase):
    def test_in_app_and_public_policy_have_identical_visible_words(self):
        markdown = (ROOT / 'docs/privacy-policy.md').read_text(encoding='utf-8')
        visible = re.sub(r'^#{1,6} ', '', markdown, flags=re.MULTILINE)
        resource = ET.parse(ROOT / 'app/src/main/res/values/privacy.xml')
        value = resource.find(".//string[@name='privacy_policy']")
        self.assertIsNotNone(value)
        in_app = ''.join(value.itertext()).replace(r'\n', '\n')
        self.assertEqual(visible.split(), in_app.split())

    def test_palette_backup_and_cloud_provider_are_disclosed(self):
        policy = (ROOT / 'docs/privacy-policy.md').read_text(encoding='utf-8')
        self.assertIn('recovery backup of unreadable custom-palette', policy)
        self.assertIn('file you choose', policy)
        self.assertIn('before resetting that palette list', policy)
        self.assertIn('cloud document', policy)


if __name__ == '__main__':
    unittest.main()
