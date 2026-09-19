# Procreate parity ledger

Last reviewed: September 19, 2026. **Status: parity not reached.**

This is an engineering comparison, not an assertion that ArtFlow matches Procreate because a
similar feature name exists. It separates implemented code, complete user workflows, and measured
quality. Procreate is an iPad product; Android hardware and stylus results must be measured on
Android rather than inferred from the iPad experience. We do not copy its branding or assets.

## Verified comparison sources

Official product and handbook references, consulted for this comparison:

- [Product capabilities](https://procreate.com/procreate)
- [Interface and gestures](https://help.procreate.com/procreate/handbook/interface-gestures/interface)
- [Brush Studio settings](https://help.procreate.com/procreate/handbook/brushes/brush-studio-settings)
- [Layers handbook](https://help.procreate.com/procreate/handbook/layers)
- [Transform interface](https://help.procreate.com/procreate/handbook/transform/transform-interface-gestures)
- [Complete handbook](https://help.procreate.com/procreate/handbook/introduction)

## Gap assessment

| Area | Procreate benchmark | ArtFlow evidence and remaining gap |
| --- | --- | --- |
| Painting feel | Pressure-sensitive painting, stabilization, expressive brushes and an interaction-focused canvas | Pressure curves, smoothing, dynamics and grains exist. No measured physical-pen latency, pressure/tilt accuracy, long-stroke consistency or artist comparison demonstrates equivalent feel. |
| Brush authoring | Extensive Brush Studio, custom shape/grain inputs and dual brushes | Many parameters and three procedural grains exist. Imported texture library, custom brush shape/dual brushes, curation and complete brush interchange remain gaps. |
| Canvas interface | Compact painting controls, size/opacity access, gestures and full-screen interaction | The original editor has a crowded app bar plus three permanent bottom rows. Workspace refinement is a separate milestone, not proven by new transform controls. |
| Transform | Freeform/uniform transform, distortion, warp, snapping, interpolation and handles | New: numerical move, uniform/free scale, rotation, horizontal skew and flips, with smooth/pixel-art sampling and one undo. Still missing: live handles/preview, selected-content transforms, movable pivot, perspective/warp and snapping. |
| Layer organization | Layer groups, masks and efficient organization | Layers, clipping, editable masks, blending, adjustments and filters exist. Groups/collapse and linked-layer user workflows remain missing. |
| Drawing assists | QuickShape, Drawing Assist, perspective/isometric/symmetry tools | Symmetry and perspective guides exist. Editable QuickShape recognition and fully comparable assisted drawing are not established. |
| Colour management | Colour interfaces, palettes and document colour profiles | Wheel/sliders/harmonies/palettes exist. An RGB/CMYK slider is not ICC-managed RGB/CMYK document storage or wide-gamut/print fidelity. Those need explicit implementation and tests. |
| Selections | Multiple selection modes and a full editing workflow | Boolean combining, feathering and shape/colour selection exist. Transforming selected content with a matching interactive preview is not implemented. |
| Reference workflow | Reference companion view and import/interaction | The reference-layer flag is not a reference window. No comparable import/resize/sample companion is available. |
| Animation and pages | Animation Assist, Page Assist and playback workflows | Frames, onion skin and export exist. Page Assist and equivalent timeline ergonomics are not established. |
| Replay | Automatic time-lapse capture and export | Animation export is not time-lapse recording. Recording/edit replay remains missing. |
| Interchange | Broad import/export including PSD workflows | PNG/JPEG/WebP/PDF/PSD/GIF/MP4/frame-sequence export exists. PSD import and independently checked Photoshop round-trip fidelity remain gaps. |
| Text and fonts | Rich text controls and font import | Text and styling exist; imported fonts and typography parity have not been verified. |
| 3D painting | Painting on imported 3D models | Not implemented. This is a product-scope gap rather than a small UI task. |
| Reliability and performance | Mature artwork editing on supported devices | Prior exact-commit automated evidence exists, but every new milestone must pass its own checks. Large documents, low memory, physical styluses, GPUs/codecs and long drawing sessions need measured evidence. |

Cloud collaboration and smart objects are independent ArtFlow roadmap ambitions, not asserted here
as Procreate features. Native-code labels, a higher tool count and a passing build are not quality
measurements. No numerical "percentage of Procreate" is assigned without a justified rubric.

## Acceptance gates before claiming equivalent quality

1. **Workflow completeness:** an artist can perform comparable drawing, masking, transform,
   organisation, colour, reference, animation and interchange tasks without missing routes or
   misleading controls. Validate artwork results, cancellation and recovery, not only screenshots.
2. **Drawing quality:** record pen-to-visible-stroke latency distributions, dropped input samples,
   frame times, memory usage and export/save latency on named low/mid/high Android devices and
   physical styluses. Define targets against those devices before measuring; do not invent scores.
3. **Visual and interaction review:** inspect actual phone/tablet screenshots in dark/light modes,
   large text and both orientations. Check reachable controls, contrast, semantics and accidental
   touches. Artist task testing must assess discoverability and perceived drawing feel.
4. **Document safety:** exact-candidate unit/static/build/device checks, edits and undo, cancellation,
   save/reopen/recovery, interruption during export and long-session stress all have documented
   results. Publisher signing, store requirements and privacy evidence are separate release gates.

## Milestone log

### 1 — Numerical layer transforms

Implemented the inverse-mapped affine kernel, exact controls, one-step pixel/mask repository
transaction, input guards and honest scope messages. Source-level and offline kernel verification
are recorded in the README; normal Android execution evidence must name the candidate revision.
A numerical transform panel is **not** equivalent to Procreate's interactive transform workflow.
