"""Exercise the real inventory task against an isolated, offline Maven fixture.

Set ARTFLOW_GRADLE_BINARY to a provisioned Gradle 8.14.3 executable to opt in. This
test never edits the application's build files or resolves external dependencies.
"""

import json
import os
import shutil
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path


GRADLE_BINARY = os.environ.get("ARTFLOW_GRADLE_BINARY")


@unittest.skipUnless(GRADLE_BINARY, "Set ARTFLOW_GRADLE_BINARY for the offline Gradle integration fixture")
class ReleaseDependencyInventoryGradleTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="artflow-dependency-inventory-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.app = self.root / "app"
        self.app.mkdir()
        (self.root / "gradle/wrapper").mkdir(parents=True)
        (self.root / "settings.gradle.kts").write_text('rootProject.name = "inventory-fixture"\ninclude(":app")\n')
        (self.root / "build.gradle.kts").write_text("// Offline fixture root\n")
        (self.root / "gradle.properties").write_text("org.gradle.workers.max=1\n")
        (self.root / "gradle/wrapper/gradle-wrapper.properties").write_text("// Executable supplied by the caller\n")
        source = Path(__file__).resolve().parents[2] / "gradle/release-dependencies.gradle.kts"
        shutil.copyfile(source, self.root / "gradle/release-dependencies.gradle.kts")
        repository = self.root / "repository/test/fixture/sample/1.0"
        repository.mkdir(parents=True)
        with zipfile.ZipFile(repository / "sample-1.0.jar", "w") as archive:
            archive.writestr("fixture.txt", "offline release input")
        (repository / "sample-1.0.pom").write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0">'
            '<modelVersion>4.0.0</modelVersion>'
            '<groupId>test.fixture</groupId><artifactId>sample</artifactId><version>1.0</version>'
            '<licenses><license><name>Apache-2.0</name></license></licenses></project>'
        )
        self.base_build = '''
repositories { maven { url = rootProject.uri("repository") } }
val releaseRuntimeClasspath = configurations.create("releaseRuntimeClasspath")
dependencies { add(releaseRuntimeClasspath.name, "test.fixture:sample:1.0") }
apply(from = rootProject.file("gradle/release-dependencies.gradle.kts"))
'''
        self.report = self.app / "build/reports/dependencies/release-runtime.json"

    def run_inventory(self, extra_build=""):
        (self.app / "build.gradle.kts").write_text(self.base_build + extra_build)
        result = subprocess.run(
            [GRADLE_BINARY, "--offline", "--no-daemon", "--console=plain", "--max-workers=1", ":app:writeReleaseDependencyInventory"],
            cwd=self.root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=180,
        )
        return result

    def assert_rejected_change(self, extra_build):
        # A failed run must not leave even a pre-existing success-looking report behind.
        self.report.parent.mkdir(parents=True)
        self.report.write_text('{"stale": true}\n')
        result = self.run_inventory(extra_build)
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("Dependency build inputs changed after configuration:", result.stdout)
        self.assertFalse(self.report.exists(), result.stdout)

    def test_stable_inputs_publish_the_actual_fixture_graph(self):
        result = self.run_inventory()
        self.assertEqual(result.returncode, 0, result.stdout)
        inventory = json.loads(self.report.read_text())
        self.assertEqual([item["id"] for item in inventory["components"]], ["test.fixture:sample:1.0"])
        self.assertEqual(inventory["components"][0]["artifacts"][0]["fileName"], "sample-1.0.jar")
        self.assertEqual(inventory["poms"][0]["status"], "resolved")

    def test_edit_between_configuration_and_task_execution_is_rejected(self):
        self.assert_rejected_change('''
tasks.named("writeReleaseDependencyInventory") {
    doFirst { rootProject.file("gradle.properties").appendText("# concurrent edit\\n") }
}
''')

    def test_edit_during_dependency_resolution_is_rejected(self):
        self.assert_rejected_change('''
releaseRuntimeClasspath.incoming.afterResolve {
    rootProject.file("gradle.properties").appendText("# edit during resolution\\n")
}
''')

    def test_added_optional_build_input_is_rejected(self):
        self.assert_rejected_change('''
tasks.named("writeReleaseDependencyInventory") {
    doFirst { rootProject.file("gradle/libs.versions.toml").writeText("[versions]\\n") }
}
''')

    def test_removed_build_input_is_rejected(self):
        self.assert_rejected_change('''
tasks.named("writeReleaseDependencyInventory") {
    doFirst { rootProject.file("gradle.properties").delete() }
}
''')


if __name__ == "__main__":
    unittest.main()
