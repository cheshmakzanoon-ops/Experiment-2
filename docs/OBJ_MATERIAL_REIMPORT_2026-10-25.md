# Editable OBJ material reimport

## Confirmed round-trip loss

The application revision tested by [run 319](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40148722269)
exports independent Metallic and Roughness maps but imports only the color texture from its OBJ ZIP.
`ModelPackage` reads `map_Kd`, the gallery transfers an OBJ string and color image separately, and
editor initialization has no material-plane payload. Reopening a painted model therefore discards
the exported scalar material appearance.

The retained run 318 `material-model-export.zip` is a concrete regression input: **1,258 bytes**,
SHA-256 `dfddda79f54ab990afcad746c2107b70dc695581f2ecfe3324781be193620798`.
It contains the exported OBJ, MTL, color PNG, metallic PNG and roughness PNG. The scalar PNG hashes
are `260c6b870929fad945e4004bc3b24b072f1674c3d2e7175c0948924e9b2add6e` and
`542ef0d56e3c406fff3fa02cc1b604c70f91b0d076d3df9e85bb91cc2cf0d5bd`, respectively.
The small fixture is preserved as an Android-test asset; original CI reports remain outside the
repository. It is ArtFlow output, not a native Procreate fixture.

This correction targets the bounded single-material OBJ ZIP contract ArtFlow already exports.
It preserves exported scalar values as independently editable channel layers. OBJ export has
already flattened each channel and resolved transparent coverage against its default; reimport
cannot reconstruct the original sparse layer stack, masks or undo history.

## Supported material contract

The original [PBR MTL proposal](https://ben3d.ca/blog/extended-wavefront-obj-mtl-for-pbr) names
`Pm`/`map_Pm` for metallic and `Pr`/`map_Pr` for roughness. The supported subset below follows
the application's own concrete output and does not infer unspecified external factor or sampling
behavior from those names alone.

| Input | Supported interpretation |
| --- | --- |
| One material used by the OBJ faces | Its existing geometry and UV coordinates remain unchanged. |
| `map_Kd`, with white `Kd` if declared | The color texture follows existing color-image handling. |
| `map_Pm` with explicit `Pm 1` | Opaque grayscale samples supply Metallic bytes directly. |
| `map_Pr` with explicit `Pr 1` | Opaque grayscale samples supply Roughness bytes directly. |
| No Metallic map | Only the existing zero default is accepted. |
| No Roughness map | Only the existing 0.6 default is accepted. |
| Texture dimensions | All present maps share one safe source size and one resulting canvas size. |
| Texture encoding | The bounded scalar-material package uses eight-bit PNG images; scalar planes must be opaque grayscale data. |

Missing maps, duplicate material definitions or scalar/color declarations, ambiguous referenced basenames, unsupported texture options,
unsupported scalar material properties and nonmatching dimensions are refused. A declared map is
not silently replaced with a default. The decoder validates the complete package before gallery
project creation. Existing color-only OBJ/glTF/USD imports retain their prior color/atlas route.

Scalar planes must not undergo an artwork RGB-profile conversion. The preparation path also checks
scalar PNG metadata before platform decoding so unsupported color/transfer interpretation cannot
silently change material values. Color textures retain their existing profile behavior.

## One UV layout and a bounded preparation cost

An exported model has one `artwork` material, even when its current UV atlas originally combined
several imported materials. The scalar route preserves that already-packed layout instead of
packing it again. Geometry, texture coordinates and face references remain the input's own values.

All planes use one target size derived from the color image and the existing 4,096-pixel maximum
dimension. If resizing is necessary, color and scalar planes use those same dimensions. PNG header,
checksum, bit-depth and size validation occurs before decoder allocation. The aggregate memory
estimate includes encoded images, source decoding buffers, retained resized planes and installation
copies. Full-size sources are decoded and fitted sequentially. An over-budget package is refused
before decoding additional planes or creating an artwork.

General multiple-material import needs a shared packing plan across every channel. Independently
packing the color and scalar image sets can choose different cell sizes and half-texel insets,
breaking registration. That broader workflow is outside this correction.

## Installation and persistence

`PreparedModelImport` carries the OBJ and all validated planes together. Gallery initialization
queues this bundle and the editor awaits its installation before becoming ready or starting
autosave. The repository prepares private plane buffers and explicit channel identities, checks
canvas dimensions, layer capacity and active edit state, then rechecks destination identity after
background preparation.

The OBJ is written to a unique staging file in the same project directory. Final publication occurs
only after the destination checks and before the single non-suspending layer commit. A failed file
publication does not alter layers or history; a capacity or stale-state refusal does not replace the
existing model. The texture layers enter together as one undoable change and leave Color active.
The model file retains its existing separate lifetime from layer undo.

The pending-bundle lifecycle must additionally survive cancellation or failure during the first
canvas save. The implementation retains the pending bundle until that save succeeds and records
the installed plane identity for an idempotent retry. The tests distinguish a fresh destination,
an unchanged already-installed bundle and a changed or partial destination. This is a bounded
initialization contract, not a claim of a new storage format or general filesystem transaction.
The receipt remains in process; process death before the first durable canvas save is not covered
by this retry mechanism.

## Independent acceptance inputs

The core fixture `own-material-import.zip` was authored with Python `struct` and `zlib`, independently
of the production OBJ and PNG writers. It is **986 bytes**, SHA-256
`19aa4a2e47765ed8d52572beff80ee9deb30f1096e4f171039fe1cf4509e6fdf`.
Its non-square, asymmetric planes include values 0, 1, 64, 128, 153, 254 and 255 in different positions.

The first two device cases use an independently authored asymmetric package and the pinned original
run 318 export. They exercise the same production preparation/installation path, exact channel
pixels, unchanged UVs, active Color, isolated Metallic editing, undo/redo, fresh storage reopening,
actual editor OBJ re-export and a rendered model view. Source archives, intermediate and reopened
planes, document/model data, re-exported archives and screenshots are retained before their relevant
assertions. The separate host auditor reads ZIP/MTL and PNG data without application codecs.
A third case performs a real initial save, injects a failure after that save, then reopens through
fresh storage and retries initialization. It requires identical layer IDs and pixels, no duplicate
insertion and pending-payload removal only after successful persistence.
These cases do not automate the file picker/gallery UI or model-touch painting. The GL capture
establishes that the imported mesh reaches the actual surface, not reference BRDF equivalence.

The new JVM coverage comprises 28 parser/preparation cases and 17 repository/initialization cases.
Repository tests cover complete insertion, private ownership, Color-only profile conversion,
capacity refusal, malformed payloads, file-publication failure, stale selection/channel changes,
active edits and cancellation. Initialization tests cover the first-save/retry boundary separately.

### Local verification and device boundary

The corrected local JVM suite passes **1,630 tests across 267 suites**, with no failures, errors or
skips. Raw XML retains all 1,585 identities from the native-mask revision and adds exactly the 45
cases above. The initial full-gate attempt found two test-compilation mistakes and two static-analysis
findings; those were corrected without removing cases or weakening assertions. Its original log
remains retained separately.

The full corrected local gate passes in **12 minutes 9 seconds**: Android-test compilation, ktlint,
Detekt, debug/release lint, debug and test APK assembly, the release bundle, and package integrity
and native-alignment checks. Lint has no errors and retains the prior 154 debug/152 release warnings
and five hints per variant. All 16 changed code/test/fixture files match their frozen candidate
hashes. The test APK contains the pinned run 318 asset with its original byte count and SHA-256.
The three compiled device methods are public and return void; the source inventory is **269**, with
all 266 identities from run 320 retained. The complete passing gate log has SHA-256
`bd20eb35cd3a59c99ec54f3dd154563fd72c714ac45a5e9377f1899ffc3a2f89`; the original failed local
attempt remains separately recorded as
`9f3fa28f3b2ea94f2c1d12f15d433022dbe50c48eb4aa54f0d59c0aff9746abd`.

### Published build and controlled test scheduling

The implementation was published as `ec8b14665ed6b4ff5f46b19d582eb496d73235d2` and triggered
[run 321](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40154781197). Its original
source artifact `12584411661` is 2,146,851 bytes, SHA-256
`ac18ebbe27eff2a3a09cd9cf1c82fb0e6e48caa30ad59f839ccc72508ccdc037`; all 938 files match the
published commit. The original build-report artifact `12584177418` is 669,876 bytes, SHA-256
`8d947d2d2e9013abea80a232b8f067b7b89c82b7b09aceb8d9098ecd5f56920b`.

Run 321 passes all 190 Python checks and the 68 native-document instruction anchors, then records
**1,629 passing JVM cases and one failure across 267 suites**, with no errors or skips. The failed
case is `CanvasModelInitializationTest.readyAndAutosaveWaitForTheWholeBundleAndItsModelFile`, at
its final `autosaves > 0` assertion. Every earlier assertion in that case passes, including Loading,
no premature autosave, complete insertion/history, initial save, gallery metadata and pending-payload
removal. Static analysis and packaging are skipped after this failure; **no emulator tests execute**.
The original failed report and the earlier passing local XML remain separately retained.

Source review identifies an ordering race in the test harness. Production `open()` publishes Ready
before synchronously finishing its state refresh and starting autosave. With
[`UnconfinedTestDispatcher`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/kotlinx.coroutines.test/-unconfined-test-dispatcher.html),
that Main continuation can resume on a worker while the separate Ready observer resumes the test's
virtual clock. `runCurrent()` cannot join unconfined work already running outside its queue, so the
five-second advance can precede timer registration.

The follow-up uses
[`StandardTestDispatcher(testScheduler)`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/kotlinx.coroutines.test/-standard-test-dispatcher.html)
for Main in all four initialization cases. The existing `runCurrent()` then drains queued Main
initialization/autosave work before the time advance. All test identities, assertions, exact 20-second
and five-second advances, failure/cancellation controls and real worker/IO boundaries remain intact.
No application, device-test, runtime, dependency or CI-gate code changes. The focused follow-up passes
all four cases. The fresh full JVM run then passes **1,630 cases across 267 suites, with no failures,
errors or skips**; ktlint, Detekt, debug lint and release lint pass in the same 3-minute-23-second gate.
Debug lint retains 154 warnings and five hints; release lint retains 152 warnings and five hints,
with zero errors. The focused log has SHA-256
`3fbe71e3294073caa807883592a3bc4199479899ce07a4bd543d37c52e56ec2c`; the complete follow-up gate log
has SHA-256 `df9aaf340d9441c7fb834ed299517d6ac5763b7d772720cbcff5677401702d10`.
The new Actions run must establish published-source acceptance; the previous failed run stays failed.

**Device acceptance is pending.** These implementation and test files are not part of native-mask
[run 320](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40151899389), which tests
`09940e4e03f99b61c07f2922a7229d4faee40c7b`. The subsequent exact-source Actions artifacts must
be recorded here before claiming execution of the new material cases.

## Remaining material and quality work

General glTF and USD material import is separate. The
[glTF specification](https://registry.khronos.org/glTF/specs/2.0/glTF-2.0.html#materials) assigns
metallic and roughness to different components of a shared linear-data texture, with separate
factors and defaults. The [USD PreviewSurface specification](https://openusd.org/release/spec_usdpreviewsurface.html)
uses connected texture outputs, scale/bias and source-color-space rules. Those contracts cannot be
implemented by assigning arbitrary images to the OBJ subset's channels.

Multiple source material sets, texture-specific UV selections/transforms, broader PBR MTL options,
native Procreate materials, glTF/USDZ material export, normal/environment maps, reference BRDF
matching and physical-device/artist acceptance remain open. This data-preservation correction does
not establish full Procreate feature or rendering-quality parity.
