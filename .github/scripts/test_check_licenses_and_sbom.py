import copy
import io
import json
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from xml.sax.saxutils import escape

from check_licenses_and_sbom import (
    BUILD_INPUTS,
    InventoryError,
    attach_license_evidence,
    build_cyclonedx_sbom,
    check_declarations,
    check_licenses,
    extract_components,
    main,
    normalize_license,
    sha256,
    validate_inventory,
)


class CheckLicensesAndSbomTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for relative in BUILD_INPUTS[:6]:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("// fixture\n", encoding="utf-8")
        (self.root / "build.gradle.kts").write_text('id("com.android.application") version "8.10.1" apply false\n')
        (self.root / "app/build.gradle.kts").write_text(
            'val composeBom = platform("androidx.compose:compose-bom:2024.09.03")\n'
            'implementation(composeBom)\n'
            'implementation("androidx.compose.ui:ui")\n'
            'testImplementation("org.mockito:mockito-core:5.7.0")\n'
            'debugImplementation("androidx.compose.ui:ui-tooling")\n'
        )
        self.report_directory = self.root / "app/build/reports/dependencies"
        (self.report_directory / "release-poms").mkdir(parents=True)
        self.bom = self.component("androidx.compose", "compose-bom", "2024.09.03", artifact=False)
        self.ui = self.component("androidx.compose.ui", "ui", "1.7.3")
        self.transitive = self.component("androidx.compose.runtime", "runtime-android", "1.7.3")
        self.inventory = {
            "schemaVersion": 1, "configuration": "releaseRuntimeClasspath", "project": ":app", "root": "project::app",
            "buildInputs": {relative: sha256((self.root / relative).read_bytes()) for relative in BUILD_INPUTS[:6]},
            "components": [self.bom, self.ui, self.transitive],
            "edges": [
                self.edge("project::app", self.bom["id"]),
                self.edge("project::app", self.ui["id"], requested="androidx.compose.ui:ui"),
                self.edge(self.bom["id"], self.ui["id"], constraint=True),
                self.edge(self.ui["id"], self.transitive["id"]),
            ],
            "poms": [], "unresolved": [],
        }
        for component in self.inventory["components"]:
            self.pom(component["id"])

    @staticmethod
    def component(group, name, version, artifact=True):
        coordinate = f"{group}:{name}:{version}"
        return {
            "id": coordinate, "group": group, "name": name, "version": version, "pomId": coordinate,
            "artifacts": [{
                "fileName": f"{name}-{version}.aar", "sha256": sha256(coordinate.encode()),
                "size": 123, "variant": f"{coordinate} variant releaseRuntimeElements",
            }] if artifact else [],
        }

    @staticmethod
    def edge(source, target, requested=None, constraint=False):
        return {"from": source, "to": target, "requested": requested or target, "constraint": constraint}

    def pom(self, coordinate, license_name="Apache License, Version 2.0", license_url="https://www.apache.org/licenses/LICENSE-2.0.txt", parent=None, raw=None):
        group, name, version = coordinate.split(":")
        if raw is None:
            parent_xml = ""
            if parent:
                pg, pn, pv = parent.split(":")
                parent_xml = f"<parent><groupId>{pg}</groupId><artifactId>{pn}</artifactId><version>{pv}</version></parent>"
            license_xml = "" if license_name is None else (
                f"<licenses><license><name>{escape(license_name)}</name><url>{escape(license_url)}</url></license></licenses>"
            )
            raw = (
                '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
                f"{parent_xml}<groupId>{group}</groupId><artifactId>{name}</artifactId><version>{version}</version>"
                f"{license_xml}</project>"
            )
        data = raw.encode("utf-8")
        relative = f"release-poms/{sha256(coordinate.encode())}.pom"
        (self.report_directory / relative).write_bytes(data)
        self.inventory["poms"] = [entry for entry in self.inventory["poms"] if entry["id"] != coordinate]
        self.inventory["poms"].append({"id": coordinate, "status": "resolved", "path": relative, "sha256": sha256(data), "size": len(data)})

    def components(self):
        return attach_license_evidence(self.inventory, self.report_directory)

    def invoke(self, *arguments):
        stdout, stderr = io.StringIO(), io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            result = main(["check_licenses_and_sbom.py", str(self.root), *map(str, arguments)])
        return result, stdout.getvalue(), stderr.getvalue()

    def write_inventory(self):
        path = self.report_directory / "release-runtime.json"
        path.write_text(json.dumps(self.inventory), encoding="utf-8")
        return path

    def test_declarations_keep_bom_members_unresolved_and_include_debug_tools(self):
        declarations = extract_components(self.root / "build.gradle.kts", self.root / "app/build.gradle.kts")
        by_name = {item["name"]: item for item in declarations}
        self.assertEqual(by_name["compose-bom"]["version"], "2024.09.03")
        self.assertIsNone(by_name["ui"]["version"])
        self.assertIsNone(by_name["ui-tooling"]["version"])
        self.assertEqual(by_name["mockito-core"]["configuration"], "testImplementation")
        self.assertTrue(all("license" not in item for item in declarations))
        self.assertEqual(check_declarations(declarations), [])

    def test_current_repository_declarations_are_separately_checked(self):
        root = Path(__file__).resolve().parents[2]
        declarations = extract_components(root / "build.gradle.kts", root / "app/build.gradle.kts")
        self.assertGreaterEqual(len(declarations), 25)
        self.assertEqual(check_declarations(declarations), [])
        self.assertTrue(all(item["version"] is None for item in declarations if item["name"] == "ui"))

    def test_dynamic_declarations_fail_without_resolving(self):
        for version in ("1.+", "latest.release", "[1,2)", "2.0-SNAPSHOT", "${version}"):
            with self.subTest(version=version):
                self.assertTrue(check_declarations([{"group": "g", "name": "a", "version": version}]))
        self.assertTrue(check_declarations([]))

    def test_resolved_sbom_contains_selected_versions_transitives_edges_and_hashes(self):
        validate_inventory(self.inventory, self.root)
        components = self.components()
        self.assertEqual(check_licenses(components), [])
        sbom = build_cyclonedx_sbom(self.inventory, components, revision="testrev")
        by_name = {item["name"]: item for item in sbom["components"]}
        self.assertEqual(by_name["ui"]["version"], "1.7.3")
        self.assertEqual(by_name["compose-bom"]["version"], "2024.09.03")
        self.assertEqual(by_name["compose-bom"]["scope"], "excluded")
        self.assertIn("runtime-android", by_name)
        self.assertNotIn("mockito-core", by_name)
        self.assertNotIn("com.android.application", by_name)
        self.assertEqual(by_name["ui"]["hashes"][0]["content"], self.ui["artifacts"][0]["sha256"])
        ui_edges = next(item for item in sbom["dependencies"] if item["ref"] == by_name["ui"]["bom-ref"])
        self.assertIn(by_name["runtime-android"]["bom-ref"], ui_edges["dependsOn"])
        self.assertEqual(sbom, build_cyclonedx_sbom(self.inventory, components, revision="testrev"))
        self.assertNotEqual(sbom["serialNumber"], build_cyclonedx_sbom(self.inventory, components, revision="different")["serialNumber"])

    def test_multiple_artifact_hashes_are_preserved_without_an_ambiguous_module_hash(self):
        second = dict(self.ui["artifacts"][0], fileName="ui-extra.jar", sha256=sha256(b"extra"))
        self.ui["artifacts"].append(second)
        sbom = build_cyclonedx_sbom(self.inventory, self.components())
        module = next(item for item in sbom["components"] if item["name"] == "ui")
        self.assertNotIn("hashes", module)
        self.assertEqual({item["hashes"][0]["content"] for item in module["components"]}, {item["sha256"] for item in self.ui["artifacts"]})

    def test_stale_build_inputs_fail(self):
        (self.root / "app/build.gradle.kts").write_text("// changed release dependencies\n")
        with self.assertRaisesRegex(InventoryError, "Stale"):
            validate_inventory(self.inventory, self.root)

    def test_new_version_catalog_invalidates_inventory(self):
        (self.root / "gradle/libs.versions.toml").write_text("[versions]\n")
        with self.assertRaisesRegex(InventoryError, "input set"):
            validate_inventory(self.inventory, self.root)

    def test_wrong_configuration_and_unresolved_graphs_fail(self):
        for field, value in (("configuration", "debugRuntimeClasspath"), ("unresolved", ["missing:library:1"]), ("components", [])):
            with self.subTest(field=field):
                modified = dict(self.inventory, **{field: value})
                with self.assertRaises(InventoryError):
                    validate_inventory(modified, self.root)

    def test_missing_or_disconnected_graph_nodes_fail(self):
        self.inventory["edges"].append(self.edge(self.ui["id"], "missing:module:1"))
        with self.assertRaisesRegex(InventoryError, "missing component"):
            validate_inventory(self.inventory, self.root)
        self.inventory["edges"].pop()
        self.inventory["edges"] = [edge for edge in self.inventory["edges"] if edge["to"] != self.transitive["id"]]
        with self.assertRaisesRegex(InventoryError, "unreachable"):
            validate_inventory(self.inventory, self.root)

    def test_duplicate_components_and_artifacts_fail(self):
        self.inventory["components"].append(copy.deepcopy(self.ui))
        with self.assertRaisesRegex(InventoryError, "Duplicate"):
            validate_inventory(self.inventory, self.root)
        self.inventory["components"].pop()
        self.ui["artifacts"].append(copy.deepcopy(self.ui["artifacts"][0]))
        with self.assertRaisesRegex(InventoryError, "Duplicate artifact"):
            validate_inventory(self.inventory, self.root)

    def test_missing_hash_or_selected_version_fails(self):
        self.ui["artifacts"][0]["sha256"] = "bad"
        with self.assertRaisesRegex(InventoryError, "SHA-256"):
            validate_inventory(self.inventory, self.root)
        self.ui["artifacts"] = []
        self.ui["version"] = "bom-managed"
        with self.assertRaisesRegex(InventoryError, "inconsistent component"):
            validate_inventory(self.inventory, self.root)

    def test_no_group_name_license_guessing(self):
        self.pom(self.ui["id"], license_name=None)
        components = self.components()
        self.assertIn("UNKNOWN", "\n".join(check_licenses(components)))
        sbom = build_cyclonedx_sbom(self.inventory, components)
        ui = next(item for item in sbom["components"] if item["name"] == "ui")
        self.assertNotIn("licenses", ui)
        self.assertNotIn('"id": "UNKNOWN"', json.dumps(sbom))
        self.assertIn({"name": "artflow:license:evidence-status", "value": "unknown"}, ui["properties"])

    def test_missing_pom_result_fails_and_explicit_unavailable_pom_is_unknown(self):
        self.inventory["poms"] = [entry for entry in self.inventory["poms"] if entry["id"] != self.ui["id"]]
        with self.assertRaisesRegex(InventoryError, "Missing POM result"):
            self.components()
        self.inventory["poms"].append({"id": self.ui["id"], "status": "unresolved", "reason": "POM unavailable"})
        self.assertIn("POM unavailable", "\n".join(check_licenses(self.components())))

    def test_parent_licenses_use_retrieved_parent_hashes(self):
        parent = "example:parent:1"
        self.pom(self.ui["id"], license_name=None, parent=parent)
        self.pom(parent, "MIT", "https://opensource.org/licenses/MIT")
        components = self.components()
        self.assertEqual(check_licenses(components), [])
        ui = next(item for item in components if item["name"] == "ui")
        self.assertEqual(ui["licenses"][0]["id"], "MIT")
        self.assertEqual(ui["licenses"][0]["pomId"], parent)
        evidence = next(item for item in self.inventory["poms"] if item["id"] == parent)
        self.assertEqual(ui["licenses"][0]["pomSha256"], evidence["sha256"])

    def test_missing_or_cyclic_parent_is_unknown(self):
        parent = "example:parent:1"
        self.pom(self.ui["id"], license_name=None, parent=parent)
        self.assertIn("POM unavailable", "\n".join(check_licenses(self.components())))
        self.pom(parent, license_name=None, parent=self.ui["id"])
        self.assertIn("Cyclic", "\n".join(check_licenses(self.components())))

    def test_pom_hash_tampering_and_path_traversal_fail(self):
        entry = next(item for item in self.inventory["poms"] if item["id"] == self.ui["id"])
        (self.report_directory / entry["path"]).write_text("changed")
        with self.assertRaisesRegex(InventoryError, "hash/size mismatch"):
            self.components()
        entry["path"] = "../../outside.pom"
        with self.assertRaisesRegex(InventoryError, "Unsafe"):
            self.components()

    def test_dtd_and_wrong_pom_identity_cannot_supply_licenses(self):
        self.pom(self.ui["id"], raw='<!DOCTYPE project [<!ENTITY a "Apache-2.0">]><project><artifactId>ui</artifactId></project>')
        self.assertIn("DTD/entity", "\n".join(check_licenses(self.components())))
        self.pom(self.ui["id"], raw='<project><groupId>androidx.compose.ui</groupId><artifactId>wrong</artifactId><version>1.7.3</version></project>')
        self.assertIn("identity mismatch", "\n".join(check_licenses(self.components())))

    def test_license_conflicts_and_copyleft_declarations_fail(self):
        for name, url in (
            ("GPL-3.0-only", "https://www.apache.org/licenses/LICENSE-2.0"),
            ("GNU Affero General Public License", "https://opensource.org/licenses/MIT"),
            ("MIT", "https://www.apache.org/licenses/LICENSE-2.0"),
            ("Unreviewed License", "https://example.org/license"),
        ):
            with self.subTest(name=name):
                self.pom(self.ui["id"], name, url)
                self.assertTrue(check_licenses(self.components()))

    def test_license_urls_require_an_exact_recognized_host(self):
        self.assertEqual(normalize_license("", "https://www.apache.org/licenses/LICENSE-2.0.txt"), "Apache-2.0")
        self.assertEqual(normalize_license("", "https://spdx.org/licenses/MIT.html"), "MIT")
        self.assertIsNone(normalize_license("", "https://apache.org.evil.example/licenses/LICENSE-2.0"))
        self.assertIsNone(normalize_license("", "file://spdx.org/licenses/MIT.html"))
        self.assertIsNone(normalize_license("MIT", "https://www.apache.org/licenses/LICENSE-2.0"))

    def test_cli_declarations_never_claim_license_verification(self):
        code, stdout, stderr = self.invoke("--declarations-only")
        self.assertEqual(code, 0, stderr)
        self.assertIn("licenses require the release inventory", stdout)
        self.assertFalse((self.report_directory / "release-runtime.json").exists())

    def test_cli_requires_resolved_evidence_and_removes_stale_sbom(self):
        output = self.root / "sbom.json"
        output.write_text('{"stale": true}')
        code, _, stderr = self.invoke("--sbom-out", output)
        self.assertEqual(code, 1)
        self.assertIn("release-runtime.json", stderr)
        self.assertFalse(output.exists())

    def test_cli_exports_unknown_evidence_but_returns_failure(self):
        self.pom(self.ui["id"], license_name=None)
        self.write_inventory()
        output = self.root / "sbom.json"
        code, _, stderr = self.invoke("--sbom-out", output)
        self.assertEqual(code, 1)
        self.assertIn("UNKNOWN", stderr)
        self.assertEqual(json.loads(output.read_text())["bomFormat"], "CycloneDX")

    def test_cli_succeeds_only_with_complete_graph_and_permitted_pom_evidence(self):
        inventory_path = self.write_inventory()
        output = self.root / "sbom.json"
        code, stdout, stderr = self.invoke("--resolved-inventory", inventory_path, "--sbom-out", output, "--revision", "verified-source")
        self.assertEqual(code, 0, stderr)
        self.assertIn("POM-declared license evidence", stdout)
        sbom = json.loads(output.read_text())
        self.assertIn({"name": "artflow:git:revision", "value": "verified-source"}, sbom["metadata"]["properties"])


if __name__ == "__main__":
    unittest.main()
