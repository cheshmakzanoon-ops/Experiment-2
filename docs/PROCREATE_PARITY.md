# ArtFlow / Procreate workflow comparison

## Emulator acceptance and corrections — 24 October 2026

The [current implementation and acceptance record](EMULATOR_PARITY_2026-10-24.md) covers brush
sampling/rendering corrections, active-layer Automatic selection, distinct Normal/Pass Through
group scopes, PSD interchange, checksum-first historical migration into v5 and accurate blend-picker examples.
It also covers shared compound-brush importing, authored mask coverage, source mode precedence,
and the inspected native spline used by newly imported pressure graphs. Existing graphs retain
their stored algorithm and signed-document checksums.
The seven emulator lanes now compare actual results with every declared device test and publish
exact-revision summaries. Saved selections are tested through real storage, reopening, constrained
edits and independently decoded PNG exports. Consult the completed Actions run for the published
revision; older dated execution statements below describe only their respective revisions.
The added standard Android 15 / 4 KB lane uses its default runtime; both original Android 15 / 16 KB
lanes remain required. Their unresolved crashes and the unchanged-source comparison are recorded
in the [runtime diagnosis](ANDROID15_RUNTIME_COMPARISON_2026-10-24.md).

[Run 309](CI_RUN_309_2026-10-24.md) executed all 246 final identities on every emulator but failed
the new P3 case's assertion about a checksum that the verified loader intentionally clears.
The corrected case checks the persisted signature directly and retains every later colour/reopen
assertion. [Run 311](CI_RUN_311_2026-10-24.md) passed that correction and the new v5/native-export
cases where reached. Six emulator lanes completed their full 250 identities and minified launch;
the main Android 15 / 16 KB lane needed a recovery first. Its independent repeat crashed twice in
ART and ended with 36 missing identities, so the run remains failed. Original attempts, artifact
hashes and their exact revision are preserved; the later preview candidate needs its own run.

Native `.procreate` group blend/mask metadata remains incomplete: affected exports preserve the
rendered appearance with editable sources hidden below it. Passing Android tests alone does not
prove Procreate-native rendering, physical stylus quality or artist acceptance.

The native-document audit additionally repairs [one-byte masks](PROCREATE_MASK_IMPORT_2026-10-24.md),
the separate [native layer hierarchy](PROCREATE_HIERARCHY_IMPORT_2026-10-24.md), and recognized
[sRGB/Display P3 import](PROCREATE_COLOR_IMPORT_2026-10-24.md). The pinned genuine corpus contains
five files but four unique archive byte sequences. Unknown profiles and native group blend/mask,
animation, editable-text and guide metadata remain separate limitations.

[Native source19 Darken](NATIVE_LAYER_DARKEN_2026-10-24.md) now has a separate persisted layer
contract. New native imports preserve its distinct alpha behavior; existing v1–v4 drawings keep
standard Darken. The actual blend dialog identifies the imported effect before a deliberate mode
choice replaces it, with repository undo restoring the original contract. Version5 prevents an old
reader from silently rendering the new state with its ordinary Darken equation.
[Native export](PROCREATE_NATIVE_EXPORT_2026-10-24.md) now separates the flat pixel index from the
group tree, retains supported P3 channels/profile and native group visibility, and preserves a hidden
background when opening as a new artwork. Required static-source checks and real-storage device
regressions bound these corrections; they do not establish full native-app interchange or pixel parity.

The [preview optimization](PREVIEW_PERFORMANCE_2026-10-24.md) removes redundant transparent-target
blending and copies of already-private regional rasters. Exact pixel/ownership regressions and a
paired device measurement define its scope; it does not establish physical input/display latency.
The [native Count/control-pressure investigation](NATIVE_CONTROL_PRESSURE_2026-10-24.md) identifies
concrete unimplemented graph consumers and the required processed-input/plot-placement bridge.
Its reconstructed arithmetic is separate from native stroke execution.

The [model-editor correction](MODEL_EDITOR_2026-10-24.md) gives modal panels precedence over the
actual model window, keeps seam segments provisional until pointer completion, rejects stale
ownership after document replacement, and replays batched/final samples. Its preview worker
coalesces growing paint and rejects a cancelled gesture's late result. Full-frame fallback makes
later wet segments sample preceding provisional pixels consistently with sequential commit.
[Native spacing export](NATIVE_SPACING_EXPORT_2026-10-24.md) preserves the original archive version
when spacing is unchanged and selects version one for deliberate spacing edits, with each component
handled independently. These additions bring the declared Android inventory to 253; their device
acceptance belongs to the combined source's completed Actions run.

The [same-image collector controls](ANDROID15_COLLECTOR_CONTROL_2026-10-24.md) remain failed.
Although the corrected setup observed CC, compatible boot images and disabled JIT, system-server
crashes preceded any reported application case. Later flag recovery invalidated the controlled
configuration. No retry or skip expansion substitutes those diagnostics for the regular matrix.

## Saved selections, device lane diagnosis — 24 October 2026

Run 293's six emulator lanes had no readable cause in the repository. The workflow now publishes every
failed instrumentation test as a check-run annotation, which stays readable on the public run page
without repository credentials; that is how the failures in this round were identified.

The API 26 lane reported seven failing tests. Five drove chrome that the editor no longer has — a
`Workspace menu`, `Brush`, `Eraser` and `Tools` dock, and a density contract that moved to font-scale
preservation — so they could not pass on any device and now exercise the live top bar and Actions
panel instead. The transform sheet's Apply settles in a coroutine and the test read its callback
before it ran; the test now waits for it and asserts the button is enabled first. Brush Studio's Count
stopped at 16 while Procreate's slider reaches 20 and imported brushes may store up to 32; the slider
now spans both.

**Saved selections are no longer memory-only.** Save & Load slots are encoded by
`SelectionMaskCodec`, stored in the document, rejected when they belong to another canvas size, and
restored when the artwork is opened again. Covered on the JVM by `SelectionMaskCodecTest`,
`SavedSelectionsTest` (including reopening) and `CanvasDocumentCodecTest` (round trip plus a document
written before the field existed). None of this is device-verified yet.

## Selection and crop corrections — 23 October 2026

Three items from the 19 September sweep are closed by source changes with JVM regressions:

- **Remove without an existing selection** acted as Add, and so did Intersect. Combination now lives
  in the pixel core (`SelectionMask.combine`): with nothing selected, Replace and Add use the new
  mask while Remove and Intersect select nothing, which is the set operation the buttons promise.
- **Copy or Cut on an emptied selection** copied blank pixels, so Paste added an invisible layer. The
  clipboard now refuses with "Nothing is selected" and leaves the previous clipboard untouched.
- **Crop has no aspect lock** is closed: Crop & Resize offers Freeform, 1:1, 4:3, 3:2 and 16:9, keeps
  the locked proportion while dragging (anchoring the opposite corner or the edge centre), stays on
  the canvas, and ignores the lock while the box is moved.

Executed evidence for this revision: the full JVM suite passed (1,342 tests, 0 failures, 0 errors,
2 skipped reference corpora), debug and instrumentation APKs compile, and ktlint and detekt pass.
Android lint, the APK/AAB builds and all six emulator lanes were **not** run on this revision. Run
293 on `b33a695` remains red in every device lane with a passing build job and no established cause
in the retained evidence, so no row above is device-verified by these changes.

## Native-informed implementation — 21 October 2026

The [latest implementation and verification record](PARITY_IMPLEMENTATION_2026-10-21.md)
starts from `adf0bc7208974f1579095ea5a890b7c1375f02f9` and the supplied Procreate 5.4.4 / 56832
archive. [Reproducible native contracts](REA_NATIVE_CONTRACTS_2026-10-21.md) now establish the
pressure-flow/opacity and wet-control aliases used by brush interchange, plus independent PSD
mask channels. Animation preview/export share range, visibility, pin and playback ordering.
The current rows below include these changes; older dated sections retain their historical
verification boundaries. Full rendering parity and physical-device acceptance remain open.

## REA brush compatibility follow-through

The [REA-derived brush integration](REA_BRUSH_COMPATIBILITY.md) adds compound archive grouping,
signed size/opacity dynamics, independent imported pressure curves, and secondary rendering fixes.
It retains unresolved source combine IDs rather than claiming exact rendering. The hash-pinned
reference corpus checks metadata and serialization only; **overall and pixel parity remain unverified**.


Assessed 19 September 2026 against **Procreate for iPad**, not Dreams or Pocket.
Original comparison baseline: `c2e4eee1ef0cc1e65ebfd49ca7c01b9f2b94d5df`.
**Overall parity has not been reached.** This is a workflow gap register and acceptance contract,
not a release certificate. A feature counts only when it is reachable and behaves correctly in the
editor; a helper class, placeholder control or passing compile does not establish completion.

Earlier comparison baseline: `019648ea501c8de5f3d0af04fc16bc97b05d8923`. The dated implementation
record above identifies the new starting revision and executed gates. Device execution remains
a separate requirement and is not implied by a source-level feature entry.

## Current comparison

Originally updated 6 October 2026 for `claude/ecstatic-gauss-3nkeht`, with brush, animation and
PSD corrections on 21 October. Rows describe what is reachable in the editor; device validation
by artists is still outstanding for every row.
The colour, native metadata, 3D material and timing-budget boundaries were rechecked against source
on 24 October. Procreate documents [RGB/CMYK profiles][8] and [separate paintable 3D materials][15];
those workflows need implementation as well as validation here. The 8-bit storage limitation below
describes external-file fidelity, without assuming a particular Procreate high-bit-depth requirement.

| Workflow | Reference product | ArtFlow now | Remaining work |
| --- | --- | --- | --- |
| Canvas-first workspace | Top bar, movable sidebar, hide-interface mode [1] | Gallery/Actions/Adjustments/Selection/Transform and Paint/Smudge/Erase/Layers/colour top bar; sidebar with size and opacity sliders, eyedropper button and undo/redo; right-hand interface, light interface, brush cursor and dynamic brush scaling options; full screen by four-finger tap; anchored popovers on tablets | Artist validation of layout and reach on phones and tablets |
| Gestures | Taps for undo/redo, pinch navigation, Copy & Paste swipe, clear scrub [1] | Two/three/four-finger taps, two/three-finger hold for rapid undo/redo, pinch/rotate/pan, quick pinch to fit, three-finger swipe for Copy & Paste, three-finger scrub to clear, touch-and-hold eyedropper, QuickMenu ring from holding the modify button (touch and hold a slot to choose its action), stylus hover outline and side-button eyedropper; scrub, swipe and four-finger tap can be switched off in Prefs | Artist validation on phones and tablets; palm rejection is off by default and needs device testing |
| Actions | Add, Canvas, Share, Video, Prefs and Help [1] | Tabbed Actions panel with those six tabs (Add includes take a photo, insert a private photo that stays out of the time-lapse, Cut & Paste and Duplicate), including Pressure and Smoothing preferences (pressure curve, stabilization, motion filtering with expression, and pressure smoothing) | Artist review of handbook wording |
| Brush library | Search, organisation and management [13] | 200 original presets in 18 categories, matching the size of a full professional library (adding touch-ups, retro, luminance, industrial, organic, water and abstract sets); searchable saved copies with rename and deletion; Favourites and your own brush sets; `.artbrush`, `.brush` and `.brushset` sharing, including compound component settings and images; `.artbrush` import carries imported images; Procreate `.brush` and `.brushset` files import as new brushes in the set's order, with their names, shape and grain images (inverted where the brush says so) and their settings read from the brush archive: spacing, jitter, scatter, count, roundness, size and opacity limits, pressure, speed, tilt and colour dynamics, tapers, wet mix, flow, blend mode and grain scale and movement. Photoshop `.abr` brush files (versions 1, 2 and 6 and later) import their sampled tips as brush shapes, named from the presets that use them | A professionally curated, artist-tested collection; native rendering and texture equivalence for imported brushes |
| Brush Studio | Staged settings, drawing pad, custom shapes/grains and dual brushes [2] | Draft/apply/cancel, exact values, pressure curves, procedural and imported grains with depth, imported shape tips, flat tips, per-brush blend mode, wet edges, burnt edges and wet mix, secondary colour pressure and jitter, stylus tilt to size/opacity and tip angle, dual brushes (Multiply, Subtract, Add), stroke fall off and taper opacity, Smudge that carries the brush's tip and grain | Native wet-paint and compound-mode equations; 24 retained-only pressure graphs; continuous grain and texture sampling; artist-tested tuning and physical tilt/pressure validation |
| Adjustments | Live adjustments in Layer and Pencil modes [14] | Hue/Saturation/Brightness, Colour Balance, Curves, Gradient Map, Gaussian, Motion and Perspective Blur, Noise, Sharpen, Bloom, Glitch, Halftone, Chromatic Aberration and Recolor, each with Layer or Pencil mode; Liquify with Push, Twirl Right and Left, Pinch, Bloat, Crystals and Edge, size/distortion sliders and Reset | Device validation of large-canvas responsiveness |
| Transform | Freeform, Uniform, Distort and Warp with snapping [3] | On-canvas box with corner, edge and rotation handles; Freeform, Uniform, Distort and Warp (4 × 4 Bézier mesh); Magnetics (45° moves, 15° rotation, proportional corners) and Snapping to canvas edges and centre with guides; flip, rotate, fit, reset and interpolation choice | Device validation of large multi-layer moves; Move with a selection lifts only the selected pixels |
| Layer organisation | Thumbnails, options, swipe actions, groups [4] | Thumbnails, tap-the-selected-layer options, swipe left for Lock/Duplicate/Delete, swipe right to multi-select then Group, Merge, Delete or Transform together, touch-and-hold drag reordering, two-finger swipe for Alpha Lock, two-finger tap then slide for opacity, two-finger hold to select contents, Background colour row with a visibility checkbox, all 26 standard blend modes in five families (darken, lighten, contrast, inversion, component; Linear Burn, Darker Color, Add, Lighter Color, Vivid, Linear and Pin Light, Hard Mix, Subtract and Divide included and kept through PSD), blend-mode codes, groups that nest (group groups; drag a layer or a whole group into or out of a group), whose blend mode and opacity apply to the merged group, editable text layers | Artist validation of deep group stacks; Merge Down bakes opacity and blend mode into pixels and does not carry alpha lock or clipping |
| Reference companion | Floating canvas/image reference [5] | Movable window with a live Canvas view or an imported image, pan/zoom/fit and colour sampling; controls remain above either sidebar and clear of normal/large focus exit, while modal panels retain precedence | Broader artist validation of window placement; the exact revision still requires its completed device matrix |
| Selections and fill | Automatic/freehand/rectangle/ellipse, Save & Load, ColorDrop [6] | Automatic samples the active layer with slide-to-set threshold, freehand, rectangle, ellipse; Add/Remove; invert, feather, Save & Load, Colour Fill, Copy & Paste; ColorDrop with slide-to-set threshold and Continue Filling (each further tap fills another area at the same threshold), Reference layers that bound ColorDrop | Artist validation of fills over anti-aliased line art and comparative reference-app selection thresholds |
| Drawing assistance | QuickShape, grids, perspective and symmetry [7] | QuickShape lines, polylines, ellipses, triangles, rectangles and polygons, adjustable while held with 45° line snapping, and Edit Shape afterwards (drag line ends, corners or ellipse axis ends; the stroke is redrawn as one undo step); symmetry, perspective, isometric and grid guides that assist only layers with Drawing Assist; stabilization | Device validation of guide dragging and of the on-canvas handles on phones; artist review of guide defaults |
| Colour | Disc, Classic, Harmony, Value, Palettes; ICC profiles [8] | Disc, Classic, Harmony, Value and Palettes tabs with history, a previous-colour swatch and a secondary colour swatch (tap to swap; brushes can blend toward it by pressure or per dab); palette import (ASE, GIMP, hex) and New from photo; sRGB or Display P3 canvas profile (New artwork or Actions > Canvas). Display P3 profiles are embedded in PNG, JPEG and layer-PNG exports. `.procreate` export retains supported sRGB/Display P3 channel encoding and embeds the generated Display P3 profile; rendered outputs that cannot carry the working profile receive converted sRGB pixels. “Simulate CMYK inks” discloses a fixed process-ink approximation without a printer profile, with an option to grey simulated colour shifts. | Profile-managed CMYK documents and print proof; current pixel storage is 8-bit ARGB and PSD 16-bit channels are reduced to 8 bits; artist validation against real press output; Classic picker is HSB only, with RGB, CMYK and Hex in the Value tab |
| Animation | Animation Assist timeline and onion skin [9] | Frame editing, duration/FPS, loop, one-shot and ping-pong; stable multiple background/foreground pins; hidden frames and previous/next onion colour controls; shared preview/export range and frame ordering; exact GIF colours when the palette fits | Artist-tested navigation and timing; reference hold-count semantics; `.procreate` animation metadata; long-animation performance and device MP4/APNG validation |
| Drawing timelapse | Recording, replay and export [10] | Automatic capture, in-app replay with scrubbing, full-length or 30-second MP4 export, 720p, 1080p or 1440p recording | Device validation of 1440p encoding |
| Gallery | Stacks, preview, import and share [11] | Import of photos and layered PSD files as new artworks, stacks with drag-to-stack (touch and hold, drop on an artwork or stack), Select mode to stack, duplicate or delete several artworks, full-screen swipe preview, share, photo import as a new canvas | Artist validation of drag gestures in long galleries |
| File interchange | Image and layered-document import and sharing [11] | PNG/JPEG/WebP/PDF/PSD/TIFF, animated GIF/PNG and MP4, layers as PNG files and frame-sequence export; `.artflow` artwork files that carry every layer, frame and setting to another device (export from Share, import in the gallery). PSD import/export retains nested folders, clipping and independent enabled/disabled layer and folder masks, with original layer alpha separate. Images dragged in from another app become a new layer. Procreate documents import their native nested hierarchy, opacity, supported blend modes, visibility, clipping, alpha lock, one-byte or RGBA layer masks and background. Recognized sRGB/Display P3 channels retain their source profile on new canvases or convert into an existing canvas profile. Tile calibration compares sRGB thumbnails consistently, with a profile-aware flattened fallback beyond the memory budget. `.procreate` export writes separate flat-layer and nested-group structures, native group visibility, supported P3 channels and its generated profile, and the persisted legacy Darken contract. A hidden native background becomes transparent when opened as a new artwork; insertion retains the existing-background behavior. | Native group blend/mask, animation, editable-text and guide metadata; generic ICC transforms and profiles beyond supported sRGB/Display P3; opening exported files in the native application and broader current-version external fixtures beyond the four distinct genuine archives exercised in the five-file gate; PSD layers with post-mask effects retain baked masks; resized enabled masks keep editable layers hidden below a rendered appearance layer; adjustment fallbacks and PSB support remain limited |
| Page and 3D workflows | Page Assist and 3D painting [12] | Page Assist: page strip with thumbnails, add, duplicate, delete and reorder; all pages export as one PDF. 3D painting: import an OBJ, glTF/GLB, USDZ or USD (binary or text) model with texture coordinates from the gallery (or a zip with the model, its materials and textures; the model's base colour texture becomes the first layer, and a model with several materials gets one artwork with a cell per material texture); a 3D window shows it lit and wearing the artwork, turns and zooms with two fingers, and paints on the model with the current brush (strokes land on the active layer of the texture, so undo, layers and export work as usual); Share exports the painted model as OBJ, material and texture Lighting studio: six presets (Studio, Soft, Sunset, Rim, Moonlight, Flat) and sliders for light angle, height, brightness, fill, warmth and exposure, plus the surface material (metallic and roughness). Strokes on the model follow its surface and run across UV seams: the stroke is brought up to the seam and carries on from it on the next texture island. | Separate paintable metallic/roughness channels and material-preserving export; current painting uses one base-colour atlas, global material sliders and one OBJ material; artist validation on real models |
| Reliability and performance | Measured through executed tasks | CPU painting/compositing with incremental live-stroke dabs, tile-cached lower layers and bounded dirty-region GL ES 2.0 display uploads; display-only motion prediction does not enter saved paint. The device timing fixture uses a 2048-square canvas with Paper, four painted layers and Ink. Ordered raw elapsed repository-call timings separate continuation, preview and commit phases, including waits; [executed historical results and scope](EMULATOR_PARITY_2026-10-24.md) remain tied to their exact revisions. | Physical input-to-display latency, frame-time/memory/thermal measurements with enforced acceptance budgets; current emulator timings have no speed threshold; large-brush performance and a GPU painting pipeline remain open |

Cloud collaboration and smart objects are separate ArtFlow roadmap ideas, not asserted Procreate
features. Telemetry is intentionally absent and is not a parity requirement. Procreate is a workflow
reference; ArtFlow uses its own assets and identity. No aesthetic superiority is asserted without
running-app inspection and artist/user evidence.

A layer marked **Reference** in its options is Procreate's fill reference: ColorDrop and the paint
bucket on other layers stop at its lines, and only one layer is the reference at a time. The older
"Exclude from export" option keeps a layer out of exports and is a separate ArtFlow feature.

## Correctness fixes, 6 October 2026

An audit of the engine against what each control promises found and fixed these, each with a
regression test that fails on the old code:

- **Symmetry** copied strokes by moving them instead of reflecting them: with Vertical symmetry a
  stroke heading right was copied heading right. Every point is now transformed.
- **GIF export** widened its LZW code size one code early, so decoders misread most of a busy
  image (149,727 of 160,000 pixels wrong in the test image), and transparent animations used the
  wrong disposal method and left trails.
- **Emboss** washed out to white and ignored its strength; **Selective Color** Black lightened.
- **2D grid** and **isometric** snapping pulled the pen to lines other than the ones drawn.
- **Tints** of fully bright colours were all the same colour.
- **Liquify's Size slider** and **Settings > Haptics** did nothing; onion skin settings only
  applied after changing frame.

## Implemented milestones and their boundaries

### Compact workspace and connected editor controls

The compact dock keeps primary tools at hand and retains the selected secondary tool. Focus mode
retains the same `ArtFlowCanvasView`; its exit button and Back restore the workspace before an
unsaved-document prompt. Layout changes cancel active gestures. Titles truncate and the save icon
no longer implies nonexistent cloud storage.

Guides, Animation, Canvas and Text now have editor routes. Text uses edit → choose position →
confirm; cancelling an anchor leaves artwork unchanged. Layer ordering calls the real repository,
not a second UI-only stack. Background colour state tracks document edits and undo/redo. Named
sliders, wrapping options and enlarged targets improve reachability, not prove full accessibility.

### Reference image companion

Only picker-granted `content://` sources are accepted, decoded with the existing Coil dependency
into at most 1024×1024 preview pixels. Drawing and picking share `ReferenceViewport`; letterboxing
cannot yield false sampled pixels. Header dragging stays bounded after available-space changes.
A centre-sample accessibility action accompanies pick mode and long press.

Reference pixels never enter layers, undo history or export. The source is session-only, and an
expired/denied grant requires choosing again. The preview is not ICC-managed full-source sampling.

### Brush Studio and practice

Library, Settings and Drawing pad form one staged editing workflow. Use brush applies the draft;
Close/Back/outside dismissal discards unapplied changes; Restore initial restores opening values.
Exact entry accepts decimal points/commas, validates ranges and rejects fractional integer values.
The settings panel scrolls and names its controls and collapse actions.

Samples use the actual rasterizer on a worker. Practice retains immutable paths and re-renders
when settings change, independently of document data. Its explicit limits are 320×180 pixels,
eight retained strokes, 128 points per stroke and 48 preview pixels of brush size. These are not
canvas limits or latency measurements. Cancelled/multi-pointer practice gestures do not commit.

### Persistent custom brush copies

Save a copy captures the **complete current parameter model** without applying the draft. Saved
copies have independent stable identities, participate in search and have rename/delete controls.
Deletion is confirmed and leaves existing artwork and the current draft unchanged. Built-ins are
not mutated. Confirmed library changes persist independently of cancelling unapplied studio edits.

One versioned JSON row in the existing Room database holds up to 128 copies, with names up to 80
characters and bounded encoded input. A singleton mutex serializes mutations. Encoding/decoding
runs off the UI thread; publication follows a successful atomic Room write. An entered small write
completes even if its caller is cancelled. Failed writes retain prior state; corrupt/newer data is
preserved with visible failure/retry instead of reset. Failed initial reads are not cached as empty.
No Room schema, permission, dependency or artwork-format migration is introduced.

New checks comprise nine JVM codec/store cases, one actual Room close/reopen device case and
three saved-library UI cases. They cover complete parameters and Unicode names, invalid/future
payloads, failed writes, concurrent saves, cancellation, owned snapshots, capacity, rename/delete
and save-versus-apply behavior. The previous aspect-ratio test compile error is corrected using
DpRect edge coordinates. **New tests require their exact published revision's completed CI.**

## Neutral theme, scale correction and adaptive Brush Studio

All Material surface roles are now explicitly neutral, including elevated container roles that
previously inherited unrelated default tints. Opaque accent/text pairs are derived from the selected
accent with measured sRGB contrast. Tests assert at least 4.5:1 for normal text pairs and 7:1 for
high-contrast pairs across six accents, light/dark modes and both contrast settings. The old Ink
accent against its dark surface measured 1.2061:1. Palette contracts are not a full accessibility
certification; disabled controls, composited overlays and every actual screen still need evaluation.
The colour calculation affects UI roles only, never artwork data or working-space profiles.

Interface scaling no longer multiplies both density and fontScale; geometry/text scale once and
the independent Android text-size preference is retained. Non-finite values fall back to 1.0.

Brush Studio uses a side-by-side settings/library and practice layout when its available body is
at least 720 dp wide and 320 dp tall. Compact or short windows retain tabs; the drawing pad can
also be maximized to its own tab. The practice image fits both dimensions at its original aspect
ratio. Draft parameters and immutable test strokes remain hoisted across layout/theme switches.

Executed locally: six production-math check groups, all 24 theme/accent combinations and 1,000
generated seeds, plus 30 Python verifier tests. Six JUnit math wrappers, one Material-role test and
two device tests add exact-revision verification. A sixth CI lane uses the API 36 Pixel C profile;
all five existing device configurations and their minified launch checks remain. Actual tablet and
compact captures must be inspected before claiming visual acceptance.

## Local candidate: attribute-first editing, not feature-count inflation

`BrushSettingsWorkspace` adds a ten-group selector, keeping All settings for the existing full
panel. A settings pane at least 480 dp wide has an attribute sidebar; smaller panes use a scrollable
selector. Selection lives above the tab/layout-specific subtrees. Only the panel's scroll position
resets when a different group opens; the brush draft and practice paths are not replaced.
Controls retain selected semantics, the existing theme and the larger-touch-target preference.

The Speed & colour group wires `velocityToSize`, `velocityToOpacity`, `velocityToHue` and
`colorPressure` to their existing production parameter functions. Pixel tests confirm the effects
reach `StrokeRasterizer`, rather than merely testing that a slider callback stores a value.
The engine normalizes velocity over 0–10 document pixels per millisecond. At full influence its
speed response can reduce size by up to 50%, opacity by up to 30%, and shift hue by up to 60 degrees.
Those are this engine's parameter contracts, not measurements or claims about Procreate's feel.

A later inspection found that this paragraph was itself out of date. `tiltInfluence` and
`tiltToRotation` are both consumed by the rasterizer: tilt widens and lightens dabs (tilt opacity is
set apart from tilt size), and tilt-to-rotation turns flat and imported tips by the pen's azimuth. The
general rotation field turns flat and imported tips, and an imported shape turns by its tip angle.
Grain rotation remains a separate texture operation. Procreate's tilt curves and the azimuth sign
convention are unvalidated on a stylus, so these statements describe the engine only.

Added verification: seven shared core/JUnit cases and five Compose cases. Local standalone checks
compile and execute the actual enum, parameter functions, colour conversions, pixel kernels and
rasterizer, with compile-only adapters for unavailable serialization annotations and unused Android/
Compose type signatures. Android rendering, generated serializers and Compose compilation are not
covered by those adapters. The seven existing Brush Studio check groups also still pass. New
Compose checks require the full device matrix; no baseline, assertion or job is removed.

### Earlier prioritization, superseded by the current comparison

This table records the earlier candidate's scope. Its remaining-work summaries are historical:
the current comparison above also identifies software work in brush synthesis, native document
interchange, colour management and 3D materials. Physical-device validation alone will not close
those implementation gaps.

| Priority | Missing workflow / quality | Concrete acceptance task |
| --- | --- | --- |
| 1 | Reliable, discoverable editing | All six device lanes pass with preserved crash evidence; inspect phone/tablet, large text and theme captures. Complete paint → change brush → edit layer → save → reopen without hidden controls. |
| 2 | Useful transformations and layer organization | Done in this branch: on-canvas transform with handles (freeform, uniform, distort, warp) with cancel/undo/redo, nested groups kept through save and PSD/Procreate import. PR #34's separate transform work overlaps this and conflicts with it, so it was not merged. Remaining: artist validation of large multi-layer moves. |
| 3 | Artist-grade brushes | Done: imported shape/grain sources, tilt-aware tips, dual brushes, 200 original presets, and Procreate (.brush/.brushset, with settings) and Photoshop (.abr) brush import. Remaining: compare named physical styluses on the same pressure/tilt/speed tasks. |
| 4 | Drawing assistance and colour fidelity | Done: QuickShape with Edit Shape nodes, ColorDrop threshold, saved selections, CMYK proof, ICC-tagged export, PSD and Procreate document import. Remaining: validation against files from current Procreate and Photoshop versions (fixtures are synthetic or open-source). |
| 5 | Broader Procreate workflows | Done: drawing timelapse with private photos left out, Page Assist, 3D painting (OBJ, glTF, USD/USDZ). Remaining: artist validation on real devices. |

A high-contrast theme is not whole-app accessibility; a decoder is not faithful interchange;
a numerical transform helper is not an ergonomic transform workflow; and automated tests alone
are not measured input latency or artist approval. No parity percentage is assigned without a
scoped, executed task matrix. The reference remains Procreate's own handbook [1][2][3][4][8][10].

## Parity sweep candidate (unexecuted)

Six workers audited one area each against Procreate's handbook and closed the gaps they could close
by reading the code. The changes are merged on `claude/dreamy-lamport-8kj8oe`; the commit messages
list them. The Procreate handbook was not reachable from the sandbox that wrote them, so every
Procreate-behaviour claim below is unverified.

**Verification.** Nothing was built with Gradle or run on a device. Some pure-Kotlin files were compiled
with the Kotlin 2.0.21 compiler bundled with Gradle and run against stub harnesses: PSD codec and
folders, layer groups and gesture router 26/26; GIF encoder 3/3; onion-skin tint 3/3; symmetry 22/22;
square and isometric grids 5/5; canvas operations 28/28. Everything else was checked by reading call
sites and grepping each changed symbol. Not run: the JVM suite, ktlint, detekt, lint, androidTest and
device lanes. CI on the pushed commit is the first real gate.

**Changes by area.**
- **Brushes:** speed may reverse (negative size or opacity response); imported Procreate values stay
  inside the library's ranges (grain scale, dab count up to 32, shape angle wrapped); tilt size and tilt
  opacity have separate amounts, each with a slider; Count and speed sliders reach every stored value.
- **Layers:** Pass Through groups do not isolate their members; merges stay inside their shared group
  and are private when any source was.
- **Selection and transform:** an emptied selection changes nothing; freehand selects the area its path
  encloses; Move with a selection lifts only the selected pixels; justified text spreads wrapped lines.
- **Drawing assist and colour:** symmetry copies are reflections (they were translations); square and
  isometric guides snap to the lines they draw; expanding to content keeps artwork in place; a resolution
  drag is one undo step; a grid size slider; print size shown in the Canvas sheet.
- **Animation:** GIF holds keep the clip's total length; onion ghosts are tinted silhouettes and refresh
  on change; onion-look changes are not undo steps; timelapse replay runs at 30 fps and advances after
  each decode; playback reads the timeline snapshot.
- **Input and interchange:** PSD import keeps Photoshop layer masks; PSD export writes plain groups as
  pass-through; plain folders import as Normal; a three-finger scrub ignores its first move.

**Open after the historical sweep** (subsequent changes are identified in the latest record above):
- Liquify Freeze Mask has no UI; editing text drops its transform; word spacing and text on a curve have
  no UI.
- Automatic selection now samples the active layer (24 October correction); comparative reference-app threshold validation remains.

Closed since that sweep: Copy or Cut on an empty selection no longer pastes a blank layer, Remove
without an existing selection no longer acts as Add, saved selections are stored with the document,
vanishing points and the symmetry axis are dragged on the canvas ("Edit vanishing points" /
"Move axis on the canvas", covered by `VanishingPointDragTest`), and Crop & Resize has an aspect
lock.
- Merge Down bakes opacity and blend mode into pixels and drops alpha lock and clipping.
- Procreate group blend modes on `.procreate` import are not read; this needs a real Procreate file.
- At the time of the sweep, PSD export baked masks into pixels. Independent masks are now
  exported except on layers whose post-mask effects require the baked fallback. Resizing enabled
  masks adds a visible rendered appearance layer above the hidden editable stack; original-size
  masks remain directly editable. PSB remains unsupported.
- Palm rejection is off by default and only rejects once a pen is down; Pencil double tap has no Android
  equivalent; dynamic brush scaling clamps at extreme zoom.
- Procreate's lightness and darkness jitter collapse to one symmetric value on import.
- Classic colour is HSB only; vanishing points cannot be moved; the axis sliders do not affect radial
  symmetry; crop has no aspect lock.
- Export ignores ping-pong and playback range; there is no onion colour control or frame hide; preview
  hold timing and onion cost are unmeasured on a device.
- Setting a document's colour profile retags it without converting pixels.
- Several `CanvasOperations` helpers (rotate freely, straighten, resample to DPI, change DPI, fit to
  content, centred square) have no caller.

## Verification record

| Candidate / run | Observed result | Meaning |
| --- | --- | --- |
| `3aec1d5` / `35443960450` | Build/static checks and four device configurations passed; initial API 35 lane stopped after 62/146 tests with a native SIGSEGV | Root cause was not established; generated GraphicsLayer methods appeared in the retained stack. Independent repeat passed, which does not erase the failure. Artifact `10585181281`. |
| `1ad0dee` / `35445726893` | 402 JVM tests passed; static findings and a wrong custom-action test key blocked completion | Corrected without disabling rules/assertions. No successful device evidence for this candidate. |
| `e8c4032` / `35446449113` | Five device configurations passed; background-colour JVM test exposed a worker/virtual-clock timing assumption | Corrected by awaiting actual observable state with a deadline, retaining exact assertions. |
| `68d29a3` / `35448121991` | JVM step passed; remaining chain-format findings blocked the build job | Corrected without changing baselines or device coverage. |
| `ba70a98` / `35449641066` | JVM step passed; static findings and API 26 dialog-capture incompatibility remained | Whole-screen accessibility-bounded pixel checks replace the unsupported capture API, retaining blank/painted/cleared assertions. |
| `b27de13` / `35451246630` | 409 JVM tests passed with zero failures/errors/skips; ktlint, detekt, both lint variants, APK/AAB and artifact preflight passed | All five device lanes stopped at compilation of a DpRect assertion. This is not runtime or parity approval. |
| `925550d` / `35452229998` | 418 JVM tests passed; all five device configurations passed; API 36 had 165 cases with zero failures/errors/skips | ktlint formatting blocked the build job; those findings are repaired in the theme/adaptive milestone without suppressions. |
| `019648e` / `35453272406` | 425 JVM tests passed; four of six device jobs passed. Tablet: one label-overflow assertion among 167 cases; API 35: native crash after 76 reported cases. Three static findings blocked the build job. | Local candidate addresses tab layout/static structure, adds diagnostics and brush navigation. Native crash root cause remains unresolved. Exact-candidate Android/static/build verification is pending. |
| `01de31a` / `37206960876` | Unit tests, ktlint, detekt, Android lint, APK/AAB builds and all six device configurations (API 26, 35, 35 16 KB repeat, 36, 36 16 KB, 36 tablet) passed | First fully green run of the branch. Phone fixes along the way: two-row top bar below 560 dp, Actions list sized to the space left, sidebar sized to the editor, crop bar that wraps, and notices that never block taps. Still not artist or latency validation. |
| `4013f5f` / `37379467644` | All seven jobs green (build, JVM tests, static analysis and the API 26, 35, 36, 35 16 KB repeat, 36 16 KB and tablet device lanes) with .procreate, Procreate brush and Photoshop .abr import, text USD, 26 blend modes, Edit Shape, eyedropper loupe, Continue Filling, secondary colour, burnt edges, grain depth, motion filtering and private photos. Emulator evidence only, not artist or physical-device validation. |
| `1ef5511` / `37429445223` | All seven jobs green (build, JVM tests, ktlint, detekt and the API 26, 35, 36, 35 16 KB repeat, 36 16 KB and tablet device lanes) with the Reflected gradient, Bicubic transform, Snapping Distance, show-only-this-layer, Directional Perspective Blur, rotated 2D grid, Curves points and the Time-lapse Recording switch. The `ArtworkWorkflowTest` SnapshotStateObserver failure seen on two lanes of run `37423489493` did not recur; its cause is not yet identified | Physical-device validation |
| `7d00e82` / `37437825111` | All seven jobs green with the Curves brightness histogram. API 35 passed on the single re-run after the emulator's system_server crashed (SIGSEGV in SettingsProvider and android.display, no app frames) on the first attempt | Emulator evidence only; physical-device validation outstanding |

Local Python artifact-verifier suite: 30 tests passed during the saved-library implementation.
New Kotlin/UI/storage tests are not represented as locally executed Android tests.

Screenshot collection uses UTP's `additionalTestOutputDir`. Earlier app-owned external captures
were removed during package uninstall; `TestEvidence` and CI now retain the host-side additional
output. Actual captures must still be inspected; no missing capture is replaced with a mockup.
Existing detekt baselines and Android lint warnings remain separate from a passing check result.

## Acceptance gates before any parity claim

1. **Functional:** every gap above is implemented or explicitly excluded from an agreed scope.
   Equivalent reference tasks must complete without placeholders or disconnected controls.
2. **Document safety:** independently checked pixels, masks, layers, frames and metadata survive
   edit → undo → redo → save → reopen, cancellation, process death and low storage.
3. **Interaction:** compact phones and tablets, portrait/landscape, large text, screen readers,
   left/right-handed use and stylus/finger navigation have recorded acceptance results.
4. **Performance:** record input-to-visible-stroke latency, frame-time percentiles, peak memory,
   document open/save and export duration on named physical devices and representative canvases.
   Compare like-for-like tasks; do not invent physical results from an emulator.
5. **Visual/usability:** inspect actual application captures and conduct artist tasks. A developer's
   judgement, matching colours or increasing a feature count does not prove equal/superior quality.

## Primary sources

These references document Procreate; they do not validate ArtFlow.

[1]: https://help.procreate.com/procreate/handbook/interface-gestures/interface
[2]: https://help.procreate.com/procreate/handbook/brushes/brush-studio
[3]: https://help.procreate.com/procreate/handbook/transform
[4]: https://help.procreate.com/procreate/handbook/layers/layers-organize
[5]: https://help.procreate.com/procreate/handbook/actions/actions-canvas
[6]: https://help.procreate.com/procreate/handbook/selections
[7]: https://help.procreate.com/procreate/handbook/guides
[8]: https://help.procreate.com/procreate/handbook/colors/colors-profiles
[9]: https://help.procreate.com/procreate/handbook/animation
[10]: https://help.procreate.com/procreate/handbook/actions/actions-video
[11]: https://help.procreate.com/procreate/handbook/gallery/gallery-import-share
[12]: https://procreate.com/procreate
[13]: https://help.procreate.com/procreate/handbook/brushes/brush-library
[14]: https://help.procreate.com/procreate/handbook/adjustments
[15]: https://help.procreate.com/procreate/handbook/3d-painting
