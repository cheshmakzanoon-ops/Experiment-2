import tempfile
import unittest
from pathlib import Path

from check_licenses_and_sbom import build_cyclonedx_sbom, check_licenses, extract_components


class CheckLicensesAndSbomTest(unittest.TestCase):
    def test_repo_dependencies_pass_and_generate_valid_cyclonedx_sbom(self) -> None:
        repo_root = Path(__file__).resolve().parents[2]
        components = extract_components(repo_root / "build.gradle.kts", repo_root / "app" / "build.gradle.kts")
        self.assertGreaterEqual(len(components), 25)
        self.assertEqual(check_licenses(components), [])
        sbom = build_cyclonedx_sbom(components, revision="testrev")
        self.assertEqual(sbom["bomFormat"], "CycloneDX")
        self.assertEqual(sbom["specVersion"], "1.5")
        self.assertEqual(len(sbom["components"]), len(components))

    def test_rejects_unknown_and_gpl_licenses(self) -> None:
        bad = [
            {"group": "org.unknown", "name": "mystery", "version": "1.0", "configuration": "implementation", "scope": "required", "license": "UNKNOWN"},
            {"group": "org.copyleft", "name": "lib", "version": "1.0", "configuration": "implementation", "scope": "required", "license": "GPL-3.0-only"},
        ]
        errors = check_licenses(bad)
        self.assertEqual(len(errors), 2)


if __name__ == "__main__":
    unittest.main()
