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
| Brush creation and feel | Brush Studio, custom shape/grain sources, dual brushes [2] | Pressure curves, taper, jitter and three procedural grains exist. A searchable eight-preset library, staged settings, exact numerical entry and an isolated drawing pad are now connected. Persistent custom presets, imported/dual textures, a comparably curated library and physical-stylus evidence remain missing. Parameter count is not brush quality. |
| Transform | Uniform/freeform scale and rotate, distort, warp, snapping and interpolation choices [3] | Reachable MOVE/TRANSFORM gestures translate only. An unused helper with scale/rotation is not a professional transform workflow. Require preview/apply/cancel, handles, pivot, selection/mask alignment and no cumulative preview resampling. |
| Layer organization | Multi-selection, drag reordering and nested groups [4] | Masks, adjustments, opacity and blending exist; up/down reorder controls are now reachable and topmost-first. No usable layer groups or linking UI. Require atomic group mutations, group compositing, retained children and undo/save/reload tests. |
| Reference companion | Floating canvas/image reference, pan/zoom, sampling [5] | A bounded, movable image companion now supports pan/zoom/fit and preview colour sampling. Missing: live canvas view, manual resize and document-embedded references. The separate `isReference` layer flag is excluded from export, unlike Procreate's fill-reference concept; do not conflate them. |
| Selections and filling | Automatic/freehand/rectangle/ellipse selection and ColorDrop [6] | Core selection masks and bucket filling exist. Missing or unverified: drag-from-colour fill, interactive threshold feedback, reusable selection workflow and equivalent gesture ergonomics. |
| Drawing assistance | QuickShape, grids, perspective and symmetry [7] | Symmetry/perspective exist. Shape tools are not press-and-hold QuickShape recognition. Recognition, editable shapes and assisted-line feel need distinct implementation and testing. |
| Colour management | RGB/CMYK profiles and imported ICC profiles [8] | RGB/HSV/CMYK controls do not establish ICC-managed painting or print colour fidelity. Profile-aware working spaces and tagged import/export remain unverified/missing. |
| Animation | Animation Assist with timeline and onion skin [9] | Basic frame editing, playback and animated exports exist; the previously disconnected timeline panel is now reachable. Need artist-tested navigation/timing, long-session memory tests and cross-tool rendering fidelity. |
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

## Milestone 2 — reference companion and connected editor workflows

The menu now exposes Guides, Animation, Canvas and Text; pending text anchors open the text panel.
Place closes the panel so the user can choose a canvas position, then confirms actual rasterisation.
Close cancels an uncommitted anchor. Wrapping options and named slider semantics improve compact
screen/accessibility use. Layer ordering now has real up/down actions and correct topmost-first
presentation, with normal-size/large-size row buttons and only one expanded opacity control.
Canvas background state follows the repository through edits and undo/redo.

The reference companion accepts picker-granted content URIs, decodes a maximum 1024×1024 preview
with the existing Coil dependency, and has pan/zoom/fit, header dragging, long-press/pick-mode
sampling and an accessible centre-sample action. Window position remains inside available space;
image movement and sampling share `ReferenceViewport`. Changing the source cancels the previous
load, and stale content is not shown as the new source. No reference pixel enters document history,
layers or export; the chosen colour changes brush state. No new permissions or dependencies.

Limits: source embedding/persistent URI grants, live canvas reference, manual window resizing and
ICC-aware full-source sampling remain missing. A denied or expired URI requires choosing again.
This is not a claim of complete equivalence to Procreate's reference workflow.

Added checks: six JVM geometry tests (including 10,000 generated mappings), one real-repository
background-colour ViewModel test, five reference UI/decoder tests and expanded real-app workflow
assertions for menu routes, layer reordering, text placement/cancellation/undo and reference closing.
Local dependency-free execution of the actual geometry code passed all six groups; 30 Python
verifier tests passed. Gradle/JUnit and device execution require the exact-commit CI run.

### Evidence collection repair and retained failure

Run `35443960450` for milestone 1 built the APK/AAB, passed JVM/static/artifact checks and four
device configurations. Its first API 35 lane stopped after 62 of 146 cases with a native SIGSEGV.
The retained stack names generated `GraphicsLayer.getShadowElevation` and
`GraphicsLayer.configureOutlineAndClip` code. This is not a proven application or emulator
root-cause diagnosis. The independent API 35 repeat passed, and an unchanged retry was requested;
its result must not erase the failed attempt. Failed artifact: `10585181281`.

The successful lane's UTP log also showed why prior workspace screenshots were missing: files were
written in an app-owned directory removed at uninstall. `TestEvidence` now uses the actual
`additionalTestOutputDir` argument, and CI archives the corresponding host output. This repairs
the evidence path rather than inventing screenshots or calling the old visual inspection complete.

## Milestone 3 — reachable advanced brush controls

The previously unscrollable advanced-brush column is now scrollable within the editor's bounded
brush dialog. Named slider semantics expose all parameters to accessibility actions. Section
headers truncate rather than crowding the collapse control, and each collapse/expand action names
its section. Two Android tests edit parameters from the first to last section in a 260-dp-high
viewport and confirm collapse/reopen does not reset them. This fixes control reachability; it does
not add imported textures, a curated brush library or prove physical drawing-feel equivalence.

### Milestone 2 verification corrections

Run `35445726893` executed 402 JVM tests (zero failures/errors/skips). Android lint had no errors,
but ktlint/detekt blocked the build job; the device lanes could not compile a test using
`SemanticsProperties.CustomActions` instead of `SemanticsActions.CustomActions`. The next revision
corrects that API reference, follows expression/argument formatting, separates the reference state
model into its own file, uses an explicit locale for hex formatting, and simplifies range guards
without removing validation. No assertions, lint rules, baselines or device lanes were weakened.
It must receive its own completed build and device results before it is called verified.

## Milestone 4 — searchable Brush Studio, staged edits and isolated practice

The previous brush-settings dialog is replaced by a bounded studio with Library, Settings and
Drawing pad tabs. Eight original presets provide distinct real-engine starting points, with
case-insensitive multi-word search and category filtering. Library samples and parameter previews
run on worker dispatchers, using the same production rasterizer as the painting engine.

Brush changes remain a local draft until Use brush. Close/Back/outside dismissal discards changes;
Restore initial returns to the opening parameters. Size and opacity join the dynamics controls.
Every parameter value opens validated numeric entry; percentages map to engine values and integer
controls reject fractions. No invalid input is silently clamped into a different accepted value.

The drawing pad stores immutable test paths and re-renders them as the draft changes. It never
calls the document repository. Bounds are explicit: 320×180 pixels, eight retained strokes,
128 samples per stroke and a 48-pixel preview size cap. Multi-pointer/cancelled gestures are not
committed. Tests do not establish latency, realistic media fidelity or physical-stylus parity.
There is still no custom preset persistence/import, dual-brush workflow or imported shape/grain.

Verification additions: seven shared core/JUnit checks cover preset search/identity, distinct and
deterministic real previews, numeric validation, independent practice buffers, renderer equality
and work caps. Compose tests cover draft cancellation/reset/apply, exact values, tab retention,
clearing and cancellation. The actual artwork workflow checks that practice preserves document
pixels and undo depth. Local standalone compilation of the production renderer/math passed all
seven groups; only unused platform/serialization types were adapted for compilation. Thirty Python
verifier tests passed. Full Android/JUnit/static execution requires the published revision's CI.

The reference behavior for staged editing, numerical settings and a re-rendering drawing pad is
Procreate's Brush Studio [2]; searchable libraries are documented separately [13]. These sources
specify workflows, not proof that ArtFlow matches their artistic quality.

### Follow-through on failed candidates

Candidate `e8c4032`, run `35446449113`, passed all five device configurations. Its JVM test for
background-colour undo/redo assumed a virtual test clock had drained real worker notifications.
Commit `68d29a3` instead waits for the expected observable state with a five-second real deadline,
retaining exact assertions. That run's JVM step passed. Its next static step exposed remaining
chain-formatting findings in reference/evidence code, corrected in this milestone with no rule,
baseline, assertion or device-lane removal. Earlier failed runs are not relabelled as passes.

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
[7]: https://help.procreate.com/procreate/handbook/guides
[8]: https://help.procreate.com/procreate/handbook/colors/colors-profiles
[9]: https://help.procreate.com/procreate/handbook/animation
[10]: https://help.procreate.com/procreate/handbook/actions/actions-video
[11]: https://help.procreate.com/procreate/handbook/gallery/gallery-import-share
[12]: https://procreate.com/procreate

[13]: https://help.procreate.com/procreate/handbook/brushes/brush-library
