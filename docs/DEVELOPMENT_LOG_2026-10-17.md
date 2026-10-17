# Development log — 17 October 2026

A complete record of what was changed on `claude/eloquent-clarke-i9hkpx` in the session that set out to
bring ArtFlow to one-to-one parity with Procreate, and that was then wound down at the owner's request.
**Parity was not reached.** Section 8 is the honest list of what is still missing. Nothing in this log
claims that a feature works on a device: no Android device or emulator was available to the session, so
every user-interface change below is *compile-checked and lint-checked offline*, and only the pure logic
underneath it was *executed* (JVM tests). CI on the final commit is the next required gate (section 7).

Related documents: [PROCREATE_PARITY.md](PROCREATE_PARITY.md) (workflow comparison, updated in this
session), [RELEASE_READINESS.md](RELEASE_READINESS.md) (release evidence), [AUDIT_2026-10.md](AUDIT_2026-10.md)
(the earlier code audit this session started from).

## 1. Starting point and shape of the session

* The branch was fast-forwarded onto the October audit branch (123 commits ahead of `main` at
  `23f0554`); the first commit of this session sits on `6217937` ("docs(audit): record run 213 results…").
* The request was to keep developing until parity with Procreate, without stopping early. Mid-session
  the owner asked twice to wind development down, record every development, commit and push. The running
  work packages were asked to finish their current change and report; the second request stopped them.
  After the first request the lead started no new feature: it fixed a defect in its own latest code,
  committed the verification harness, and wrote this record.
* Work was done by the lead directly (everything in section 3, committed and pushed) and by seven
  parallel work packages (WP1–WP7, section 4), each started in its own git worktree. The wind-down
  request stopped those packages before any of them finished, so **none of their work is merged into
  the application tree**; every line they had written is preserved as a patch in
  [wip-2026-10-17/](wip-2026-10-17/README.md) together with the briefs they were given and the audits
  those briefs came from. Commit trailers are `Co-Authored-By: Claude <noreply@anthropic.com>` and
  `Claude-Session: …`; no model name is recorded in any commit, comment or document.

## 2. How things were verified (read this before trusting any line below)

| Level | What it means | Used for |
| --- | --- | --- |
| **JVM-executed** | JUnit tests run with the Kotlin compiler from the Gradle distribution against `core/` and `domain/` (no Android classes). 578 tests passed before the gallery commit; the run on the final head was cut short, so 578 is the last full count. | Pure logic: codecs, maths, parsers, state machines |
| **Compile-checked (offline)** | The whole `app/src/main` tree compiled with Kotlin 2.0.21 (K2) plus the Compose and serialization compiler plugins against the real Android, Compose, Hilt and AndroidX jars, with a resource check. A deliberately injected type error in a Compose file was detected. | Every Android/Compose change |
| **Lint-checked (offline)** | ktlint 1.3.1 and detekt 1.23.6 with the repository's `.editorconfig`, `config/detekt/detekt.yml` and baseline, run over the whole tree. | Style, complexity, naming, unused imports |
| **CI** | The repository's Android CI (Gradle unit tests, ktlint/detekt, build, device tests on an emulator matrix). | The only gate that runs Android unit tests and device tests |
| **Device** | An artist using the app on a device. | **Nothing in this log has reached this level.** |

The offline compile/lint harness was built during the session (section 6). Its worth was shown by CI
run 214: commit `6e0549b` failed *Static analysis* on exactly the kinds of findings the harness then
found over the whole tree (section 7).

## 3. Developments by the lead

Each entry: commit, what changed, evidence, limits.

### 3.1 Colour

* **`a0d0943` Procreate `.swatches` palettes — open and share.** New `core/color/ProcreateSwatches`:
  reads the ZIP's `Swatches.json` (a list of palettes or one palette; hue/saturation/brightness/alpha in
  0..1; empty slots and unreadable swatches skipped; the entry is read through a 4 MB budget; hue wraps)
  and writes one palette padded to Procreate's thirty slots. `PaletteCodec` recognises the ZIP signature
  so the existing *Import palette* button opens `.swatches`; every palette in Colour › Palettes has a
  share command that offers its `.swatches` file. Evidence: `ProcreateSwatchesTest` (JVM). **Limit:** the
  layout is from memory of the format and was never checked against a file written by Procreate.
* **`b810725` Value tab shows real units and takes typed values.** The channel readout rounded 0..1
  values to 0 or 1; hue now reads in degrees, saturation/brightness/CMYK in percent, RGB 0–255. Tapping a
  number opens a dialog for an exact value that rejects anything outside the channel's range. The dead
  *Hex* mode chip (a slider with an empty range) was removed; the Hex field stays. Evidence:
  `ColorChannelEntryTest` (JVM); the dialog is compile-checked only.
* **`785247c` Palette management.** Palettes tab: *New palette*; per-palette menu with Rename, Duplicate
  (built-in palettes can be copied into an editable one), Set as default (moves it to the front), Share as
  `.swatches` and Delete; in your own palettes a "+" tile adds the current colour and a long press on a
  swatch offers Replace and Remove. `SettingsRepository.updatePalette` replaces a palette where it stands.
  Evidence: `PaletteEditingTest` and a `SettingsRepositoryTest` case (the latter runs in CI only).
  Later moved into `PaletteEditor` (see 3.9).

### 3.2 Input

* **`5f15305` Hardware-keyboard shortcuts.** A fixed table of 41 shortcuts (undo/redo, clipboard,
  selection, tool letters, `[` `]` for size, Alt+`[` `]` for opacity, zoom, rotate and pan, layers, save,
  export) in `core/canvas/KeyboardShortcuts`; `MainActivity` offers each key press to the open editor
  before any view sees it, except while a text field has focus, and lists the table in Android's
  keyboard-shortcuts helper (Meta + `/`). Plain Tab and the arrow keys stay with focus navigation, so
  panning and the interface toggle need Ctrl. Escape closes the topmost panel like Back but never leaves
  the artwork. Evidence: `KeyboardShortcutsTest` (JVM); `HardwareKeyboardTest` is a device test that CI
  must run. **Limit:** the table is fixed (Procreate's is too), not remappable.

### 3.3 Selection, layers, text, video

* **`8a3865c` Freehand selection selects the enclosed area; Clear erases.** The bar's *Freehand* tool
  painted a fixed 12 px band along the drag instead of selecting what the path encloses; Freehand and
  Lasso now both select the region the dragged path encloses (closed on release, closing edge shown in
  the preview; a tap or two-point drag no longer replaces the selection with an empty one). *Clear* on
  the selection bar used to drop the selection; it now erases the selected pixels on the active layer and
  a new *Deselect* chip drops the selection. `CanvasInputDeviceTest` was changed to drive Freehand like
  Lasso and gained a tap case. Evidence: compile-checked; the device test must run in CI.
* **`1dccd40` Layer menu actions act on the layer they were opened from.** Select, Copy, Fill, Clear,
  Mask and Invert in a layer's ⋮ menu acted on the *active* layer, so choosing one on another row changed
  the wrong layer. Choosing one now selects that row first.
* **`aeb4176` Text *Justify* works.** The chip existed but text rendered left-aligned because
  `TextRasterizer` never called `TextLayout.justify`. Lines inside a text box with a wrap width now fill
  it (not the last line of a paragraph); a line wider than the box keeps natural spacing. Evidence:
  `TextJustifyTest` (JVM spacing maths); the rasteriser draws with `android.graphics`, compile-checked.
* **`e3ced38` Time-lapse recording switch, replay speed, full-length export.** `TimelapseRecorder.isEnabled`
  had no setter anywhere, so recording could never be turned off. *Actions › Video* now has a
  *Time-lapse recording* switch kept per artwork (a marker file beside the frames, so *Clear recording*
  does not turn it back on). The replay window's single *Export MP4* always produced the 30-second cut; it
  now offers 30-second and full-length, and a speed button steps replay 0.5×/1×/2×/4×. Evidence:
  compile-checked.

### 3.4 Files from other apps

* **`6e0549b` Open with / Share to ArtFlow.** `MainActivity` accepts *Edit with* and *Share* for
  pictures, *Share* for generic files and zips, and *Open with* for `.procreate .psd .artflow .brush
  .brushset .swatches .abr .artbrush .glb .gltf .usdz`, matched by name because file managers usually hand
  them over as `application/octet-stream`. Files queue in `IncomingFiles`; the gallery imports them one by
  one with the code behind its Import menu (now `GalleryImports`, plus the extracted `BrushImports`).
  `core/export/FileKinds` decides what a file is from its name, its first bytes and the claimed type;
  swatch, ASE and GPL files become palettes; a zip with no telling name is judged by its contents. A file
  that arrives while an artwork is open waits for the gallery and the user is told. Evidence:
  `FileKindsTest` (JVM); intent matching and UI are compile-checked, with `IncomingFilesTest` a CI device
  test.

### 3.5 New artwork dialog

* **`7da7016` Sizes in px / in / cm / mm, device screen size, saved sizes.** `NewArtworkDialog` replaces
  the old private dialog: width and height are typed in pixels, inches, centimetres or millimetres
  (switching units keeps the pixel size; changing the DPI in a physical unit changes the pixels; digits of
  other scripts and the Arabic decimal mark are understood), pixel and physical size are shown as you
  type, and *Create* is disabled with a message while the size is too large or the DPI is outside
  36–1200. New presets: *Screen size* (this display's pixels and density), *4K Canvas* (4096×2304 @132) and
  *4×6 Photo* (1200×1800 @300). A custom size can be saved (named after the artwork name, or its size),
  is listed first and can be deleted; saved sizes persist in the `canvas.sizePresets` setting and damaged
  lines are skipped on load. The default preset name `"FHD 1080p"` matched no preset (Settings showed a
  name the list did not contain); the default is now `"Screen HD"`. Evidence: `CanvasUnitsTest` (the unit
  round trip is checked for every pixel size 1..8192 at six DPIs), `CanvasPresetsTest`, a
  `SettingsRepositoryTest` case (CI); dialog compile- and lint-checked. **Limit:** no maximum-layers
  readout (Procreate shows one) and no colour-profile choice beyond sRGB / Display P3.

### 3.6 Gallery

* **`a86f140` Stacks, hand-arranged order, bulk actions.**
  * Stack card menu: *Rename*, *Merge into another stack*, *Un-stack*, *Share all*; all three structural
    changes are one Room query (`ProjectDao.renameStack`: renaming onto an existing name merges, a null
    name dissolves).
  * Manual order: dragging an artwork to the left or right edge (outer 28 %) of another card puts it
    before/after it instead of stacking; a bar on that edge shows where it will go and the card or stack a
    drop would stack onto is outlined. The arrangement is the new *Manual* sort (`gallery.order`: ids,
    pruned to artworks that still exist, at most 5000). The grid scrolls while a held card is near its top
    or bottom, and the held card is pinned so scrolling cannot end the drag. Stacks sit at the place of
    their first artwork.
  * Select mode gained an overflow menu: *Select all*, *Share* (several PNGs through one share sheet) and
    *Favourite / Remove favourite* for the whole selection.
  * Dialogs moved from `GalleryScreen` into `GalleryDialogs`.
  * Evidence: `GalleryOrderTest` (JVM), a `SettingsRepositoryTest` case (CI), compile- and lint-checked.
    **Limit:** drag zones, hint drawing, auto-scroll and pinning have never been run; gallery backup and
    restore were *not* built.

### 3.7 Static-analysis repair

* **`264dde4` Style and structure fixes for the session's earlier commits.** Found by running the offline
  ktlint/detekt harness over the whole tree for the first time: chained calls and one overlong line in the
  colour and file-kind code, an unused import, two files whose name did not match their single class,
  `runShortcut` at cyclomatic complexity 40 (limit 30, now one function per shortcut group) and
  `CanvasViewModel` over detekt's `LargeClass` limit (palette commands moved to `PaletteEditor`).
  `GalleryScreen` was one line over `LongMethod` since `6e0549b` (dialog host extracted). A doc comment
  that had slipped onto the wrong function in `CanvasViewModel` is back on `timelapseFrames`. Behaviour
  unchanged.

### 3.8 Verification tooling

* **`bb0264c` `tools/offline-check`** — the compile / lint / unit-test harness described in section 6,
  committed with its README, shims and self-tests (downloaded libraries are not committed).

### 3.9 Structure changes that are not features

`PaletteEditor` (palette commands), `GalleryDialogs`/`GalleryShare` (gallery dialogs and sharing),
`ShortcutEnvironment` (shortcut runner), `CanvasUnit`/`CanvasPresets`/`GalleryOrder` (pure logic). They
exist to keep `CanvasViewModel`, `GalleryScreen` and the shortcut runner inside the detekt limits.

## 4. Parallel work packages (stopped before completion; not merged)

Seven packages were briefed from the audits ([wip-2026-10-17/briefs](wip-2026-10-17/briefs/)). They
were stopped by the owner's request while still working; the lead did not restart them. What each had
on disk when it stopped is archived verbatim as a patch in [wip-2026-10-17/patches](wip-2026-10-17/patches/),
and [wip-2026-10-17/README.md](wip-2026-10-17/README.md) records, per package, the base commit, the
contents, what was *not* done, the JVM-test and offline-compile result of the snapshot, and how to apply it.
In short:

| Package | Scope | What the snapshot holds | Wired into the app? |
| --- | --- | --- | --- |
| WP1 grains | Believable procedural grains, shape tips, preset retune | `GrainSynth`/`GrainField`/`TileNoise` (seamlessly tiling fractal and cellular grains), changes to `BrushTexture`, `CustomGrains`, `BrushLibraryCodec`, tests | Replaces the grain generator in place; no preset retune, no visual sign-off |
| WP2 adjustments | Curves, Colour Balance ranges, Gradient Map, Bloom, Glitch, Halftone, Chromatic Aberration, Noise, Perspective Blur | Engine classes for each (`CurveSet`, `ColorBalance`, `GradientMap`, `BloomEffect`, `GlitchEffect`, `HalftoneEffect`, `NoiseEffect`, `ChromaticAberration`, `AdjustmentOptions`) and tests that also pin the old maths | Engine only; no Curves editor, no Gradient Map editor, no overlay changes |
| WP3 history | Undo depth 250 through tile deltas, coalesced slider edits | Two commits (`RasterDelta`, `core/history`) with tests, plus an unfinished edit of `CanvasRepositoryImpl` and a `DeepUndoTest` | Not wired; undo depth is still 30 |
| WP4 transform | Modal Transform, numeric entry, Bicubic, selection leftovers | A Catmull-Rom `Bicubic` sampler, `TransformSource`, edits to `TransformQuad`, `WarpMesh`, `LayerTransform`, the canvas view and the transform bar, a bicubic test | Partly (interpolation path); no Done/Cancel, numeric entry or selection work |
| WP5 gestures | Gesture Controls, rotate snap, mouse/trackpad, stylus buttons | Pure logic: `GestureSettings`, `PalmRejection`, `PointerIntents`/`PointerKind`, `RotationSnap`, `TouchGestures`, `ViewMath`, router and pressure edits, tests | No sheet, no settings persistence, no manifest change |
| WP6 guides | Editable, styled, persisted drawing guides | A rewrite of part of `SymmetryEngine` and a stroke test | No |
| WP7 animation | Timeline strip, loop modes, onion-skin colours, pinned layers | Pure logic: `HoldDuration`, `OnionSkin`, `PinnedLayers`, `PlaybackRange`, `TimelineStrip`, `FramePin`, `AnimationFrame` additions, tests; a large deletion in `AnimationTimeline` | No (the code comments say so) |

Treat the snapshots as drafts: each is a work-in-progress state taken at the moment of stopping, was
written by an agent whose output has not been reviewed line by line, and has not been seen working in
the app.

## 5. Defects found and fixed along the way

| Defect (as found) | Fixed in |
| --- | --- |
| Layer ⋮ menu Select/Copy/Fill/Clear/Mask/Invert acted on the active layer, not the tapped row | `1dccd40` |
| Value tab printed 0 or 1 for saturation, brightness and CMYK; *Hex* chip was a dead slider | `b810725` |
| *Justify* shown but rendered left-aligned | `aeb4176` |
| Time-lapse recording could never be switched off; replay *Export MP4* always the 30 s cut | `e3ced38` |
| *Freehand* selection was a 12 px band, not the enclosed area; *Clear* only deselected | `8a3865c` |
| Default new-canvas preset `"FHD 1080p"` matched no preset | `7da7016` |
| `GalleryScreen` over detekt `LongMethod` since `6e0549b` (CI run 214 *Static analysis* failure) | `7da7016` / `a86f140` |
| Lint violations in the session's own earlier commits | `264dde4` |

## 6. Verification tooling developed

`tools/offline-check/` (commit `bb0264c`; full description in its README) lets the repository be checked
in a sandbox with no Android SDK and no Google Maven:

* `compile.sh` — stands in for `compileDebugKotlin`: Kotlin 2.0.21 (K2) with the Compose and serialization
  plugins against Android 16 framework classes from `org.robolectric:android-all`, JetBrains Compose
  Multiplatform 1.7.3 desktop jars (the same common `androidx.compose.*` API as Jetpack Compose 1.7) and
  hand-written shims for the AndroidX pieces that exist only on Google Maven; generates `R` and
  `BuildConfig`; runs an aapt2-style resource check.
* `lint.sh` — ktlint 1.3.1 and detekt 1.23.6 with the repository's own configuration and baseline.
* `unit.sh` — compiles and runs the JUnit tests, including the data- and presentation-layer ones, against a
  mockable Android jar. 784 tests in 132 classes passed when it was last run by its author (the
  figure for the final head is in section 7).
* `tests/` — self-tests that inject mistakes into a copy of `app/src` and check they are reported.

Limits, from its README: no KSP (so no Hilt graph or Room SQL validation), no manifest merge, no Android
Lint, no packaging, no `androidTest` compile; the shims are written from public APIs and can be too
permissive. A clean run is strong evidence, not proof. The earlier, simpler JVM harness used for the first
part of the session (core and domain only) is superseded by `unit.sh` and is not committed.

## 7. CI evidence

* Run **214** (`workflow_dispatch`, commit `6e0549b`): *Run JVM unit tests* — **passed** (the Gradle
  `testDebugUnitTest` run that includes the data- and presentation-layer tests the offline JVM harness
  cannot compile); *Static analysis* — **failed**; assemble, bundle, artifact checks and all device
  jobs were skipped as a result. The offline harness later reproduced the findings and they were fixed in
  `7da7016`, `264dde4` and `a86f140`.
* No CI run exists yet for any commit after `6e0549b`. **A run on the final commit is required before
  any statement above is upgraded from "compile- and lint-checked offline".**

## 8. What is still missing (gap register)

Condensed from the four read-only audits made at the start of the session (model-written; individual
claims were spot-checked, not all verified). Items marked ◐ were started by a work package this session;
see section 4 for how far they got.

**Brush engine and Brush Studio.** Per-tool brush slots (Paint / Smudge / Erase with "behaves as"), an
in-editor brush picker with recents, persisting the active brush across launches; real rendering modes
(Light/Uniform/Intense/Heavy glaze, Uniform/Intense blending) and flow accumulation; the Wet Mix model
(Dilution, Charge, Attack, Pull, Grade, Blur, Blur jitter); pressure taper and touch taper as causal ramps;
a built-in shape library and shape/grain editors, higher-resolution filtered grains, grain blend modes and
jitter ◐; per-brush stabilisation (StreamLine, Stabilization, Motion filtering, Expression) with its own
Studio section; Properties (max/min size and opacity, preview size, sizes above 512 px); lateral jitter,
count/rotation/roundness jitter, speed→flow, Darkness; tilt/azimuth as independent controls; dual-brush
with any library brush and live drawing; library management (edit built-ins with Reset, rename/reorder,
About metadata, `.brush`/`.brushset` **export**); Smudge strength from opacity/pressure and stepping along
the segment, Clone/Heal settings UI, Clone source marker.

**Layers, history, selection, transform.** Undo depth 250 (30 today) ◐, one history step per slider drag
◐, group duplicate/delete semantics, a real *Combine Down*, mask thumbnails / *Apply* / *Clear* /
show-mask overlay ◐, layer linking UI, layer cap tied to canvas size with an n/max counter, selectable Pass
Through, persisted collapse state; selection leftovers (tap-to-place polygon, live feather, marching ants
and mask-visibility slider, saved selections stored in the document, live Automatic preview) ◐; a modal
Transform with Done/Cancel, numeric entry, pivot, Bicubic, warp grid density, selection outline that
follows the pixels, and Paste entering Transform ◐; system-clipboard interoperability.

**Adjustments.** Curves editor with per-channel curves, Colour Balance shadows/midtones/highlights,
Gradient Map editor and library, and Bloom, Glitch, Halftone, Chromatic Aberration, Noise, Perspective
Blur modes ◐.

**Gestures and input.** Per-gesture Gesture Controls sheet with practice pad, rotate snap and zoom
percentage HUD, mouse/trackpad support, stylus-button mapping, palm rejection by contact size, ChromeOS
manifest/config-change readiness ◐; shortcut customisation.

**Guides.** Edit Drawing Guide mode with handles, guide colour/opacity/thickness, persisted guides, grid
and isometric snap that agrees with the drawn lines, perspective point add/remove ◐.

**Animation.** Timeline strip with thumbnails and scrubbing, playback controls, 3-way loop picker,
onion-skin tint colours (the parity document previously claimed them; the fields were never used),
per-artwork onion settings, background/foreground (pinned) layers ◐; HEVC/alpha video.

**Text.** Per-range styling, on-canvas box with caret and resize handles, kerning/baseline/word spacing,
OpenType features, system-font list, fonts packed into `.artflow`, RTL and emoji.

**Colour.** Harmony handles on the wheel, Disc inner brightness ring, eyedropper sample source/size,
Adobe RGB/CMYK/ICC import and true wide-gamut output, HDR, colour-blind simulation, Colour Companion.

**Canvas, gallery, files.** Maximum-layers readout; Time-lapse settings and background in the new-canvas
dialog; native `.procreate` and `.brushset` **export**; print; USDZ export; ICC in PSD/TIFF/PDF; vector
PDF; in-app gallery backup/restore; Room migrations instead of destructive fallback; reference companion
(resizable/opaque/flip, Face mode, several images); Page Assist PDF import; Tracing Assist; richer Canvas
information; localisation (`locales_config.xml`, `values-xx`, about 135 literals still in code); a tablet
window-size-class layout.

**Cannot be established without a device.** Everything in this log about touch feel, gesture
recognition, drag behaviour, rendering speed and visual quality.

## 9. Process notes

* The work packages' worktrees were created from different commits (one from `main`); the lead
  fast-forwarded a worktree that held only untracked files rather than asking the agent to rebase.
* No pull request was created; everything is on the working branch.

## 10. State at the stop

The owner stopped the session while offline checks of the archived snapshots and a full unit run on the final head were still running; they were abandoned and are not recorded. Last known offline results on the lead's code: whole-app compile 0 errors (before the final small drag-tracking fix, `7ebeb8e`, which is lint-clean but was not recompiled alone), ktlint and detekt 0 findings over the whole tree, 578 JVM tests passed (before `GalleryOrderTest`'s 8, which also passed). No CI run exists for any commit after `6e0549b`.
