#!/usr/bin/env python3
"""Exercise the real AGP-bundled R8 boundary generator, including separate app/test rewriting."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile


class TestAbiRulesIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.app = self.compile("app", {
            "fixture/Library.java": """package fixture;
                public class Library {
                    public static int invokedOnlyByTest() { return new InternalHelper().answer(); }
                    public static int unused() { return 99; }
                }""",
            "fixture/InternalHelper.java": """package fixture;
                final class InternalHelper { int answer() { return 42; } }""",
        })
        self.tests = self.compile("test", {
            "tests/Caller.java": """package tests;
                public class Caller {
                    public static void main(String[] args) {
                        if (fixture.Library.invokedOnlyByTest() != 42) throw new AssertionError();
                        System.out.println("BOUNDARY_OK");
                    }
                }""",
        }, self.app)

    def run_process(self, arguments, success=True):
        result = subprocess.run([str(item) for item in arguments], text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=90)
        if success:
            self.assertEqual(0, result.returncode, result.stdout)
        else:
            self.assertNotEqual(0, result.returncode, result.stdout)
        return result.stdout

    def compile(self, name, sources, classpath=None):
        directory = self.root / name
        files = []
        for relative, content in sources.items():
            file = directory / "src" / relative
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(content)
            files.append(file)
        classes = directory / "classes"
        command = [self.java_home / "bin/javac", "-d", classes]
        if classpath is not None:
            command.extend(["-cp", classpath])
        self.run_process(command + files)
        jar = directory / "classes.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            for file in sorted(classes.rglob("*.class")):
                archive.write(file, file.relative_to(classes).as_posix())
        return jar

    def generate(self, app=None, test=None, output="evidence", success=True, libraries=()):
        destination = self.root / output
        command = [self.java_home / "bin/java", "-cp", self.generator_classpath,
                   "artflow.build.TestAbiRules", "--output", destination,
                   "--library", self.java_home]
        for item in app or [self.app]:
            command.extend(["--app-input", item])
        for item in test or [self.tests]:
            command.extend(["--test-input", item])
        for item in libraries:
            command.extend(["--library", item])
        result = self.run_process(command, success)
        return destination, result

    def r8(self, arguments):
        return self.run_process([self.java_home / "bin/java", "-cp", self.r8_tool,
                                 "com.android.tools.r8.R8", "--classfile", "--release",
                                 "--lib", self.java_home] + arguments)

    def test_stable_traced_api_executes_while_unreferenced_helpers_still_shrink(self):
        evidence, _ = self.generate()
        rules = (evidence / "keep-rules.pro").read_text()
        self.assertIn("-keep class fixture.Library", rules)
        self.assertNotIn("allowobfuscation", rules)
        self.assertIn("invokedOnlyByTest", rules)
        self.assertNotIn("unused", rules)
        self.assertNotIn("-dont", rules)
        app_rules = self.root / "app.pro"
        mapping = self.root / "mapping.txt"
        usage = self.root / "usage.txt"
        app_rules.write_text(rules + f"\n-printmapping {mapping}\n-printusage {usage}\n")
        optimized_app = self.root / "app-optimized.jar"
        self.r8(["--pg-conf", app_rules, "--output", optimized_app, self.app])
        self.assertIn("fixture.Library -> fixture.Library:", mapping.read_text())
        self.assertIn("fixture.InternalHelper", usage.read_text())
        self.assertIn("unused()", usage.read_text())
        # AGP keeps test code and applies the app mapping to its external references.
        test_rules = self.root / "test.pro"
        test_rules.write_text("-dontoptimize\n-keep class * { *; }\n-keep interface * { *; }\n"
                              "-keep enum * { *; }\n-keepattributes *\n"
                              f"-applymapping {mapping}\n")
        optimized_tests = self.root / "test-remapped.jar"
        # AGP adds tested code as a classpath provider; --lib would mean stable platform code,
        # whose references R8 intentionally does not adapt to an application mapping.
        self.r8(["--classpath", self.app, "--no-minification", "--no-tree-shaking",
                 "--pg-conf", test_rules, "--output", optimized_tests, self.tests])
        result = self.run_process([self.java_home / "bin/java", "-cp",
                                   os.pathsep.join(map(str, [optimized_app, optimized_tests])), "tests.Caller"])
        self.assertIn("BOUNDARY_OK", result)

    def test_actual_kotlin_default_impls_link_across_separately_optimized_app_and_test(self):
        # These are the exact Kotlin/annotation class bytes from AGP's retained app input,
        # not a hand-written stand-in or a separately downloaded test dependency.
        tests = self.compile("kotlin-test", {
            "tests/KotlinCaller.java": """package tests;
                public class KotlinCaller {
                    public static void main(String[] args) {
                        kotlin.coroutines.ContinuationInterceptor.DefaultImpls.releaseInterceptedContinuation(
                            null, new kotlin.coroutines.Continuation<Object>() {
                                public kotlin.coroutines.CoroutineContext getContext() { return null; }
                                public void resumeWith(Object result) {}
                            });
                        System.out.println("KOTLIN_DEFAULT_IMPL_OK");
                    }
                }""",
        }, self.kotlin_input)
        evidence, _ = self.generate(app=[self.kotlin_input], test=[tests],
                                    libraries=[self.annotations_input], output="kotlin-evidence")
        rules = (evidence / "keep-rules.pro").read_text()
        self.assertIn("releaseInterceptedContinuation", rules)
        app_rules = self.root / "kotlin-app.pro"
        mapping = self.root / "kotlin-mapping.txt"
        usage = self.root / "kotlin-usage.txt"
        app_rules.write_text(rules + f"\n-allowaccessmodification\n-printmapping {mapping}\n-printusage {usage}\n")
        optimized_app = self.root / "kotlin-app.jar"
        self.r8(["--lib", self.annotations_input, "--pg-conf", app_rules,
                 "--output", optimized_app, self.kotlin_input])
        self.assertIn("kotlin.coroutines.ContinuationInterceptor$DefaultImpls -> "
                      "kotlin.coroutines.ContinuationInterceptor$DefaultImpls:", mapping.read_text())
        self.assertIn("kotlin.jvm.internal.Intrinsics -> ", mapping.read_text())
        self.assertNotIn("kotlin.jvm.internal.Intrinsics -> kotlin.jvm.internal.Intrinsics:", mapping.read_text())
        self.assertIn("fold(", usage.read_text())
        test_rules = self.root / "kotlin-test.pro"
        test_rules.write_text("-dontoptimize\n-keep class * { *; }\n-keep interface * { *; }\n"
                              "-keep enum * { *; }\n-keepattributes *\n"
                              f"-applymapping {mapping}\n")
        optimized_tests = self.root / "kotlin-test.jar"
        self.r8(["--lib", self.annotations_input, "--classpath", self.kotlin_input,
                 "--no-minification", "--no-tree-shaking", "--pg-conf", test_rules,
                 "--output", optimized_tests, tests])
        result = self.run_process([self.java_home / "bin/java", "-cp",
                                   os.pathsep.join(map(str, [optimized_app, optimized_tests])), "tests.KotlinCaller"])
        self.assertIn("KOTLIN_DEFAULT_IMPL_OK", result)

    def test_duplicate_app_owned_source_cannot_hide_external_references(self):
        original, _ = self.generate()
        overlap, _ = self.generate(test=[self.tests, self.app], output="overlap")
        self.assertEqual((original / "keep-rules.pro").read_bytes(), (overlap / "keep-rules.pro").read_bytes())
        self.assertEqual(1, json.loads((overlap / "report.json").read_text())["testInputClasses"])

    def test_missing_app_member_rejects_and_removes_stale_success_evidence(self):
        evidence, _ = self.generate()
        incompatible = self.compile("incompatible", {
            "fixture/Library.java": "package fixture; public class Library { public static int other() { return 7; } }",
        })
        _, error = self.generate(app=[incompatible], success=False)
        self.assertIn("missing application members", error)
        self.assertFalse((evidence / "report.json").exists())
        self.assertFalse((evidence / "keep-rules.pro").exists())

    def test_conflicting_input_definitions_are_not_silently_selected(self):
        other = self.compile("conflict", {
            "fixture/Library.java": "package fixture; public class Library { public static int invokedOnlyByTest() { return 13; } }",
        })
        _, error = self.generate(app=[self.app, other], success=False)
        self.assertIn("Conflicting bytecode definitions", error)

    def test_inherited_field_and_method_are_traced_to_the_defining_class(self):
        app = self.compile("inherited", {
            "fixture/Parent.java": "package fixture; public class Parent { public int value; public int api() { return value; } }",
            "fixture/Child.java": "package fixture; public class Child extends Parent {}",
        })
        tests = self.compile("inherited-test", {
            "tests/Inherited.java": """package tests; public class Inherited {
                public int exercise() { fixture.Child child = new fixture.Child(); child.value = 42; return child.api(); }
            }""",
        }, app)
        evidence, _ = self.generate(app=[app], test=[tests])
        report = json.loads((evidence / "report.json").read_text())
        self.assertIn("fixture.Parent", report["boundary"]["classes"])
        self.assertIn("int fixture.Parent.value", report["boundary"]["fields"])
        self.assertIn("int fixture.Parent.api()", report["boundary"]["methods"])


def file_record(path):
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    return {"file": path.name, "sizeBytes": path.stat().st_size, "sha256": digest}


def retain_kotlin_fixture_inputs(application_input, destination):
    """Retain exact class subsets and bind them to the actual app dependency bytecode."""
    result = {"applicationInput": file_record(application_input)}
    with zipfile.ZipFile(application_input) as source:
        for role, prefix, required in (
            ("kotlin", "kotlin/", "kotlin/coroutines/ContinuationInterceptor$DefaultImpls.class"),
            ("annotations", "org/jetbrains/annotations/", "org/jetbrains/annotations/NotNull.class"),
        ):
            names = sorted(name for name in source.namelist() if name.startswith(prefix) and name.endswith(".class"))
            if required not in names or len(names) != len(set(names)):
                raise ValueError(f"Missing or duplicate actual app dependency class for {role}")
            path = destination / f"{role}-input.jar"
            with zipfile.ZipFile(path, "w") as retained:
                for name in names:
                    item = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                    retained.writestr(item, source.read(name))
            result[role] = {**file_record(path), "classes": len(names)}
    if result["applicationInput"] != file_record(application_input):
        raise ValueError("Application input changed while fixture dependencies were retained")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--r8-tool", type=Path, required=True)
    parser.add_argument("--generator-classes", type=Path, required=True)
    parser.add_argument("--application-input", type=Path, required=True)
    parser.add_argument("--report-directory", type=Path, required=True)
    args = parser.parse_args()
    args.report_directory.mkdir(parents=True, exist_ok=True)
    report = args.report_directory / "report.json"
    report.unlink(missing_ok=True)
    inputs = retain_kotlin_fixture_inputs(args.application_input, args.report_directory)
    TestAbiRulesIntegrationTest.java_home = args.java_home
    TestAbiRulesIntegrationTest.r8_tool = args.r8_tool
    TestAbiRulesIntegrationTest.generator_classpath = os.pathsep.join(map(str, [args.generator_classes, args.r8_tool]))
    TestAbiRulesIntegrationTest.kotlin_input = args.report_directory / "kotlin-input.jar"
    TestAbiRulesIntegrationTest.annotations_input = args.report_directory / "annotations-input.jar"
    result = unittest.main(argv=[__file__], verbosity=2, exit=False).result
    value = {"schemaVersion": 1, "scope": "benchmark-test-abi-fixtures", "passed": result.wasSuccessful(),
             "tests": result.testsRun, "failures": len(result.failures), "errors": len(result.errors),
             "skipped": len(result.skipped), "r8Tool": file_record(args.r8_tool), "inputs": inputs,
             "publisherQualified": False}
    report.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")
    print(json.dumps(value, sort_keys=True))
    sys.exit(0 if result.wasSuccessful() else 1)
