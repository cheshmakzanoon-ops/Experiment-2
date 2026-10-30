#!/usr/bin/env python3
"""Verify captured state for the disposable Android 15 collector comparison.

No adb calls or configuration changes. A requested property alone never passes.
ART contract: android-15.0.0_r1, runtime/gc/collector/mark_compact.cc;
odrefresh/CacheInfo.xsd; runtime/gc/space/image_space.cc.
"""
import argparse
import json
import pathlib
import re
import sys
import uuid
import xml.etree.ElementTree as ET

FORCE = "persist.device_config.runtime_native_boot.force_disable_uffd_gc"
ENABLE = "persist.device_config.runtime_native_boot.enable_uffd_gc_2"
FINGERPRINT = "google/sdk_gphone16k_x86_64/emu64xa16k:15/AE3A.240806.043/12960925:userdebug/dev-keys"
KERNEL = "6.6.50-android15-8-g8adecb593e9b-ab12525588"
CACHE = "/data/misc/apexdata/com.android.art/dalvik-cache/x86_64/"
NAMESPACE = "http://schemas.android.com/art/apex-cache-info/v1_0"
IDENTITY_KEYS = ("ro.build.fingerprint", "ro.build.version.sdk", "ro.product.cpu.abilist", "ro.kernel.qemu", "ro.debuggable",
                 "ro.build.version.security_patch", "ro.build.id", "ro.build.version.incremental")
PRESERVED_KEYS = IDENTITY_KEYS + ("ro.dalvik.vm.enable_uffd_gc", ENABLE, "dalvik.vm.gctype")
LOG_LINE = re.compile(r"^\d\d-\d\d\s+\d\d:\d\d:\d\d\.\d+\s+(\d+)\s+\d+\s+[A-Z]\s+[^:]+:\s*(.*)$")
FALLBACK = re.compile(r"read barrier state mismatch|could not create image space|(?:failed|unable) to (?:load|map).*image|(?:using|falling back to).*jit[- ]?zygote", re.I)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read(path):
    return path.read_text(encoding="utf-8").strip()


def properties(path):
    result = {}
    for line in read(path).splitlines():
        key, separator, value = line.partition("=")
        require(separator and key and key not in result, "Malformed or duplicate property")
        result[key] = value
    return result


def libart_hash(path):
    match = re.fullmatch(r"([0-9a-f]{64})\s+/apex/com.android.art/lib64/libart\.so", read(path))
    require(match, "Missing or malformed actual libart hash")
    return match[1]


def cached_force(path, required=True):
    if not path.exists() or not path.stat().st_size:
        require(not required, "Missing ART boot cache")
        return None
    raw = path.read_bytes()
    require(b"<!DOCTYPE" not in raw.upper(), "Unexpected XML doctype")
    root = ET.fromstring(raw)
    prefix = "{" + NAMESPACE + "}" if root.tag.startswith("{") else ""
    require(root.tag == prefix + "cacheInfo", "Unexpected boot-cache root/namespace")
    groups = root.findall(prefix + "systemProperties")
    require(len(groups) == 1, "Missing or duplicate boot-cache systemProperties")
    values = {}
    for item in groups[0]:
        require(item.tag == prefix + "item" and "k" in item.attrib and "v" in item.attrib, "Malformed cached property")
        key = item.attrib["k"]
        require(key not in values, "Duplicate cached property")
        values[key] = item.attrib["v"]
    value = values.get(FORCE)
    require(not required or value == "true", "ART boot-cached force-disable flag is not true")
    return value


def process(pid_file, cmdline_file, maps_file, log_file, name, collector, rebuilt):
    raw_pid = read(pid_file)
    require(re.fullmatch(r"[1-9][0-9]*", raw_pid), "Invalid or ambiguous process PID")
    pid = int(raw_pid)
    cmdline = cmdline_file.read_bytes().split(b"\0", 1)[0].decode().strip()
    require(cmdline == name, "Captured PID is not the expected process: " + name)
    messages = []
    for line in log_file.read_text(errors="replace").splitlines():
        match = LOG_LINE.match(line)
        if match and int(match[1]) == pid:
            messages.append(match[2])
    observed = set()
    for message in messages:
        match = re.search(r"Using (CollectorType\w+) GC\.", message)
        if match:
            observed.add(match[1])
    require(observed == {collector}, "Actual PID collector evidence missing or conflicting: " + str(sorted(observed)))
    require(not any(FALLBACK.search(message) for message in messages), "Runtime image mismatch/fallback in captured process")
    mappings = []
    for line in read(maps_file).splitlines():
        match = re.fullmatch(r"[0-9a-f]+-[0-9a-f]+\s+[rwxps-]{4}\s+[0-9a-f]+\s+[0-9a-f]+:[0-9a-f]+\s+\d+\s+(.+)", line)
        if match:
            mappings.append(match[1])
    require(mappings, "No valid process mappings")
    if rebuilt:
        require(CACHE + "boot.art" in mappings and CACHE + "boot.oat" in mappings, "Rebuilt primary boot.art/boot.oat are not actually mapped")
        require(not any("boot_minimal" in path for path in mappings), "Minimal/JIT-Zygote image fallback")
    return {"pid": pid, "cmdline": cmdline, "collector": collector, "mappedBootArtifacts": sorted({p for p in mappings if "/boot" in p})}


def verify(root, phase):
    if phase in ("instrumentation", "minified"):
        configured = json.loads(read(root / "configured-verification.json"))
        require(configured["status"] == "PASS", "Setup was not verified")
        boot = read(root / (phase + "-boot-id.txt"))
        require(boot == configured["bootId"], "App phase is not on the configured boot")
        actual = properties(root / (phase + "-properties.txt"))
        expected = properties(root / "configured" / "properties.txt")
        for key in PRESERVED_KEYS + (FORCE, "dalvik.vm.usejit", "dalvik.vm.usejitprofiles", "sys.boot_completed"):
            require(actual.get(key) == expected.get(key), "App runtime property changed: " + key)
        require(actual.get(FORCE) == "true" and actual.get("dalvik.vm.usejit") == "false" and actual.get("dalvik.vm.usejitprofiles") == "false", "App runtime mode was not retained")
        result = process(root / (phase + "-app-pid.txt"), root / (phase + "-app-cmdline.txt"),
                         root / (phase + "-app-maps.txt"), root / (phase + "-logcat.txt"),
                         "com.artflow.studio", "CollectorTypeCC", True)
        return {"phase": phase, "status": "PASS", "bootId": boot, "runtimePropertiesUnchanged": True, "app": result}
    directory = root / phase
    props = properties(directory / "properties.txt")
    require(all(props.get(key) for key in IDENTITY_KEYS), "Missing runtime image identity property")
    require(props.get("ro.build.version.sdk") == "35" and props.get("ro.kernel.qemu") == "1" and props.get("ro.debuggable") == "1", "Not a debuggable API35 emulator")
    require("x86_64" in props.get("ro.product.cpu.abilist", "").split(","), "Not x86_64")
    require(props.get("ro.build.fingerprint") == FINGERPRINT, "Unexpected system-image fingerprint")
    require(read(directory / "page-size.txt") == "16384", "Not a 16KB runtime")
    require(read(directory / "kernel.txt") == KERNEL, "Unexpected kernel revision")
    require(read(directory / "uid.txt") == "0", "adb root not verified")
    art_hash = libart_hash(directory / "libart-sha256.txt")
    boot = read(directory / "boot-id.txt")
    uuid.UUID(boot)
    require(props.get("sys.boot_completed") == "1", "Android boot not complete")
    if phase != "rebooted":
        require(props.get("dalvik.vm.usejit") == "false" and props.get("dalvik.vm.usejitprofiles") == "false", "JIT-disabled state not verified")
    value = cached_force(directory / "cache-info.xml", required=phase != "before")
    if phase == "before":
        require(props.get(FORCE, "") in ("", "false") and value in (None, "false"), "Baseline already requests force-disabled UFFD")
        require(read(directory / "device-config-force-disable.txt") in ("null", "false"), "Baseline DeviceConfig is not default/false")
    else:
        before = root / "before"
        initial = properties(before / "properties.txt")
        require(boot != read(before / "boot-id.txt"), "Full reboot not demonstrated")
        for key in PRESERVED_KEYS:
            require(props.get(key) == initial.get(key), "Unrelated property changed: " + key)
        require(art_hash == libart_hash(before / "libart-sha256.txt"), "ART runtime binary changed")
        require(read(directory / "kernel-version.txt") == read(before / "kernel-version.txt"), "Kernel build changed")
        require(props.get(FORCE) == "true" and read(directory / "device-config-force-disable.txt") == "true", "Requested force-disable flag not retained")
        if phase == "configured":
            require(boot == read(root / "rebooted" / "boot-id.txt"), "Unexpected extra boot during JIT configuration")
    collector = "CollectorTypeCMC" if phase == "before" else "CollectorTypeCC"
    processes = {}
    for label, name in (("zygote", "zygote64"), ("system-server", "system_server")):
        processes[label] = process(directory / (label + "-pid.txt"), directory / (label + "-cmdline.txt"),
                                   directory / (label + "-maps.txt"), directory / "logcat.txt", name, collector, phase != "before")
    return {"phase": phase, "status": "PASS", "bootId": boot, "fingerprint": props["ro.build.fingerprint"],
            "kernel": KERNEL, "libartSHA256": art_hash, "pageSize": 16384, "cachedForceDisable": value,
            "jit": {key: props.get(key) for key in ("dalvik.vm.usejit", "dalvik.vm.usejitprofiles")}, "processes": processes}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=pathlib.Path)
    parser.add_argument("--phase", required=True, choices=("before", "rebooted", "configured", "instrumentation", "minified"))
    args = parser.parse_args()
    try:
        result = verify(args.root, args.phase)
        status = 0
    except (OSError, ValueError, ET.ParseError, KeyError, TypeError) as error:
        result = {"phase": args.phase, "status": "FAIL", "error": str(error)}
        status = 1
    args.root.mkdir(parents=True, exist_ok=True)
    (args.root / (args.phase + "-verification.json")).write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result))
    return status


if __name__ == "__main__":
    sys.exit(main())
