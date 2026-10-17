# Unmerged work from the 17 October 2026 session

Everything in this directory is **work that is not part of the application**. It is kept so that nothing the session produced is lost.

* `patches/` - what each of seven work packages had on disk when the owner asked for development to stop. Each was produced with `git diff HEAD --binary` in the package's worktree (new files included), except WP3 whose two finished commits are an `.mbox` for `git am`. **They are drafts**: written by agents that were stopped mid-work, not reviewed line by line, and never seen running in the app.
* `briefs/` - the instructions each package was given (`COMMON_BRIEF.md` applies to all). Paths in them refer to the session's scratch directory; the compile / lint / unit-test harness they mention is now [`tools/offline-check`](../../tools/offline-check/README.md), and the simpler JVM harness they also mention is superseded by its `unit.sh`.
* `audits/` - the four read-only audits (brush engine, layers/selection/adjustments, canvas/gallery/gestures/export, colour/guides/animation/text) the briefs were written from. They are model-written notes against commit `6217937`; file and line pointers were not all re-checked.

The full account of the session, including what *was* merged and how it was verified, is in [../DEVELOPMENT_LOG_2026-10-17.md](../DEVELOPMENT_LOG_2026-10-17.md).

## How to apply one

```bash
git checkout -b wp2 6e0549b                 # the base named in the patch file name
git apply --3way docs/wip-2026-10-17/patches/WP2-adjustments.base-6e0549b.patch
# WP3's finished commits:  git am docs/wip-2026-10-17/patches/WP3-history.committed.base-264dde4.mbox
```

To bring one onto the current branch instead, apply it with `--3way` and expect conflicts in `AppSettings`, `SettingsRepositoryImpl`, `CanvasViewModel`, `ArtFlowCanvasView` and `EditorSheets`, which the packages touched and later commits changed. WP1 and WP2 were based on `6e0549b`, the others on `264dde4`. Run `tools/offline-check/compile.sh`, `lint.sh` and `unit.sh` before trusting any of it.

## Packages

### WP1 - Believable procedural grains (and, planned, shape tips and a preset retune).

* **Patch:** `patches/WP1-grains.base-6e0549b.patch` (base `6e0549b`)
* **Contains:** Rebuilds the procedural grain generator on seamlessly tiling noise: `core/render/TileNoise.kt` (deterministic integer-hash noise that repeats after its period), `GrainField.kt` (a wrapping float map to draw into), `GrainSynth.kt` (512 x 512 tiling grains - paper, charcoal, pastel and others - from thresholded fractal noise with soft shoulders instead of lattice cells), edits to `BrushTexture.kt`, `CustomGrains.kt` and a 4-line change in `BrushLibraryCodec.kt`, tests `GrainSynthTest` and additions to `CustomGrainsTest`.
* **Not done:** No preset retune, no shape-tip work, and the new grains were not compared against the old contact sheets before the stop; saved brushes that name a grain must be re-checked.
* **Checked when archived:** all 562 Android-free JVM tests passed; not run.

### WP2 - Adjustment depth: Curves, Colour Balance ranges, Gradient Map, Bloom, Glitch, Halftone, Chromatic Aberration, Noise, Perspective Blur.

* **Patch:** `patches/WP2-adjustments.base-6e0549b.patch` (base `6e0549b`)
* **Contains:** Engine classes in `core/pixels`: `CurveSet` (Gamma, Red, Green, Blue curves of 2-16 points, monotone cubic, stored as `curve_c_n` / `curve_c_i_x|y`), `ColorBalance` (Shadows / Midtones / Highlights with luminance weights; the old global keys still shift every pixel evenly), `GradientMap` (2-16 stops as `gradient_stops`, `gradient_i_position|rgb`; the old two-hue map renders as before), `BloomEffect`, `GlitchEffect` (five modes, seeded), `HalftoneEffect` (Full Colour, Screen Print, Newspaper), `NoiseEffect` (Clouds, Billow, Ridges, White; tiling), `ChromaticAberration` (Perspective and Displace), `AdjustmentOptions`, `ColorPlanes`, `PixelHash`; edits to `LiveAdjustments`, `AdjustmentProcessor`, `ImageFilters`, `AdjustmentType`; tests including `LegacyAdjustmentMaths`, which keeps the previous formulas so old adjustment layers can be compared.
* **Not done:** No on-screen controls at all: no Curves graph editor, no Gradient Map editor, no changes to `AdjustmentOverlay`; the per-effect before/after images the brief asked for were not produced; adjustment layers in the editor sheet were not touched.
* **Checked when archived:** all 602 Android-free JVM tests passed; not run.

### WP3 - Undo depth 250 through delta-compacted history, coalesced slider edits.

* **Patch:** `patches/WP3-history.committed.base-264dde4.mbox + WP3-history.uncommitted.on-top-of-committed.patch` (base `264dde4`)
* **Contains:** Committed in the agent's worktree: `feat(pixels): tile deltas between two versions of a layer` (`RasterDelta`, tests) and `feat(history): undo history that keeps deep steps as tile deltas` (`core/history`, tests) - pure logic. Uncommitted when it stopped: an edit of `data/repository/canvas/CanvasRepositoryImpl.kt` (about 100 changed lines, undo/redo/pushUndo/restore) and `DeepUndoTest`.
* **Not done:** Integration into `CanvasRepositoryImpl` unfinished: `MAX_HISTORY` is still 30 on the branch tip, slider edits still push one step per tick, the byte budget was not re-tuned, nothing was measured on a large canvas.
* **Checked when archived:** inconclusive (see run log); not run.

### WP4 - Selection leftovers and a modal Transform.

* **Patch:** `patches/WP4-selection-transform.base-264dde4.patch` (base `264dde4`)
* **Contains:** A Catmull-Rom `Bicubic` sampler and `TransformSource` (splits a layer into floating and staying pixels once per session) in `core/pixels`; edits to `TransformQuad`, `WarpMesh` and `LayerTransform`; small edits to `ArtFlowCanvasView`, `TransformCompanions` and `StudioChrome` (the interpolation choice); `BicubicTransformTest`.
* **Not done:** No Done/Cancel session, numeric entry, pivot, warp grid density, paste-enters-Transform, tap-polygon lasso, live feather, marching ants / mask visibility, persisted saved selections or live Automatic preview.
* **Checked when archived:** not run; not run.

### WP5 - Gesture Controls, rotate snap, zoom HUD, mouse/trackpad, stylus buttons, ChromeOS readiness.

* **Patch:** `patches/WP5-gestures-input.base-264dde4.patch` (base `264dde4`)
* **Contains:** Pure logic in `core/canvas`: `GestureSettings` (the Gesture-controls value and its choices), `PalmRejection` (contact size while a stylus is in use), `PointerKind` / `PointerIntents` (tool types and button states to intents, mouse and stylus buttons), `RotationSnap` (snap with hysteresis), `TouchGestures` (named gestures for a practice pad), `ViewMath` (wheel zoom and pan arithmetic); edits to `PointerGestureRouter`, `PointerPressure` and a 3-line change in `CanvasViewModel`; six test classes.
* **Not done:** No Gesture Controls sheet, practice pad, settings persistence, zoom HUD, view wiring, mouse hover cursor, manifest or `configChanges` change.
* **Checked when archived:** not run; not run.

### WP6 - Editable, styled, persisted drawing guides.

* **Patch:** `patches/WP6-guides.base-264dde4.patch` (base `264dde4`)
* **Contains:** About 180 changed lines in `core/symmetry/SymmetryEngine.kt` and `SymmetryStrokeTest`. This package had barely started.
* **Not done:** Everything else in the brief: Edit Drawing Guide mode, guide styling, perspective point handling, lattice fixes, persistence.
* **Checked when archived:** all 586 Android-free JVM tests passed; not run.

### WP7 - Animation Assist: timeline strip, loop modes, onion-skin colours, pinned layers.

* **Patch:** `patches/WP7-animation.base-264dde4.patch` (base `264dde4`)
* **Contains:** Pure logic in `core/animation`: `HoldDuration`, `OnionSkin` (ghost list and tint), `PinnedLayers` (Background / Foreground selection rule), `PlaybackRange`, `TimelineStrip` (index and scroll arithmetic), `domain/model/animation/FramePin`, additions to `AnimationFrame`, and tests; `AnimationTimeline.kt` lost about 430 lines (code that the audit had found unused - check before applying).
* **Not done:** No timeline strip UI, no renderer change for tinted onion skin, layers do not carry a pin in the document, the repository's frame operations do not call the range-shift functions.
* **Checked when archived:** not run; not run.

## What "checked" means here

The JVM run is `tools/offline-check`'s predecessor harness (core and domain sources and their Android-free tests, compiled with the Kotlin compiler from the Gradle distribution) run against a copy of the snapshot. The compile run is `tools/offline-check/compile.sh` against the whole snapshot tree. Neither executes anything on a device; neither says the feature works. "not run" means the check was cut short when the owner asked to stop.
