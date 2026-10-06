# ArtFlow / Procreate workflow comparison

Assessed 19 September 2026 against **Procreate for iPad**, not Dreams or Pocket.
Original comparison baseline: `c2e4eee1ef0cc1e65ebfd49ca7c01b9f2b94d5df`.
**Overall parity has not been reached.** This is a workflow gap register and acceptance contract,
not a release certificate. A feature counts only when it is reachable and behaves correctly in the
editor; a helper class, placeholder control or passing compile does not establish completion.

Latest inspected remote baseline: `019648ea501c8de5f3d0af04fc16bc97b05d8923`. The comparison
below describes that remote baseline. Local candidate additions and their unexecuted Android gates
are recorded separately; they are not silently counted as shipped capabilities.

## Current comparison

Updated 6 October 2026 for the `claude/ecstatic-gauss-3nkeht` branch. Rows describe what is
reachable in the editor; device validation by artists is still outstanding for every row.

| Workflow | Reference product | ArtFlow now | Remaining work |
| --- | --- | --- | --- |
| Canvas-first workspace | Top bar, movable sidebar, hide-interface mode [1] | Gallery/Actions/Adjustments/Selection/Transform and Paint/Smudge/Erase/Layers/colour top bar; sidebar with size and opacity sliders, eyedropper button and undo/redo; right-hand interface, light interface, brush cursor and dynamic brush scaling options; full screen by four-finger tap; anchored popovers on tablets | Artist validation of layout and reach on phones and tablets |
| Gestures | Taps for undo/redo, pinch navigation, Copy & Paste swipe, clear scrub [1] | Two/three/four-finger taps, two/three-finger hold for rapid undo/redo, pinch/rotate/pan, quick pinch to fit, three-finger swipe for Copy & Paste, three-finger scrub to clear, touch-and-hold eyedropper with a 1, 3×3, 5×5 or 11×11 pixel sample (or Layer Select: the hold picks the topmost layer painted under the finger), QuickMenu ring from holding the modify button (touch and hold a slot to choose its action), a History scrubber from holding undo or redo (slide through every kept step, which Procreate cannot do), stylus hover outline and side-button eyedropper; scrub, swipe and four-finger tap can be switched off in Prefs, and the rapid undo delay is adjustable (0.2 to 1.5 s) | Artist validation on phones and tablets |
| Actions | Add, Canvas, Share, Video, Prefs and Help [1] | Tabbed Actions panel with those six tabs (Add includes take a photo, insert a private photo that stays out of the time-lapse, Cut & Paste and Duplicate), including Pressure and Smoothing preferences (pressure curve, stabilization, motion filtering with expression, pressure smoothing and a pulled-string stabiliser that Procreate lacks), Selection mask visibility, and Project canvas, which shows the artwork alone on a second screen (HDMI, cast or wireless display) | Artist review of handbook wording |
| Brush library | Search, organisation and management [13] | 200 original presets in 18 categories, matching the size of a full professional library (adding touch-ups, retro, luminance, industrial, organic, water and abstract sets); searchable saved copies with rename and deletion; Favourites and your own brush sets; the eraser and Smudge can each keep a brush of their own (and their own size); `.artbrush` share and import that carry imported images; Procreate `.brush` and `.brushset` files import as new brushes in the set's order, with their names, shape and grain images (inverted where the brush says so) and their settings read from the brush archive: spacing, jitter, scatter, count, roundness, size and opacity limits, pressure, speed, tilt and colour dynamics, tapers, wet mix, flow, blend mode and grain scale and movement. Photoshop `.abr` brush files (versions 1, 2 and 6 and later) import their sampled tips as brush shapes, named from the presets that use them | A professionally curated, artist-tested collection |
| Brush Studio | Staged settings, drawing pad, custom shapes/grains and dual brushes [2] | Draft/apply/cancel, exact values, pressure curves, procedural and imported grains with depth, brightness and contrast and blend (Multiply, Subtract, Hard mix), imported shape tips, flat tips, per-brush blend mode, the six rendering modes (Light, Uniform, Intense and Heavy glaze; Uniform and Intense blending), wet edges, burnt edges and wet mix with dilution, pull and blur, secondary colour pressure and jitter, colour jitter per dab or once per stroke, colour pressure and colour tilt (hue, saturation and brightness), count jitter, stylus tilt to size/opacity and tip angle, pressure to flow, dual brushes (Multiply, Subtract, Add), stroke fall off, taper opacity and tip sharpness, a separate Touch taper for finger strokes, minimum and maximum size and opacity (the sidebar sliders span them), Smudge that carries the brush's tip and grain | Artist-tested tuning of presets and dynamics |
| Adjustments | Live adjustments in Layer and Pencil modes [14] | Hue/Saturation/Brightness, Colour Balance, Curves (a draggable five-point curve for Gamma, Red, Green and Blue), Gradient Map (colour ramps: Noir, Heat, Ocean, Sepia, Dusk, Forest, Neon and Duotone, with Reverse), Opacity, Gaussian, Motion and Perspective Blur, Noise (Grain, Clouds, Billows and Ridges, with size), Sharpen, Bloom (Transition, Size and Burn), Glitch (Signal, Artifact, Wave and Diverge), Halftone (Full colour, Screen print and Newspaper), Chromatic Aberration and Recolor, each with Layer or Pencil mode; Liquify with Push, Twirl Right and Left, Pinch, Bloat, Crystals, Edge and Reconstruct, size, distortion and momentum sliders, a Pressure switch, Adjust (scales the last gesture from none to full) and Reset | Device validation of large-canvas responsiveness |
| Transform | Freeform, Uniform, Distort and Warp with snapping [3] | On-canvas box with corner, edge and rotation handles; Freeform, Uniform, Distort and Warp (4 × 4 Bézier mesh, or a 6 × 6 Advanced Mesh that keeps the current bend when switched on); Magnetics (45° moves, 15° rotation, proportional corners) and Snapping to canvas edges and centre with guides; flip, rotate, fit, reset and interpolation choice | Device validation of large multi-layer moves |
| Layer organisation | Thumbnails, options, swipe actions, groups [4] | Thumbnails, tap-the-selected-layer options, swipe left for Lock/Duplicate/Delete, swipe right to multi-select then Group, Merge, Delete or Transform together, touch-and-hold drag reordering, two-finger swipe for Alpha Lock, two-finger tap then slide for opacity, two-finger hold to select contents, Background colour row with a visibility checkbox, all 26 blend modes in five families (darken, lighten, contrast, inversion, component; Linear Burn, Darker Color, Add, Lighter Color, Vivid, Linear and Pin Light, Hard Mix, Subtract and Divide included and kept through PSD), blend-mode codes, groups that nest (group groups; drag a layer or a whole group into or out of a group), whose blend mode and opacity apply to the merged group, editable text layers, and non-destructive layer effects that Procreate lacks: an outline and a drop shadow drawn around the layer's pixels, kept up to date as you paint | Artist validation of deep group stacks |
| Reference companion | Floating canvas/image reference [5] | Movable window with a live Canvas view or an imported image, pan/zoom/fit and colour sampling | Artist validation of window placement on phones |
| Selections and fill | Automatic/freehand/rectangle/ellipse, Save & Load, ColorDrop [6] | Automatic with slide-to-set threshold, freehand, rectangle, ellipse; Add/Remove; invert, feather, Save & Load, Colour Fill, Copy & Paste; ColorDrop with slide-to-set threshold and Continue Filling (each further tap fills another area at the same threshold), Reference layers that bound ColorDrop, gap closing (up to 16 px) so fills stay inside line art that is not quite joined, and a Lasso Fill tool that fills a traced shape in one stroke; Procreate has neither | Artist validation of fills over anti-aliased line art |
| Drawing assistance | QuickShape, grids, perspective and symmetry [7] | QuickShape lines, polylines, ellipses, triangles, rectangles and polygons, adjustable while held with 45° line snapping, and Edit Shape afterwards (drag line ends, corners or ellipse axis ends; the stroke is redrawn as one undo step); symmetry (vertical, horizontal, quadrant and radial, with the axis dragged on the canvas, with Rotational symmetry and mirrored radial segments for kaleidoscope patterns), perspective (with Edit vanishing points: drag the points on the canvas), isometric and grid guides (with a Grid size slider, and guide colour, opacity and thickness) that assist only layers with Drawing Assist; stabilization | Device validation |
| Colour | Disc, Classic, Harmony, Value, Palettes; ICC profiles [8] | Disc, Classic, Harmony, Value and Palettes tabs with history, a previous-colour swatch and a secondary colour swatch (tap to swap; brushes can blend toward it by pressure or per dab); palette import (ASE, GIMP, hex) and sharing (any palette, as ASE), a default palette (star it) shown under Disc, Classic, Harmony and Value, New from photo, a palette from a camera photo (Palette Capture), New palette, and editing your own palettes (tap the empty cell to add the current colour, touch and hold a swatch to remove it); sRGB or Display P3 canvas profile (New artwork or Actions > Canvas) shown correctly on screen, embedded as ICC in PNG and JPEG exports and converted to sRGB for other formats CMYK print proof on screen (process-ink simulation) with an option to grey out colours that won't print. View checks Procreate lacks: Greyscale (values only), Notan (three flat values) and Protanopia, Deuteranopia and Tritanopia colour-vision simulation, on screen only. | Artist validation of the print proof against real press output |
| Animation | Animation Assist timeline and onion skin [9] | Frame editing, duration/FPS, loop and ping-pong, background and foreground frames, onion skin frames, opacity, Color Secondary Frames and Blend Primary Frame, and animated export | Artist-tested navigation and timing |
| Drawing timelapse | Recording, replay and export [10] | Automatic capture, in-app replay with scrubbing, full-length or 30-second MP4 export, 720p, 1080p or 1440p recording | Device validation of 1440p encoding |
| Gallery | Stacks, preview, import and share [11] | New artwork from 15 canvas presets or a custom size that can be saved as your own preset, named by its size (and deleted), import of photos and layered PSD files as new artworks, stacks with drag-to-stack (touch and hold, drop on an artwork or stack), Select mode to stack, duplicate or delete several artworks, full-screen swipe preview, share, photo import as a new canvas | Artist validation of drag gestures in long galleries |
| File interchange | Image and layered-document import and sharing [11] | PNG/JPEG/WebP/PDF/PSD/TIFF, animated GIF/PNG and MP4, layers as PNG files and frame-sequence export; `.artflow` artwork files that carry every layer, frame and setting to another device (export from Share, import in the gallery); PSD layer import; photo import PSD export writes layer groups as Photoshop folders (nested, with opacity, visibility and blend), and PSD import rebuilds folders as groups and keeps clipping masks. Images dragged in from another app (split screen or a floating window) and dropped on the canvas become a new layer. Procreate documents (.procreate) import from the gallery as a new artwork of the same size and resolution: nested groups, opacity, all blend modes, visibility, clipping, alpha lock, layer masks and the background colour, decoded layer by layer from LZO or LZ4 tiles; the tile layout and channel order are calibrated against the file's own thumbnail, and documents too large for the device's memory come in flattened. | Validation of .procreate import against files from current Procreate versions (fixtures are synthetic); broader external round-trip fixtures |
| Page and 3D workflows | Page Assist and 3D painting [12] | Page Assist: page strip with thumbnails, add, duplicate, delete and reorder; all pages export as one PDF. 3D painting: import an OBJ, glTF/GLB, USDZ or USD (binary or text) model with texture coordinates from the gallery (or a zip with the model, its materials and textures; the model's base colour texture becomes the first layer, and a model with several materials gets one artwork with a cell per material texture); a 3D window shows it lit and wearing the artwork (Mesh shows its triangle edges, like Show 3D mesh), turns and zooms with two fingers, and paints on the model with the current brush (strokes land on the active layer of the texture, so undo, layers and export work as usual); Share exports the painted model as OBJ, material and texture Lighting studio: six presets (Studio, Soft, Sunset, Rim, Moonlight, Flat) and sliders for light angle, height, brightness, fill, warmth and exposure, a second light with its own angle, height and strength (Add light), plus the surface material (metallic and roughness). Strokes on the model follow its surface and run across UV seams: the stroke is brought up to the seam and carries on from it on the next texture island. | Artist validation of 3D painting on real models |
| Reliability and performance | Measured through executed tasks | CPU painting and compositing; strokes in progress and finished strokes recomposite and upload only the area they touched; GL ES 2.0 display; motion prediction draws the brush stroke a few milliseconds ahead of the pen (display only, never painted) Brush dabs visit only the pixels each row of the circle reaches, large dabs are painted on up to four cores, and stroke commits touch only the area the stroke can reach (about 3x faster for large soft brushes on the JVM benchmark). A device test times live-stroke frames on a 2048 x 2048 layered canvas on every CI device lane. Strokes are drawn live: each preview frame stamps only the dabs new pen samples add (the result is pixel-identical to a full redraw, checked for every brush), layers below the painted one are cached in tiles, and the commit lays down the kept dabs instead of redrawing. Measured on the API 26 emulator (2048 x 2048, five layers), frame p50 / p95 / stroke commit: 12 px pen 0.9 / 7.8 / 135 ms; 80 px textured brush 2.8 / 7.5 / 60 ms; 300 px soft airbrush 23.6 / 33.6 / 321 ms (from 141 / 175 / 1114 ms before live strokes and the tight stroke reach). | Physical-device latency and frame-time measurements; a GPU painting engine for very large brushes |

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

At the time, `tiltInfluence` and `tiltToRotation` were stored but not consumed, and rotation did
not turn shaped tips. All three are now live: tilt widens and softens dabs and can turn the tip
with the pen (`StrokeRasterizer`), and shaped and flat tips rotate (`BrushPatch`). Grain rotation
remains a separate texture operation.

Added verification: seven shared core/JUnit cases and five Compose cases. Local standalone checks
compile and execute the actual enum, parameter functions, colour conversions, pixel kernels and
rasterizer, with compile-only adapters for unavailable serialization annotations and unused Android/
Compose type signatures. Android rendering, generated serializers and Compose compilation are not
covered by those adapters. The seven existing Brush Studio check groups also still pass. New
Compose checks require the full device matrix; no baseline, assertion or job is removed.

### Highest-impact work still needed after this candidate

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
