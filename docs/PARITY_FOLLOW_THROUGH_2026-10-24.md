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

Large-brush Smudge still performs CPU work synchronously on the UI thread. A local desktop JVM
probe on an 8,192 × 4,882 canvas, with an 8,845 px diagonal and brush size 512, took about 789 ms
upright (70 dabs) and 1,951 ms at full tilt (24 larger dabs) during concurrent build work. These are
diagnostic desktop measurements, not Android latency results. Maximum-canvas responsiveness,
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

This remains a single-atlas implementation. Existing OBJ/glTF/USD imports retain base color but do
not newly decode imported metallic/roughness textures. Original material sets are not independently
preserved. The renderer uses approximate diffuse/specular lighting, not a physically accurate BRDF
or Procreate's renderer. Receiving applications must support the optional PBR MTL extensions; export
reports this limitation. Native `.procreate` materials, glTF/USDZ material export, normal maps,
environment lighting, and physical-device/reference-app comparisons remain open.

Existing canvas crop and anchored resize operations transform all texture planes together but do
not rewrite stored model UV coordinates. Full-atlas resampling preserves normalized placement;
cropping or resizing without resampling can change where paint appears on the model. The export
guards preserve agreement with the current preview, not the model's appearance before a canvas crop.

## Verification

Before publication, the complete JVM suite passed **1,563 tests in 259 suites**, with no failures,
errors or skips. This includes the pinned native brush corpus and genuine Procreate document tests.
The 93 CI-verifier tests and 63 reference-tool tests also passed (**156 Python checks**).
The complete Gradle gate passed: debug and Android-test compilation, `ktlintCheck`, `detekt`,
`lintDebug`, `lintRelease`, `assembleDebug`, `assembleDebugAndroidTest` and `bundleRelease`.
Debug lint reports 154 warnings and five hints; release lint reports 152 warnings and five hints.
These include one new advisory suggesting a KTX bitmap helper; both variants report no errors.
The debug APK and release AAB also passed the repository's package/native-alignment verifier.

The Android inventory contains **264 identities per lane**, retaining all 256 original identities
and adding eight executable device cases. The required seven-lane Actions run must account for
1,848 outcomes, allowing only the two existing API 26 compatibility skips. The published revision's
device results are pending at this commit. Compiled instrumentation alone is not evidence that its
tests executed; the final run and original artifacts remain the acceptance source.

## Remaining requirements for a parity claim

The [parity register](PROCREATE_PARITY.md) remains the broader source of truth. Outstanding work
includes native wet/compound/grain behavior and retained pressure graphs without rendering consumers,
profile-managed CMYK and broader ICC transforms, native document metadata and native-app interchange,
remaining 3D material/import/export capabilities, physical stylus/display performance, and artist tasks.

The official [Paint, Smudge and Erase handbook](https://help.procreate.com/procreate/handbook/brushes/paint-smudge-erase)
and [3D painting handbook](https://help.procreate.com/procreate/handbook/3d-painting) define the reference
workflows. They do not validate ArtFlow's implementation or establish comparable quality.
