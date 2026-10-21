"""Synthetic tests for identity-pinned REA keyed-archive class graph verification."""
import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("graph", Path(__file__).with_name("rea_keyed_graph_audit.py"))
graph = importlib.util.module_from_spec(spec)
spec.loader.exec_module(graph)


def source():
    # Mirror the *entire* corpus cardinality: 394 logical brushes, 140
    # compound containers and 534 component archives. No reference samples
    # or proprietary metadata are embedded in these synthetic fixtures.
    brushes = [{
        "archives": [
            {"path": "Brush.archive", "sha256": "a" * 64},
            {"path": "Sub01/Brush.archive", "sha256": "b" * 64},
        ]
    }]
    for index in range(1, 394):
        parts = [{"path": "Brush.archive", "sha256": f"{index:064x}"}]
        if index <= 139:
            parts.append({"path": "Sub01/Brush.archive", "sha256": f"{index + 500:064x}"})
        brushes.append({"archives": parts})
    return {"reference_sha256": graph.EXPECTED_IPA, "brushes": brushes}


def evidence():
    return {
        "subject": {"digest": {"sha256": "a" * 64}},
        "normalized_result": {
            "target_sha256": "a" * 64,
            "roots": {"root": {"UID": 1}},
            "objects": [
                {"id": 0, "kind": "nil", "value": "$null", "class_name": None},
                {"id": 1, "kind": "archived-object", "class_name": "SilicaBrush", "value": {}},
                {"id": 2, "kind": "archived-object", "class_name": "ValkyrieMagnitudinalCurve", "value": {"points": {"UID": 3}}},
                {"id": 3, "kind": "archived-object", "class_name": "NSMutableArray",
                 "value": {"NS.objects": [{"UID": 4}, {"UID": 5}]}},
                {"id": 4, "kind": "scalar", "class_name": None, "value": "{0.0, 0.9}"},
                {"id": 5, "kind": "scalar", "class_name": None, "value": "{1.0, 0.1}"},
                {"id": 6, "kind": "archived-object", "class_name": "ValkyrieBrushRollParameters",
                 "value": {"activationFalloff": 1.0, "activationOrigin": 2.0, "mode": 1, "activationRange": 3.0}},
                {"id": 7, "kind": "archived-object", "class_name": "NSDate", "value": {"NS.time": 100.0}},
            ],
        },
    }


class ReaGraphTests(unittest.TestCase):
    def test_valid_graph(self):
        report = graph.verified_samples(source(), [evidence()])
        self.assertEqual(8, report["totals"]["all_objects"])
        self.assertEqual(2, report["totals"]["curve_control_points"])
        self.assertEqual(1, report["totals"]["ValkyrieMagnitudinalCurve"])
        self.assertTrue(report["samples"][0]["graph_verified"])

    def test_reference_digest_and_root_identity(self):
        data = evidence()
        data["subject"]["digest"]["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "digest"):
            graph.verified_samples(source(), [data])
        broken = evidence()
        broken["normalized_result"]["objects"][1]["class_name"] = "UntrustedRoot"
        with self.assertRaisesRegex(ValueError, "SilicaBrush"):
            graph.verified_samples(source(), [broken])

    def test_invalid_curve_pointer_and_nonfinite_values(self):
        corrupt = evidence()
        corrupt["normalized_result"]["objects"][2]["value"]["points"] = {"UID": 100}
        with self.assertRaisesRegex(ValueError, "reference"):
            graph.verified_samples(source(), [corrupt])
        corrupt = evidence()
        corrupt["normalized_result"]["objects"][4]["value"] = "{0.0, nan}"
        with self.assertRaisesRegex(ValueError, "coordinate"):
            graph.verified_samples(source(), [corrupt])

    def test_duplicate_sample_rejected(self):
        with self.assertRaisesRegex(ValueError, "Repeated"):
            graph.verified_samples(source(), [evidence(), evidence()])


if __name__ == "__main__":
    unittest.main()
