# Physical device, stylus and performance qualification

**Current disposition: OPEN / UNQUALIFIED.** No physical device, actual pressure/tilt pen,
long-session thermal campaign, or optical input-to-photon measurements were available for this
implementation. The Python tests use explicitly synthetic files to test the validator. They are
not campaign evidence. Existing emulator/JVM timing results do not close this gate.

This protocol implements the physical performance portion of
[release sign-off](../release/RELEASE_SIGNOFF.md#external-release-gates). The independent signed
artifact, upgrade/data-preservation, complete emulator matrix and publisher/store gates still
apply. A physical report covers only its exact installed APK set, devices and approved workloads.
It always reports `publisherSignatureQualified: false` and `releaseApproved: false` because those
decisions belong to their separate gates. It also reports `provenanceAuthenticated: false`:
local record validation does not authenticate the origin of a measurement or a person's identity.

## What the tool does

Use [.github/scripts/physical_qualification.py](../../.github/scripts/physical_qualification.py)
with Python 3.10+ and Android Platform Tools on the measurement host. It reuses APK inspection and
hashing from the existing installed-APK provenance tool; it does not build or install an app.

| Command | Result |
| --- | --- |
| `init` | New incomplete plan/campaign forms with an attempt UUID; all acceptance limits, owners and observations remain empty. |
| `capture` | Read-only adb collection against one explicit serial, including installed APK snapshots and command outputs. Never runs a workload or injects input. |
| `reference` | A relative path, exact byte length and SHA-256 record for a retained file. |
| `approval-digest` | Canonical digest for a named owner's approval of the exact plan. Does not approve it. |
| `review-digest` | Canonical digest for a named reviewer's decision on the completed observations. Does not accept them. |
| `verify` | Hash/provenance, measured coverage and acceptance validation; creates a new JSON report and exits nonzero for absent, unknown or failing evidence. |

`captureComplete: true` means identity files were collected, not that physical qualification
succeeded. Every capture explicitly says `qualified: false`. Existing capture directories and
verification outputs cannot be overwritten by these commands.

The verifier checks the consistency of submitted records, measured values, hashes and named
decisions. It cannot establish that a raw recording came from the claimed hardware, that a named
person made the decision, or that a submitted wall-clock timestamp is genuine. A well-formed
synthetic dataset can satisfy those structural checks, as the explicitly synthetic positive unit
fixture demonstrates. The observer and reviewer must independently establish the actual physical
observations, measurement derivation and approval trail. A qualified report is conditional on that
real provenance; generating local records or hashes cannot replace it.

## 1. Define and approve the campaign before collection

Run from the repository root, choosing an evidence directory outside tracked source:

```bash
python3 .github/scripts/physical_qualification.py init --output /path/to/new-campaign
```

Fill `plan.json` using actual release-owner decisions. Preserve the generated `campaignId`.
The source record needs the full 40-character revision and Git tree, the build/run identity, and
a retained build-produced JSON record. The application needs its package, version, actual
release/non-debuggable/minified settings, and every APK in the approved install set. Copy those
APK bytes into the campaign; `reference` computes their records. Both single APKs and base/split
sets are supported, including resource-only splits. One campaign accepts one exact install set;
different device-specific sets require separately bound campaigns or the same approved universal
APK. Do not substitute a rebuilt APK even if its version number matches.

The build-produced identity JSON must contain:

| Field | Required value |
| --- | --- |
| `schemaVersion` | `1` |
| `revision`, `gitTree`, `buildId` | Exact source/build identity matching the plan |
| `sourceDirty` | `false`, supported by the build's retained provenance |
| `packageName`, `versionCode`, `versionName` | Actual application identity |
| `buildType`, `debuggable`, `minified` | `release`, `false`, `true` |
| `apkSha256` | Sorted SHA-256 list of the complete approved install set |

Retain the actual build job's evidence supporting that record. This script cannot recover a Git
revision or verify publisher ownership from APK bytes alone. The signed-artifact gate must bind
its accepted APK/AAB and signer to the same build. After Play re-signing or any other artifact
change, the former physical report applies only to the formerly tested bytes.

The template preserves the existing required device roles:

| Role | Evidence required |
| --- | --- |
| `android8_low_memory` | Measured API 26/27, RAM at or below an explicitly approved `lowMemoryMaxTotalRamMiB`, and the low-memory workload |
| `android15_phone` | Actual phone with measured API 35 |
| `android16_phone` | Actual phone with measured API 36 |
| `tablet_active_stylus` | Actual tablet, identified physical active pen, pressure/tilt/palm observations and optical latency |
| `arm64_16k` | Physical ARM64 device, API 35+, measured 16,384-byte system pages and installed primary ABI `arm64-v8a` |

One device may satisfy multiple roles if its measured identity supports them. Reusing its device
ID is required; relabelling one serial as multiple devices is rejected. The owner should expand
the template's minimum matrix to the supported hardware/workloads they intend to claim.

Each workload defines exact canvas width/height, layers/frames, brush identity and settings,
starting artwork, repeatable steps, warmup duration and measured duration. Hash the starting
artwork and brush-settings files. All five workload tags—drawing, large canvas, long session,
low memory and stylus—must be exercised. Every planned run needs frame-presentation and memory
criteria; long sessions also need thermal evidence spanning the full approved session. Separate
criterion IDs can use different sample counts/durations for short and sustained workloads.

Every criterion defines its metric/unit, exact measurement boundaries and protocol, comparison,
limit, statistic, minimum samples and minimum observed time. There are **no default acceptance
limits or session lengths**. The generated p95/max fields are editable proposed statistics, not
accepted performance targets. The original [plan](../../agent.md#performance-targets) lists
60 FPS, brush latency below 20 ms, startup below two seconds and typical-canvas memory below
500 MB as development targets. Do not silently weaken an already agreed criterion or reinterpret
those values: p95 frame intervals are not minimum FPS, optical latency is not a repository-call
timer, and MB is not MiB. The owner must record operational definitions and preserve the actual
agreed bounds. Startup and field crash-rate targets are outside this particular validator.

Fill `acceptance.owner` and `approvedAtUtc` with the actual person's decision and UTC time.
Compute the plan digest:

```bash
python3 .github/scripts/physical_qualification.py approval-digest --plan /path/to/new-campaign/plan.json
```

The owner retains an approval JSON with `schemaVersion: 1`, `owner`, `approvedAtUtc`, and
`planSha256` from that command. Record the file using `reference` and put its record in
`acceptance.approvalEvidence`. The digest covers the entire plan except that single self-reference,
avoiding a circular hash. Changed source, APKs, thresholds, workloads or timestamps invalidate
the approval. Keep the actual approval trail with the campaign; generating a digest does not
grant approval. Collection cannot precede the approved time.

```bash
python3 .github/scripts/physical_qualification.py reference --root /path/to/new-campaign --file approval.json
python3 .github/scripts/physical_qualification.py reference --root /path/to/new-campaign --file plan.json
```

Place the final plan reference in `campaign.json`'s `plan` field. All references use paths relative
to that evidence root; their exact bytes and size are rechecked. Missing, empty, changed, linked
or path-escaping evidence fails validation. Empty stderr files are permitted only in raw command
capture records.

## 2. Capture each actual device before and after its runs

Install the already verified release artifact using the separate release procedure. Record the
physical device and stylus model, the operator, and a photo/video or lab record that ties the
physical apparatus to its adb serial and campaign ID. A hardware-looking `getprop` string alone
does not prove a physical device. Emulator indicators are rejected, but positive physical
observation still requires a reviewer.

```bash
python3 .github/scripts/physical_qualification.py capture \
  --output /path/to/new-campaign/devices/tablet-before \
  --serial ACTUAL_ADB_SERIAL \
  --campaign-id UUID_FROM_PLAN \
  --revision FULL_40_CHARACTER_REVISION
```

After all that device's declared runs, repeat with a new `tablet-after` output directory. Capture
records retain command arguments, exit status, stdout/stderr and SHA-256 for `getprop`, `getconf
PAGE_SIZE`, `/proc/meminfo`, package manager inventory, display/input/battery/thermal dumps, and
every installed APK pulled from `pm path`. The verifier rederives OS, fingerprint, hardware/model,
device ABIs, installed primary ABI, version, debuggable flags, RAM and page size from those raw
outputs. Both captures must identify the same runtime and exact approved APK set, and must bracket
every run on that device.

`getconf PAGE_SIZE` measures the system page size, as documented by
[Android's 16 KB guidance](https://developer.android.com/guide/practices/page-sizes#test).
The package manager's `primaryCpuAbi` describes the installed package ABI. These records alone
do not prove that every native library loaded or every workflow ran; keep the native/runtime
test evidence separately. Permission denial, disconnected adb, missing package/ABI, timeout or
failed APK pull remains an incomplete campaign. Do not turn failed commands into success.

Each `devices` entry in `campaign.json` needs `id`, actual `physical: true`, `operator`,
`physicalEvidence`, `formFactor` (`phone` or `tablet`), the before `capture` reference and
the `postCapture` reference. A stylus device also needs a `stylus` object with actual manufacturer,
model and an `evidence` reference. Never use the synthetic test fixtures as device observations.

## 3. Measure the approved work, retaining original captures

The collector is deliberately not a benchmark: an operator must perform the actual campaign.
Use the real release app, starting artwork and pen/touch movements defined by the approved plan.
Record every declared attempt, including failure, interruption, unsupported sensor or unknown
result. Injected MotionEvents, `adb input`, test-runner gestures and stylus-looking event structs
cannot qualify physical pen behavior or optical latency.

| Metric | Accepted method and required interpretation |
| --- | --- |
| `present_to_present_ms` / `presented_fps` | Actual app surface presentation or calibrated external video. Retain the exact GL surface/layer identity and proof that the capture covers it. For FPS, define the counting window; a whole-run average cannot establish minimum FPS. |
| `input_to_photon_ms` | High-speed `optical_video` or `photodiode` apparatus. Define the physical input/contact trigger and the first displayed ink response; retain original recording/sensor data, calibration, annotation/extraction and measurement uncertainty. |
| `total_pss_mib` | `dumpsys_meminfo` or `perfetto_process_memory`, explicitly identifying the app process and TOTAL PSS. Convert retained KiB to MiB consistently. PSS is neither Java heap nor total device memory. |
| `thermal_status` | Actual `thermalservice` or `thermal_api` observations on API 29+, with the named status source; values are ordinal Android statuses 0–6. |
| `skin_temperature_c` | A named, calibrated `external_temperature_probe` with sensor location and ambient conditions. A battery temperature must not be relabelled as skin temperature. |

The GL canvas makes frame provenance significant. Android warns that its normal UI timing data
can miss OpenGL rendering, even when `gfxinfo` returns output
([Android rendering guidance](https://developer.android.com/topic/performance/vitals/render)).
The [Perfetto FrameTimeline documentation](https://perfetto.dev/docs/data-sources/frametimeline)
also explicitly says SurfaceViews are not supported by that data source. Consequently, a generic
UI FrameTimeline/HWUI report cannot qualify this app's `GLSurfaceView` canvas. For supported
devices, Android documents app-layer present-to-present/FPS data through
[SurfaceFlinger timestats](https://developer.android.com/games/optimize/vitals/slow-session).
Retain its raw layer attribution and exact capture window; a binned histogram does not recover
exact per-frame timestamps or arbitrary percentiles. Use a suitable verified surface capture or
external video where the required metric cannot be derived exactly. Missing coverage stays unknown.

The accepted `surface_presentation` method requires `surfaceName` and a hashed
`surfaceCoverageEvidence` record in addition to the raw trace and analysis. The generic method
name does not prove that coverage; the reviewer must inspect the original evidence. Repository
elapsed calls, render CPU duration, frame presentation and physical input-to-photon are different
measurements. Only the two optical methods may supply the latency metric.

For memory/thermal protocols, see [Android dumpsys](https://developer.android.com/tools/dumpsys)
and [PowerManager thermal status](https://developer.android.com/reference/android/os/PowerManager#getCurrentThermalStatus()).
On Android 8, unavailable thermal status must be recorded as unavailable and an approved external
sensor used when thermal evidence is required. Initial/final dumps alone are not long-session
thermal evidence. Capture samples over the approved duration, with display refresh, brightness,
ambient temperature, battery start/end, charging state and power mode. Preserve collection errors,
crashes, ANRs, cancelled sessions and the complete raw logs.

### Observation schema

Each `runs` entry must have the exact planned run `id`, its `deviceId`, `inputSource`, `status`,
`startedAtUtc`, `endedAtUtc`, `rawEvidence`, `environment`, and `measurements`. Use `completed`
only after completing all prescribed actions; use `failed` or `unknown` otherwise. Timestamps
include warmup; the tool subtracts the approved warmup when checking measured duration. Each
measurement's elapsed time starts at the beginning of the measured window after warmup.

`environment` requires numeric `ambientTemperatureC`, `displayRefreshHz`, `brightnessPercent`,
`batteryStartPercent`, `batteryEndPercent`, boolean `charging`, explicit `powerMode` and a nonempty
`evidence` reference list. Retain actual configuration/observations rather than copying settings
from the plan.

Every item in `measurements` has `id` equal to its planned criterion ID, `unit`, `method`, `scope`,
nonempty `rawEvidence`, `analysisEvidence` and `samples` references. `scope` identifies the exact
surface, process, sensor, or physical endpoints. Optical latency also needs positive
`uncertaintyMs` and `calibration` details. Keep the extraction script, query, annotations and
calibration in the analysis record so another reviewer can reproduce the CSV from the raw data.
The CSV cannot also serve as its own original capture. Its exact header is:

```csv
sample_id,elapsed_seconds,value
```

Add only observed samples. Sample IDs must be unique; elapsed seconds must be finite,
nonnegative and monotonically ordered; values must be finite and use the declared unit.
Unsupported, missing or invalid observations must not become zero. Durations/PSS must be positive;
zero FPS and Android thermal status zero are valid observations with their normal meanings.
Minimum sample count and first-to-last observed duration are both enforced. Long-session metric
coverage must span at least the approved long-session duration. No interpolation, extrapolation,
outlier removal, warmup relabelling or histogram expansion is performed by the verifier.

The statistics are `min`, `max`, arithmetic `mean`, and nearest-rank `p50`, `p95`, `p99`:
the sorted sample at `ceil(percentile × count / 100)`. Optical uncertainty is included
conservatively: upper-bound criteria compare `statistic + uncertaintyMs` to the limit; lower-bound
criteria compare `statistic - uncertaintyMs`. A result on the wrong side of an approved strict
inequality fails even if its rounded display would appear to meet the target.

Every physical stylus run also needs `stylusChecks`, each with `id`, `status`, `expected`,
`observed` and nonempty raw `evidence`. At minimum, include `pressure`, `tilt` and `palm_rejection`.
Record barrel-button/hardware-eraser behavior when present in the supported pen/workload contract.
Physical checks require actual observations and a `passed` status; failed/unknown checks do not
qualify. Synthetic device tests remain valuable engineering evidence but cannot fill these records.

## 4. Review, validate and retain all outcomes

The actual reviewer checks physical provenance, artifact identity, full workload execution,
GL-surface coverage, optical endpoints/calibration, sampling completeness, metric extraction,
stylus behavior and every failure. Fill `review.reviewer`, `review.reviewedAtUtc` and
`review.decision` with that person's real decision. The review time must follow all measured runs
and completion of every post-run identity capture, including its installed APK snapshots.

```bash
python3 .github/scripts/physical_qualification.py review-digest --campaign /path/to/new-campaign/campaign.json
```

The retained review JSON contains `schemaVersion: 1`, `reviewer`, `reviewedAtUtc`, the actual
`decision`, and `campaignSha256` from that command. Put its file reference in `review.evidence`.
The digest excludes only that self-reference. Reusing a stale review after changing observations
is rejected. Hash binding detects changes; it does not cryptographically prove a person's
authority or that a video shows the claimed apparatus. Preserve the real approval/review trail
and use independent human inspection. This is an evidence validator, not a hardware attestation
service or a video-analysis engine.

```bash
python3 .github/scripts/physical_qualification.py verify \
  --plan /path/to/new-campaign/plan.json \
  --campaign /path/to/new-campaign/campaign.json \
  --revision FULL_40_CHARACTER_REVISION \
  --output /path/to/new-campaign/verification-01.json
```

| Status | Meaning |
| --- | --- |
| `UNQUALIFIED` / exit 1 | Required evidence, approval, coverage or measurement is missing, inconsistent, unknown or unreviewed. Blank forms produce this result. |
| `FAILED` / exit 1 | A retained workload or measured acceptance criterion failed. Any additional missing evidence remains listed as a problem. |
| `QUALIFIED_FOR_RECORDED_PHYSICAL_CAMPAIGN` / exit 0 | The exact recorded physical campaign met its approved criteria and passed evidence review. Other release gates remain open. |

Every planned attempt must appear exactly once. Missing/extra attempts, failed/unknown states and
skips cannot be dropped to obtain a pass. Archive the full failed campaign before an explicitly
approved new campaign; retain links to earlier outcomes in the approval trail. An edited manifest
cannot prove that undisclosed attempts never happened, so the reviewer must also inspect the lab's
continuous attempt log. The tool never retries or deletes evidence.

Archive the complete evidence root, original recordings, extraction/analysis materials, build
provenance, approvals, reports, tool revision and lab attempt log. Keep device identifiers and
artwork in the authorized release evidence location. Source publication alone does not change the
open physical or publisher release decision.

## Local validator verification

```bash
python3 -m unittest discover -s .github/scripts -p 'test_physical_qualification.py'
```

The suite covers coherent synthetic campaigns and rejects missing/changed artifacts, wrong source,
dirty-build identity, unknown/emulated hardware, ABI/page-size/version mismatches, stale approvals,
synthetic stylus input, software timers substituted for optical latency, omitted roles/attempts,
bad sample counts/durations, non-finite data or derived arithmetic, missing raw evidence, path
escape/symlinks, broken output links, unaccounted optical uncertainty, failed measurements,
premature review timestamps and stale review decisions. Those checks
validate the tooling's behavior; none establishes physical performance of ArtFlow.
