# ArtFlow — Digital Art Studio for Android

[![Android CI](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/workflows/android-ci.yml/badge.svg?branch=main)](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/workflows/android-ci.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

ArtFlow is an offline painting and illustration application for Android 8 (API 26) and later,
with an OpenGL ES 2.0 capable device. Kotlin and Jetpack Compose provide the editor; a Kotlin
CPU painting/compositing engine supplies artwork to
an OpenGL ES 2.0 display surface. It includes pressure-sensitive brushes, layers and masks,
selections and transforms, animation, reference images, and 3D texture painting.

**Release status:** engineering candidate. Current implementation and verification outcomes are
recorded by revision in the [follow-through log](docs/release/PRODUCTION_FOLLOWTHROUGH_2026-11-19.md),
with earlier results in [Test evidence](docs/release/TEST_EVIDENCE.md). Publisher signing,
physical-device/stylus acceptance, upgrade testing, and store submission remain separate gates in
[Release sign-off](docs/release/RELEASE_SIGNOFF.md). A helper's unit test or green emulator run does
not establish physical performance, complete format compatibility, or Procreate parity.

## Download Android APK (latest `main` CI build)

[**Download ArtFlow Android APK (GitHub Actions ZIP)**](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44930844997/artifacts/14718242864)

This is the latest APK built by **`main`** on November 21, 2026, from
[commit `1f5b020`](https://github.com/cheshmakzanoon-ops/Experiment-2/commit/1f5b020e2e80ac8faec9a917ee16665cd2eb4127).
The [Android CI build and device-test run](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44930844997)
succeeded. Sign in to GitHub, download the linked ZIP, extract
`apk/debug/app-debug.apk`, and install that APK on an Android 8+ device.

**APK SHA-256:** `618dd61354f87a26368686806b6681ffd96333ffded9bec49a89e097d6991549`.

**This is a debug-signed test APK, not a publisher-signed production release.**
The same ZIP contains an unsigned `app-release.aab` and a mapping file; neither
is an installable publisher release. GitHub Actions artifacts expire according
to the repository's retention policy. For future builds, visit the
[Android CI workflow on `main`](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/workflows/android-ci.yml?query=branch%3Amain)
and select the `artflow-builds-unverified` artifact from the latest successful run.
Other release gates remain open: the same revision's
[release-optimized qualification](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44930845034)
had one failing test (68 of 69 passed).

## Current development

The [November 21 production hardening](docs/release/PRODUCTION_HARDENING_2026-11-21.md) protects
artwork duplication against concurrent autosave cleanup, streams completed MP4 exports without
a second full-video memory allocation, keeps timelapse work bound to its artwork, and caps
incoming shared files at 32 pending items with explicit overflow feedback. The record links
failure/concurrency regressions, export-submission keyboard handling, and the unchanged release
qualification requirements.

The [production follow-through record](docs/release/PRODUCTION_FOLLOWTHROUGH_2026-11-19.md) tracks
the twenty-wave maintenance effort and its publication checkpoints. It covers selection/history
admission, provider work off the UI thread, early import/export allocation checks, saved-brush
startup readiness, font content identity, lossless brush/palette text, and brush/native-package
transaction ownership. Each source revision requires its own qualification; the record keeps
implementation, local checks, hosted device results and external release acceptance separate.

This incorporates the earlier [November 19 hardening](docs/release/PRODUCTION_HARDENING_2026-11-19.md)
from [PR #41](https://github.com/cheshmakzanoon-ops/Experiment-2/pull/41): bounded image/PSD decoding,
document-bound atomic imports, import lifetime cleanup, consistent native-package export snapshots,
preview failure handling, dependency evidence, and the optimized/normal-release/upgrade qualification
workflows. Publisher and physical-device qualification remain separate gates.

The preceding November 18 integration milestone addressed these connected editor paths:

1. Apply a consistent raster-memory budget while editing and reopening saved documents; reject
   capacity-increasing edits before they change the artwork or undo history.
2. Route native artwork imports through bounded disk staging and ZIP streaming, with temporary
   file cleanup and missing-font/grain notices.
3. Connect persistent layer linking and precision transforms to actual editor controls.

See [V1 integration](docs/release/V1_INTEGRATION_2026-11-18.md) for implementation, regression
coverage and its historical verification status. That milestone incorporates the earlier font,
backup, codec, and CI hardening from [PR #40](https://github.com/cheshmakzanoon-ops/Experiment-2/pull/40).

## Implemented workflows

| Area | Running application |
| --- | --- |
| Painting | Pressure/tilt response, smoothing, textured and dual brushes, wet pickup, eraser, smudge, clone/heal, fill, gradients, symmetry, and perspective assistance. Imported brush settings have varying rendering coverage. |
| Layers | Raster layers, blending, nested groups, clipping, independent masks, adjustment/filter layers, selections, transforms, multi-selection, persistent links, and undo/redo. Link selected layers in the Layers panel; use Whole-layer transform in Tool options for linked masks. |
| Storage | Version-7 documents, checksum-first legacy migration, atomic manifests, immutable raster generations, autosave/recovery, independent duplication, and gallery management. |
| Portable artworks | Native packages contain layers, frames, masks, document settings, custom grains, and font dependency metadata. Optional font content fingerprints identify same-name substitutions; imported saved pixels remain intact when a font is missing, different or unverified. Third-party font binaries are not automatically redistributed. |
| Text | Editable text, platform/imported fonts, wrapping, alignment, letter spacing, and styling. Pixel transforms, crop, and canvas resize can rasterize text; full affine vector typography remains unfinished. |
| Color | sRGB and Display P3 documents, conversion/tagging, palettes, harmonies, and an approximate fixed-ink CMYK preview. This is not printer-profile-managed CMYK editing. |
| Animation | Frames, duration/role controls, onion skins, playback, GIF/APNG/MP4 and frame-sequence export, and timelapse recording. Device encoder and memory limits apply. |
| 3D | Model import, UV-based painting, selected color/metallic/roughness channels, and a GLES model shader. Simultaneous three-channel stamping is not integrated. |
| Interchange | Image/video formats, raster PDF, PSD/PSB parsing, supported Procreate document/brush formats, and model packages, within the boundaries below. |

## Runtime architecture

- **Painting and composition:** `CanvasRepositoryImpl`, `StrokeRasterizer`, and
  [`Compositor`](app/src/main/java/com/artflow/studio/core/render/Compositor.kt) operate on
  [`PixelBuffer`](app/src/main/java/com/artflow/studio/core/pixels/PixelBuffer.kt) ARGB arrays.
  Dirty regions, copy-on-write snapshots, caches, and bounded undo reduce work and memory.
- **Display:** [`ArtFlowCanvasView`](app/src/main/java/com/artflow/studio/presentation/ui/components/canvas/ArtFlowCanvasView.kt)
  creates a GLES2 context; `OpenGLCanvasRenderer` presents the CPU-composited image. The active
  `TileCache` retains a full-sized buffer and tracks computed regions.
- **Storage:** [`ProjectStorage`](app/src/main/java/com/artflow/studio/data/local/ProjectStorage.kt)
  publishes a manifest after its raster generations are durable. Recovery protection is independent
  of whether its dialog is visible. Packages have separate bounded archive handling. Import and
  duplication cleanup acquire destination ownership only when storage creates the directory;
  a reserved database ID does not permit deleting an independently created destination.
- **Unintegrated modules:** `data/renderer/gpu/RenderBackendFactory`, `VirtualTileCanvas`, and the
  GLES/software/Vulkan abstractions are not the editor's active rendering pipeline. `IccColorEngine`,
  `CmykBuffer`, and simultaneous `PbrMaterialAtlas` stamping are not connected to the editor.
- **Native prototype:** `app/src/main/jni` is excluded from the Gradle app build. Current artifact
  checks identify `libandroidx.graphics.path.so` as the packaged native library and verify its ELF
  alignment across packaged ABIs.

## Capability boundaries

- **Large canvases:** the dimension ceiling is 8,192 pixels per edge and 40 million pixels total.
  Device heap limits are stricter. One 40 MP ARGB plane alone needs 160 MB before masks, history,
  preview, and exports. These ceilings are not certified interactive workloads.
  The display preview fits the current device's queried GLES texture limit and can reduce further
  if a texture allocation fails. Zoom can therefore reveal a lower-resolution preview; editing
  coordinates, document pixels, saves, and exports retain the original artwork resolution.
  If no primary preview can be displayed, the editor reports the failure and cancels/blocks canvas
  pointer edits until a preview is available again. Save and navigation remain available. This
  fallback does not remove CPU memory limits or qualify large canvases for interactive performance.
- **Undo and saved selections:** undo and redo share a limit of 30 retained states and a separate
  memory allowance. The number of available steps depends on artwork size and device memory;
  some large artworks can have no undo available. Adding a saved selection must fit both memory
  and document-storage limits. Rejection leaves existing slots unchanged. Readable older documents
  keep their compressed saved selections, subject to the device's memory limit, and those slots
  can still be removed. See the [selection memory boundaries](docs/verification/selection-memory.md)
  for the exact allowances and regression coverage.
- **Photo insertion:** image picker and camera decoding can downsample to fit the requested size
  and the device's available memory. A bounded copy supplies both metadata and decoding; oversized
  or unreadable input is rejected with an import error. This sampling applies to inserted photos,
  not to silently reducing the pixels of an existing editable native document.
- **Brush assets and imports:** startup waits for saved brush shapes and textures before opening
  the editor. A load failure offers Retry or an explicit choice to open with available brushes;
  missing textures can change subsequent strokes. Imported batches must fit decoding, retained
  asset and library limits. Rejected batches preserve existing brushes and shared textures, and
  assets are retained at the durable library commit. The registry does not evict textures to admit
  a new import. Use fewer brushes or smaller source images when an import exceeds its allowance.
- **Import allocation:** native and Procreate archives have directory checks before platform ZIP
  parsing, as well as separate metadata, entry and raster limits. ABR tips are sampled into fixed
  tiles without allocating a padded full-size square. These limits depend on device heap and current
  headroom, so a file within its format ceiling can still be refused. The
  [import and saved-asset memory record](docs/verification/import-asset-memory.md) describes exact
  allowances, transaction/cancellation boundaries, font identity, and regression coverage. The
  estimates do not certify total-process memory use or physical-device performance.
- **Still-image export:** admission covers output scaling, fit/fill intermediates, selected/content
  crops, editable layers and masks, PDF oversampling, and format-specific encoding workspace.
  A request within the dimension ceiling can still need too much memory. Refusal preserves the
  source and existing exports and asks the artist to reduce output scale or export fewer layers.
  See the [still-export memory record](docs/verification/still-export-memory.md) for the supported
  paths and conservative estimates; native codec peak memory still needs device measurements.
- **Performance:** `StrokeTimingDeviceTest` records repository timings and excludes input delivery,
  GL upload/draw, and physical display latency. It has no speed threshold. The `benchmark` build
  type supports minified launch smoke and selected workflow qualification; it has no performance
  benchmark suite or physical latency acceptance threshold.
- **Procreate:** supported rasters, hierarchy, layer masks, and blends have independent fixture
  coverage. Application-level animation/guide interchange and some group/effect semantics remain
  incomplete. Some exports use a rendered appearance layer with hidden sources. Self-roundtrips
  do not prove that the reference application accepts the exports.
- **Photoshop:** PSD/PSB parsing includes supported raster layers, folders, and masks. Imported
  16-bit channels are reduced to 8-bit; 32-bit channels are unsupported. CMYK uses approximate
  sRGB conversion, embedded ICC profiles are not applied, and auxiliary alpha/spot channels are
  not imported as editable channels. Some alternate layer records use one flattened composite.
  Successful imports disclose those limitations when applicable. Full editable Photoshop text,
  adjustments, smart objects, and all color modes are outside the implemented contract. See the
  [resource/color boundary record](docs/verification/psd-image-resources.md).
- **Model import:** OBJ, glTF/GLB and USD/USDZ support is a subset aimed at UV painting. Models
  need usable texture coordinates; geometry, texture and device-memory limits can require refusal.
  The 300,000-triangle ceiling is an upper bound, not a certified interactive workload. Sparse
  glTF accessors are refused with guidance to export dense accessors. Import extracts supported
  geometry and textures; it does not preserve editable animation, rigs or complete material/scene
  authoring structures. See the [model input boundary record](docs/verification/model-input-allocation.md).
- **Timelapse:** recording captures downscaled JPEG snapshots while enabled. Quick edits can be
  combined into one frame, repeated frames can be omitted, and long recordings are reduced to a
  coarser sequence. Full-length export uses all retained frames; it is not a complete edit history
  or a replacement for saving artwork. Private layers are excluded from captured composites.
- **PDF:** pages contain raster artwork; vector typography/shapes and PDF/A remain unimplemented.
- **Brush/input fidelity:** some imported auxiliary curves remain unused. The physical touch-major
  palm helper is separate from active contact-size/hover filtering. Physical stylus qualification
  remains necessary.
- **Future scope:** cloud synchronization, smart objects, sparse document storage, GPU painting,
  print-profile CMYK, full non-destructive typography, and expanded 3D materials are separate
  milestones. An offline first release does not require analytics or a developer service.

## Build and verification

Use JDK 17, Android SDK Platform 36 / Build Tools 36.0.0, and the committed wrapper (Gradle 8.14.3 /
Android Gradle Plugin 8.10.1). The normal application does not require the NDK.

```bash
./gradlew testDebugUnitTest
./gradlew ktlintCheck detekt lintDebug lintRelease
./gradlew assembleDebug bundleRelease
./gradlew connectedDebugAndroidTest
python3 -m unittest discover -s .github/scripts -p 'test_*.py'
python3 -m unittest discover -s tools -p 'test_*.py'
python3 .github/scripts/verify_workflow_pins.py .github/workflows
python3 .github/scripts/scan_secrets.py .
```

GitHub Actions runs the full declared device-test inventory on API 26, Android 15 4 KB and two
independent 16 KB lanes, and Android 16 phone/16 KB/tablet configurations. Original failures and
narrowly permitted framework-recovery attempts are retained. A recovered pass must be disclosed
separately from a clean first invocation. Android 15 16 KB emulator runtime mitigations are not
substitutes for stock ARM64 device acceptance.

`detekt` passes against an existing baseline; historical findings remain. Android Lint warnings
remain visible. CI pins actions and scans for signing material and selected secret patterns. The
CycloneDX gate inventories the resolved release runtime graph and artifact hashes, with retained
Maven POM evidence for license declarations and inheritance. Missing, contradictory, or unreviewed
license evidence fails the gate. This inventories build inputs before shrinking; it does not certify
the final binary's embedded components or establish that dependencies are free of vulnerabilities.

### Optimized workflow qualification

The separate **Release-optimized qualification** workflow builds and runs selected drawing,
rendering, save/reopen, export, and production Room upgrade regressions on API 36 / x86_64 with
4096-byte process pages. It uses `-PartflowTestBuildType=benchmark`; the default remains the full
debug device matrix. Its verifier derives the selected test identities from the checked-out source
and requires every case to pass on the original attempt, with no skips or recovery retries.

The benchmark APK inherits the release R8 rules, minification and resource shrinking, and is
non-debuggable. It also retains the exact app-owned classes/members referenced by its separately
compiled instrumentation, with generated rules that preserve their exact external names, and two audited reflection
fields. These rules protect the instrumentation's referenced entrypoints and constrain optimization
beyond the publisher release rules. The [test ABI record](docs/verification/optimized-test-r8.md) explains the generated
rules and retained evidence. The artifact uses a disposable debug signing key, test-only Compose
and Android View host activities, and Hilt test application wiring. The shared View host is
included in debug and benchmark only. Publisher signing, the actual publisher release APK/bundle,
and physical-device acceptance remain separate gates.

To build the tested variant, run:

```bash
./gradlew :app:verifyReleaseQualificationBuildType :app:assembleBenchmark :app:assembleBenchmarkAndroidTest \
  -PartflowTestBuildType=benchmark
```

With the required emulator running, `.github/scripts/run-release-qualification.sh` performs one
bounded instrumentation invocation and retains its exit status and original diagnostic log.
`release_qualification.py verify` then checks all selected results, the measured runtime, installed
APK bytes against the built files, retained pixel/export assertions, and actual R8/resource-shrinker
outputs. A missing log or failed log capture cannot qualify. Reusing a prior attempt or stale device
results is refused; use a fresh output directory and clean build evidence for a new invocation.
The Actions artifact retains the first attempt's binaries, mapping, reports and diagnostics even
when compilation or instrumentation fails. A configured workflow is not passing execution evidence;
use its exact-revision report to determine acceptance.

### Normal release payload UI qualification

The **Normal release payload UI qualification** workflow exercises the ordinary release APK
through a separate UIAutomator driver with its own package, process and instrumentation target.
The app uses its production Application and normal R8 rules. A disposable certificate signs a
copy of the unsigned APK; the verifier requires unchanged application payload entries.

The one-attempt workflow creates and paints artwork, saves and reopens it, shares a PNG through
the real FileProvider, force-stops the app, and requires all exported pixels to survive a fresh
process reopen. The final candidate needs its complete passing runtime report and retained APK
identity; compiling either APK does not meet this gate. See the
[release payload protocol](docs/verification/release-payload-ui.md). The separate
[installed-upgrade campaign](docs/verification/installed-package-upgrade.md) must also pass using
actual old-app data. Both use disposable signing and leave publisher, store and physical-device
acceptance open.

### Signed releases

CI builds unsigned candidate bundles. A publisher configures private, git-ignored
`keystore.properties` and runs:

```bash
./gradlew bundleRelease -PrequireReleaseSigning=true
```

This fails without valid signing inputs. Never commit signing keys or describe a debug-signed
minified launch APK as a publisher-signed production artifact.

## Development and release records

- [Development plan](agent.md) and [contributing](CONTRIBUTING.md).
- [Current production follow-through](docs/release/PRODUCTION_FOLLOWTHROUGH_2026-11-19.md) and
  [import/asset memory boundaries](docs/verification/import-asset-memory.md), plus
  [still-export memory admission](docs/verification/still-export-memory.md).
- [Current audit](docs/PRODUCTION_READINESS_AUDIT.md) and [historical release evidence](docs/RELEASE_READINESS.md).
- [Readiness matrix](docs/release/READINESS_MATRIX.md), [test evidence](docs/release/TEST_EVIDENCE.md),
  [risk register](docs/release/RISK_REGISTER.md), and [release sign-off](docs/release/RELEASE_SIGNOFF.md).

## Privacy and license

The manifest does not request `android.permission.INTERNET`; developer networking, advertising,
and analytics are absent. Android backup and user-selected document providers follow the device's
configuration. See [privacy policy](docs/privacy-policy.md). Licensed under [GPLv3](LICENSE).
