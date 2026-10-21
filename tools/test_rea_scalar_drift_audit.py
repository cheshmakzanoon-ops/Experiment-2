"""Synthetic regression tests for original-value clamp and quantization risk."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("drift", Path(__file__).with_name("rea_scalar_drift_audit.py"))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def corpus():
    return {
        "reference_sha256": audit.PINNED_SHA,
        "brushes": [{
            "path": "A.brush",
            "archives": [
                {"path": "Brush.archive", "top": {"root": {
                    "maxSize": 16.0, "paintSize": 1.0, "textureScale": 0.1,
                    "plotSpacing": 0.0, "shapeCount": 0.0625, "textureMovement": 0.8709,
                }}},
                {"path": "Sub01/Brush.archive", "top": {"root": {
                    "maxSize": 1.0, "paintSize": 1.0, "textureScale": 1.0,
                    "plotSpacing": 0.1, "shapeCount": 0.0, "textureMovement": 1.0,
                }}},
            ],
        }],
    }


class ScalarDriftTests(unittest.TestCase):
    def test_clamping_and_quantization_count_independent_fields(self):
        report = audit.audit(corpus(), expected=(1, 2))
        self.assertEqual(1, report["components_with_at_least_one_drift_risk"])
        self.assertEqual(5, len(report["individual_field_occurrences_at_risk"]))
        self.assertEqual(1, report["number_of_risky_fields_per_component"]["5"])
        self.assertEqual(1, report["number_of_risky_fields_per_component"]["0"])

    def test_nonfinite_values_do_not_create_spurious_predictions(self):
        risky = audit.vulnerable_fields({
            "maxSize": float("nan"), "textureScale": float("inf"),
            "shapeCount": 0.0, "textureMovement": 0.0,
        })
        self.assertFalse(risky)

    def test_provenance_and_schema_issues_fail_closed(self):
        bad = corpus()
        bad["reference_sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            audit.audit(bad, expected=(1, 2))
        bad = corpus()
        bad["brushes"][0]["archives"][0]["path"] = "Sub01/Brush.archive"
        with self.assertRaisesRegex(ValueError, "primary/secondary"):
            audit.audit(bad, expected=(1, 2))

    def test_writer_preserves_unquantized_inputs_as_a_distinct_requirement(self):
        value = 0.0625
        quantized = (int(value * 15 + 0.5)) / 15
        self.assertNotEqual(value, quantized)
        self.assertIn("shapeCount", audit.vulnerable_fields({"shapeCount": value}))


if __name__ == "__main__":
    unittest.main()
