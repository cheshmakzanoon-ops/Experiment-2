"""The harness must preserve failures, never manufacture page-size coverage."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('run-device-tests.sh').resolve()
NATIVE_TEXT = (b'Timestamp: 2026-10-25 00:00:01.123456789+0000\nCmdline: com.artflow.studio\n'
               b'pid: 1234, tid: 1234, name: app  >>> com.artflow.studio <<<\n'
               b'backtrace:\n    #00 pc 00000001 libart.so\nmemory map:\n    exact original bytes\n')


class DeviceHarnessTest(unittest.TestCase):
    def run_harness(self, expected, gradle_status=0, runtime_mode="default", retry_status=None,
                    collector_status=None, native_status=None, readiness_failure=None,
                    recovery_readiness_failure=None, kernel_statuses=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            scripts = root / '.github/scripts'
            scripts.mkdir(parents=True)
            for name in ('run-device-tests.sh', 'configure-emulator-runtime.sh'):
                shutil.copyfile(SCRIPT.with_name(name), scripts / name)
            shutil.copyfile(SCRIPT.with_name('collect_installed_apks.py'), scripts / 'real_collect_installed_apks.py')
            (scripts / 'collect_installed_apks.py').write_text(
                'import os\nfrom pathlib import Path\nimport runpy\nimport sys\n'
                'with Path(os.environ["CALLS"]).open("a") as log: log.write("capture " + " ".join(sys.argv[1:]) + "\\n")\n'
                'if os.environ["CAPTURE_STATUS"] != "None": raise SystemExit(int(os.environ["CAPTURE_STATUS"]))\n'
                'runpy.run_path(str(Path(__file__).with_name("real_collect_installed_apks.py")), run_name="__main__")\n')
            shutil.copyfile(SCRIPT.with_name('collect_native_tombstones.py'), scripts / 'real_collect_native_tombstones.py')
            (scripts / 'collect_native_tombstones.py').write_text(
                'import os\nfrom pathlib import Path\nimport runpy\nimport sys\n'
                'with Path(os.environ["CALLS"]).open("a") as log: log.write("tombstones " + " ".join(sys.argv[1:]) + "\\n")\n'
                'if os.environ["NATIVE_STATUS"] != "None": raise SystemExit(int(os.environ["NATIVE_STATUS"]))\n'
                'runpy.run_path(str(Path(__file__).with_name("real_collect_native_tombstones.py")), run_name="__main__")\n')
            if retry_status is not None:
                # Classifier and runtime correctness have their own independent suites.
                # This fixture isolates ordering and status propagation around an allowed recovery.
                (scripts / 'configure-emulator-runtime.sh').write_text('#!/bin/sh\nexit 0\n')
                (scripts / 'classify_android15_reference_queue_crash.py').write_text(
                    'import os\nfrom pathlib import Path\n'
                    'with Path(os.environ["CALLS"]).open("a") as log: log.write("classifier\\n")\n')
                (scripts / 'recover-android15-emulator.sh').write_text(
                    '#!/bin/sh\nprintf "recover\\n" >> "$CALLS"\ntouch "$RUNNER_TEMP/recovered"\n')
            (scripts / 'verify_emulator_kernel.py').write_text(
                'import json, os, sys\nfrom pathlib import Path\n'
                'with Path(os.environ["CALLS"]).open("a") as log: log.write("kernel " + " ".join(sys.argv[1:]) + "\\n")\n'
                'output = Path(sys.argv[sys.argv.index("--output") + 1])\n'
                'statuses = json.loads(os.environ["KERNEL_STATUSES"])\n'
                'status = statuses[min(int(output.name.split("-")[-1]) - 1, len(statuses) - 1)]\n'
                'output.mkdir(parents=True)\n'
                '(output / "verification.json").write_text(json.dumps({"status": status}))\n'
                'raise SystemExit(status)\n')
            commands = root / 'commands'
            commands.mkdir()
            (root / 'fixture-tombstone').write_bytes(NATIVE_TEXT)
            # No getconf is provided. The API 26 image does not have one either.
            stubs = {
                'adb': f'#!{sys.executable}\n' + '''import hashlib
import os
from pathlib import Path
import re
import sys
args = sys.argv[1:]
with Path(os.environ["CALLS"]).open("a") as log:
    log.write(" ".join(args) + "\\n")
data = Path("fixture-tombstone").read_bytes()
recovered = (Path(os.environ["RUNNER_TEMP"]) / "recovered").exists()
readiness_failure = os.environ["RECOVERY_READINESS_FAILURE" if recovered else "READINESS_FAILURE"]
if any("getconf" in arg for arg in args):
    raise SystemExit(127)
if args[:3] == ["shell", "am", "start"]:
    print("Status: ok")
elif args[:3] == ["shell", "am", "wait-for-broadcast-idle"]:
    print("All broadcast queues are idle!")
elif args[:2] == ["shell", "service"] and len(args) >= 4 and args[2] == "check":
    print(f"Service {args[3]}: " + ("not found" if readiness_failure == args[3] else "found"))
elif args == ["shell", "cmd", "package", "list", "packages", "android"]:
    if readiness_failure == "packages": raise SystemExit(1)
    print("package:android")
elif args == ["shell", "getprop", "sys.boot_completed"]:
    count = Path(os.environ["RUNNER_TEMP"]) / "boot-check-count"
    checked = int(count.read_text()) if count.exists() else 0
    count.write_text(str(checked + 1))
    print("0" if readiness_failure == "boot" or (readiness_failure == "boot-twice" and checked < 2) else "1")
elif args[:2] == ["shell", "pidof"]:
    print(42)
elif args[:2] == ["pull", "/sdcard/artflow-window.xml"]:
    Path(args[2]).write_text('package="com.artflow.studio"')
elif args == ["shell", "id", "-u"]:
    print(0)
elif args == ["shell", "cat", "/proc/sys/kernel/random/boot_id"]:
    print("fixture-boot")
elif args[:3] == ["shell", "date", "-u"]:
    print("2026-10-25T00:00:10Z")
elif args == ["shell", "ls", "-1", "/data/tombstones"]:
    print("tombstone_00\\ntombstone_00.pb")
elif args[:2] == ["shell", "stat"]:
    print(str(len(data)) + ":1234")
elif args[:3] == ["exec-out", "head", "-c"]:
    sys.stdout.buffer.write(data[:int(args[3])])
elif args[0] == "shell" and len(args) == 2 and "sha256sum" in args[1]:
    size = int(re.search(r"head -c ([0-9]+)", args[1])[1])
    print(hashlib.sha256(data[:size]).hexdigest() + "  -")
''',
                'sleep': '#!/bin/sh\nexit 0\n',
            }
            for name, content in stubs.items():
                path = commands / name
                path.write_text(content)
                path.chmod(0o755)
            gradle = root / 'gradlew'
            gradle.write_text(f'#!{sys.executable}\n' + '''import hashlib
import io
import json
import os
from pathlib import Path
import sys
import uuid
import zipfile
with Path(os.environ["CALLS"]).open("a") as log:
    log.write(" ".join(sys.argv[1:]) + "\\n")
if sys.argv[1] == "connectedDebugAndroidTest":
    prefix = "-Pandroid.testInstrumentationRunnerArguments.artflowApkEvidenceToken="
    token = next(arg[len(prefix):] for arg in sys.argv[2:] if arg.startswith(prefix))
    assert str(uuid.UUID(token)) == token
    snapshot = (Path("app/build/outputs/connected_android_test_additional_output/debugAndroidTest/connected") /
                "fixture-device/test-evidence/apk-provenance" / token)
    snapshot.mkdir(parents=True)
    source = {"schemaVersion": 1, "attemptToken": token, "capturedAtUtc": "2026-10-25T00:00:00Z",
              "pid": 1234, "packages": {}, "errors": []}
    for package, built in (("com.artflow.studio", "debug"), ("com.artflow.studio.test", "androidTest/debug")):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"fixture")
            archive.writestr("classes.dex", package.encode())
        data = buffer.getvalue()
        relative = f"installed/{package}/00.apk"
        target = snapshot / relative
        target.parent.mkdir(parents=True)
        target.write_bytes(data)
        built_apk = Path("app/build/outputs/apk") / built / "app.apk"
        built_apk.parent.mkdir(parents=True, exist_ok=True)
        built_apk.write_bytes(data)
        source["packages"][package] = {"packageName": package, "errors": [], "apks": [{
            "devicePath": f"/data/app/{package}/{token}/base.apk", "file": relative, "sizeBytes": len(data),
            "sha256": hashlib.sha256(data).hexdigest(), "captureComplete": True, "errors": []}]}
    (snapshot / "manifest.json").write_text(json.dumps(source))
    with Path(os.environ["CALLS"]).open("a") as log:
        log.write("utp-uninstall-main-and-test\\n")
    count = Path(os.environ["RUNNER_TEMP"]) / "instrumentation-count"
    attempt = int(count.read_text()) if count.exists() else 0
    count.write_text(str(attempt + 1))
    raise SystemExit(int(os.environ["GRADLE_STATUS"] if attempt == 0 else os.environ["RETRY_STATUS"]))
''')
            gradle.chmod(0o755)
            log = root / 'calls.txt'
            env = dict(os.environ, PATH=f'{commands}:{os.environ["PATH"]}',
                       RUNNER_TEMP=str(root), CALLS=str(log), GRADLE_STATUS=str(gradle_status),
                       RETRY_STATUS=str(retry_status if retry_status is not None else gradle_status),
                       ART_JIT_MODE=runtime_mode, CAPTURE_STATUS=str(collector_status),
                       NATIVE_STATUS=str(native_status), READINESS_FAILURE=str(readiness_failure),
                       RECOVERY_READINESS_FAILURE=str(recovery_readiness_failure),
                       KERNEL_STATUSES=json.dumps(kernel_statuses))
            env.pop('EXPECTED_PAGE_SIZE', None)
            env.pop('ARTFLOW_KERNEL_MANIFEST', None)
            if kernel_statuses is not None:
                manifest = root / 'kernel-manifest.json'
                manifest.write_text('{}')
                env['ARTFLOW_KERNEL_MANIFEST'] = str(manifest)
            if expected is not None:
                env['EXPECTED_PAGE_SIZE'] = expected
            result = subprocess.run(['bash', str(scripts / SCRIPT.name)], cwd=root, env=env,
                                    capture_output=True, text=True, timeout=10)
            manifests = {path.parent.name: json.loads(path.read_text()) for path in
                         (root / 'artflow-device/apk-provenance').glob('*/manifest.json')}
            self.native_manifests = {path.parent.name: json.loads(path.read_text()) for path in
                                     (root / 'artflow-device/native-tombstones').glob('*/manifest.json')}
            self.native_payloads = {str(path.relative_to(root / 'artflow-device/native-tombstones')): path.read_bytes()
                                    for path in (root / 'artflow-device/native-tombstones').glob('*/files/*')}
            self.kernel_records = {path.parent.name: json.loads(path.read_text()) for path in
                                   (root / 'artflow-device/kernel-runtime').glob('*/verification.json')}
            return result, log.read_text() if log.exists() else '', manifests

    def test_failed_runtime_configuration_prevents_instrumentation(self):
        result, calls, _ = self.run_harness('16384', runtime_mode='invalid')
        self.assertEqual(2, result.returncode)
        self.assertNotIn('connectedDebugAndroidTest', calls)
        self.assertIn('logcat', calls)

    def test_api26_needs_no_getconf(self):
        result, calls, _ = self.run_harness('4096')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('-Pandroid.testInstrumentationRunnerArguments.expectedPageSize=4096', calls)
        self.assertNotIn('getconf', calls)
        self.assertIn('assembleBenchmark', calls)
        self.assertNotIn('kernel --manifest', calls)

    def test_unready_services_fail_closed_before_instrumentation(self):
        for failed_service in ('boot', 'packages', 'activity', 'settings'):
            with self.subTest(failed_service=failed_service):
                result, calls, manifests = self.run_harness('16384', readiness_failure=failed_service)
                self.assertEqual(1, result.returncode, result.stderr)
                self.assertIn('did not become ready', result.stderr)
                self.assertEqual(45, calls.count('shell getprop sys.boot_completed\n'))
                self.assertNotIn('connectedDebugAndroidTest', calls)
                self.assertNotIn('assembleBenchmark', calls)
                self.assertNotIn('shell am wait-for-broadcast-idle', calls)
                self.assertEqual({}, manifests)
                self.assertIn('logcat', calls)

    def test_transient_boot_readiness_waits_then_runs_the_suite(self):
        result, calls, _ = self.run_harness('16384', readiness_failure='boot-twice')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(3, calls.count('shell getprop sys.boot_completed\n'))
        self.assertIn('connectedDebugAndroidTest', calls)

    def test_readiness_command_cannot_report_success_after_the_deadline(self):
        # Advance Bash's clock inside a foreground command, without waiting in real time.
        # All readiness probes succeed, but their combined completion is too late.
        function = 'wait_for_system_settle() {' + SCRIPT.read_text().split(
            'wait_for_system_settle() {', 1)[1].split('\n}\n', 1)[0] + '\n}\n'
        probe = function + '''
timeout() {
  if [[ "$*" == '10 adb shell getprop sys.boot_completed' ]]; then
    echo 1
  elif [[ "$*" == '15 adb shell cmd package list packages android' ]]; then
    SECONDS=1000
  else
    echo 'Service: found'
  fi
}
sleep() { :; }
SECONDS=0
wait_for_system_settle
'''
        result = subprocess.run(['bash', '-c', probe], capture_output=True, text=True, timeout=3)
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn('did not become ready', result.stderr)

    def test_unready_recovery_cannot_start_a_second_instrumentation(self):
        result, calls, manifests = self.run_harness('16384', gradle_status=37, runtime_mode='disabled',
                                                  retry_status=0, recovery_readiness_failure='settings')
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertEqual(1, calls.count('connectedDebugAndroidTest'))
        self.assertEqual(1, calls.count('recover\n'))
        self.assertEqual({'attempt-1'}, set(manifests))
        self.assertEqual(37, manifests['attempt-1']['instrumentationStatus'])
        self.assertNotIn('assembleBenchmark', calls)

    def test_kernel_preflight_failure_stops_before_instrumentation_and_classifier(self):
        result, calls, manifests = self.run_harness('16384', gradle_status=37, runtime_mode='disabled',
                                                  retry_status=0, kernel_statuses=(31,))
        self.assertEqual(31, result.returncode, result.stderr)
        self.assertNotIn('connectedDebugAndroidTest', calls)
        self.assertNotIn('classifier', calls)
        self.assertNotIn('recover\n', calls)
        self.assertNotIn('assembleBenchmark', calls)
        self.assertEqual({}, manifests)
        self.assertEqual({'attempt-1': {'status': 31}}, self.kernel_records)
        self.assertIn('logcat', calls)

    def test_kernel_is_verified_after_readiness_for_each_invocation(self):
        result, calls, manifests = self.run_harness('16384', gradle_status=37, runtime_mode='disabled',
                                                  retry_status=0, kernel_statuses=(0, 0))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, calls.count('kernel --manifest'))
        self.assertLess(calls.index('shell am wait-for-broadcast-idle'), calls.index('kernel --manifest'))
        self.assertLess(calls.index('kernel --manifest'), calls.index('connectedDebugAndroidTest'))
        self.assertLess(calls.index('recover\n'), calls.rindex('shell am wait-for-broadcast-idle'))
        self.assertLess(calls.rindex('shell am wait-for-broadcast-idle'), calls.rindex('kernel --manifest'))
        self.assertLess(calls.rindex('kernel --manifest'), calls.rindex('connectedDebugAndroidTest'))
        self.assertEqual({'attempt-1', 'attempt-2'}, set(self.kernel_records))
        self.assertEqual({'attempt-1', 'attempt-2'}, set(manifests))

    def test_failed_kernel_recheck_preserves_first_failure_and_prevents_retry(self):
        result, calls, manifests = self.run_harness('16384', gradle_status=37, runtime_mode='disabled',
                                                  retry_status=0, kernel_statuses=(0, 41))
        self.assertEqual(41, result.returncode, result.stderr)
        self.assertEqual(1, calls.count('connectedDebugAndroidTest'))
        self.assertEqual(1, calls.count('recover\n'))
        self.assertEqual(1, calls.count('classifier\n'))
        self.assertEqual({'attempt-1'}, set(manifests))
        self.assertEqual(37, manifests['attempt-1']['instrumentationStatus'])
        self.assertEqual({'attempt-1': {'status': 0}, 'attempt-2': {'status': 41}}, self.kernel_records)
        self.assertNotIn('assembleBenchmark', calls)

    def test_instrumentation_evidence_is_captured_before_replacing_the_app(self):
        result, calls, manifests = self.run_harness('4096')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertLess(calls.index('logcat -b all -d'), calls.index('assembleBenchmark'))
        self.assertLess(calls.index('pull /sdcard/Android/data/'), calls.index('install -r'))
        self.assertLess(calls.index('utp-uninstall-main-and-test'), calls.index('capture --output'))
        self.assertLess(calls.index('capture --output'), calls.index('assembleBenchmark'))
        self.assertNotIn('shell pm path', calls)
        self.assertEqual(0, manifests['attempt-1']['instrumentationStatus'])
        self.assertTrue(all(record['installedApks'][0]['captureComplete']
                            for record in manifests['attempt-1']['packages'].values()))

    def test_16k_expectation_is_sent_to_real_instrumentation(self):
        result, calls, _ = self.run_harness('16384')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('expectedPageSize=16384', calls)

    def test_missing_or_invalid_expectation_fails_closed(self):
        for expected in (None, '', 'unknown', '4096;exit 0', '0'):
            with self.subTest(expected=expected):
                result, calls, _ = self.run_harness(expected)
                self.assertEqual(2, result.returncode)
                self.assertNotIn('connectedDebugAndroidTest', calls)
                self.assertIn('logcat', calls)

    def test_failed_device_tests_cannot_be_replaced_by_a_successful_launch(self):
        result, calls, manifests = self.run_harness('4096', gradle_status=37)
        self.assertEqual(37, result.returncode)
        self.assertNotIn('assembleBenchmark', calls)
        self.assertIn('logcat', calls)
        self.assertEqual(37, manifests['attempt-1']['instrumentationStatus'])
        self.assertTrue(manifests['attempt-1']['retainInstalledApks'])

    def test_capture_failure_preserves_original_instrumentation_status(self):
        for status in (0, 37):
            with self.subTest(instrumentation_status=status):
                result, calls, _ = self.run_harness('4096', gradle_status=status, collector_status=19)
                self.assertEqual(status, result.returncode, result.stderr)
                self.assertIn('APK provenance capture failed', result.stderr)
                self.assertEqual(status == 0, 'assembleBenchmark' in calls)

    def test_first_attempt_is_captured_before_classification_and_recovery(self):
        result, calls, manifests = self.run_harness('16384', gradle_status=37,
                                                    runtime_mode='disabled', retry_status=0)
        self.assertEqual(0, result.returncode, result.stderr)
        capture = 'capture --output'
        self.assertLess(calls.index('shell am wait-for-broadcast-idle'), calls.index('connectedDebugAndroidTest'))
        self.assertLess(calls.index(capture), calls.index('classifier'))
        self.assertLess(calls.index('classifier'), calls.index('recover'))
        self.assertLess(calls.index('recover'), calls.rindex('shell am wait-for-broadcast-idle'))
        self.assertLess(calls.rindex('shell am wait-for-broadcast-idle'), calls.rindex('connectedDebugAndroidTest'))
        self.assertLess(calls.rindex('connectedDebugAndroidTest'), calls.rindex(capture))
        self.assertLess(calls.rindex(capture), calls.index('assembleBenchmark'))
        self.assertEqual({'attempt-1', 'attempt-2'}, set(manifests))
        self.assertEqual(37, manifests['attempt-1']['instrumentationStatus'])
        self.assertEqual(0, manifests['attempt-2']['instrumentationStatus'])
        self.assertNotEqual(manifests['attempt-1']['attemptToken'], manifests['attempt-2']['attemptToken'])
        for manifest in manifests.values():
            self.assertIn('artflowApkEvidenceToken=' + manifest['attemptToken'], calls)
            self.assertTrue(manifest['runnerCaptures'][0]['accepted'])

    def test_failed_retry_is_captured_and_preserves_its_exit_status(self):
        result, calls, manifests = self.run_harness('16384', gradle_status=37,
                                                    runtime_mode='disabled', retry_status=23)
        self.assertEqual(23, result.returncode)
        self.assertNotIn('assembleBenchmark', calls)
        self.assertEqual(37, manifests['attempt-1']['instrumentationStatus'])
        self.assertEqual(23, manifests['attempt-2']['instrumentationStatus'])
        self.assertEqual(1, calls.count('recover\n'))

    def test_full_tombstone_is_retained_before_recovery_and_success_never_overwrites_it(self):
        result, calls, manifests = self.run_harness('16384', gradle_status=37,
                                                    runtime_mode='disabled', retry_status=0)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertLess(calls.index('capture --output'), calls.index('tombstones --output'))
        self.assertLess(calls.index('tombstones --output'), calls.index('classifier'))
        self.assertLess(calls.index('classifier'), calls.index('recover'))
        self.assertEqual(1, calls.count('tombstones --output'))
        self.assertEqual({'attempt-1'}, set(self.native_manifests))
        native = self.native_manifests['attempt-1']
        self.assertEqual(manifests['attempt-1']['attemptToken'], native['attemptToken'])
        self.assertEqual(37, native['instrumentationStatus'])
        self.assertEqual('captured', native['captureStatus'])
        self.assertTrue(native['files'][0]['transferIntegrityVerified'])
        self.assertEqual({'attempt-1/files/tombstone_00': NATIVE_TEXT}, self.native_payloads)
        self.assertNotIn('root\n', calls)

    def test_second_failed_attempt_retains_its_own_tombstone_without_extra_recovery(self):
        result, calls, manifests = self.run_harness('16384', gradle_status=37,
                                                    runtime_mode='disabled', retry_status=23)
        self.assertEqual(23, result.returncode, result.stderr)
        self.assertEqual(2, calls.count('tombstones --output'))
        self.assertEqual(1, calls.count('recover\n'))
        self.assertNotIn('assembleBenchmark', calls)
        self.assertEqual({'attempt-1', 'attempt-2'}, set(self.native_manifests))
        for attempt, native in self.native_manifests.items():
            self.assertEqual(manifests[attempt]['attemptToken'], native['attemptToken'])
            self.assertEqual(manifests[attempt]['instrumentationStatus'], native['instrumentationStatus'])
            self.assertEqual('captured', native['captureStatus'])
            self.assertEqual(NATIVE_TEXT, self.native_payloads[attempt + '/files/tombstone_00'])

    def test_native_diagnostic_failure_preserves_failure_and_success_skips_capture(self):
        result, calls, _ = self.run_harness('4096', gradle_status=37, native_status=19)
        self.assertEqual(37, result.returncode)
        self.assertIn('Native tombstone capture failed', result.stderr)
        self.assertNotIn('assembleBenchmark', calls)
        result, calls, _ = self.run_harness('4096', native_status=19)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn('tombstones --output', calls)
        self.assertNotIn('/data/tombstones', calls)
        self.assertEqual({}, self.native_manifests)


if __name__ == '__main__':
    unittest.main()
