import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('audit', Path(__file__).with_name('rea_brush_dependency_audit.py'))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)

SHA = audit.EXPECTED_SHA


def fixture():
    return {'reference_sha256': SHA, 'brushes': [
        {'path': 'A.brush', 'members': ['Brush.archive', 'QuickLook/Thumbnail.png'],
         'archives': [{'path': 'Brush.archive', 'top': {'root': {
             'shapeCount': 0.0, 'shapeInverted': False, 'bundledShapePath': 'BuiltIn.png',
             'bundledGrainPath': '$null'}}}]},
        {'path': 'B.brush', 'members': ['Brush.archive', 'Sub01/Brush.archive',
             'QuickLook/Thumbnail.png', 'GL-Palm-Shape.png'],
         'archives': [
             {'path': 'Brush.archive', 'top': {'root': {'shapeCount': 0.5, 'textureScale': 2.5,
                 'bundledShapePath': 'BuiltIn.png', 'bundledGrainPath': 'Grit.png'}}},
             {'path': 'Sub01/Brush.archive', 'top': {'root': {'shapeCount': 0.5, 'textureScale': 0.0,
                 'bundledShapePath': 'Other.png', 'bundledGrainPath': 'Grit.png'}}},
         ]},
    ]}


class AuditTests(unittest.TestCase):
    def test_structural_counts_and_unread_keys(self):
        reader = 'val count = unit("shapeCount"); val shape = brush["bundledShapePath"]'
        report = audit.analyze(fixture(), reader, expected_counts=(2, 3))
        self.assertEqual(2, report['logical_brushes'])
        self.assertEqual(1, report['compound_brushes'])
        self.assertEqual(5, report['observed_settings_fields'])
        self.assertEqual(2, report['read_fields'])
        self.assertEqual(3, report['unread_fields'])
        self.assertEqual(2, report['embedded_preview_thumbnails'])
        self.assertEqual(1, report['separate_brush_asset_member_count'])
        self.assertEqual(2, report['distinct_bundled_shape_paths'])
        self.assertEqual(1, report['distinct_bundled_grain_paths'])

    def test_reference_identity_and_structure_fail_closed(self):
        bad = fixture()
        bad['reference_sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'hash mismatch'):
            audit.analyze(bad, '', expected_counts=(2, 3))
        bad = fixture()
        bad['brushes'][1]['archives'][1]['path'] = 'Bad/Brush.archive'
        with self.assertRaisesRegex(ValueError, 'primary/secondary'):
            audit.analyze(bad, '', expected_counts=(2, 3))

    def test_reader_detects_only_exact_key_accesses(self):
        keys = audit.reader_keys('number("alpha") flag("enabled") brush["name"] other("ignore")')
        self.assertEqual({'alpha', 'enabled', 'name'}, keys)


if __name__ == '__main__':
    unittest.main()
