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

| Workflow | Reference product | ArtFlow now | Remaining work |
| --- | --- | --- | --- |
| Canvas-first workspace | Primary painting controls, movable sidebar, hide-interface mode [1] | Compact dock, focus mode, explicit panel closing, neutral contrast-tested themes and split-view tablet Brush Studio | Handedness-aware sidebar, anchored canvas panels, broader adaptive-layout and artist validation |
| Brush library | Search, organization and management [13] | Eight original starter presets; searchable saved copies with rename and confirmed deletion | Favorites, user sets, preset interchange, a professionally curated and artist-tested collection |
| Brush Studio | Staged settings, numerical input, re-rendering drawing pad, custom shapes/grains and dual brushes [2] | Local draft/apply/cancel/reset, exact values, pressure curves, procedural grains, isolated practice, complete-parameter saved copies | Imported shape/grain assets, dual-brush workflow, tilt-aware rendering and physical-stylus response validation |
| Transform | Scale, rotate, distort, warp, snapping and interpolation controls [3] | Reachable MOVE/TRANSFORM translation only | Interactive handles/pivot, preview/apply/cancel, mask/selection alignment and no cumulative preview resampling |
| Layer organization | Multi-selection, drag ordering and nested groups [4] | Topmost-first stack, active-layer up/down, masks, opacity, blending, adjustments and filters | Nested groups, multi-selection/linking UI, atomic group operations with faithful composition and reload |
| Reference companion | Floating canvas/image reference with sampling and navigation [5] | Movable image window with bounded decode, pan/zoom/fit, long-press/pick-mode sampling | Live canvas view, manual resize and source persistence; sampling currently uses a decoded preview |
| Selections and fill | Automatic/freehand/rectangle/ellipse selection and ColorDrop [6] | Selection masks, boolean combination, invert/feather and bucket fill | Drag-from-colour fill, interactive threshold feedback, reusable selections and equivalent ergonomics |
| Drawing assistance | QuickShape, grids, perspective and symmetry [7] | Shapes, symmetry and perspective guides | Press-and-hold shape recognition, editable recognized shapes and validated assisted-line feel |
| Colour | Working-space profiles and imported ICC profiles [8] | Wheel, RGB/HSV/CMYK controls, hex, harmonies and palettes | ICC-managed working spaces and tagged interchange; CMYK sliders are not print colour management |
| Animation | Animation Assist timeline and onion skin [9] | Reachable frame editing, duration/FPS, playback, onion skin and animated export | Artist-tested navigation/timing, long-session memory and cross-tool fidelity |
| Drawing timelapse | Process recording and video export [10] | Not implemented | Capture/replay/export of drawing progress; animation export is not timelapse recording |
| File interchange | Image/layered-document import and sharing [11] | PNG/JPEG/WebP/PDF/PSD/GIF/MP4 and frame-sequence export | PSD import and broader external-application round-trip fixtures |
| Page and 3D workflows | Page Assist and 3D painting [12] | Not implemented | Remain explicit gaps for full product equivalence |
| Reliability and performance | Must be compared through executed tasks, not marketing claims | Automated engine/storage/export/UI tests; CPU painting/composition with GL ES 2.0 display | Physical latency, sustained frame times, large documents, interruption, low storage and long-session evaluation |

Cloud collaboration and smart objects are separate ArtFlow roadmap ideas, not asserted Procreate
features. Telemetry is intentionally absent and is not a parity requirement. Procreate is a workflow
reference; ArtFlow uses its own assets and identity. No aesthetic superiority is asserted without
running-app inspection and artist/user evidence.

The stored `isReference` layer flag is excluded from ArtFlow export. It is **not** equivalent to
Procreate's fill-reference layer concept or the new floating reference-image companion.

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

Inspection also found a misleading implication in the previous documentation: storing
`tiltInfluence` and `tiltToRotation` does not make tilt active. Neither field is consumed by the
current rasterizer. The general rotation field likewise does not rotate a shaped tip; round tips
are still used. Grain rotation remains a separate, implemented texture operation. These gaps stay
open instead of receiving disconnected UI controls or a feature-completion checkmark.

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
| 2 | Useful transformations and layer organization | Reconcile unmerged PR #34 without overwriting newer work. Then transform selected/masked art with handles and cancel/undo/redo; organize nested groups and preserve composition on reload. |
| 3 | Artist-grade brushes | Imported shape/grain sources, real tilt-aware tips, dual brushes and curated presets. Compare named physical styluses using the same pressure/tilt/speed tasks, not parameter counts. |
| 4 | Drawing assistance and colour fidelity | Editable hold-to-recognize shapes, interactive colour-drop filling, saved selections, ICC-tagged workflows and external layered-document fixtures. |
| 5 | Broader Procreate workflows | Drawing-process timelapse, Page Assist and 3D painting remain explicit product-scope gaps. |

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
