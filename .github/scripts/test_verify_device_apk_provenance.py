import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

import collect_installed_apks as collector
from verify_device_apk_provenance import verify_device_run


class DeviceApkAcceptanceTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.project = self.root / "project"
        self.project.mkdir()
        self.run = self.root / "run"
        self.run.mkdir()

    def attempt(self, number=1, status=0, mismatch=False):
        token = f"00000000-0000-4000-8000-{number:012d}"
        namespace = (self.project / collector.EVIDENCE_ROOT / "debugAndroidTest/connected/device" /
                     "test-evidence/apk-provenance" / token)
        namespace.mkdir(parents=True)
        source = {"schemaVersion": 1, "attemptToken": token, "capturedAtUtc": "2026-11-19T12:00:00Z",
                  "pid": 1234, "errors": [], "packages": {}}
        for package, pattern in collector.PACKAGES.items():
            output = io.BytesIO()
            with zipfile.ZipFile(output, "w") as archive:
                archive.writestr("AndroidManifest.xml", b"fixture")
                archive.writestr("classes.dex", package.encode())
            data = output.getvalue()
            built = self.project / pattern.replace("*.apk", "app.apk")
            built.parent.mkdir(parents=True, exist_ok=True)
            built.write_bytes(data + (b"different" if mismatch else b""))
            relative = f"installed/{package}/00.apk"
            snapshot = namespace / relative
            snapshot.parent.mkdir(parents=True)
            snapshot.write_bytes(data)
            source["packages"][package] = {"packageName": package, "errors": [], "apks": [{
                "file": relative, "devicePath": f"/data/app/{package}/base.apk", "sizeBytes": len(data),
                "sha256": hashlib.sha256(data).hexdigest(), "captureComplete": True, "errors": []}]}
        (namespace / "manifest.json").write_text(json.dumps(source))
        captured = self.run / "apk-provenance" / f"attempt-{number}"
        collector.collect(captured, self.project, status, token)
        receipts = self.run / "instrumentation-attempts"
        receipts.mkdir(exist_ok=True)
        (receipts / f"attempt-{number}-token.txt").write_text(token + "\n")
        (receipts / f"attempt-{number}-exit-code.txt").write_text(str(status) + "\n")
        return captured

    def verify(self, api=36, page_size=4096):
        return verify_device_run(self.project, self.run, api, page_size)

    def change(self, path, mutation):
        value = json.loads(path.read_text())
        mutation(value)
        path.write_text(json.dumps(value))

    def recovery(self):
        self.attempt(status=37)
        self.attempt(number=2)
        recovery = self.run / "android15-framework-retry"
        results = recovery / "first-attempt-results"
        results.mkdir(parents=True)
        classname, method = "com.artflow.studio.RuntimeTest", "interrupted"
        failure = ("java.lang.NullPointerException: runtime failure\n"
                   "at java.lang.ref.ReferenceQueue.enqueuePending(ReferenceQueue.java:239)\n"
                   "at java.lang.Daemons$ReferenceQueueDaemon.runInternal(Daemons.java:260)\n")
        (results / "TEST-original.xml").write_text(
            f'<testsuite><testcase classname="{classname}" name="{method}"><failure>{failure}</failure>'
            '</testcase></testsuite>')
        log = f"11-19 12:00:00.000 101 102 I TestRunner: started: {method}({classname})\n"
        for message in ("FATAL EXCEPTION: ReferenceQueueDaemon\nProcess: com.artflow.studio, PID: 101\n" + failure).splitlines():
            log += "11-19 12:00:00.100 101 110 E AndroidRuntime: " + message + "\n"
        (results / f"logcat-{classname}-{method}.txt").write_text(log)
        (recovery / "first-attempt-logcat.txt").write_text(log)
        return results

    def test_original_runner_and_collector_match_actual_current_apk_bytes(self):
        self.attempt()
        report = self.verify()
        self.assertTrue(report["firstInvocationPassed"])
        self.assertFalse(report["frameworkRecovery"])
        self.assertEqual(1, len(report["attempts"]))
        for package, record in report["attempts"][0]["packages"].items():
            self.assertEqual(hashlib.sha256((self.project / record["path"]).read_bytes()).hexdigest(), record["sha256"])
            self.assertIn(package, collector.PACKAGES)

    def test_missing_collector_or_original_invocation_cannot_qualify(self):
        capture = self.attempt()
        for path in (capture / "manifest.json", self.run / "instrumentation-attempts/attempt-1-token.txt",
                     self.run / "instrumentation-attempts/attempt-1-exit-code.txt",
                     capture / "runner-captures/00/manifest.json"):
            with self.subTest(path=path.name):
                data = path.read_bytes()
                path.unlink()
                with self.assertRaises((OSError, ValueError)):
                    self.verify()
                path.write_bytes(data)

    def test_a_different_installed_apk_cannot_qualify_against_current_build(self):
        self.attempt(mismatch=True)
        with self.assertRaisesRegex(ValueError, "inconsistent installed APK|bytes differ"):
            self.verify()

    def test_current_build_mutation_cannot_reuse_previous_matching_hashes(self):
        self.attempt()
        path = next(self.project.glob(collector.PACKAGES["com.artflow.studio"]))
        path.write_bytes(path.read_bytes() + b"changed after capture")
        with self.assertRaisesRegex(ValueError, "Current built APK differs"):
            self.verify()

    def test_stale_tokens_and_original_runner_substitution_are_rejected(self):
        capture = self.attempt()
        for path in (capture / "manifest.json", capture / "runner-captures/00/manifest.json"):
            with self.subTest(path=path):
                data = path.read_bytes()
                self.change(path, lambda value: value.update(attemptToken="00000000-0000-4000-8000-000000000999"))
                with self.assertRaisesRegex(ValueError, "stale"):
                    self.verify()
                path.write_bytes(data)
        self.change(capture / "runner-captures/00/manifest.json",
                    lambda value: value["packages"]["com.artflow.studio"]["apks"][0].update(sha256="a" * 64))
        with self.assertRaisesRegex(ValueError, "bytes differ"):
            self.verify()

    def test_diagnostic_capture_errors_incomplete_records_and_links_cannot_pass(self):
        capture = self.attempt()
        path = capture / "manifest.json"
        data = path.read_bytes()
        mutations = (
            lambda value: value.update(errors=[{"error": "transport failed"}]),
            lambda value: value["packages"]["com.artflow.studio"].update(errors=[{"error": "copy failed"}]),
            lambda value: value["packages"]["com.artflow.studio"]["installedApks"][0].update(captureComplete=False),
            lambda value: value["packages"]["com.artflow.studio"]["installedApks"][0].update(sha256=float("nan")),
            lambda value: value["packages"].pop("com.artflow.studio.test"),
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                self.change(path, mutation)
                with self.assertRaises(ValueError):
                    self.verify()
                path.write_bytes(data)
        external = self.root / "substitute.json"
        path.rename(external)
        path.symlink_to(external)
        with self.assertRaisesRegex(ValueError, "linked"):
            self.verify()

    def test_failed_instrumentation_cannot_be_relabelled_as_success(self):
        capture = self.attempt(status=37)
        with self.assertRaisesRegex(ValueError, "invocation failed"):
            self.verify()
        self.change(capture / "manifest.json", lambda value: value.update(instrumentationStatus=0, retainInstalledApks=False))
        with self.assertRaisesRegex(ValueError, "stale collector"):
            self.verify()

    def test_extra_attempts_and_unpaired_receipts_cannot_hide_retries(self):
        self.attempt()
        extra = self.run / "apk-provenance/attempt-2"
        extra.mkdir()
        with self.assertRaisesRegex(ValueError, "Collector attempts"):
            self.verify()
        extra.rmdir()
        extra = self.run / "instrumentation-attempts/attempt-2-exit-code.txt"
        extra.write_text("0")
        with self.assertRaisesRegex(ValueError, "unexpected original invocation"):
            self.verify()

    def test_only_declared_recovery_preserves_original_failure_and_is_disclosed(self):
        original = self.recovery()
        report = self.verify(35, 16384)
        self.assertFalse(report["firstInvocationPassed"])
        self.assertTrue(report["frameworkRecovery"])
        self.assertEqual([37, 0], [attempt["instrumentationStatus"] for attempt in report["attempts"]])
        with self.assertRaisesRegex(ValueError, "authorize framework recovery"):
            self.verify(36, 16384)
        (original / "TEST-original.xml").write_text(
            '<testsuite><testcase classname="com.artflow.studio.RuntimeTest" name="interrupted">'
            '<failure>java.lang.AssertionError: pixels changed</failure></testcase></testsuite>')
        with self.assertRaisesRegex(ValueError, "authorize framework recovery"):
            self.verify(35, 16384)

    def test_recovery_without_original_evidence_is_not_a_clean_first_pass(self):
        self.attempt()
        (self.run / "android15-framework-retry").mkdir()
        with self.assertRaisesRegex(ValueError, "without its original two"):
            self.verify()

    def test_missing_or_changed_original_failed_apk_cannot_qualify_recovery(self):
        self.recovery()
        path = self.run / "apk-provenance/attempt-1/runner-captures/00/installed/com.artflow.studio/00.apk"
        data = path.read_bytes()
        path.unlink()
        with self.assertRaisesRegex(ValueError, "Missing or linked"):
            self.verify(35, 16384)
        path.write_bytes(data + b"changed original")
        with self.assertRaisesRegex(ValueError, "failed-attempt APK bytes changed"):
            self.verify(35, 16384)

    def test_required_workflow_summary_rejects_otherwise_green_missing_provenance(self):
        for name in ("source", "results", "evidence"):
            (self.root / name).mkdir()
        (self.root / "source/Example.kt").write_text(
            'package com.artflow.studio\nclass Example {\n@Test fun passes() {}\n}\n')
        (self.root / "results/TEST-one.xml").write_text(
            '<testsuite tests="1"><testcase classname="com.artflow.studio.Example" name="passes"/></testsuite>')
        (self.root / "evidence/runtime-environment.txt").write_text(
            'sdk=36\npageSize=4096\nabis=x86_64\nfingerprint=example\n')
        (self.root / "launch.txt").write_text('Minified release-equivalent launch: PASS\n')
        command = [sys.executable, str(Path(__file__).with_name("summarize_device_results.py")),
                   "--source", str(self.root / "source"), "--results", str(self.root / "results"),
                   "--evidence", str(self.root / "evidence"), "--launch", str(self.root / "launch.txt"),
                   "--api", "36", "--page-size", "4096", "--revision", "1" * 40,
                   "--device-run", str(self.run), "--project", str(self.project), "--output", str(self.run)]
        failed = subprocess.run(command, capture_output=True, text=True, timeout=10)
        self.assertEqual(1, failed.returncode, failed.stderr)
        report = json.loads((self.run / "verification.json").read_text())
        self.assertEqual({"passed": 1}, report["counts"])
        self.assertTrue(report["minifiedLaunch"])
        self.assertIsNone(report["apkProvenance"])
        self.assertIn("Installed APK provenance", report["problems"][0])
        self.attempt()
        passed = subprocess.run(command, capture_output=True, text=True, timeout=10)
        self.assertEqual(0, passed.returncode, passed.stderr)
        self.assertIn("first invocation; no framework recovery", passed.stdout)


if __name__ == "__main__":
    unittest.main()
