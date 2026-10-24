"""Bounded parser and fixed-fixture checks; no IPA, LLVM, or native execution needed."""
import copy
import json
from pathlib import Path
import struct
import tempfile
import unittest

import ipa_layer_blend_contracts as contracts


class LayerBlendContractsTest(unittest.TestCase):
    def test_truncated_wrapper_is_rejected_before_unpacking(self):
        with self.assertRaisesRegex(ValueError, 'Truncated AIR wrapper'):
            contracts.shader_bitcode(bytes(contracts.WRAPPER_OFFSET + 19))

    def test_wrong_wrapper_fields_are_rejected(self):
        for field in range(4):
            with self.subTest(field=field):
                payload = self.module_bytes()
                fields = [0x0b17c0de, 0, 20, contracts.BITCODE_LENGTH, 0]
                fields[field] += 1
                struct.pack_into('<5I', payload, contracts.WRAPPER_OFFSET, *fields)
                with self.assertRaisesRegex(ValueError, 'AIR wrapper differs'):
                    contracts.shader_bitcode(payload)

    def test_truncated_module_is_rejected_before_llvm_parsing(self):
        with self.assertRaisesRegex(ValueError, 'Truncated AIR bitcode'):
            contracts.shader_bitcode(self.module_bytes()[:-1])

    def test_complete_but_unpinned_module_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'AIR hash differs'):
            contracts.shader_bitcode(self.module_bytes())

    def test_exact_rational_cases_match_the_independent_committed_literals(self):
        path = Path(__file__).resolve().parents[1] / 'app/src/test/resources/layers/native-layer-blend-fixtures.json'
        report = self.report()
        self.assertEqual(9, len(report['derivedFixtures']))
        self.assertEqual(9, len({row['name'] for row in report['derivedFixtures']}))
        contracts.check_fixtures(report, path)

    def test_changed_expected_output_fails_the_gate(self):
        fixture = self.fixture()
        fixture['fixtures'][0]['output'][3] = '1/2'
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'changed.json'
            path.write_text(json.dumps(fixture))
            with self.assertRaisesRegex(ValueError, 'Derived layer-blend fixtures differ'):
                contracts.check_fixtures(self.report(), path)

    def test_wrong_reference_or_schema_cannot_validate_a_fixture_set(self):
        for field, value in [('referenceSha256', '0' * 64), ('schemaVersion', 2)]:
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                fixture = self.fixture()
                fixture[field] = value
                path = Path(directory) / 'wrong-source.json'
                path.write_text(json.dumps(fixture))
                with self.assertRaisesRegex(ValueError, 'Fixture provenance differs'):
                    contracts.check_fixtures(self.report(), path)

    @staticmethod
    def module_bytes():
        payload = bytearray(contracts.WRAPPER_OFFSET + 20 + contracts.BITCODE_LENGTH)
        struct.pack_into('<5I', payload, contracts.WRAPPER_OFFSET, 0x0b17c0de, 0, 20, contracts.BITCODE_LENGTH, 0)
        return payload

    @staticmethod
    def report():
        return {'ipaSha256': contracts.REFERENCE_SHA256, 'derivedFixtures': contracts.fixtures()}

    @classmethod
    def fixture(cls):
        return {'schemaVersion': 1, 'referenceSha256': contracts.REFERENCE_SHA256,
                'fixtures': copy.deepcopy(cls.report()['derivedFixtures'])}


if __name__ == '__main__':
    unittest.main()
