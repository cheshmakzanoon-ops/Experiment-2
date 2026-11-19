"""Verify the kernel acceptance boundary using realistic adb responses and actual file hashes."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("verify_emulator_kernel.py")
SPEC = importlib.util.spec_from_file_location("verify_emulator_kernel", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
RELEASE = "6.6.66-android15-8-gd0c43a640eab-ab13812146"
FINGERPRINT = "google/sdk_gphone16k_x86_64/emu64xa16k:15/AE3A.240806.043/12960925:userdebug/dev-keys"


class VerifyEmulatorKernelTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.kernel = self.root / "kernel-ranchu"
        self.kernel.write_bytes(b"pinned official kernel fixture\x00\x01")
        self.manifest_path = self.root / "prepared.json"
        self.manifest = {
            "kernel_release": RELEASE,
            "expected_api": 35,
            "expected_fingerprint": FINGERPRINT,
            "kernel_sha256": hashlib.sha256(self.kernel.read_bytes()).hexdigest(),
            "kernel_bytes": self.kernel.stat().st_size,
            "kernel_path": "kernel-ranchu",
        }
        self.properties = {
            "ro.kernel.qemu": "1", "ro.debuggable": "1",
            "ro.build.version.sdk": "35", "ro.build.fingerprint": FINGERPRINT,
            "dalvik.vm.usejit": "false",
        }
        self.responses = {
            "shell uname -r": RELEASE + "\r\n",
            "shell cat /proc/version": f"Linux version {RELEASE} (build@host)\n",
            "shell cat /proc/modules": "virtio_blk 32768 0 - Live 0x0000000000000000\n",
            "shell cat /proc/sys/kernel/random/boot_id": "43c78224-4809-43e7-99be-f54e8a3e2bd4\n",
            "shell cat /proc/cmdline": "androidboot.hardware=ranchu\n",
            "shell dmesg": "[0.100] virtio_blk: registered virtio block device\n",
            "logcat -b all -d -v threadtime": "11-19 10:00:00.100 100 100 I zygote64: Using CollectorTypeCC GC.\n",
            f"shell cat {MODULE.CACHE_INFO}": "<cacheInfo/>\n",
            "shell getconf PAGESIZE": "16384\n",
        }
        self.calls = []
        self.failures = {}

    def runner(self, args):
        self.calls.append(args)
        key = " ".join(args)
        if key in self.failures:
            return {"returncode": 1, "stdout": "", "stderr": self.failures[key]}
        text = (
            "".join(f"[{key}]: [{value}]\r\n" for key, value in self.properties.items())
            if key == "shell getprop" else self.responses[key]
        )
        return {"returncode": 0, "stdout": text, "stderr": ""}

    def verify(self):
        self.manifest_path.write_text(json.dumps(self.manifest))
        output = self.root / "evidence"
        report = MODULE.verify(self.manifest_path, output, self.runner)
        self.assertEqual(report, json.loads((output / "verification.json").read_text()))
        return report

    def test_valid_pinned_kernel_records_evidence_without_mutating_android(self):
        report = self.verify()
        self.assertTrue(report["passed"], report["errors"])
        self.assertEqual(RELEASE, report["runtime"]["kernelRelease"])
        self.assertEqual(1, report["runtime"]["moduleCount"])
        self.assertIn("CollectorTypeCC", (self.root / "evidence/art-collectors.txt").read_text())
        self.assertEqual("<cacheInfo/>\n", (self.root / "evidence/art-cache-info.txt").read_text())
        self.assertTrue(all(args[:2] in (["shell", "getprop"], ["shell", "uname"], ["shell", "cat"],
                                        ["shell", "dmesg"], ["shell", "getconf"], ["logcat", "-b"])
                            for args in self.calls))

    def test_wrong_kernel_digest_size_and_running_release_each_fail(self):
        for key, value in (("kernel_sha256", "0" * 64), ("kernel_bytes", 999), ("kernel_release", "6.6.50-other")):
            with self.subTest(key=key):
                original = self.manifest[key]
                self.manifest[key] = value
                report = self.verify()
                self.assertFalse(report["passed"])
                self.assertTrue((self.root / "evidence/dmesg.txt").is_file())
                self.manifest[key] = original

    def test_wrong_sdk_fingerprint_and_non_emulator_device_each_fail(self):
        for key, value in (("ro.build.version.sdk", "36"), ("ro.build.fingerprint", "another/image"),
                           ("ro.kernel.qemu", "0"), ("ro.debuggable", "0")):
            with self.subTest(key=key):
                original = self.properties[key]
                self.properties[key] = value
                report = self.verify()
                self.assertFalse(report["passed"])
                self.assertTrue(any(key in error for error in report["errors"]))
                self.properties[key] = original

    def test_module_errors_in_kernel_or_android_logs_fail_and_preserve_original(self):
        failures = (
            "virtio_gpu: Unknown symbol drm_gem_object_free (err -22)",
            "virtio_gpu: disagrees about version of symbol module_layout",
            "virtio_gpu: version magic '6.6.50 SMP' should be '6.6.66 SMP'",
            "modprobe: Failed to load kernel module virtio_gpu: Exec format error",
            "module verification failed: malformed signature",
            "insmod: virtio_gpu.ko: Required key not available",
            "Loading of unsigned module is rejected",
        )
        for command in ("shell dmesg", "logcat -b all -d -v threadtime"):
            original = self.responses[command]
            for failure in failures:
                with self.subTest(command=command, failure=failure):
                    self.responses[command] = original + failure + "\n"
                    report = self.verify()
                    self.assertFalse(report["passed"])
                    self.assertIn(failure, report["moduleFailures"])
            self.responses[command] = original

    def test_stock_unsigned_module_taint_is_preserved_without_hiding_actual_load_failures(self):
        warning = "virtio_blk: module verification failed: signature and/or required key missing - tainting kernel"
        self.responses["shell dmesg"] += warning + "\ninit: Loaded kernel module /lib/modules/virtio_blk.ko\n"
        self.responses["shell dmesg"] += "dlkm_loader: Loaded kernel module /vendor/lib/modules/failover.ko\n"
        report = self.verify()
        self.assertTrue(report["passed"], report["errors"])
        self.assertEqual([warning], report["moduleWarnings"])
        self.responses["shell dmesg"] += "virtio_gpu: Unknown symbol drm_gem_object_free (err -22)\n"
        self.assertFalse(self.verify()["passed"])

    def test_missing_required_adb_output_fails_but_other_logs_survive(self):
        for command in ("shell getprop", "shell uname -r", "shell cat /proc/modules", "shell dmesg"):
            with self.subTest(command=command):
                self.failures[command] = "adb: device offline"
                report = self.verify()
                self.assertFalse(report["passed"])
                self.assertTrue((self.root / "evidence/art-collectors.txt").is_file())
                self.failures.clear()

    def test_getconf_is_optional_and_does_not_claim_process_page_size_acceptance(self):
        self.failures["shell getconf PAGESIZE"] = "getconf: inaccessible or not found"
        report = self.verify()
        self.assertTrue(report["passed"], report["errors"])
        self.assertTrue(report["warnings"])
        self.assertIn("RuntimeEnvironmentTest", report["runtime"]["pageSizeAcceptance"])

    def test_invalid_manifest_still_retains_runtime_logs(self):
        del self.manifest["kernel_sha256"]
        report = self.verify()
        self.assertFalse(report["passed"])
        self.assertTrue((self.root / "evidence/modules.txt").is_file())

    def test_missing_adb_and_timeouts_become_preserved_failures(self):
        with patch.object(MODULE.subprocess, "run", side_effect=FileNotFoundError("adb")):
            result = MODULE.run_adb(["shell", "getprop"])
        self.assertIsNone(result["returncode"])
        self.assertIn("FileNotFoundError", result["error"])
        timeout = subprocess.TimeoutExpired(["adb"], 20, output=b"partial evidence\n", stderr=b"offline\n")
        with patch.object(MODULE.subprocess, "run", side_effect=timeout):
            result = MODULE.run_adb(["shell", "dmesg"])
        self.assertEqual("partial evidence\n", result["stdout"])
        self.assertEqual("offline\n", result["stderr"])
        self.assertIn("TimeoutExpired", result["error"])

    def test_cli_missing_adb_is_nonzero_and_writes_verification(self):
        self.manifest_path.write_text(json.dumps(self.manifest))
        output = self.root / "cli-evidence"
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "--manifest", str(self.manifest_path), "--output", str(output)],
            env={"PATH": str(self.root)}, capture_output=True, text=True, timeout=10,
        )
        self.assertEqual(1, result.returncode, result.stderr)
        report = json.loads((output / "verification.json").read_text())
        self.assertFalse(report["passed"])
        self.assertIn("FileNotFoundError", report["commands"]["properties"]["error"])


if __name__ == "__main__":
    unittest.main()
