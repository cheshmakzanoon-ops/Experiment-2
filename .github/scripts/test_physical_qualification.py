"""Synthetic validator fixtures only: these tests provide no physical-device evidence."""
import copy
import json
import math
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from physical_qualification import (
    capture, checked_reference, decision_digest, initialize, reference, samples, statistic, verify,
)


REVISION = "a" * 40
TREE = "b" * 40


class PhysicalQualificationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name) / "campaign"
        initialize(self.root)
        self.plan = json.loads((self.root / "plan.json").read_text())
        self.campaign = json.loads((self.root / "campaign.json").read_text())
        self.write("raw/approval.txt", "FIXTURE ONLY: owner approval of the complete plan\n")
        self.write("raw/review.txt", "FIXTURE ONLY: reviewer inspected provenance and analysis\n")
        self.write("raw/physical.txt", "FIXTURE ONLY: simulated device observation\n")
        self.write("raw/capture.bin", b"FIXTURE ONLY: not a real trace, video, or measurement\n")
        self.write("raw/analysis.txt", "FIXTURE ONLY: sample extraction protocol and uncertainty\n")
        self.write("raw/artwork.artflow", b"FIXTURE ONLY: artificial workload\n")
        self.write("raw/brush.json", '{"fixture": true}\n')
        apk = self.root / "artifacts/base.apk"
        apk.parent.mkdir()
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"FIXTURE manifest")
            archive.writestr("classes.dex", b"FIXTURE dex")
        self.apk = apk
        self.plan["application"].update(versionCode=42, versionName="fixture", artifacts=[self.ref("artifacts/base.apk")])
        self.plan["source"].update(revision=REVISION, gitTree=TREE, buildId="fixture-build-42")
        app = self.plan["application"]
        self.build = {"schemaVersion": 1, "revision": REVISION, "gitTree": TREE,
                      "buildId": "fixture-build-42", "sourceDirty": False,
                      **{key: app[key] for key in ("packageName", "versionCode", "versionName",
                                                  "buildType", "debuggable", "minified")},
                      "apkSha256": [self.ref("artifacts/base.apk")["sha256"]]}
        self.write_json("build.json", self.build)
        self.plan["source"]["buildEvidence"] = self.ref("build.json")
        acceptance = self.plan["acceptance"]
        acceptance.update(owner="Fixture owner", approvedAtUtc="2026-11-19T10:00:00Z",
                          approvalEvidence=self.ref("raw/approval.txt"), lowMemoryMaxTotalRamMiB=4096)
        for criterion in acceptance["criteria"]:
            criterion.update(limit=50, minSamples=4, minDurationSeconds=90,
                             definition="Fixture metric endpoints", protocol="Fixture measured protocol")
        for workload in acceptance["workloads"]:
            workload.update(description="Fixture workload", steps="Fixture actions", brush="Fixture brush",
                            canvas={"width": 512, "height": 512, "layers": 3, "frames": 1},
                            durationSeconds=90, warmupSeconds=5,
                            projectEvidence=self.ref("raw/artwork.artflow"),
                            brushSettingsEvidence=self.ref("raw/brush.json"))
        devices = [("legacy", 26, 4096, "phone"), ("fifteen", 35, 4096, "phone"),
                   ("sixteen", 36, 4096, "phone"), ("tablet", 35, 16384, "tablet")]
        self.calls = []
        self.campaign["devices"] = []
        for identity, sdk, page_size, factor in devices:
            self.make_capture(identity, sdk, page_size)
            self.make_capture(identity, sdk, page_size, phase="post")
            self.campaign["devices"].append({
                "id": identity, "physical": True, "operator": "Fixture operator",
                "physicalEvidence": self.ref("raw/physical.txt"), "formFactor": factor,
                "capture": self.ref(f"devices/{identity}/capture.json"),
                "postCapture": self.ref(f"devices/{identity}_post/capture.json"),
                "stylus": {"manufacturer": "Fixture", "model": "Fixture pen",
                           "evidence": self.ref("raw/physical.txt")},
            })
        role_device = {"android8_low_memory": "legacy", "android15_phone": "fifteen",
                       "android16_phone": "sixteen", "tablet_active_stylus": "tablet", "arm64_16k": "tablet"}
        self.campaign["runs"] = []
        for spec in acceptance["runs"]:
            observations = []
            for criterion in spec["criterionIds"]:
                self.write(f"samples/{spec['id']}-{criterion}.csv",
                           "sample_id,elapsed_seconds,value\n1,0,10\n2,30,11\n3,60,12\n4,90,13\n")
                observations.append({
                    "id": criterion,
                    "unit": {"frames": "ms", "optical_latency": "ms", "memory": "MiB", "thermal": "C"}[criterion],
                    "method": {"frames": "surface_presentation", "optical_latency": "optical_video",
                               "memory": "dumpsys_meminfo", "thermal": "external_temperature_probe"}[criterion],
                    "scope": "Fixture exact surface, process or sensor",
                    "surfaceName": "Fixture GLSurfaceView",
                    "surfaceCoverageEvidence": self.ref("raw/analysis.txt"),
                    "rawEvidence": [self.ref("raw/capture.bin")], "analysisEvidence": self.ref("raw/analysis.txt"),
                    "samples": self.ref(f"samples/{spec['id']}-{criterion}.csv"),
                    "uncertaintyMs": 0.5, "calibration": "Fixture optical calibration",
                })
            self.campaign["runs"].append({
                "id": spec["id"], "deviceId": role_device[spec["role"]], "status": "completed",
                "inputSource": spec["inputSource"], "startedAtUtc": "2026-11-19T10:10:00Z",
                "endedAtUtc": "2026-11-19T10:12:05Z", "rawEvidence": [self.ref("raw/capture.bin")],
                "environment": {"ambientTemperatureC": 21, "displayRefreshHz": 60, "brightnessPercent": 50,
                                "batteryStartPercent": 90, "batteryEndPercent": 89, "charging": False,
                                "powerMode": "Fixture normal", "evidence": [self.ref("raw/capture.bin")]},
                "measurements": observations,
                "stylusChecks": [{"id": name, "status": "passed", "expected": "Fixture expectation",
                                  "observed": "Fixture observation", "evidence": [self.ref("raw/capture.bin")]}
                                 for name in ("pressure", "tilt", "palm_rejection")],
            })
        self.campaign["review"] = {"reviewer": "Fixture reviewer", "reviewedAtUtc": "2026-11-19T11:00:00Z",
                                   "decision": "accepted", "evidence": self.ref("raw/review.txt")}
        self.save()

    def write(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(content, bytes):
            path.write_bytes(content)
        else:
            path.write_text(content)

    def write_json(self, name, content):
        self.write(name, json.dumps(content, indent=2) + "\n")

    def ref(self, name):
        return reference(self.root, name)

    def save(self):
        acceptance = self.plan["acceptance"]
        # The synthetic fixture owner/reviewer approve the current synthetic data.
        # Dedicated stale-decision tests bypass this helper after modifying content.
        if all(not isinstance(item.get("limit"), float) or math.isfinite(item["limit"])
               for item in acceptance["criteria"]):
            self.write_json("raw/approval.txt", {"schemaVersion": 1, "owner": acceptance["owner"],
                            "approvedAtUtc": acceptance["approvedAtUtc"],
                            "planSha256": decision_digest(self.plan, "acceptance", "approvalEvidence")})
            acceptance["approvalEvidence"] = self.ref("raw/approval.txt")
        self.write_json("plan.json", self.plan)
        self.campaign["plan"] = self.ref("plan.json")
        review = self.campaign["review"]
        self.write_json("raw/review.txt", {"schemaVersion": 1, "reviewer": review["reviewer"],
                        "reviewedAtUtc": review["reviewedAtUtc"], "decision": review["decision"],
                        "campaignSha256": decision_digest(self.campaign, "review", "evidence")})
        review["evidence"] = self.ref("raw/review.txt")
        self.write_json("campaign.json", self.campaign)

    def verify(self):
        self.save()
        return verify(self.root / "plan.json", self.root / "campaign.json", REVISION)

    def rejected(self, text=None):
        report = self.verify()
        self.assertFalse(report["qualified"], report)
        self.assertIn(report["status"], {"UNQUALIFIED", "FAILED"})
        if text:
            self.assertIn(text, " ".join(report["problems"] + report["failures"]))
        return report

    def make_capture(self, identity, sdk, page_size, phase="pre"):
        properties = {
            "ro.product.manufacturer": "Fixture", "ro.product.model": "Fixture tablet",
            "ro.build.fingerprint": "fixture/physical/model:16/release", "ro.build.version.sdk": str(sdk),
            "ro.product.cpu.abilist": "arm64-v8a,armeabi-v7a", "ro.hardware": "fixture-chip",
            "ro.kernel.qemu": "0",
        }
        def fake_run(arguments, **kwargs):
            self.calls.append(arguments)
            command = arguments[3:]
            if command == ["shell", "getprop"]:
                text = "\n".join(f"[{key}]: [{value}]" for key, value in properties.items()) + "\n"
            elif command == ["shell", "getconf", "PAGE_SIZE"]:
                text = f"{page_size}\n"
            elif command == ["shell", "cat", "/proc/meminfo"]:
                text = "MemTotal:        3145728 kB\n"
            elif command == ["shell", "dumpsys", "package", "com.artflow.studio"]:
                text = ("  versionCode=42 minSdk=26 targetSdk=36\n  versionName=fixture\n"
                        "  flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]\n  primaryCpuAbi=arm64-v8a\n")
            elif command == ["shell", "pm", "path", "com.artflow.studio"]:
                text = "package:/data/app/fixture/base.apk\n"
            elif command[0] == "pull":
                shutil.copyfile(self.apk, command[2])
                text = "1 file pulled\n"
            else:
                text = "FIXTURE device dump\n"
            return subprocess.CompletedProcess(arguments, 0, text.encode(), b"")
        directory = identity if phase == "pre" else f"{identity}_post"
        captured_at = "2026-11-19T10:05:00Z" if phase == "pre" else "2026-11-19T10:15:00Z"
        with patch("physical_qualification.subprocess.run", side_effect=fake_run), patch(
                "physical_qualification.utc_now", return_value=captured_at):
            result = capture(self.root / "devices" / directory, f"fixture-{identity}", "com.artflow.studio",
                             self.plan["campaignId"], REVISION)
        self.assertTrue(result["captureComplete"])
        self.assertFalse(result["qualified"])

    def mutate_capture_output(self, device_index, command, content, phase="pre"):
        device = self.campaign["devices"][device_index]
        key = "capture" if phase == "pre" else "postCapture"
        path = self.root / device[key]["path"]
        record = json.loads(path.read_text())
        log = record["commands"][command]["stdout"]["path"]
        (path.parent / log).write_text(content)
        record["commands"][command]["stdout"] = reference(path.parent, log, allow_empty=True)
        path.write_text(json.dumps(record))
        device[key] = self.ref(device[key]["path"])

    def test_complete_synthetic_fixture_qualifies_only_defined_campaign(self):
        report = self.verify()
        self.assertTrue(report["qualified"], report)
        self.assertEqual(report["status"], "QUALIFIED_FOR_RECORDED_PHYSICAL_CAMPAIGN")
        self.assertFalse(report["publisherSignatureQualified"])
        self.assertFalse(report["releaseApproved"])
        self.assertEqual(len(report["runs"]), 5)

    def test_blank_forms_are_unqualified_and_have_no_limits(self):
        path = self.root / "blank"
        initialize(path)
        report = verify(path / "plan.json", path / "campaign.json", REVISION)
        self.assertFalse(report["qualified"])
        blank = json.loads((path / "plan.json").read_text())
        self.assertTrue(all(item["limit"] is None for item in blank["acceptance"]["criteria"]))

    def test_collector_is_explicit_serial_read_only_and_never_claims_qualification(self):
        self.assertTrue(self.calls)
        for arguments in self.calls:
            self.assertEqual(arguments[1], "-s")
            self.assertTrue(arguments[2].startswith("fixture-"))
            self.assertNotIn("install", arguments)
            self.assertNotIn("input", arguments[:4])

    def test_disconnected_or_missing_adb_retains_failed_capture(self):
        output = self.root / "disconnected"
        with patch("physical_qualification.subprocess.run", side_effect=FileNotFoundError("fixture missing adb")):
            result = capture(output, "fixture-missing", "com.artflow.studio", self.plan["campaignId"], REVISION)
        self.assertFalse(result["captureComplete"])
        self.assertFalse(result["qualified"])
        self.assertTrue(result["errors"])
        self.assertTrue((output / "capture.json").is_file())

    def test_existing_capture_is_not_overwritten(self):
        with self.assertRaises(FileExistsError):
            capture(self.root / "devices/legacy", "fixture", "com.artflow.studio", self.plan["campaignId"], REVISION)

    def test_emulator_and_unattested_hardware_are_rejected(self):
        device = self.campaign["devices"][0]
        device["physical"] = False
        self.rejected("physical-hardware")
        device["physical"] = True
        original = (self.root / "devices/legacy/raw/properties.stdout.txt").read_text()
        self.mutate_capture_output(0, "properties", original.replace("[ro.kernel.qemu]: [0]", "[ro.kernel.qemu]: [1]"))
        self.rejected("virtual-device")

    def test_injected_input_cannot_be_relabelled_as_a_physical_stylus_run(self):
        self.campaign["runs"][3]["inputSource"] = "injected"
        self.rejected("synthetic")

    def test_repository_timing_cannot_satisfy_optical_latency(self):
        measurement = self.campaign["runs"][3]["measurements"][2]
        for method in ("repository_elapsed", "gfxinfo", "frame_timeline", "surface_presentation"):
            with self.subTest(method=method):
                measurement["method"] = method
                self.rejected("not optical latency")

    def test_surface_measurement_needs_actual_canvas_coverage(self):
        del self.campaign["runs"][0]["measurements"][0]["surfaceCoverageEvidence"]
        self.rejected("evidence reference")

    def test_installed_artifact_substitution_fails(self):
        self.write("artifacts/different.apk", self.apk.read_bytes() + b"different")
        self.plan["application"]["artifacts"] = [self.ref("artifacts/different.apk")]
        self.build["apkSha256"] = [self.ref("artifacts/different.apk")["sha256"]]
        self.write_json("build.json", self.build)
        self.plan["source"]["buildEvidence"] = self.ref("build.json")
        self.rejected("Installed APK bytes")

    def test_resource_only_split_is_valid_but_cannot_be_missing_from_the_device(self):
        split = self.root / "artifacts/split.apk"
        with zipfile.ZipFile(split, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"FIXTURE split manifest")
            archive.writestr("resources.arsc", b"FIXTURE resources without classes.dex")
        self.plan["application"]["artifacts"].append(self.ref("artifacts/split.apk"))
        self.build["apkSha256"] = sorted(item["sha256"] for item in self.plan["application"]["artifacts"])
        self.write_json("build.json", self.build)
        self.plan["source"]["buildEvidence"] = self.ref("build.json")
        self.rejected("Installed APK bytes do not equal")

    def test_exact_revision_build_identity_and_clean_source_are_required(self):
        self.plan["source"]["revision"] = "c" * 40
        self.rejected("requested source")
        self.plan["source"]["revision"] = REVISION
        self.build["sourceDirty"] = True
        self.write_json("build.json", self.build)
        self.plan["source"]["buildEvidence"] = self.ref("build.json")
        self.rejected("build identity")

    def test_runtime_version_and_debuggable_flags_are_measured(self):
        self.mutate_capture_output(0, "package", "versionCode=41\nversionName=fixture\nflags=[ DEBUGGABLE ]\n")
        self.rejected("version mismatch")
        self.mutate_capture_output(0, "package", "versionCode=42\nversionName=fixture\nflags=[ DEBUGGABLE ]\n")
        self.rejected("debuggable")

    def test_acceptance_owner_and_prior_approval_are_required(self):
        self.plan["acceptance"]["owner"] = "TBD"
        self.rejected("acceptance owner")
        self.plan["acceptance"]["owner"] = "Fixture owner"
        self.plan["acceptance"]["approvedAtUtc"] = "2026-11-19T10:06:00Z"
        self.rejected("before device collection")

    def test_unapproved_limit_and_nonfinite_limit_fail_closed(self):
        for value in (None, float("nan"), float("inf"), True):
            with self.subTest(value=value):
                self.plan["acceptance"]["criteria"][0]["limit"] = value
                self.rejected()

    def test_missing_required_device_role_cannot_be_waived(self):
        self.plan["acceptance"]["requiredRoles"].remove("arm64_16k")
        self.rejected("coverage")

    def test_missing_or_extra_attempts_fail_closed(self):
        missing = self.campaign["runs"].pop()
        self.rejected("attempts")
        self.campaign["runs"].append(missing)
        repeated = copy.deepcopy(missing)
        repeated["id"] = "retry"
        self.campaign["runs"].append(repeated)
        self.rejected("attempts")

    def test_failed_unknown_and_skipped_workloads_never_pass(self):
        for status in ("failed", "unknown", "skipped"):
            with self.subTest(status=status):
                self.campaign["runs"][0]["status"] = status
                result = self.rejected("incomplete")
                self.assertEqual(result["status"], "FAILED" if status == "failed" else "UNQUALIFIED")

    def test_measured_failure_returns_failed(self):
        self.plan["acceptance"]["criteria"][0]["limit"] = 12
        report = self.rejected("criterion failed")
        self.assertEqual(report["status"], "FAILED")
        self.assertEqual(report["runs"]["android15_phone"]["results"][0]["value"], 13)

    def test_optical_uncertainty_counts_against_acceptance(self):
        self.plan["acceptance"]["criteria"][1]["limit"] = 13.25
        report = self.rejected("optical_latency")
        result = report["runs"]["tablet_active_stylus"]["results"][2]
        self.assertEqual(result["comparisonValueIncludingUncertainty"], 13.5)

    def test_latent_optical_calibration_unknown_fails(self):
        self.campaign["runs"][3]["measurements"][2]["calibration"] = "unknown"
        self.rejected("calibration")

    def test_sample_count_and_duration_are_enforced(self):
        self.plan["acceptance"]["criteria"][0]["minSamples"] = 5
        self.rejected("Insufficient")
        self.plan["acceptance"]["criteria"][0]["minSamples"] = 4
        self.plan["acceptance"]["criteria"][0]["minDurationSeconds"] = 91
        self.rejected("Insufficient")

    def test_raw_file_corruption_and_csv_substitution_are_rejected(self):
        self.write("raw/capture.bin", b"changed bytes")
        self.rejected("changed")
        # Restore the original bytes before testing a different provenance defect.
        self.write("raw/capture.bin", b"FIXTURE ONLY: not a real trace, video, or measurement\n")
        measurement = self.campaign["runs"][0]["measurements"][0]
        measurement["rawEvidence"] = [measurement["samples"]]
        self.rejected("Derived CSV")

    def test_missing_and_changed_references_cannot_pass(self):
        self.campaign["runs"][0]["rawEvidence"] = []
        self.rejected("Raw evidence")

    def test_evidence_paths_cannot_escape_or_follow_symlink(self):
        for relative in ("../outside", "/etc/passwd", "raw/../capture.bin", "raw\\capture.bin"):
            with self.subTest(relative=relative), self.assertRaises(ValueError):
                checked_reference(self.root, {"path": relative, "sizeBytes": 1, "sha256": "a" * 64})
        (self.root / "linked").symlink_to(self.root / "raw", target_is_directory=True)
        with self.assertRaises(ValueError):
            reference(self.root, "linked/capture.bin")

    def test_measured_page_size_and_low_memory_limits_are_required(self):
        self.mutate_capture_output(3, "page_size", "4096\n")
        self.mutate_capture_output(3, "page_size", "4096\n", phase="post")
        self.rejected("16 KB")
        self.mutate_capture_output(3, "page_size", "16384\n")
        self.mutate_capture_output(3, "page_size", "16384\n", phase="post")
        self.plan["acceptance"]["lowMemoryMaxTotalRamMiB"] = 2048
        self.rejected("OS/RAM")

    def test_thermal_cannot_be_omitted_from_long_session(self):
        self.plan["acceptance"]["runs"][4]["criterionIds"].remove("thermal")
        self.rejected("Long sessions")

    def test_long_session_cannot_use_only_a_short_initial_sample(self):
        self.plan["acceptance"]["workloads"][2]["durationSeconds"] = 3600
        self.rejected("Long-session metric coverage")

    def test_post_capture_is_required_and_must_match_runtime(self):
        self.mutate_capture_output(3, "page_size", "4096\n", phase="post")
        self.rejected("runtime or installed artifact changed")

    def test_identity_captures_must_bracket_each_run(self):
        self.campaign["runs"][0]["endedAtUtc"] = "2026-11-19T10:16:00Z"
        self.rejected("timestamps/duration")

    def test_stylus_capability_unknown_or_missing_blocks_campaign(self):
        self.campaign["runs"][3]["stylusChecks"][0]["status"] = "unknown"
        self.rejected("stylus result")
        self.campaign["runs"][3]["stylusChecks"] = []
        self.rejected("stylus checks")

    def test_observed_stylus_failure_is_a_failure_not_unknown(self):
        self.campaign["runs"][3]["stylusChecks"][0]["status"] = "failed"
        self.assertEqual(self.rejected("physical stylus check failed")["status"], "FAILED")

    def test_environment_and_post_run_review_are_required(self):
        del self.campaign["runs"][0]["environment"]["powerMode"]
        self.rejected("conditions")
        self.campaign["runs"][0]["environment"]["powerMode"] = "Fixture"
        self.campaign["review"]["reviewedAtUtc"] = "2026-11-19T10:00:00Z"
        self.rejected("Review predates")

    def test_unreviewed_campaign_is_not_qualified(self):
        self.campaign["review"]["decision"] = "unknown"
        self.rejected("Named reviewer")

    def test_stale_plan_reference_is_rejected(self):
        self.plan["acceptance"]["owner"] = "Another fixture owner"
        self.write_json("plan.json", self.plan)
        report = verify(self.root / "plan.json", self.root / "campaign.json", REVISION)
        self.assertFalse(report["qualified"])
        self.assertIn("changed", report["problems"][0])

    def test_approval_is_bound_to_exact_criteria_not_just_an_owner_name(self):
        self.plan["acceptance"]["criteria"][0]["limit"] = 1000
        self.write_json("plan.json", self.plan)
        self.campaign["plan"] = self.ref("plan.json")
        self.write_json("campaign.json", self.campaign)
        report = verify(self.root / "plan.json", self.root / "campaign.json", REVISION)
        self.assertFalse(report["qualified"])
        self.assertIn("Owner approval does not cover", report["problems"][0])

    def test_review_is_bound_to_exact_observations(self):
        self.campaign["runs"][0]["environment"]["brightnessPercent"] = 75
        self.write_json("campaign.json", self.campaign)
        report = verify(self.root / "plan.json", self.root / "campaign.json", REVISION)
        self.assertFalse(report["qualified"])
        self.assertIn("Reviewer decision does not cover", report["problems"][0])

    def test_duplicate_json_keys_are_rejected(self):
        self.write("campaign.json", '{"schemaVersion":1,"schemaVersion":1}\n')
        report = verify(self.root / "plan.json", self.root / "campaign.json", REVISION)
        self.assertFalse(report["qualified"])
        self.assertIn("Duplicate JSON", report["problems"][0])


class PhysicalSamplesTest(unittest.TestCase):
    def test_percentile_definition_is_nearest_rank(self):
        self.assertEqual(statistic(list(range(1, 101)), "p95"), 95)
        self.assertEqual(statistic([1, 2, 3, 4], "p50"), 2)

    def test_bad_samples_fail_closed(self):
        invalid = ["1,0,nan\n", "1,0,inf\n", "1,0,-1\n", "1,0,0\n", "1,0,1\n1,1,2\n",
                   "1,2,1\n2,1,2\n", "1,0,1,extra\n", "1,0\n"]
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "samples.csv"
            for rows in invalid:
                with self.subTest(rows=rows):
                    path.write_text("sample_id,elapsed_seconds,value\n" + rows)
                    with self.assertRaises((ValueError, TypeError)):
                        samples(path, "input_to_photon_ms")

    def test_thermal_status_is_ordinal_not_arbitrary_temperature(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "samples.csv"
            path.write_text("sample_id,elapsed_seconds,value\n1,0,38\n")
            with self.assertRaises(ValueError):
                samples(path, "thermal_status")


if __name__ == "__main__":
    unittest.main()
