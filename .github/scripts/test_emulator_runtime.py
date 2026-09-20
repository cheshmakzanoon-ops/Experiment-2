"""Exercise the actual shell configurator with a stateful adb fixture, not an emulator."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("configure-emulator-runtime.sh")
FAKE_ADB = r'''import json
import os
from pathlib import Path
import sys

root = Path(os.environ["ADB_FIXTURE"])
args = sys.argv[1:]
command = " ".join(args)
with (root / "calls.txt").open("a") as out:
    out.write(command + "\n")
if command == os.environ.get("FAIL_COMMAND"):
    sys.exit(1)
props_file = root / "properties.json"
props = json.loads(props_file.read_text())
if args[:2] == ["shell", "getprop"]:
    key = args[2]
    value = props.get(key, "")
    if key == "dalvik.vm.usejit" and os.environ.get("BAD_READBACK") and (root / "started").exists():
        value = "true"
    print(value)
elif args[:2] == ["shell", "setprop"]:
    props[args[2]] = args[3]
    props_file.write_text(json.dumps(props))
elif args == ["shell", "start"]:
    (root / "started").touch()
    if not os.environ.get("NEVER_BOOT"):
        props["sys.boot_completed"] = "1"
        props_file.write_text(json.dumps(props))
elif args == ["shell", "id", "-u"]:
    print(os.environ.get("ROOT_UID", "0"))
elif args not in (["root"], ["wait-for-device"], ["shell", "stop"]):
    sys.exit("Unexpected adb call: " + command)
'''


class EmulatorRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        adb = self.root / "adb"
        adb.write_text(f"#!{sys.executable} -S\n" + FAKE_ADB)
        adb.chmod(0o755)
        self.properties = {
            "ro.kernel.qemu": "1",
            "ro.debuggable": "1",
            "ro.build.fingerprint": "fixture/userdebug",
            "ro.build.version.sdk": "35",
            "ro.product.cpu.abilist": "x86_64",
            "dalvik.vm.usejit": "true",
            "dalvik.vm.usejitprofiles": "true",
            "sys.boot_completed": "1",
        }

    def run_script(self, mode="disabled", **overrides):
        (self.root / "properties.json").write_text(json.dumps(self.properties))
        env = dict(os.environ)
        for key in ("FAIL_COMMAND", "BAD_READBACK", "NEVER_BOOT", "ROOT_UID"):
            env.pop(key, None)
        env.update(
            PATH=str(self.root) + os.pathsep + env["PATH"],
            RUNNER_TEMP=str(self.root),
            ADB_FIXTURE=str(self.root),
            ART_JIT_MODE=mode,
            ART_BOOT_TIMEOUT_SECONDS="1",
        )
        env.update(overrides)
        return subprocess.run(["bash", str(SCRIPT)], env=env, capture_output=True, text=True, timeout=15)

    def calls(self):
        path = self.root / "calls.txt"
        return path.read_text().splitlines() if path.exists() else []

    def record(self):
        return (self.root / "artflow-device/runtime-configuration.txt").read_text()

    def test_disabled_mode_restarts_before_verifying_the_effective_properties(self):
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        calls = self.calls()
        operations = ["root", "wait-for-device", "shell id -u", "shell stop",
                      "shell setprop dalvik.vm.usejit false", "shell setprop dalvik.vm.usejitprofiles false",
                      "shell setprop sys.boot_completed 0", "shell start", "shell getprop sys.boot_completed"]
        positions = [calls.index(operation) for operation in operations]
        self.assertEqual(sorted(positions), positions)
        verified = self.record().split("verifiedConfiguration:\n")[1]
        self.assertIn("dalvik.vm.usejit=false", verified)
        self.assertIn("dalvik.vm.usejitprofiles=false", verified)
        self.assertIn("configurationStatus=PASS", verified)

    def test_default_mode_records_but_does_not_mutate_or_restart_runtime(self):
        result = self.run_script("default")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(all(call.startswith("shell getprop ") for call in self.calls()))
        self.assertIn("dalvik.vm.usejit=true", self.record())

    def test_invalid_mode_and_deadline_fail_before_any_adb_call(self):
        self.assertNotEqual(0, self.run_script("unexpected").returncode)
        self.assertEqual([], self.calls())
        self.assertNotEqual(0, self.run_script(ART_BOOT_TIMEOUT_SECONDS="0").returncode)
        self.assertEqual([], self.calls())

    def test_physical_devices_and_non_debuggable_images_are_refused(self):
        for key in ("ro.kernel.qemu", "ro.debuggable"):
            with self.subTest(key=key):
                self.properties[key] = "0"
                self.assertNotEqual(0, self.run_script().returncode)
                self.assertNotIn("root", self.calls())
                self.assertNotIn("shell stop", self.calls())
                self.properties[key] = "1"

    def test_failed_root_does_not_stop_android(self):
        result = self.run_script(ROOT_UID="2000")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("shell stop", self.calls())
        self.assertNotIn("configurationStatus=PASS", self.record())

    def test_property_write_failure_is_not_reported_as_success_and_restarts_services(self):
        result = self.run_script(FAIL_COMMAND="shell setprop dalvik.vm.usejit false")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("shell start", self.calls())
        self.assertNotIn("configurationStatus=PASS", self.record())

    def test_ignored_property_and_boot_timeout_fail_closed(self):
        result = self.run_script(BAD_READBACK="1")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("property verification failed", result.stderr)
        self.assertNotIn("configurationStatus=PASS", self.record())
        result = self.run_script(NEVER_BOOT="1")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("deadline", result.stderr)
        self.assertNotIn("configurationStatus=PASS", self.record())


if __name__ == "__main__":
    unittest.main()
