"""Exercise REA evidence provenance, logical-brush grouping and source key coverage."""
import copy
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).parent
spec = importlib.util.spec_from_file_location("rea_audit", ROOT / "rea_brush_coverage.py")
audit_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit_module)


def fixture():
    return {
        "reference_sha256": audit_module.REFERENCE_SHA256,
        "brushes": [
            {
                "path": "A.brush",
                "members": ["Brush.archive", "QuickLook/Thumbnail.png"],
                "archives": [{
                    "path": "Brush.archive",
                    "top": {"root": {
                        "name": "A", "shapeCount": 0.1,
                        "bundledShapePath": "S.png", "bundledGrainPath": "$null",
                    }},
                }],
            },
            {
                "path": "B.brush",
                "members": ["Brush.archive", "Sub01/Brush.archive", "Shape.png"],
                "archives": [
                    {"path": "Brush.archive", "top": {"root": {
                        "shapeCount": 0.2, "bundledShapePath": "S.png",
                        "bundledGrainPath": "G.png",
                    }}},
                    {"path": "Sub01/Brush.archive", "top": {"root": {
                        "shapeCount": 0.3, "bundledShapePath": "T.png",
                        "bundledGrainPath": "G.png", "unknownCurve": {},
                    }}},
                ],
            },
        ],
    }


class ReaCorpusCoverageTests(unittest.TestCase):
    def test_realistic_compound_and_literal_reader_keys(self):
        reader = 'number("shapeCount"); flag("unknown"); brush["bundledShapePath"]'
        report = audit_module.audit(fixture(), reader, counts=(2, 3))
        self.assertEqual(2, report["logical_brushes"])
        self.assertEqual(1, report["compound_brushes"])
        self.assertEqual(3, report["settings_components"])
        self.assertEqual(5, report["observed_keys"])
        self.assertEqual(2, report["reader_referenced_keys"])
        self.assertEqual(3, report["reader_unreferenced_keys"])
        self.assertEqual(2, report["embedded_thumbnails"] + 1)
        self.assertEqual(1, report["separate_image_member_count"])
        self.assertEqual(2, report["distinct_shape_references"])
        self.assertEqual(1, report["distinct_grain_references"])

    def test_auxiliary_whitelist_counts_structural_preservation_separately(self):
        reader = (
            'number("shapeCount"); brush["bundledShapePath"]\n'
            'internal val AUXILIARY_CURVE_KEYS: Set<String> = setOf(\n'
            '    "unknownCurve", "not-in-corpus",\n'
            ')'
        )
        report = audit_module.audit(fixture(), reader, counts=(2, 3))
        self.assertEqual(2, report["direct_reader_keys"])
        self.assertEqual(1, report["preserved_auxiliary_curve_keys"])
        self.assertEqual(3, report["reader_referenced_keys"])
        self.assertEqual(2, report["reader_unreferenced_keys"])
        self.assertEqual(["unknownCurve"], report["preserved_auxiliary_curve_names"])

    def test_rejects_different_reference_identity(self):
        bad = fixture()
        bad["reference_sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            audit_module.audit(bad, "", counts=(2, 3))

    def test_rejects_duplicate_container_and_orphan_secondary(self):
        duplicate = fixture()
        duplicate["brushes"][1]["path"] = "A.brush"
        with self.assertRaisesRegex(ValueError, "duplicate"):
            audit_module.audit(duplicate, "", counts=(2, 3))
        orphan = fixture()
        orphan["brushes"][1]["archives"][0]["path"] = "Sub01/Brush.archive"
        with self.assertRaisesRegex(ValueError, "primary/secondary"):
            audit_module.audit(orphan, "", counts=(2, 3))

    def test_catches_broken_component_count(self):
        missing = fixture()
        missing["brushes"][1]["archives"].pop()
        with self.assertRaisesRegex(ValueError, "size"):
            audit_module.audit(missing, "", counts=(2, 3))

    def test_literal_regex_ignores_non_reader_calls(self):
        found = audit_module.referenced_keys(
            'number("pressureToSize"); unit("shapeCount"); '
            'signed("jitter"); flag("inverted"); brush["name"]; '
            'other("notReallyRead"); val description = "unit(\"fake\")"'
        )
        self.assertTrue({"pressureToSize", "shapeCount", "jitter",
                         "inverted", "name"}.issubset(found))
        self.assertNotIn("notReallyRead", found)


if __name__ == "__main__":
    unittest.main()
