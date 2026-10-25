# Emulator-driven parity follow-through — 24 October 2026

This change addresses observable Smudge, clipboard and 3D-material gaps. **It does not establish
complete Procreate feature or quality parity.** Android emulator correctness, native archive
compatibility, physical drawing performance and artist acceptance are separate requirements.

## Starting revision and retained failures

[Android CI run 315](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40134242551)
tests `9b868dcb5a143b4612df3e771fe02cdef3c996d5`, with 256 declared Android identities per lane.
Its build job passed. The two Android 15 / 16 KB jobs failed with incomplete coverage:

| Lane | Passed | Empty process-crash failure | Missing identities |
| --- | ---: | --- | ---: |
| Main Android 15 / 16 KB | 59 | `CanvasInputDeviceTest#asynchronousShapeStaysBoundToItsOriginalLayer` | 196 |
| Independent Android 15 / 16 KB repeat | 43 | `CanvasInputDeviceTest#changingAlphaLockDuringAnOpenToolDiscardsTheStaleEdit` | 212 |

The other five required lanes completed successfully: API 26 recorded 254 passes and its two existing
OS-compatibility skips; API 35 / 4 KB, API 36 / 4 KB, API 36 / 16 KB and API 36 tablet each recorded
256 passes. Their complete inventories and minified launches passed. This leaves run 315 failed
overall; successful lanes do not replace the two incomplete required lanes.

Both original captures identify the app's HeapTaskDaemon, SIGSEGV at address `0xc`, and frame zero
`art::gc::collector::MarkCompact::ScanObject<true>+36`, PC `0x641f94`, in ART Build ID
`e4a3c6c284a8b17d09fd84d661cf181b`. The fault signature is not an application assertion or a proven
root cause. In the main lane, the libc signal at 21:53:49.937 UTC precedes the failed-case start at
21:53:51.255; the tombstone appears inside that case's later capture. The existing classifier
therefore does not establish the required case-window attribution and refuses automatic recovery.

The unmodified original ZIPs were independently checked against GitHub's digests:

- Main artifact `12577140067`: 4,181,159 bytes,
  SHA-256 `c5f05d7d19241037e08931bf6e5b4565838c2012cc088e352b5290fec5845b2c`.
- Repeat artifact `12576379609`: 3,803,361 bytes,
  SHA-256 `35483c725944c18a848cfbf66da337c3320afd0ea3dcfab94a9ae83b750d2dbd`.

No required lane, assertion, allowed-skip list, runtime setting or recovery predicate was changed
for the application improvements below. The earlier [official repair investigation](ANDROID15_OFFICIAL_REPAIR_REVIEW_2026-10-24.md)
remains relevant; these changes do not repair Android's runtime.

## Smudge: selected brush response and continuous input

Previously, Smudge copied the selected tip and size but dropped calibrated pressure before the pixel
engine. It also placed one patch per input event, so a fast movement could leave a large unstamped
middle. Its visible opacity slider edited the Paint brush even when an independent Smudge brush
was selected.

The sampler now carries distance between events, interpolates pressure/tilt/orientation, and includes
the final release sample. It uses the existing ArtFlow size, opacity, flow and tilt responses. A shared
patch buffer and size-proportional spacing bound allocations and work for large brushes; invalid or
extreme input cannot create an unbounded sampling loop. Selection coverage, alpha lock, provisional
editing, cancellation and the existing undo transaction continue to apply. Touch uses the existing
touch-specific brush response. Both opacity controls and their ranges follow the selected tool.

The regression set covers low/zero pressure, opacity/flow zero, sparse versus dense input, historical
and final samples, orientation, release endpoints, large-brush work, selection/alpha lock, cancellation,
undo/redo, signed storage and reopen. The real editor test changes the Smudge opacity control and
checks that Paint's independent value remains unchanged.

This is a correction to ArtFlow's documented brush behavior, not a reconstruction of Procreate's
wet-paint equations, full Brush Studio dynamics or physical stylus response.

Large-brush Smudge still performs CPU work synchronously on the UI thread. The fractional-sampling
follow-up below includes a paired desktop regression measurement. Maximum-canvas responsiveness,
physical pen latency and thermal/frame-budget behavior remain open quality requirements.

## Clipboard: prepare first, commit once

The previous Cut & Paste erased source pixels before checking whether a new layer could be added.
Paste also inserted its layer and pixels as separate history changes.

The repository now prepares all buffers, checks capacity when inserting a layer and verifies the
original target before a single non-suspending commit. Cut, Paste, Copy & Paste and Cut & Paste each
form one undo action. Basic Cut also retains the previous clipboard when a lock or a concurrent
source/selection change prevents its commit. Insertion into a full 1,024-layer project, or an empty
selection, locked cut source, provisional edit or stale target leaves the document and previous
clipboard intact. Basic Cut remains available at capacity. Fractional selection coverage and the
source's existing metadata remain intact. Clipboard pixels carry their material channel; Copy Merged
explicitly produces color artwork.

JVM coverage checks pixels, layer identity, active layer, undo/redo depth, history marks and previous
clipboard content. Emulator coverage exercises actual signed storage, a fresh repository, independent
Android PNG decoding, and failure at the real project layer limit without changing saved bytes.

## 3D: independently paintable material maps

Color, Metallic and Roughness now have independent persisted paint layers over the existing UV atlas.
The Model window exposes channel selection and a scalar Paint value. The current tip, pressure and
opacity place neutral data values into the selected scalar channel. Provisional maps reach the live
GL renderer; cancellation and undo restore both their pixels and visible material response.

- Scalar layers retain masks, group visibility/opacity, frames, duplication, clipboard behavior and
  history. Ordinary color composition and color exports exclude their grayscale data.
- Trim Transparent includes visible material content across all frames when calculating the crop,
  so a sparse or empty Color plane cannot discard separately painted scalar maps. Opaque zero-valued
  material texels count as content, and undo restores the original dimensions and maps.
- Working-color profile changes do not transform scalar material values.
- `.artflow` version 6 stores channel identity. Default Color metadata is omitted so older signed
  representations can be verified before migration; pre-v6 material metadata is rejected. Older
  readers reject v6 instead of mistakenly painting material data into the color image.
- Cross-channel merges and flattening refuse operations that would lose independent maps.
- OBJ ZIP export includes color plus available `metallic.png` and `roughness.png`, with `map_Pm` and
  `map_Pr` MTL references. Transparent map coverage resolves against fixed defaults: metallic 0 and
  roughness 0.6. Scalar PNG data is not color-profile converted. Cropping or fitting that would break
  UV alignment is rejected.
- Other rendered formats clearly report that they contain color artwork. Editable material layers
  remain available in the ArtFlow artwork package.

Core, repository and codec tests cover channel composition, historical checksums, migration, masks,
groups, profile independence, frames, duplication, exports and refusal of destructive merges. Device
tests exercise real Model controls and GL pixels, provisional cancellation, undo/redo, actual document
storage, fresh reopen, and independently decoded exported maps.

### Material boundaries

Each stroke paints one selected channel; simultaneous multi-channel strokes and per-brush material
dynamics are not implemented. This remains a single-atlas implementation. Existing OBJ/glTF/USD
imports retain base color but do not newly decode imported metallic/roughness textures. Original material sets are not independently
preserved. The renderer uses approximate diffuse/specular lighting, not a physically accurate BRDF
or Procreate's renderer. Receiving applications must support the optional PBR MTL extensions; export
reports this limitation. Native `.procreate` materials, glTF/USDZ material export, normal maps,
environment lighting, and physical-device/reference-app comparisons remain open.

Existing canvas crop and anchored resize operations transform all texture planes together but do
not rewrite stored model UV coordinates. Full-atlas resampling preserves normalized placement;
cropping or resizing without resampling can change where paint appears on the model. The export
guards preserve agreement with the current preview, not the model's appearance before a canvas crop.

## Verification

Before the initial feature commit `086ebd8`, the complete JVM suite passed **1,563 tests in 259 suites**, with no failures,
errors or skips. This includes the pinned native brush corpus and genuine Procreate document tests.
The 93 CI-verifier tests and 63 reference-tool tests also passed (**156 Python checks**).
The complete Gradle gate passed: debug and Android-test compilation, `ktlintCheck`, `detekt`,
`lintDebug`, `lintRelease`, `assembleDebug`, `assembleDebugAndroidTest` and `bundleRelease`.
Debug lint reports 154 warnings and five hints; release lint reports 152 warnings and five hints.
These include one new advisory suggesting a KTX bitmap helper; both variants report no errors.
The debug APK and release AAB also passed the repository's package/native-alignment verifier.

Run 317's source `8ce26b9` contains **264 Android identities per lane**, retaining all 256 original
identities and adding eight executable device cases. Its required seven-lane Actions run must account for
1,848 outcomes, allowing only the two existing API 26 compatibility skips. Compiled instrumentation
alone is not evidence that its tests executed; the final run and original artifacts remain the
acceptance source.

### Run 316: concurrency-test scheduling failure

[Run 316](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40139301647) tested
`086ebd82eca5e1d042407a0cf86c4b1ab4a0e43c`. Its JVM gate ran all 1,563 tests: 1,561 passed and
two clipboard concurrency tests failed. Both failed at their initial assertion that Cut was still
pending, before the concurrent mutation or the stale-target safety assertion. The eight-pixel
background preparation could finish before `withContext` suspended; queuing the Main dispatcher
alone did not establish the intended interleaving. The entire emulator matrix was skipped.

The original report ZIP, artifact `12578830106`, has SHA-256
`c850cba36ff4d6c5f2121b05bb834367f4508a7fd2a9a067a66f94c94d152ac3` and confirms two failures,
zero errors and zero skips across 259 suites. The exact Actions source ZIP, artifact `12578034492`,
has SHA-256 `304119b8f939c01248f213f718f9733452fb5a9cad1dee76547616118d9091e9`; its revision
and full 264-case inventory were independently verified. Run 316 supplies no device-execution or
minified-launch evidence.

The follow-up injects the clipboard preparation dispatcher through an internal constructor, while
the public injected constructor continues to use `Dispatchers.Default`. The two tests queue the
worker itself, change the source or selection, and then explicitly release preparation. The test
identities and all stale-target, source-pixel, history and previous-clipboard assertions remain.
No production validation predicate, workflow gate or skip rule was changed.

The follow-up's complete 1,563-test JVM suite passed locally with `-XX:ActiveProcessorCount=2`,
with zero failures, errors or skips. Hilt/application compilation, Android-test Kotlin compilation,
`ktlintCheck` and `detekt` also passed.

### Run 317: corrected-source acceptance

[Run 317](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40140188018) tests
`8ce26b95d981c82630730d2e5765da87bcbbaaaa`. Its build job passed all 1,563 JVM tests in 259 suites,
with zero failures, errors or skips, and all 156 Python checks. The signing rejection, pinned
reference corpus and native-contract checks, ktlint, Detekt, both lint variants, debug APK,
release AAB and package/native-alignment checks passed. The lint counts remain 154 warnings and
five hints for debug, and 152 warnings and five hints for release, with zero errors.

The original build report ZIP, artifact `12578582861`, is 788,479 bytes with SHA-256
`e1cd366d08d5020bfdf7f2abef14d35c5393e26dc4c7052a89e57c412b3cb2a3`. Its raw JUnit XML and package
preflight were independently checked. The source ZIP, artifact `12579000614`, is 2,073,644 bytes
with SHA-256 `f9bc6d20381039f1d34c83f8cc4edf84c25b909922e84daa59aaa89cb510201f`; its exact revision,
264 declared device identities and retention of all 256 baseline identities were verified.

The completed matrix has **1,625 passes, one empty process-crash failure, two permitted skips and
220 missing identities** out of 1,848 required outcomes. No unexpected identity is present. Run 317
is failed overall because one required lane is incomplete.

| Lane | Passed | Failed | Skipped | Missing | Minified launch |
| --- | ---: | ---: | ---: | ---: | --- |
| API 26 / 4 KB | 262 | 0 | 2 | 0 | Pass |
| API 35 / 16 KB main | 43 | 1 | 0 | 220 | Not reached |
| API 35 / 4 KB | 264 | 0 | 0 | 0 | Pass |
| API 36 / 4 KB | 264 | 0 | 0 | 0 | Pass |
| API 35 / 16 KB independent repeat | 264 | 0 | 0 | 0 | Pass after recovery |
| API 36 / 16 KB | 264 | 0 | 0 | 0 | Pass |
| API 36 tablet / 4 KB | 264 | 0 | 0 | 0 | Pass |

Each passing lane's raw XML accounts for all 264 source identities, its measured API/page size matches
the requested configuration, and its minified launch passes. The two API 26 skips remain the existing
cancelled-pointer flag and scoped MediaStore cases. Five lanes pass without
recovery; the independent API 35 / 16 KB repeat has the preserved recovery described below.

The main Android 15 / 16 KB lane failed after 43 passes, one empty process-crash failure and 220
missing identities. Its named failure is
`CanvasInputDeviceTest#centeredPinchChangesViewWithoutUndoOrPainting`. The app's PID 4588,
HeapTaskDaemon TID 4593 records SIGSEGV at `0xc` at 23:12:34.157 UTC, before that case starts at
23:12:35.084; the ART tombstone arrives at 23:12:35.204. Frame zero, PC and ART Build ID match the
retained run-315 signature. This remains crash evidence, not an application assertion or a proven
root cause. The unchanged strict classifier rejects recovery because the signal is outside the
named case's window. No retry occurred, and runtime-test/minified-launch evidence is missing.

The independent repeat's first attempt records five passes, one empty failure and 258 missing
identities at `AppLaunchTest#activityLaunchesAndReachesResumed`. That case starts at 23:10:10.160 UTC;
the app's PID/TID 4668 signals SIGSEGV at `0x40` at 23:10:10.809, with its matching tombstone at
23:10:11.363. Frame zero is `ClassLinker::ResolveMethod+1057`, PC `0x328cd1`, in the same ART Build ID.
The unchanged exact-source classifier accepts this case-window attribution. One recovery is recorded
with changed boot IDs, followed by all 264 passing cases. The first attempt remains in the artifact;
unrelated Google Play services crashes are excluded. This is a final pass after recovery, not a clean
first attempt or an established runtime repair.

All seven original device ZIPs were checked against GitHub's recorded sizes and SHA-256 digests:

| Lane | Artifact ID | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| API 26 | `12578669697` | 3,420,984 | `442697aa3c95ce6c89cd92bff061cbc51ed969cf630dafae0ccfe700231fc348` |
| API 35 / 16 KB main | `12578868623` | 3,719,290 | `ae7008758579fa8b6233c5341380b348b849c3f64a02daef3f7e0a86802eaa9d` |
| API 35 / 4 KB | `12578719349` | 10,624,935 | `fb36303d17726948cd26080f5a2546ad9eef6f4c7eec35cdec88ba5c78e1f9e2` |
| API 36 / 4 KB | `12579575002` | 8,554,574 | `068e9e74be02631fda76a41cfa5d9c7eb8ea1349d54c771b8ad4b4805673055b` |
| API 35 / 16 KB repeat | `12580081133` | 12,432,238 | `e16b1eff757882508b0b1740052d6447ede5b0df13f56f91c932fee903b6be8b` |
| API 36 / 16 KB | `12579785109` | 9,271,480 | `f254ec70f5efc7dbac776a9ae4b4261174a83e6e7695a43c264f2d7ca8f6e983` |
| API 36 tablet | `12579193390` | 12,541,733 | `fa147998469f34e18b3633a8e3fc9986da47ca27721478fe1694077bc1565891` |

All eight new feature cases pass in each of the six final passing lanes. The failed main lane reaches
only the new batched-Smudge case; its other seven additions are missing. Original Smudge and opacity
captures were visually reviewed on API 26 and the standard API 35/36 lanes. Original material captures
were reviewed on the standard API 35, API 36 / 16 KB and tablet lanes: the provisional metallic spot
appears, cancellation removes it, commitment retains it, and the new channel controls remain readable.

The API 36 material lanes' independently decoded exports match every one of the 1,024 scalar texels
against reopened alpha coverage and fallback values: metallic defaults to 0, roughness to 153/255,
with painted centers 255 and 64 respectively. Their color texture stays unchanged, and the OBJ archive
retains both map files and MTL references. Independent clipboard PNG decoding on API 26, standard
API 35/36 and the 16 KB repeat confirms the expected 16 × 8 opaque-red composite; the passing actual
storage/capacity tests verify the original and pasted layers plus preservation at 1,024 layers.

These executed feature checks do not replace the failed required lane. Run 317 does not establish
complete emulator acceptance or full Procreate parity.

### Follow-up: fractional Smudge placement

Reviewing run 317's original images exposed a quality gap that its existing within-device assertions
did not detect. API 26's continuous Smudge capture differs from the identical API 35 / 4 KB and
API 36 / 4 KB captures at 839 pixels, with a one-pixel change in horizontal extent. Their source and
batched-Smudge captures are identical. Each lane still passes sparse-versus-dense input, release,
undo/redo and storage checks; those checks do not establish cross-device pixel identity.

A focused Kotlin replay reproduced the differing captures from tiny coordinate changes. The shared
patch primitive sampled fractional source positions but floored destination placement. A 0.0002-pixel
translation across an integer boundary changed an 8-bit color channel by as much as 132 levels.
Run 317 did not retain the exact mapped coordinates or fixture viewport, so the upstream source of
its input variation remains unproved.

The correction confines changed behavior to Smudge. A reusable, integer-aligned source snapshot with
a one-pixel sampling halo is captured before any destination writes. Destination pixels sample their
true displaced source position with alpha-weighted bilinear interpolation, while tip coverage uses
the fractional center and the existing round-dab antialiasing convention. Authored tips retain their
independence from round-brush hardness. Clone and Healing keep their existing shared primitives.
Spacing, pressure/tilt input, selection, alpha lock and transactional history retain their contracts.

Six new JVM regressions cover the epsilon jump, an independently calculated fractional color,
whole-pixel translation, selection/alpha lock, authored tip coverage and transparent source edges.
The six additions and 17 existing focused cases pass. The same 0.0002-pixel probe now changes no
ARGB channel. A new device case drives actual MotionEvents across the integer boundary and retains
the viewport, scale, mapped coordinates and both PNGs before checking its two-level quantization
bound. All 264 previous device identities remain, making 265 per lane.

The corrected source passes all 1,569 local JVM tests across 260 suites, with no failures, errors or
skips; instrumentation Kotlin compilation, ktlint, Detekt, debug/release lint, both debug APKs and the
release bundle also pass. Lint retains 154 debug and 152 release warnings plus five hints per variant,
with no errors. The rebuilt debug APK and release bundle pass the native packaging/alignment verifier.
Stale generated dex shards caused local duplicate-class packaging failures; the original shards and
logs were preserved, and only generated dex task outputs were regenerated without build-cache reuse.
No source assertion, CI gate, runtime setting or recovery classifier was relaxed. The fresh
published-source Actions run remains pending for this correction.

A paired desktop probe compares source `8ce26b9` with the final corrected sampler on the same
8,192 × 4,882 canvas (39,993,344 pixels), 8,845.076-pixel path and size-512 brush. Three warmed pairs
alternate execution order, reset the source before each gesture, pin each JVM to one CPU and use a
384 MiB heap cap. The probe ran during the full build; the fixture includes brush overhang near the
canvas edge. Source hashes were verified against the preserved old and current files.

| Gesture | Dabs | Old median wall time | Corrected median wall time |
| --- | ---: | ---: | ---: |
| Upright | 70 | 612.7 ms | 601.2 ms |
| Full tilt | 24 | 1,733.0 ms | 1,677.2 ms |

No garbage collection occurred inside a measured gesture. The largest observed post-gesture heap
use was 167.02 MiB. The sampling halo adds 8,224 retained bytes upright and 24,608 at full tilt;
the largest patch is 9,474,084 bytes. No material slowdown was observed in this small comparison.
The small median differences establish neither a speed improvement nor Android/physical pen latency.

## Remaining requirements for a parity claim

The [parity register](PROCREATE_PARITY.md) remains the broader source of truth. Outstanding work
includes native wet/compound/grain behavior and retained pressure graphs without rendering consumers,
profile-managed CMYK and broader ICC transforms, native document metadata and native-app interchange,
remaining 3D material/import/export capabilities, physical stylus/display performance, and artist tasks.

The official [Paint, Smudge and Erase handbook](https://help.procreate.com/procreate/handbook/brushes/paint-smudge-erase)
and [3D painting handbook](https://help.procreate.com/procreate/handbook/3d-painting) define the reference
workflows. They do not validate ArtFlow's implementation or establish comparable quality.
