# WP2 - Adjustment depth (engine + UI): Curves, Colour Balance, Gradient Map, Bloom, Glitch, Halftone, Chromatic Aberration, Noise, Perspective Blur

Read scratchpad/COMMON_BRIEF.md first, then scratchpad/audits/layers-selection-adjust.md (ADJUSTMENTS section). Package name for resource files: `adjust`.

## Context
Adjustments are destructive, live-previewed effects on the active layer (or its selection), Procreate style: Adjustments menu -> pick one -> slide a finger on the canvas (amount effects) or use a bottom bar (colour adjustments) -> Apply/Cancel,
with Layer vs Pencil modes (Pencil paints the effect on with a soft dab). Code map:
- core/pixels/LiveAdjustments.kt: `Kind` enum, `Settings(amount, parameters: Map<String, Float>, angleDegrees)`, `apply(kind, source, settings, selection, alphaLocked, checkpoint)`, bloom/glitch/halftone/recolor/perspectiveBlur implementations.
- core/pixels/AdjustmentProcessor.kt: colour maths shared with NON-DESTRUCTIVE adjustment layers (AdjustmentType in domain/model/layer/AdjustmentType.kt with `defaultParameters`/`parameterRanges`; adjustment layers persist `adjustmentParams: Map<String, Float>` in .artflow documents and the Compositor applies them).
  `buildCurveLut` already does monotone-cubic LUTs from up to five control points keyed `point_N_x`/`point_N_y`.
- core/pixels/ImageFilters.kt: blur, motion blur, noise, sharpen, chromatic aberration helpers.
- presentation/ui/components/editor/AdjustmentOverlay.kt (+ presentation/ui/viewmodel/AdjustmentSessionController.kt, AdjustmentPaint.kt): the live UI. Parameter UI today is `visibleParameters()` = plain sliders.
Persisted parameter maps are `Map<String, Float>`: any new parameter scheme must encode into that (e.g. `curve_r_n`, `curve_r_0_x`, ...), keep old keys working (old documents/adjustment layers must render identically) and have defaults for missing keys.

## Gaps to close (audit): make each match Procreate's behaviour as closely as you can from memory of Procreate 5.x
1. **Curves**: Gamma (combined RGB), Red, Green, Blue curves; an on-screen graph editor (histogram optional) with draggable control points (tap to add, drag to move, drag off / double tap to remove, 2..16 points per curve, endpoints movable vertically), channel selector, Reset; monotone cubic or Catmull-Rom LUT per channel;
   engine + non-destructive adjustment layers support the same params. Backwards compatible with the existing 5-point single-curve keys (treat as the combined curve).
2. **Colour Balance**: Shadows / Midtones / Highlights tabs, each with Cyan-Red, Magenta-Green, Yellow-Blue sliders, using smooth tonal weighting functions (e.g. Photoshop-style) and preserving alpha; old global keys map to midtones or equivalent so old layers look unchanged.
3. **Gradient Map**: a gradient with N colour stops (positions + colours, optionally alpha), applied by luminance; a library of >= 12 built-in gradients (sepia, duotones, sunset, thermal, cool/warm, etc.) shown as swatches; an editor (add/move/delete/recolour stops; tapping a stop opens the existing colour picker or a simple HSV control);
   reuse core/tool/GradientTool.kt stops/presets code if it fits. Encode stops into the parameter map. Old hue keys stay supported.
4. **Bloom** (Procreate: Transition, Size, Opacity, Burn): implement a proper threshold/blur/screen bloom with those controls (amount on the canvas slide controls the main strength; extra sliders in the bar).
5. **Glitch**: several modes with parameters, e.g. Artifact (blocky JPEG-like displacement), Wave (sinusoidal row displacement), Displace (random band offsets), Signal (RGB split + scan lines), Tear; a mode selector + amount (+ seed so previews are stable between slider moves).
6. **Halftone**: Full Colour (CMY-like rotated dot screens), Screen Print/Mono, Newspaper (45 degree black dots), with Size and Sharpness; anti-aliased dots; alpha preserved.
7. **Chromatic Aberration**: Perspective (radial from a movable centre point) and Displace (directional) modes; amount.
8. **Noise**: tileable Perlin/fBm noise option with Scale, Octaves/Persistence, mono or colour, plus the current white noise; amount via slide.
9. **Perspective Blur**: add a directional mode (as in Procreate: Position and Directional) in addition to the existing positional one.
For each: live preview must stay responsive: per-row cancellation checkpoints in long loops (see how ImageFilters.gaussianBlur uses `checkpoint`), no per-pixel allocation, results independent of thread order (deterministic given settings), alpha handling consistent with LiveAdjustments (alpha lock, selection masks).

## UI
- Extend AdjustmentOverlay so each Kind shows the right controls: mode chips, sliders with numeric values, the Curves graph (a new composable file, e.g. presentation/ui/components/editor/CurvesEditor.kt, drawn with Canvas + pointerInput; handle multi-touch gracefully; touch targets >= 48dp for handles via a larger hit radius),
  the Gradient Map editor (new file GradientMapEditor.kt). Keep the existing Layer/Pencil/Apply/Cancel row. Content descriptions for graph and handles (semantics).
- Non-destructive adjustment layers (Layers panel "Add adjustment layer", presentation/ui/components/editor/EditorSheets.kt) should get the improved Curves/Colour Balance/Gradient Map controls too if it is straightforward (reuse the same composables); at minimum they must not break.

## Tests
JVM tests for: LUT monotonicity/identity for default curves, per-channel curve application on known pixels, curve parameter encode/decode round trip incl. old keys; colour-balance tonal weighting (shadows affects dark pixels more than bright, alpha preserved, identity at zeros);
gradient map endpoints/interpolation and library validity; bloom/glitch/halftone/noise determinism (same settings -> identical buffers), selection masks/alpha lock respected, no changes where amount = 0; checkpoint cancellation stops work early.
Render before/after PNGs of a synthetic test image (gradient + shapes + a photo-like noise field) for each effect and LOOK at them (Read tool) to confirm they look like the named effect; keep the images in your scratchpad, not in the repo.

## Acceptance
Full JVM suite green; Android compile + lint harness green if available (otherwise be extra careful); old documents with adjustment layers still load (add a test with the old keys); report which visual outputs you inspected.
