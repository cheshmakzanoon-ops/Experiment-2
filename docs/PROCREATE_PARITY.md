# ArtFlow / Procreate workflow comparison

Assessed 19 September 2026. Baseline: `c2e4eee1ef0cc1e65ebfd49ca7c01b9f2b94d5df`.
This is a gap register and acceptance contract, not a claim of parity or a release certificate.
The reference product is **Procreate for iPad**, not Procreate Dreams or Pocket.

## What counts as comparable quality

A feature must be reachable in the shipping editor, preserve document semantics, survive
save/reopen, behave consistently under undo/redo and interruption, and have executed regression
coverage. Visual quality additionally requires inspecting the running app on compact and large
screens, light/dark modes, enlarged text and accessibility settings. Drawing feel requires measured
latency and artist evaluation on physical styluses. Source review alone cannot establish these.

Procreate is a workflow reference. ArtFlow should use its own artwork, icons, typography decisions
and product identity, not copy proprietary assets. Android-specific advantages must be demonstrated
by tasks and user testing rather than claimed from screenshots.

## Gap register

| Workflow | Procreate reference | ArtFlow assessment / required work |
| --- | --- | --- |
| Canvas-first workspace | Minimal painting controls, sidebar controls, hide-interface mode [1] | This milestone adds a compact primary dock, expandable secondary tools and focus mode. A movable/handedness-aware sidebar, anchored tablet panels and measured ergonomic validation remain absent. |
| Brush creation and feel | Brush Studio, custom shape/grain sources, dual brushes [2] | Pressure curves, taper, jitter and three procedural grains exist. Imported/dual textures, a comparably curated brush library and physical-stylus response evidence are missing. Parameter count is not brush quality. |
| Transform | Uniform/freeform scale and rotate, distort, warp, snapping and interpolation choices [3] | Reachable MOVE/TRANSFORM gestures translate only. An unused helper with scale/rotation is not a professional transform workflow. Require preview/apply/cancel, handles, pivot, selection/mask alignment and no cumulative preview resampling. |
| Layer organization | Multi-selection, drag reordering and nested groups [4] | Masks, adjustments, opacity and blending exist. No usable layer groups or linking UI. Require atomic group mutations, group compositing, retained children and undo/save/reload tests. |
| Reference companion | Floating canvas/image reference, pan/zoom, sampling [5] | At baseline there is no reference window or image-reference workflow. The stored `isReference` flag is hidden from export, unlike Procreate's fill-reference concept; do not conflate them. |
| Selections and filling | Automatic/freehand/rectangle/ellipse selection and ColorDrop [6] | Core selection masks and bucket filling exist. Missing or unverified: drag-from-colour fill, interactive threshold feedback, reusable selection workflow and equivalent gesture ergonomics. |
| Drawing assistance | QuickShape, grids, perspective and symmetry [7] | Symmetry/perspective exist. Shape tools are not press-and-hold QuickShape recognition. Recognition, editable shapes and assisted-line feel need distinct implementation and testing. |
| Colour management | RGB/CMYK profiles and imported ICC profiles [8] | RGB/HSV/CMYK controls do not establish ICC-managed painting or print colour fidelity. Profile-aware working spaces and tagged import/export remain unverified/missing. |
| Animation | Animation Assist with timeline and onion skin [9] | Basic frame editing, playback and animated exports exist. Need artist-tested navigation/timing, long-session memory tests and cross-tool rendering fidelity. |
| Timelapse | Automatic recording and video export [10] | Animation export is not recording the drawing process. Timelapse capture/replay is absent. |
| File interchange | Image and layered document import/share [11] | Several export formats work; PSD import does not. Require layered round-trips and external-application compatibility fixtures, not only self-decoding exports. |
| Page and 3D workflows | Page Assist and 3D painting [12] | Not implemented. These remain scope gaps for a full product-equivalence claim. |
| Reliability and performance | Must be compared by executed tasks, not marketing claims | CPU-side strokes/composition with GL upload; no physical latency, sustained frame-time, memory-pressure or long-session parity measurements. Existing automated tests are valuable but not substitutes. |

Cloud collaboration and smart objects are ArtFlow roadmap ideas, not asserted Procreate features.
Lack of analytics is intentional; adding telemetry is not a parity requirement.

## Milestone 1 — compact workspace and focus mode

Implementation: `StudioToolDock.kt`, `CanvasScreen.kt`.

- Primary painting tools stay one tap away. Any selected secondary tool remains visible after the
  full collection collapses. No existing tool engine or document format is replaced.
- Focus removes the bars but retains the same `ArtFlowCanvasView`. An explicit exit control and
  Android Back restore the workspace before any dirty-document exit prompt.
- Focus/expanded state uses per-project saveable UI state. Switching layout cancels an active
  gesture rather than joining strokes across changed canvas geometry.
- The dock uses stateful accessible buttons and the app's larger-target preference. The new code
  inherits existing light/dark/high-contrast colours instead of hard-coding a second theme.

Added verification: `StudioToolDockTest`; focus assertions and screenshots in
`ArtworkWorkflowTest`. Review exact-commit CI reports and collected `studio-workspace.png` /
`studio-focus.png`. Tests being present is not evidence they have passed. No benchmark or
physical-artist assessment has been completed by this milestone.

## Acceptance gates before any parity claim

1. **Functional:** every gap above is implemented or explicitly excluded from an agreed scope;
   the same reference tasks complete without placeholders or disconnected controls.
2. **Document safety:** independently checked pixels, masks, layers, frames and metadata survive
   edit → undo → redo → save → reopen, cancellation, process death and low storage.
3. **Interaction:** compact phones and tablets, portrait/landscape, large text, screen readers,
   left/right-handed use and stylus/finger navigation have executed acceptance results.
4. **Performance:** record input-to-visible-stroke latency, frame-time percentiles, peak memory,
   document open/save and export duration on named physical devices and representative canvases.
   Set targets and compare like-for-like tasks; do not invent numbers from an emulator.
5. **Visual/usability:** inspect actual application captures and conduct recorded artist tasks.
   A developer's aesthetic judgement is not proof that ArtFlow is equal or superior.

## Primary sources

These links document the comparison product; they do not validate ArtFlow.

[1]: https://help.procreate.com/procreate/handbook/interface-gestures/interface
[2]: https://help.procreate.com/procreate/handbook/brushes/brush-studio
[3]: https://help.procreate.com/procreate/handbook/transform
[4]: https://help.procreate.com/procreate/handbook/layers/layers-organize
[5]: https://help.procreate.com/procreate/handbook/actions/actions-canvas
[6]: https://help.procreate.com/procreate/handbook/selections
[7]: https://help.procreate.com/procreate/handbook/drawing-guides-and-assistance
[8]: https://help.procreate.com/procreate/handbook/colors/colors-profiles
[9]: https://help.procreate.com/procreate/handbook/animation
[10]: https://help.procreate.com/procreate/handbook/actions/actions-video
[11]: https://help.procreate.com/procreate/handbook/gallery/gallery-import-share
[12]: https://procreate.com/procreate
