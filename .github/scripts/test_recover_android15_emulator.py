import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("recover-android15-emulator.sh")
FAKE_ADB = r'''import json
import os
from pathlib import Path
import sys

root = Path(os.environ["ADB_FIXTURE"])
args = sys.argv[1:]
command = " ".join(args)
with (root / "calls.txt").open("a") as out:
    out.write(command + "\n")
state_path = root / "state.json"
state = json.loads(state_path.read_text())

if args == ["wait-for-device"]:
    sys.exit(0)
if args == ["reboot"]:
    if os.environ.get("REBOOT_FAIL") == "1":
        sys.exit(1)
    state["boot_id"] = "boot-after"
    state["sys.boot_completed"] = "1"
    state_path.write_text(json.dumps(state))
    sys.exit(0)
if args[:3] == ["shell", "cat", "/proc/sys/kernel/random/boot_id"]:
    print(state["boot_id"])
    sys.exit(0)
if args[:2] == ["shell", "getprop"]:
    print(state.get(args[2], ""))
    sys.exit(0)
if args[:3] == ["shell", "pm", "clear"]:
    sys.exit(0)
if args in (["kill-server"], ["start-server"]):
    sys.exit(0)
sys.exit("unexpected adb call: " + command)
'''


class Android15RecoveryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        adb = self.root / "adb"
        adb.write_text(f"#!{sys.executable} -S\n" + FAKE_ADB)
        adb.chmod(0o755)
        configure = self.root / "configure-emulator-runtime.sh"
        configure.write_text('#!/bin/sh\nprintf "configure\\n" >> "$CALLS"\nexit "$CONFIGURE_STATUS"\n')
        configure.chmod(0o755)
        self.script = self.root / "recover-android15-emulator.sh"
        self.script.write_text(SCRIPT.read_text())
        self.script.chmod(0o755)
        (self.root / "state.json").write_text(json.dumps({"boot_id": "boot-before", "sys.boot_completed": "1"}))
        self.calls_file = self.root / "calls.txt"

    def run_script(self, configure_status="0", **extra):
        env = dict(
            os.environ,
            PATH=str(self.root) + os.pathsep + os.environ["PATH"],
            RUNNER_TEMP=str(self.root),
            ADB_FIXTURE=str(self.root),
            CALLS=str(self.calls_file),
            CONFIGURE_STATUS=configure_status,
            ANDROID_RECOVERY_TIMEOUT_SECONDS="3",
        )
        env.update(extra)
        return subprocess.run(["bash", str(self.script)], env=env, capture_output=True, text=True, timeout=10)

    def calls(self):
        return self.calls_file.read_text().splitlines() if self.calls_file.exists() else []

    def test_reboot_is_proven_by_changed_boot_id_before_reconfiguration(self):
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        calls = self.calls()
        self.assertLess(calls.index("reboot"), calls.index("configure"))
        record = (self.root / "artflow-device/framework-recovery.txt").read_text()
        self.assertIn("bootIdBefore=boot-before", record)
        self.assertIn("bootIdAfter=boot-after", record)
        self.assertIn("recoveryStatus=PASS", record)

    def test_failed_runtime_reconfiguration_fails_closed(self):
        result = self.run_script(configure_status="17")
        self.assertEqual(17, result.returncode)
        self.assertNotIn("recoveryStatus=PASS", (self.root / "artflow-device/framework-recovery.txt").read_text())

    def test_invalid_timeout_fails_before_reboot(self):
        result = self.run_script(ANDROID_RECOVERY_TIMEOUT_SECONDS="0")
        self.assertEqual(2, result.returncode)
        self.assertNotIn("reboot", self.calls())


if __name__ == "__main__":
    unittest.main()
