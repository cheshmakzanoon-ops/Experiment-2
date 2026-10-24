"""Standard-library-only checks: no IPA, Capstone, Android or native execution."""
import json
import math
from pathlib import Path
import tempfile
import unittest

import native_pressure_spline as spline


class NativePressureSplineFixturesTest(unittest.TestCase):
    def test_analytic_boundary_and_rounding_checks(self):
        checks = spline.validate_model()
        self.assertEqual(5, len(checks))
        self.assertTrue(all(checks.values()))

    def test_all_committed_fixtures_regenerate_without_native_assets(self):
        path = Path(__file__).resolve().parents[1] / 'app/src/test/resources/brush/native-spline-fixtures.json'
        document = json.loads(path.read_text())
        self.assertEqual(spline.provenance(), document['provenance'])
        self.assertFalse(document['provenance']['reference_code_executed'])
        self.assertEqual(13, len(document['fixtures']))
        for expected in document['fixtures']:
            with self.subTest(fixture=expected['name']):
                self.assertEqual(expected, spline.fixture(expected['name'], expected['points']))

    def test_invalid_points_and_nonfinite_pressure_fail_explicitly(self):
        for points in ([[0, 0], [0.5, 0], [0.5, 1]], [[0, math.nan], [1, 1]], [[-0.1, 0], [1, 1]]):
            with self.subTest(points=points), self.assertRaises(AssertionError):
                spline.prepare(points)
        table = spline.prepare(None)
        for pressure in (math.nan, math.inf, -math.inf):
            with self.subTest(pressure=pressure), self.assertRaises(AssertionError):
                spline.lookup(table, pressure)

    def test_check_is_read_only_and_rejects_changed_expectations(self):
        document = {'provenance': spline.provenance(), 'fixtures': []}
        data = json.dumps(document).encode('utf-8')
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'fixture.json'
            path.write_bytes(data)
            self.assertEqual(64, len(spline.check_document(path, document)))
            document['provenance']['channel_count'] = 40
            with self.assertRaises(ValueError):
                spline.check_document(path, document)
            self.assertEqual(data, path.read_bytes())


if __name__ == '__main__':
    unittest.main()
