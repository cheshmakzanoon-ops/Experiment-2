"""Keep the Procreate sample fetch in CI from failing silently."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / '.github/workflows/android-ci.yml'
STEP = '      - name: Fetch Procreate sample documents'


class ProcreateFetchTest(unittest.TestCase):
    def fetch_step(self):
        text = WORKFLOW.read_text(encoding='utf-8')
        start = text.index(STEP)
        end = text.index('      - name: ', start + len(STEP))
        return text[start:end]

    def test_a_failed_fetch_cannot_be_hidden(self):
        self.assertNotIn('continue-on-error', self.fetch_step())

    def test_the_fetch_retries_before_it_gives_up(self):
        self.assertIn('for attempt in 1 2 3; do', self.fetch_step())

    def test_the_fetch_requires_a_procreate_document(self):
        self.assertIn('demo_files/*.procreate', self.fetch_step())
        self.assertIn('No Procreate sample documents after', self.fetch_step())


if __name__ == '__main__':
    unittest.main()
