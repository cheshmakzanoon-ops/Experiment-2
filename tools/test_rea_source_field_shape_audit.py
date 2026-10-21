"""Synthetic REA schema tests; never embeds reference application contents."""
import copy
import unittest

import rea_source_field_shape_audit as audit


def specimen():
    root = {
        "name": "Sample",
        "shapeCountRoll": False,
        "plotSpacing": 0.125,
        "dualBlendMode": 2,
        "dynamicsPressureOverall": {"points": {"NS.objects": ["{0, 0}", "{1, 1}"]}},
        "attackRollParameters": {
            "activationFalloff": 1.57, "activationOrigin": 3.14,
            "mode": 1, "activationRange": 3.14,
        },
        "color": {"bytes": 16, "sha256": "a" * 64},
    }
    return {
        "reference_sha256": audit.EXPECTED_SHA,
        "brushes": [
            {"path": "Payload/Paint.app/Example.brush", "archives": [
                {"path": "Brush.archive", "top": {"root": root}},
                {"path": "Sub01/Brush.archive", "top": {"root": root}},
            ]},
        ],
    }


class AuditTests(unittest.TestCase):
    def test_exact_schema_roles_and_component_counts(self):
        record = audit.inspect(specimen(), expected=(1, 2))
        self.assertEqual(1, record["logical_brushes"])
        self.assertEqual(1, record["compound_brushes"])
        self.assertEqual(2, record["settings_components"])
        self.assertEqual(7, record["distinct_fields"])
        self.assertEqual(4, record["primitive_fields_per_component"]["max"])
        self.assertEqual(["attackRollParameters"], record["structured_field_names"])
        self.assertEqual(["color"], record["binary_field_names"])
        self.assertEqual(16, record["largest_binary_field_bytes"])

    def test_reference_identity_and_grouping_are_strict(self):
        changed = specimen()
        changed["reference_sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "source hash"):
            audit.inspect(changed, expected=(1, 2))
        changed = specimen()
        changed["brushes"][0]["archives"][0]["path"] = "Sub01/Brush.archive"
        with self.assertRaisesRegex(ValueError, "primary/secondary"):
            audit.inspect(changed, expected=(1, 2))

    def test_untrusted_fields_are_rejected(self):
        for name, value in [("../traversal", 0.1), ("badField", float("nan"))]:
            changed = copy.deepcopy(specimen())
            changed["brushes"][0]["archives"][0]["top"]["root"][name] = value
            with self.assertRaises(ValueError):
                audit.inspect(changed, expected=(1, 2))

    def test_rejects_inconsistent_shapes(self):
        changed = copy.deepcopy(specimen())
        changed["brushes"][0]["archives"][1]["top"]["root"] = dict(
            changed["brushes"][0]["archives"][1]["top"]["root"]
        )
        changed["brushes"][0]["archives"][1]["top"]["root"]["dualBlendMode"] = 2.0
        with self.assertRaisesRegex(ValueError, "inconsistent"):
            audit.inspect(changed, expected=(1, 2))


if __name__ == "__main__":
    unittest.main()
