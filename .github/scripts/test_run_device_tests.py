"""The harness must preserve failures, never manufacture page-size coverage."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('run-device-tests.sh').resolve()


class DeviceHarnessTest(unittest.TestCase):
    def run_harness(self, expected, gradle_status=0):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            commands = root / 'commands'
            commands.mkdir()
            # No getconf is provided. The API 26 image does not have one either.
            stubs = {
                'adb': '''#!/bin/sh
printf '%s\\n' "$*" >> "$CALLS"
case "$*" in
  *getconf*) exit 127 ;;
  'shell am start '*) echo 'Status: ok' ;;
  'shell pidof '*) echo 42 ;;
  'pull /sdcard/artflow-window.xml '*) printf 'package="com.artflow.studio"' > "$3" ;;
esac
exit 0
''',
                'sleep': '#!/bin/sh\nexit 0\n',
            }
            for name, content in stubs.items():
                path = commands / name
                path.write_text(content)
                path.chmod(0o755)
            gradle = root / 'gradlew'
            gradle.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$CALLS"\nexit "$GRADLE_STATUS"\n')
            gradle.chmod(0o755)
            log = root / 'calls.txt'
            env = dict(os.environ, PATH=f'{commands}:{os.environ["PATH"]}',
                       RUNNER_TEMP=str(root), CALLS=str(log), GRADLE_STATUS=str(gradle_status))
            env.pop('EXPECTED_PAGE_SIZE', None)
            if expected is not None:
                env['EXPECTED_PAGE_SIZE'] = expected
            result = subprocess.run(['bash', str(SCRIPT)], cwd=root, env=env,
                                    capture_output=True, text=True, timeout=10)
            return result, log.read_text() if log.exists() else ''

    def test_api26_needs_no_getconf(self):
        result, calls = self.run_harness('4096')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('-Pandroid.testInstrumentationRunnerArguments.expectedPageSize=4096', calls)
        self.assertNotIn('getconf', calls)
        self.assertIn('assembleBenchmark', calls)

    def test_16k_expectation_is_sent_to_real_instrumentation(self):
        result, calls = self.run_harness('16384')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('expectedPageSize=16384', calls)

    def test_missing_or_invalid_expectation_fails_closed(self):
        for expected in (None, '', 'unknown', '4096;exit 0', '0'):
            with self.subTest(expected=expected):
                result, calls = self.run_harness(expected)
                self.assertEqual(2, result.returncode)
                self.assertNotIn('connectedDebugAndroidTest', calls)
                self.assertIn('logcat', calls)

    def test_failed_device_tests_cannot_be_replaced_by_a_successful_launch(self):
        result, calls = self.run_harness('4096', gradle_status=37)
        self.assertEqual(37, result.returncode)
        self.assertNotIn('assembleBenchmark', calls)
        self.assertIn('logcat', calls)


if __name__ == '__main__':
    unittest.main()
