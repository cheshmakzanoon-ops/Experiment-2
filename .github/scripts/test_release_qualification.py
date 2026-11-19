"""Optimized qualification must reject missing work and mismatched installed artifacts."""
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

from release_qualification import APK_PATHS, BUILD_CONTRACT, CLASSES, EVIDENCE, REFLECTION_RULES, RESULTS, TEST_ABI, selected_inventory, verify


TOKEN = "12345678-1234-1234-1234-123456789abc"
REVISION = "a" * 40


class ReleaseQualificationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.project = Path(self.temporary.name)
        self.output = self.project / "qualification"
        self.output.mkdir()
        self.evidence = self.project / EVIDENCE / "connected/device/test-evidence"
        self.evidence.mkdir(parents=True)
        source = self.project / "app/src/androidTest/java"
        source.mkdir(parents=True)
        for qualified in CLASSES:
            package, name = qualified.rsplit(".", 1)
            (source / f"{name}.kt").write_text(f"package {package}\nclass {name} {{\n@Test fun qualifies() {{}}\n}}\n")
        self.expected = sorted(selected_inventory(source))
        self.write_json(self.output / "test-inventory.json", self.expected)
        (self.output / "attempt-token.txt").write_text(TOKEN)
        (self.output / "instrumentation-exit-code.txt").write_text("0\n")
        (self.output / "instrumentation-log-exit-code.txt").write_text("0\n")
        (self.output / "first-attempt-instrumentation.log").write_text("BUILD SUCCESSFUL\n")
        self.xml = self.project / RESULTS / "device/TEST-qualified.xml"
        self.xml.parent.mkdir(parents=True)
        self.results = ET.Element("testsuite")
        for identity in self.expected:
            classname, name = identity.split("#")
            ET.SubElement(self.results, "testcase", classname=classname, name=name)
        self.save_results()
        (self.evidence / "runtime-environment.txt").write_text(
            "sdk=36\npageSize=4096\nabis=x86_64\nfingerprint=fixture/device\n")
        self.write_json(self.project / BUILD_CONTRACT, {
            "schemaVersion": 2, "buildType": "benchmark", "minifyEnabled": True,
            "shrinkResources": True, "debuggable": False, "signingConfig": "debug",
            "publisherReleaseRulesUnchanged": True, "benchmarkAddsGeneratedTestAbiRules": True,
            "benchmarkAddsAuditedReflectionFields": True, "testAbiAllowsObfuscation": True,
            "publisherQualified": False,
        })
        self.installed = {"schemaVersion": 1, "attemptToken": TOKEN, "packageName": "com.artflow.studio",
                          "buildType": "benchmark", "debuggable": False}
        self.write_json(self.evidence / "qualification-build.json", self.installed)
        self.mapping = self.project / "app/build/outputs/mapping/benchmark"
        self.mapping.mkdir(parents=True)
        for name, content in {"mapping.txt": "com.artflow.studio.RealClass -> a:\n",
                              "configuration.txt": "-keep class fixture.MainActivity\n",
                              "usage.txt": "fixture.UnusedClass\n",
                              "resources.txt": "@drawable/removed : reachable=false\n"}.items():
            (self.mapping / name).write_text(content)
        self.abi = self.project / TEST_ABI
        self.abi.mkdir(parents=True)
        for name, classes in (("app-input.jar", ["fixture/Library.class"]), ("test-input.jar", ["tests/Caller.class"])):
            with zipfile.ZipFile(self.abi / name, "w") as archive:
                for entry in classes:
                    archive.writestr(entry, b"fixture class inventory; actual bytecode is exercised by the R8 fixture gate")
        self.generated_rules = "-keep,allowobfuscation class fixture.Library {\n  public static int value();\n}\n"
        (self.abi / "keep-rules.pro").write_text(self.generated_rules)
        self.abi_report = {
            "schemaVersion": 1, "generator": "R8 TraceReferences", "r8Version": "8.10.24",
            "scope": "benchmark-instrumentation-boundary", "allowObfuscation": True,
            "publisherReleaseRulesModified": False, "appInputClasses": 1, "testInputClasses": 1,
            "r8Tool": {"file": "builder-8.10.1.jar", "sizeBytes": 10, "sha256": "a" * 64},
            "boundary": {"classes": ["fixture.Library"], "fields": [], "methods": ["int fixture.Library.value()"], "packages": []},
            "externalUnresolvedReferences": [],
            "files": {key: self.abi_record(name) for key, name in (
                ("appInput", "app-input.jar"), ("testInput", "test-input.jar"), ("rules", "keep-rules.pro"))},
        }
        self.save_abi()
        reflection = Path(__file__).resolve().parents[2] / REFLECTION_RULES
        (self.project / REFLECTION_RULES).write_text(reflection.read_text())
        with (self.mapping / "configuration.txt").open("a") as configuration:
            configuration.write(self.generated_rules + reflection.read_text())
        self.namespace = self.evidence / "apk-provenance" / TOKEN
        self.namespace.mkdir(parents=True)
        manifest = {"schemaVersion": 1, "attemptToken": TOKEN, "errors": [], "packages": {}}
        for package, relative in APK_PATHS.items():
            built = self.project / relative
            built.parent.mkdir(parents=True)
            with zipfile.ZipFile(built, "w") as archive:
                archive.writestr("AndroidManifest.xml", b"fixture manifest")
                archive.writestr("classes.dex", package.encode())
            data = built.read_bytes()
            capture = Path("installed") / package / "00.apk"
            snapshot = self.namespace / capture
            snapshot.parent.mkdir(parents=True)
            snapshot.write_bytes(data)
            manifest["packages"][package] = {"packageName": package, "errors": [], "apks": [{
                "file": capture.as_posix(), "captureComplete": True, "errors": [],
                "sizeBytes": len(data), "sha256": hashlib.sha256(data).hexdigest(),
            }]}
        self.write_json(self.namespace / "manifest.json", manifest)
        self.write_json(self.evidence / "optimized-workflow.json", {
            "schemaVersion": 1, "buildType": "benchmark", "width": 64, "height": 64,
            "paintSaveReopenExportPixelsEqual": True,
        })
        header = b"\x89PNG\r\n\x1a\n" + struct.pack(">I", 13) + b"IHDR" + struct.pack(">II", 64, 64)
        (self.evidence / "optimized-workflow-export.png").write_bytes(header)
        (self.evidence / "optimized-workflow-export-ui.png").write_bytes(header)

    @staticmethod
    def write_json(path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value))

    def save_results(self):
        ET.ElementTree(self.results).write(self.xml)

    def abi_record(self, name):
        data = (self.abi / name).read_bytes()
        return {"file": name, "sizeBytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}

    def save_abi(self):
        self.write_json(self.abi / "report.json", self.abi_report)

    def report(self):
        return verify(self.project, self.output, REVISION)

    def assert_rejected(self, fragment):
        report = self.report()
        self.assertFalse(report["passed"], report)
        self.assertIn(fragment, "\n".join(report["problems"]))
        self.assertTrue((self.output / "verification.json").is_file())
        self.assertTrue((self.output / "verification.md").is_file())

    def test_accepts_complete_first_attempt_with_identical_installed_apks(self):
        report = self.report()
        self.assertTrue(report["passed"], report["problems"])
        self.assertEqual(len(CLASSES), report["counts"]["passed"])
        self.assertEqual(set(APK_PATHS), set(report["installedApks"]["packages"]))
        self.assertIn("resources.txt", report["build"]["r8AndResourceShrinker"])
        self.assertFalse(report["build"]["testAbi"]["publisherReleaseEquivalent"])
        self.assertEqual(1, report["build"]["testAbi"]["keptMethods"])
        self.assertEqual(2, report["build"]["testAbi"]["reflectionFields"])

    def test_missing_test_cannot_pass(self):
        self.results.remove(self.results[0])
        self.save_results()
        self.assert_rejected("Missing test:")

    def test_skipped_test_cannot_pass_even_when_gradle_exits_zero(self):
        ET.SubElement(self.results[0], "skipped")
        self.save_results()
        self.assert_rejected("no skips/retries permitted")

    def test_test_failure_cannot_pass(self):
        ET.SubElement(self.results[0], "failure", message="Pixel mismatch")
        self.save_results()
        self.assert_rejected("failure")

    def test_duplicate_or_retried_test_cannot_pass(self):
        self.results.append(ET.fromstring(ET.tostring(self.results[0])))
        self.save_results()
        self.assert_rejected("Duplicate result")

    def test_extra_case_cannot_mask_missing_coverage(self):
        ET.SubElement(self.results, "testcase", classname="other.Test", name="unexpected")
        self.save_results()
        self.assert_rejected("Unexpected test:")

    def test_malformed_report_is_preserved_as_failed_verification(self):
        self.xml.write_text("<broken")
        self.assert_rejected("results:")

    def test_original_timeout_status_is_not_replaced_by_green_xml(self):
        (self.output / "instrumentation-exit-code.txt").write_text("124\n")
        self.assert_rejected("failed or timed out: 124")

    def test_partial_log_capture_cannot_qualify(self):
        (self.output / "instrumentation-log-exit-code.txt").write_text("1\n")
        self.assert_rejected("log could not be preserved completely")

    def test_debug_installed_application_cannot_qualify(self):
        self.installed.update(buildType="debug", debuggable=True)
        self.write_json(self.evidence / "qualification-build.json", self.installed)
        self.assert_rejected("non-debuggable benchmark")

    def test_stale_runtime_evidence_cannot_qualify(self):
        self.installed["attemptToken"] = "00000000-0000-0000-0000-000000000000"
        self.write_json(self.evidence / "qualification-build.json", self.installed)
        self.assert_rejected("this attempt")

    def test_wrong_installed_apk_bytes_cannot_qualify(self):
        with (self.project / APK_PATHS["com.artflow.studio"]).open("ab") as stream:
            stream.write(b"different build")
        self.assert_rejected("Installed APK bytes do not match")

    def test_incomplete_snapshot_cannot_qualify(self):
        path = self.namespace / "manifest.json"
        manifest = json.loads(path.read_text())
        manifest["packages"]["com.artflow.studio"]["apks"][0]["captureComplete"] = False
        self.write_json(path, manifest)
        self.assert_rejected("Incomplete installed APK capture")

    def test_malformed_runner_records_produce_a_failed_report(self):
        path = self.namespace / "manifest.json"
        original = json.loads(path.read_text())
        malformed = [None, [], {**original, "packages": None}]
        for record in (None, [], {"apks": None}, {"apks": [None]}):
            value = json.loads(json.dumps(original))
            if isinstance(record, dict):
                value["packages"]["com.artflow.studio"].update(record)
            else:
                value["packages"]["com.artflow.studio"] = record
            malformed.append(value)
        for value in malformed:
            with self.subTest(value=value):
                self.write_json(path, value)
                self.assert_rejected("installedApks:")

    def test_second_attempt_snapshot_is_rejected(self):
        (self.namespace.parent / "00000000-0000-0000-0000-000000000000").mkdir()
        self.assert_rejected("stale/repeated captures")

    def test_disabled_optimization_cannot_qualify(self):
        (self.mapping / "configuration.txt").write_text("-dontoptimize\n")
        self.assert_rejected("disables a required production optimization")

    def test_mapping_without_obfuscated_application_classes_cannot_qualify(self):
        (self.mapping / "mapping.txt").write_text("com.artflow.studio.RealClass -> com.artflow.studio.RealClass:\n")
        self.assert_rejected("no obfuscated production class")

    def test_missing_resource_shrinker_output_cannot_qualify(self):
        (self.mapping / "resources.txt").unlink()
        self.assert_rejected("resources.txt")

    def test_missing_traced_boundary_evidence_cannot_qualify(self):
        (self.abi / "report.json").unlink()
        self.assert_rejected("report.json")

    def test_exact_annotation_interface_rules_are_supported(self):
        rules = self.generated_rules.replace("class fixture.Library", "@interface fixture.Library")
        (self.abi / "keep-rules.pro").write_text(rules)
        self.abi_report["files"]["rules"] = self.abi_record("keep-rules.pro")
        self.save_abi()
        config = self.mapping / "configuration.txt"
        config.write_text(config.read_text().replace(self.generated_rules, rules))
        result = self.report()
        self.assertTrue(result["passed"], result["problems"])

    def test_r8_enum_name_preservation_is_explicit(self):
        rules = self.generated_rules.replace("-keep,allowobfuscation class", "-keep enum")
        (self.abi / "keep-rules.pro").write_text(rules)
        self.abi_report["files"]["rules"] = self.abi_record("keep-rules.pro")
        self.save_abi()
        config = self.mapping / "configuration.txt"
        config.write_text(config.read_text().replace(self.generated_rules, rules))
        result = self.report()
        self.assertTrue(result["passed"], result["problems"])
        self.assertEqual(["fixture.Library"], result["build"]["testAbi"]["enumNamesPreservedByR8"])

    def test_generated_non_enum_classes_must_allow_obfuscation(self):
        (self.abi / "keep-rules.pro").write_text(self.generated_rules.replace(",allowobfuscation", ""))
        self.abi_report["files"]["rules"] = self.abi_record("keep-rules.pro")
        self.save_abi()
        self.assert_rejected("disables obfuscation for a non-enum class")

    def test_mutated_generated_rules_cannot_reuse_a_successful_trace(self):
        with (self.abi / "keep-rules.pro").open("a") as rules:
            rules.write("-keep class ** { *; }\n")
        self.assert_rejected("artifact hash mismatch")

    def test_wildcard_keep_cannot_be_certified_by_updating_its_hash(self):
        with (self.abi / "keep-rules.pro").open("a") as rules:
            rules.write("-keep class ** { *; }\n")
        self.abi_report["files"]["rules"] = self.abi_record("keep-rules.pro")
        self.save_abi()
        self.assert_rejected("wildcard instead of an exact symbol")

    def test_external_class_cannot_be_added_to_the_traced_boundary(self):
        self.abi_report["boundary"]["classes"].append("other.Library")
        self.save_abi()
        self.assert_rejected("outside its application input")

    def test_shared_source_copy_cannot_hide_app_owned_references(self):
        with zipfile.ZipFile(self.abi / "test-input.jar", "a") as archive:
            archive.writestr("fixture/Library.class", b"same app-owned name")
        self.abi_report["files"]["testInput"] = self.abi_record("test-input.jar")
        self.abi_report["testInputClasses"] = 2
        self.save_abi()
        self.assert_rejected("app-owned duplicate")

    def test_trace_must_be_applied_to_the_application_r8_configuration(self):
        (self.mapping / "configuration.txt").write_text("-keep class fixture.MainActivity\n")
        self.assert_rejected("did not apply the recorded")

    def test_reflection_contract_cannot_expand_to_additional_private_fields(self):
        with (self.project / REFLECTION_RULES).open("a") as rules:
            rules.write("-keepclassmembers class fixture.Library { int other; }\n")
        self.assert_rejected("exceed the two audited private fields")

    def test_retained_member_counts_must_match_actual_rules(self):
        self.abi_report["boundary"]["methods"].append("void fixture.Library.extra()")
        self.save_abi()
        self.assert_rejected("do not match the retained symbol inventory")

    def test_obfuscation_cannot_be_disabled_on_the_generated_boundary(self):
        self.abi_report["allowObfuscation"] = False
        self.save_abi()
        self.assert_rejected("scope or obfuscation contract")

    def test_wrong_runtime_cannot_qualify(self):
        (self.evidence / "runtime-environment.txt").write_text(
            "sdk=35\npageSize=16384\nabis=x86_64\nfingerprint=fixture/device\n")
        self.assert_rejected("Wrong measured optimized-test runtime")

    def test_missing_export_proof_cannot_qualify(self):
        (self.evidence / "optimized-workflow-export.png").unlink()
        self.assert_rejected("optimized-workflow-export.png")

    def test_real_checkout_inventory_covers_all_selected_classes(self):
        repo = Path(__file__).resolve().parents[2]
        inventory = selected_inventory(repo / "app/src/androidTest/java")
        self.assertEqual(set(CLASSES), {identity.split("#", 1)[0] for identity in inventory})
        self.assertIn("com.artflow.studio.OptimizedArtworkWorkflowTest#"
                      "realPointerPaintSurvivesSaveFreshActivityReopenAndUiPngExport", inventory)


if __name__ == "__main__":
    unittest.main()
