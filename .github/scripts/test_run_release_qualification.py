"""The optimized harness must preserve its one attempt, including timeout/failure status."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("run-release-qualification.sh").resolve()


class ReleaseQualificationHarnessTest(unittest.TestCase):
    def run_harness(self, status=0, api=36, previous_attempt=False, stale_results=False, stale_evidence=False):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            scripts = project / ".github/scripts"
            scripts.mkdir(parents=True)
            shutil.copyfile(SCRIPT, scripts / SCRIPT.name)
            (scripts / "release_qualification.py").write_text(
                'import json, sys\nfrom pathlib import Path\n'
                'output = Path(sys.argv[sys.argv.index("--output") + 1])\n'
                'output.write_text(json.dumps(["example.Test#run"]))\n'
                'print("example.Test")\n')
            commands = project / "commands"
            commands.mkdir()
            adb = commands / "adb"
            adb.write_text(f"#!{sys.executable}\n" +
                           'import os, sys\nfrom pathlib import Path\n'
                           'with Path("calls.txt").open("a") as log: log.write("adb " + " ".join(sys.argv[1:]) + "\\n")\n'
                           'if sys.argv[1:] == ["shell", "getprop", "ro.build.version.sdk"]: print(os.environ["API"])\n'
                           'else: raise SystemExit(14)\n')
            adb.chmod(0o755)
            gradle = project / "gradlew"
            gradle.write_text(f"#!{sys.executable}\n" +
                              'import os, sys\nfrom pathlib import Path\n'
                              'with Path("calls.txt").open("a") as log: log.write("gradle " + " ".join(sys.argv[1:]) + "\\n")\n'
                              'print("Original instrumentation output")\n'
                              'raise SystemExit(int(os.environ["STATUS"]))\n')
            gradle.chmod(0o755)
            output = project / "artflow-release-qualification"
            if previous_attempt:
                output.mkdir()
                (output / "attempt-token.txt").write_text("previous")
                (output / "first-attempt-logcat.txt").write_text("Original failed-attempt logcat\n")
                (output / "device-properties.txt").write_text("Original failed-attempt device\n")
            if stale_results:
                path = project / "app/build/outputs/androidTest-results/connected/benchmark/TEST-stale.xml"
                path.parent.mkdir(parents=True)
                path.write_text("<testsuite/>")
            if stale_evidence:
                path = project / "app/build/outputs/connected_android_test_additional_output/benchmarkAndroidTest/stale.txt"
                path.parent.mkdir(parents=True)
                path.write_text("old evidence")
            env = dict(os.environ, PATH=f"{commands}:{os.environ['PATH']}", RUNNER_TEMP=str(project),
                       STATUS=str(status), API=str(api))
            result = subprocess.run(["bash", str(scripts / SCRIPT.name)], cwd=project, env=env,
                                    capture_output=True, text=True, timeout=10)
            calls = (project / "calls.txt").read_text() if (project / "calls.txt").exists() else ""
            files = {path.name: path.read_text() for path in output.glob("*") if path.is_file()}
            return result, calls, files

    def test_targets_benchmark_and_passes_required_runtime_arguments_once(self):
        result, calls, files = self.run_harness()
        self.assertEqual(0, result.returncode, result.stderr)
        invocations = [line for line in calls.splitlines() if line.startswith("gradle ")]
        self.assertEqual(1, len(invocations))
        self.assertIn("connectedBenchmarkAndroidTest -PartflowTestBuildType=benchmark", invocations[0])
        self.assertIn("-Pandroid.testInstrumentationRunnerArguments.class=example.Test", invocations[0])
        self.assertIn("-Pandroid.testInstrumentationRunnerArguments.expectedBuildType=benchmark", invocations[0])
        self.assertIn("-Pandroid.testInstrumentationRunnerArguments.expectedPageSize=4096", invocations[0])
        self.assertIn(files["attempt-token.txt"].strip(), invocations[0])
        self.assertEqual("0\n", files["instrumentation-exit-code.txt"])
        self.assertEqual(["example.Test#run"], json.loads(files["test-inventory.json"]))

    def test_failure_is_preserved_without_retry_or_diagnostic_status_replacement(self):
        result, calls, files = self.run_harness(status=7)
        self.assertEqual(7, result.returncode)
        self.assertEqual(1, len([line for line in calls.splitlines() if line.startswith("gradle ")]))
        self.assertEqual("7\n", files["instrumentation-exit-code.txt"])
        self.assertIn("Original instrumentation output", files["first-attempt-instrumentation.log"])
        self.assertIn("adb logcat -b all -d", calls)

    def test_timeout_status_is_preserved(self):
        result, _, files = self.run_harness(status=124)
        self.assertEqual(124, result.returncode)
        self.assertEqual("124\n", files["instrumentation-exit-code.txt"])

    def test_wrong_api_stops_before_instrumentation(self):
        result, calls, _ = self.run_harness(api=35)
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("gradle ", calls)

    def test_second_attempt_is_refused(self):
        result, calls, files = self.run_harness(previous_attempt=True)
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("gradle ", calls)
        self.assertEqual("previous", files["attempt-token.txt"])

    def test_refused_second_attempt_preserves_original_diagnostics(self):
        result, calls, files = self.run_harness(previous_attempt=True)
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("adb ", calls)
        self.assertEqual("Original failed-attempt logcat\n", files["first-attempt-logcat.txt"])
        self.assertEqual("Original failed-attempt device\n", files["device-properties.txt"])

    def test_stale_reports_are_refused(self):
        result, calls, _ = self.run_harness(stale_results=True)
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("gradle ", calls)

    def test_stale_evidence_is_refused_without_touching_the_device(self):
        result, calls, files = self.run_harness(stale_evidence=True)
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("gradle ", calls)
        self.assertNotIn("adb ", calls)
        self.assertNotIn("attempt-token.txt", files)


if __name__ == "__main__":
    unittest.main()
