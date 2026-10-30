"""Captured-state parser tests; no device or runtime configuration is exercised."""
import importlib.util
import json
import pathlib
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("cc_verify", pathlib.Path(__file__).with_name("verify_android15_cc_control.py"))
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


class CollectorEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.props = {"ro.build.version.sdk": "35", "ro.kernel.qemu": "1", "ro.debuggable": "1",
                      "ro.product.cpu.abilist": "x86_64", "ro.build.fingerprint": VERIFY.FINGERPRINT,
                      "ro.build.version.security_patch": "2024-08-05", "ro.build.id": "AE3A.240806.043", "ro.build.version.incremental": "12960925",
                      "sys.boot_completed": "1", "dalvik.vm.usejit": "false", "dalvik.vm.usejitprofiles": "false",
                      "ro.dalvik.vm.enable_uffd_gc": "true", VERIFY.ENABLE: "", "dalvik.vm.gctype": ""}
        for index, phase in enumerate(("before", "rebooted", "configured")):
            directory = self.root / phase
            directory.mkdir()
            props = dict(self.props, **{VERIFY.FORCE: "" if index == 0 else "true"})
            (directory / "properties.txt").write_text("\n".join(k + "=" + v for k, v in props.items()))
            (directory / "boot-id.txt").write_text("00000000-0000-0000-0000-00000000000" + ("1" if index == 0 else "2"))
            (directory / "kernel.txt").write_text(VERIFY.KERNEL)
            (directory / "kernel-version.txt").write_text("#1 SMP PREEMPT Fri Oct 18 23:59:20 UTC 2024")
            (directory / "page-size.txt").write_text("16384")
            (directory / "uid.txt").write_text("0")
            (directory / "libart-sha256.txt").write_text("01" * 32 + "  /apex/com.android.art/lib64/libart.so\n")
            (directory / "device-config-force-disable.txt").write_text("null" if index == 0 else "true")
            value = "false" if index == 0 else "true"
            (directory / "cache-info.xml").write_text('<cacheInfo><systemProperties><item k="' + VERIFY.FORCE + '" v="' + value + '"/></systemProperties></cacheInfo>')
            collector = "CollectorTypeCMC" if index == 0 else "CollectorTypeCC"
            lines = []
            for pid, label, name in ((101, "zygote", "zygote64"), (202, "system-server", "system_server")):
                (directory / (label + "-pid.txt")).write_text(str(pid))
                (directory / (label + "-cmdline.txt")).write_bytes(name.encode() + b"\0")
                (directory / (label + "-maps.txt")).write_text(self.maps())
                lines.append(f"10-24 12:00:00.123 {pid:5d} {pid:5d} I {name}: Using {collector} GC.")
            (directory / "logcat.txt").write_text("\n".join(lines))
        (self.root / "configured-verification.json").write_text(json.dumps(VERIFY.verify(self.root, "configured")))

    @staticmethod
    def maps():
        return "\n".join(f"1000-2000 r--p 00000000 00:01 1 {VERIFY.CACHE}{name}" for name in ("boot.art", "boot.oat"))

    def app(self, phase="instrumentation"):
        (self.root / (phase + "-boot-id.txt")).write_text((self.root / "configured/boot-id.txt").read_text())
        (self.root / (phase + "-properties.txt")).write_text((self.root / "configured/properties.txt").read_text())
        (self.root / (phase + "-app-pid.txt")).write_text("303")
        (self.root / (phase + "-app-cmdline.txt")).write_bytes(b"com.artflow.studio\0")
        (self.root / (phase + "-app-maps.txt")).write_text(self.maps())
        (self.root / (phase + "-logcat.txt")).write_text("10-24 12:00:03.000   303   303 I .artflow.studio: Using CollectorTypeCC GC.\n")

    def test_valid_source_supported_states_and_both_app_phases(self):
        for phase in ("before", "rebooted", "configured"):
            self.assertEqual("PASS", VERIFY.verify(self.root, phase)["status"])
        for phase in ("instrumentation", "minified"):
            self.app(phase)
            self.assertEqual(303, VERIFY.verify(self.root, phase)["app"]["pid"])

    def test_namespaced_boot_cache_is_supported(self):
        path = self.root / "configured/cache-info.xml"
        path.write_text(path.read_text().replace("<cacheInfo>", '<cacheInfo xmlns="' + VERIFY.NAMESPACE + '">'))
        self.assertEqual("PASS", VERIFY.verify(self.root, "configured")["status"])

    def test_live_true_property_cannot_replace_false_boot_cache(self):
        path = self.root / "configured/cache-info.xml"
        path.write_text(path.read_text().replace('v="true"', 'v="false"'))
        with self.assertRaisesRegex(ValueError, "boot-cached"):
            VERIFY.verify(self.root, "configured")

    def test_duplicate_and_wrongly_nested_cached_keys_are_rejected(self):
        path = self.root / "configured/cache-info.xml"
        original = path.read_text()
        item = '<item k="' + VERIFY.FORCE + '" v="true"/>'
        for corrupt in (original.replace(item, item + item), original.replace("systemProperties", "unrelated"), "<cacheInfo>"):
            path.write_text(corrupt)
            with self.assertRaises((ValueError, VERIFY.ET.ParseError)):
                VERIFY.verify(self.root, "configured")

    def test_full_reboot_is_required(self):
        (self.root / "configured/boot-id.txt").write_text((self.root / "before/boot-id.txt").read_text())
        with self.assertRaisesRegex(ValueError, "Full reboot"):
            VERIFY.verify(self.root, "configured")

    def test_kernel_and_page_size_changes_fail(self):
        for filename, value in (("kernel.txt", "6.6.66"), ("page-size.txt", "4096")):
            path = self.root / "configured" / filename
            original = path.read_text()
            path.write_text(value)
            with self.assertRaises(ValueError):
                VERIFY.verify(self.root, "configured")
            path.write_text(original)

    def test_unrelated_enable_flag_change_fails(self):
        path = self.root / "configured/properties.txt"
        path.write_text(path.read_text().replace(VERIFY.ENABLE + "=\n", VERIFY.ENABLE + "=true\n"))
        with self.assertRaisesRegex(ValueError, "Unrelated property"):
            VERIFY.verify(self.root, "configured")

    def test_runtime_binary_change_is_rejected(self):
        (self.root / "configured/libart-sha256.txt").write_text("02" * 32 + "  /apex/com.android.art/lib64/libart.so\n")
        with self.assertRaisesRegex(ValueError, "runtime binary changed"):
            VERIFY.verify(self.root, "configured")

    def test_disabled_jit_is_required_after_service_restart(self):
        path = self.root / "configured/properties.txt"
        path.write_text(path.read_text().replace("dalvik.vm.usejit=false", "dalvik.vm.usejit=true"))
        with self.assertRaisesRegex(ValueError, "JIT-disabled"):
            VERIFY.verify(self.root, "configured")

    def test_another_process_cc_does_not_prove_app_collector(self):
        self.app()
        path = self.root / "instrumentation-logcat.txt"
        path.write_text(path.read_text().replace("303", "404"))
        with self.assertRaisesRegex(ValueError, "collector evidence"):
            VERIFY.verify(self.root, "instrumentation")

    def test_app_cannot_pass_after_a_new_kernel_boot(self):
        self.app()
        (self.root / "instrumentation-boot-id.txt").write_text("00000000-0000-0000-0000-000000000099")
        with self.assertRaisesRegex(ValueError, "configured boot"):
            VERIFY.verify(self.root, "instrumentation")

    def test_app_cannot_pass_after_runtime_flags_change(self):
        self.app()
        path = self.root / "instrumentation-properties.txt"
        original = path.read_text()
        for old, new in (("dalvik.vm.usejit=false", "dalvik.vm.usejit=true"),
                         (VERIFY.FORCE + "=true", VERIFY.FORCE + "=false")):
            path.write_text(original.replace(old, new))
            with self.assertRaisesRegex(ValueError, "App runtime property changed"):
                VERIFY.verify(self.root, "instrumentation")

    def test_conflicting_own_collector_fails(self):
        self.app()
        path = self.root / "instrumentation-logcat.txt"
        path.write_text(path.read_text() + path.read_text().replace("CollectorTypeCC", "CollectorTypeCMC"))
        with self.assertRaisesRegex(ValueError, "conflicting"):
            VERIFY.verify(self.root, "instrumentation")

    def test_wrong_cmdline_fails(self):
        self.app()
        (self.root / "instrumentation-app-cmdline.txt").write_text("com.google.android.gms")
        with self.assertRaisesRegex(ValueError, "expected process"):
            VERIFY.verify(self.root, "instrumentation")

    def test_system_boot_or_minimal_image_does_not_pass(self):
        self.app()
        path = self.root / "instrumentation-app-maps.txt"
        for text in (self.maps().replace(VERIFY.CACHE, "/system/framework/x86_64/"), self.maps().replace("boot.art", "boot_minimal.art")):
            path.write_text(text)
            with self.assertRaisesRegex(ValueError, "boot.art/boot.oat"):
                VERIFY.verify(self.root, "instrumentation")

    def test_own_image_failure_is_rejected_but_other_process_is_separate(self):
        self.app()
        path = self.root / "instrumentation-logcat.txt"
        original = path.read_text()
        error = "10-24 12:00:03.001   404   404 E runtime: ValidateOatFile found read barrier state mismatch\n"
        path.write_text(original + error)
        self.assertEqual("PASS", VERIFY.verify(self.root, "instrumentation")["status"])
        path.write_text(original + error.replace("404", "303"))
        with self.assertRaisesRegex(ValueError, "mismatch/fallback"):
            VERIFY.verify(self.root, "instrumentation")


if __name__ == "__main__":
    unittest.main()
