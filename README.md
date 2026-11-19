# ArtFlow — Digital Art Studio for Android

[![Android CI](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/workflows/android-ci.yml/badge.svg?branch=main)](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/workflows/android-ci.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

ArtFlow is an offline painting and illustration application for Android 8 and later. Kotlin and
Jetpack Compose provide the editor; a Kotlin CPU painting/compositing engine supplies artwork to
an OpenGL ES 2.0 display surface. It includes pressure-sensitive brushes, layers and masks,
selections and transforms, animation, reference images, and 3D texture painting.

**Release status:** engineering candidate. Build and emulator results are recorded by commit in
[Test evidence](docs/release/TEST_EVIDENCE.md). Publisher signing, physical-device/stylus acceptance,
upgrade testing, and store submission remain separate gates in
[Release sign-off](docs/release/RELEASE_SIGNOFF.md). A helper's unit test or green emulator run does
not establish physical performance, complete format compatibility, or Procreate parity.

## Current development

The November 18 integration milestone addresses practical gaps traced through the running editor:

1. Apply a consistent raster-memory budget while editing and reopening saved documents; reject
   capacity-increasing edits before they change the artwork or undo history.
2. Route native artwork imports through bounded disk staging and ZIP streaming, with temporary
   file cleanup and missing-font/grain notices.
3. Connect persistent layer linking and precision transforms to actual editor controls.

See [V1 integration](docs/release/V1_INTEGRATION_2026-11-18.md) for implementation, regression
coverage, verification status, and next tasks. This milestone incorporates the earlier font,
backup, codec, and CI hardening from [PR #40](https://github.com/cheshmakzanoon-ops/Experiment-2/pull/40).

## Implemented workflows

| Area | Running application |
| --- | --- |
| Painting | Pressure/tilt response, smoothing, textured and dual brushes, wet pickup, eraser, smudge, clone/heal, fill, gradients, symmetry, and perspective assistance. Imported brush settings have varying rendering coverage. |
| Layers | Raster layers, blending, nested groups, clipping, independent masks, adjustment/filter layers, selections, transforms, multi-selection, persistent links, and undo/redo. Link selected layers in the Layers panel; use Whole-layer transform in Tool options for linked masks. |
| Storage | Version-7 documents, checksum-first legacy migration, atomic manifests, immutable raster generations, autosave/recovery, independent duplication, and gallery management. |
| Portable artworks | Native packages contain layers, frames, masks, document settings, custom grains, and font dependency metadata. Third-party font binaries are not automatically redistributed. |
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
  of whether its dialog is visible. Packages have separate bounded archive handling.
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
  Exhausted preview uploads report an editor error. This fallback does not remove CPU memory
  limits or qualify large canvases for interactive performance.
- **Performance:** `StrokeTimingDeviceTest` records repository timings and excludes input delivery,
  GL upload/draw, and physical display latency. It has no speed threshold. The `benchmark` build
  type supports minified launch smoke and selected workflow qualification; it has no performance
  benchmark suite or physical latency acceptance threshold.
- **Procreate:** supported rasters, hierarchy, layer masks, and blends have independent fixture
  coverage. Application-level animation/guide interchange and some group/effect semantics remain
  incomplete. Some exports use a rendered appearance layer with hidden sources. Self-roundtrips
  do not prove that the reference application accepts the exports.
- **Photoshop:** PSD/PSB parsing includes supported raster layers, folders, and masks. Imported
  16-bit channels are reduced to 8-bit. Full editable Photoshop text, adjustments, smart objects,
  and all color modes are outside the implemented contract.
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
non-debuggable. It uses a disposable debug signing key, test-only Compose and Android View host
activities, and Hilt test application wiring. The shared View host is included in debug and
benchmark only. These differences make this a scoped optimized test artifact; publisher signing,
the actual publisher release APK/bundle, and physical-device acceptance remain separate gates.

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
- [Current audit](docs/PRODUCTION_READINESS_AUDIT.md) and [historical release evidence](docs/RELEASE_READINESS.md).
- [Readiness matrix](docs/release/READINESS_MATRIX.md), [test evidence](docs/release/TEST_EVIDENCE.md),
  [risk register](docs/release/RISK_REGISTER.md), and [release sign-off](docs/release/RELEASE_SIGNOFF.md).

## Privacy and license

The manifest does not request `android.permission.INTERNET`; developer networking, advertising,
and analytics are absent. Android backup and user-selected document providers follow the device's
configuration. See [privacy policy](docs/privacy-policy.md). Licensed under [GPLv3](LICENSE).
