"""Bounded matching of REA brush asset names to IPA central-directory paths."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("asset_links", Path(__file__).with_name("rea_ipa_resource_links.py"))
links = importlib.util.module_from_spec(spec)
spec.loader.exec_module(links)


def fixture():
    return {"reference_sha256": links.EXPECTED_HASH, "brushes": [
        {"path": "A.brush", "archives": [{"path": "Brush.archive", "top": {"root": {
            "bundledShapePath": "Procreate_Library/Leaf.png",
            "bundledGrainPath": "Texture.jpg",
        }}}]},
        {"path": "B.brush", "archives": [
            {"path": "Brush.archive", "top": {"root": {
                "bundledShapePath": "Procreate_Library/Leaf.png",
                "bundledGrainPath": "Texture.jpg",
            }}},
            {"path": "Sub01/Brush.archive", "top": {"root": {
                "bundledShapePath": "Missing.png",
                "bundledGrainPath": "$null",
            }}},
        ]},
    ]}


class ResourceLinkTests(unittest.TestCase):
    def test_path_matching_is_conservative(self):
        names = {
            "Payload/Paint.app/Resources/Procreate_Library/Leaf.png",
            "Payload/Paint.app/Resources/Texture.jpg",
            "Payload/Paint.app/Frameworks/Demo/Texture.jpg",
            "Payload/Paint.app/Assets.car",
            "Payload/Paint.app/Resources/Leaf.png",
        }
        result = links.report(fixture(), names, "synthetic", counts=(2, 3))
        self.assertEqual(2, result["summary"]["shape"]["distinct_paths"])
        self.assertEqual(3, result["summary"]["shape"]["component_uses"])
        self.assertEqual(1, result["opaque_asset_catalogs"])
        shape = result["references"]["shape"]
        grain = result["references"]["grain"]
        self.assertEqual("unique-path-candidate", shape["Procreate_Library/Leaf.png"]["status"])
        self.assertEqual("no-loose-file-candidate", shape["Missing.png"]["status"])
        self.assertEqual("ambiguous-path-candidates", grain["Texture.jpg"]["status"])
        self.assertEqual(2, grain["Texture.jpg"]["candidate_count"])

    def test_does_not_treat_basename_collision_as_strict_suffix(self):
        found = links.resolve(
            {"Library/Leaf.png": 1},
            {"Payload/Paint.app/OtherLibrary/Leaf.png"},
        )
        self.assertEqual("no-loose-file-candidate", found["Library/Leaf.png"]["status"])

    def test_rejects_unsafe_paths_and_modified_evidence(self):
        a = fixture()
        a["brushes"][0]["archives"][0]["top"]["root"]["bundledShapePath"] = "../escape"
        with self.assertRaisesRegex(ValueError, "Invalid reference"):
            links.reference_paths(a, counts=(2, 3))
        b = fixture()
        b["reference_sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "digest"):
            links.reference_paths(b, counts=(2, 3))

    def test_rejects_ambiguous_zip_members_and_checks_hash(self):
        with tempfile.TemporaryDirectory() as temp:
            p = Path(temp) / "fixture.ipa"
            with zipfile.ZipFile(p, "w") as archive:
                archive.writestr("Payload/Paint.app/Brush.png", b"first")
                archive.writestr("payload/paint.app/brush.png", b"second")
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                links.read_inventory(p, expected_hash=None)
            with self.assertRaisesRegex(ValueError, "SHA-256"):
                links.read_inventory(p)

    def test_inventory_excludes_directories(self):
        with tempfile.TemporaryDirectory() as temp:
            p = Path(temp) / "fixture.ipa"
            with zipfile.ZipFile(p, "w") as archive:
                archive.writestr("Payload/Paint.app/", b"")
                archive.writestr("Payload/Paint.app/Assets.car", b"synthetic")
            names, _ = links.read_inventory(p, expected_hash=None)
            self.assertEqual({"Payload/Paint.app/Assets.car"}, names)


if __name__ == "__main__":
    unittest.main()
