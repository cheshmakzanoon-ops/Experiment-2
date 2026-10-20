# WP1 - Realistic brush grains and shape tips (pure engine + preset retune)

Read scratchpad/COMMON_BRIEF.md first. Package name for resource files: `grains`.

## Why
The lead rendered all 200 built-in presets with the real engine (scratchpad/shots/presets-*.png; look at a few with the Read tool, e.g. presets-00, -01, -02, -04, -06) and found the textured brushes look artificial:
woven/plaid grids ("Pastel stick", "Burlap", "Linen", "Oil pastel"), big pixel blocks ("Charcoal smudge", "Pebble", "Charcoal side"), regular hatching, per-pixel white-noise "paper", many near-identical soft round Water presets,
an almost invisible Watercolour. Cause: core/render/BrushTexture.kt generates grain by hashing integer lattice cells (no spatial correlation, nearest sampling), and shape tips are only "round" or an imported image.
Procreate's textured brushes (graphite, charcoal, pastel, chalk, oil/acrylic bristles, watercolour paper) read as natural media. This package closes that visible gap without shipping any third-party assets (procedural only).

## Scope
1. **Grain synthesis** (core/render/BrushTexture.kt and a new core/render/GrainSynth.kt or similar): replace each procedural `Kind`'s generator by a spatially-correlated, seamlessly-tiling grain map
   (precompute once, lazily, thread-safe, e.g. 256x256 or 512x512 8-bit or float tiles per Kind, shared across brushes) built from tileable value/gradient noise with fBm octaves, ridged/billow variants, fibre noise (many short random strokes),
   weave/thread structures with per-thread irregularity, Worley/cellular noise, and thresholding with soft shoulders. Sample with BILINEAR interpolation at continuous coordinates (existing u,v rotation/scale math) with wrap-around.
   Targets: PAPER = soft fibrous tooth with mid-frequency variation (like cold-press paper, not salt-and-pepper); CANVAS = irregular woven threads with slubs; CHARCOAL = coarse, slightly directional tooth, ragged, no 2x2 blocks;
   FINE = fine even grain; BLOTCH = soft cloudy watercolour granulation; BRISTLE = long streaks with tapered breaks; SPECKLE = spray-like stipple with varied dot sizes; HALFTONE and HATCH = intentional graphic patterns but anti-aliased (no hard stair-steps).
   Add useful NEW kinds if they improve the library (e.g. sandpaper, concrete/stone, linen, crackle, pencil-tooth, pastel-tooth), each with a stable lowercase `id`. NEVER change or remove existing `Kind.id` values (saved brushes and documents reference them); labels may change.
   Keep the `coverage(x, y)` contract: canvas-anchored, deterministic (same input -> same output, independent of threads/order), 0..1, `depth` semantics unchanged, `scale` 0.25..8 and `rotation` unchanged. It is called per pixel in hot loops (core/render/StrokeRasterizer.kt, core/pixels/Stamping.kt): no allocation, no boxing; measure with a micro-benchmark and report ns/pixel before vs after (must be within ~1.5x of the old cost).
2. **Imported grains** (core/render/CustomGrains.kt `Tile`): sampling must be bilinear too (today nearest => blocky when scaled up). Keep the format and ids unchanged. Consider (only if cheap) a small mip pyramid for scale < 1 to avoid aliasing.
3. **Built-in shape tips**: generate procedural stamp shapes and make them selectable as a brush's shape source: e.g. bristle fan (several bristle streaks), rough round (noisy ragged edge), chalk/pastel edge, charcoal side, dry brush, splatter/blot, sponge, leaf-like speckle.
   Look at how `BrushParams.shapeId`/`CustomGrains.get(id)`/`GrainStorage` handle image tips today and add a built-in registry with reserved ids (e.g. prefix `builtin-`), resolved in-memory WITHOUT being written into saved brush files/.artbrush/.artflow documents (but a saved brush that references a built-in id must still render identically on another install).
   Rendering of image tips already exists (Stamping.kt); confirm rotation/roundness/scatter/count behave sensibly with them.
   Brush Studio UI (presentation/ui/components/brush/AdvancedBrushSettingsPanel.kt, "Shape" section): add a compact picker listing the built-in tips with preview thumbnails next to "Round tip"/"Import shape". Keep the UI change small and accessible (content descriptions, 48dp targets, strings in strings_grains.xml).
4. **Retune the 200 presets** (domain/model/brush/StudioBrushes.kt, StudioBrushesMore.kt, StudioBrushesExtra.kt): use the new grains/tips so each textured preset looks like its name (pencils, charcoals, pastels, chalk, crayon, dry brush, oil/acrylic bristles, impasto, linen, burlap, sandpaper, spray, stipple, texture packs).
   Make the Water set distinguishable (wet wash vs dry wash vs granulating vs bloom: use grain, wet edges, opacity, size, flow, jitter), and make Watercolour/Glazing presets visible but translucent. Do not change preset ids/names/categories (saved libraries reference ids) unless you add new presets; counts may grow.
   Iterate with contact sheets: `ROOT=$PWD .../scratchpad/jvm/run.sh` first (compiles the engine), then `ROOT=$PWD .../scratchpad/jvm/sheet.sh <outDir> [filter]` renders presets (filter = substring of "Category / Name", case-insensitive) into PNG sheets; LOOK at them. Also render at larger brush sizes and with a grain at scale 0.5/1/2/4 to check no blockiness or visible tiling repetition at normal zoom.
5. **Tests** (app/src/test/java/com/artflow/studio/core/render/...): determinism; every tile wraps seamlessly (value continuity across the wrap edge is within the noise's local variation); values in 0..1; mean/variance sanity per Kind (not all-black/white); old `Kind.id`s still resolve; bilinear sampling of a custom tile is continuous (no jumps > 1 level between adjacent sub-pixel samples for a smooth ramp tile);
   built-in tip ids resolve and are never serialised into .artbrush output; presets referencing grains/tips all resolve; a preset-render smoke test (every preset renders without exceptions and paints something visible).
   Existing tests that assert specific old grain values must be updated honestly (explain in the commit message).

## Acceptance
- Contact sheets of at least: all Sketching, Charcoals, Drawing, Painting, Artistic, Textures, Water, Spraypaints categories look natural and varied; no visible plaid/block artefacts at 1x and 2x.
- Full JVM suite green (`ROOT=$PWD .../jvm/run.sh`), plus Android compile/lint harness green if available for the UI change.
- Report includes before/after micro-benchmark numbers and which contact sheets you inspected (paths).

## Out of scope (other agents do these)
Rendering modes, wet-mix parameters, tapers, stabilisation, per-tool brush slots, library management.
