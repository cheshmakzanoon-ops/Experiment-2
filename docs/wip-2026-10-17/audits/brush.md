# Audit: brush engine + Brush Studio vs Procreate 5.3 (read-only audit of HEAD 6217937; model output, verify before relying)

R = app/src/main/java/com/artflow/studio. BP=domain/model/brush/BrushParams.kt, SR=core/render/StrokeRasterizer.kt,
ST=core/pixels/Stamping.kt, BT=core/render/BrushTexture.kt, CG=core/render/CustomGrains.kt,
AP=presentation/ui/components/brush/AdvancedBrushSettingsPanel.kt, BSD=.../brush/BrushStudioDialog.kt,
SBC=.../brush/SavedBrushControls.kt, BFB=.../brush/BrushFileButtons.kt, ACV=presentation/ui/components/canvas/ArtFlowCanvasView.kt,
CS=presentation/ui/screens/canvas/CanvasScreen.kt, IF=core/canvas/InputFilters.kt, SS=core/canvas/StrokeStabilizer.kt,
PXB=core/tool/PixelBrushes.kt, CRI=data/repository/canvas/CanvasRepositoryImpl.kt, BLS=data/repository/BrushLibraryStore.kt,
BLC=domain/model/brush/BrushLibraryCodec.kt, BA=data/local/BrushArchives.kt, PB=core/export/ProcreateBrush.kt, CVM=presentation/ui/viewmodel/CanvasViewModel.kt

Engine = CPU dab-stamper: walks dabs along a polyline into a per-stroke 8-bit coverage buffer, then deposits once with grain,
wet and burnt edges, opacity and blend mode. Solid: stroke-level opacity ceiling, pressure curve, event-time speed, incremental
live strokes, seeded replay. ~45 controls exposed and consumed. Missing/stubbed: Wet Mix, real rendering modes, pressure/touch
taper, per-brush stabilisation, grain/shape libraries and editors, Properties limits, About page, per-tool brushes.
Studio sections (BrushAttribute.kt:4-19): 11 groups, no Stabilization/Apple-Pencil/About/Shape-source/Rendering section.

Status table (Full / Partial / Absent):
- Path: Spacing Full (% of dab diameter). StreamLine Partial (amount only, per-sample EMA, input stage only so BrushPreview/Practice ignore it).
  Lateral Jitter Absent (scatter is isotropic). Fall off Partial (distance only, fades opacity not size).
- Stabilization amount Partial (global prefs, same filter as StreamLine). Motion filtering+expression Partial (one-euro filter, global only).
- Taper: Pressure taper Absent. Touch taper size Partial (ramp = fraction of WHOLE stroke length, blocks live drawing SR:798-801,
  PreviewCache.kt:136-150). Taper opacity Partial (one amount both ends). Tip taper/style/size link Absent.
- Shape: source import Partial (photo/.brush/.abr -> 256x256 8-bit tile). Editor + built-in library Absent. Scatter Full.
  Rotation Full (round tips ignore it). Azimuth Partial (on/off; StrokePoint.azimuth never set/read). Randomized Partial (one angle per stroke).
  Follow stroke, Count jitter Absent. Count Partial (UI cap 5, engine 32). Flip X/Y Partial. Roundness Full. Inversion/filtering Absent/Partial.
- Grain: Moving vs Texturized Partial (moving = shifted canvas-anchored pattern). Scale/Rotation/Depth Full. Zoom, depth jitter, offset jitter,
  grain blend mode, brightness/contrast, filtering Absent (nearest sampling = blocky when scaled). Import Partial (256x256 grey tile).
  Built-in library Partial: 9 procedural grains in BT (PAPER, CANVAS, CHARCOAL, FINE, BLOTCH, BRISTLE, HALFTONE, HATCH, SPECKLE).
- Render: Flow Partial. Rendering mode Partial (2 states: glaze MAX_COVERAGE / build-up SOURCE_OVER; no Light/Uniform/Intense/Heavy glaze or
  Uniform/Intense blending). Wet edges Partial (alpha-rim heuristic). Burnt edges Partial. Per-brush blend mode Full.
- Wet mix: Dilution, Charge, Attack, Pull, Grade, Blur, Blur jitter Absent (one `wetMix` slider: single-pixel pickup from pre-stroke layer, max 60%).
- Colour dynamics: stamp jitter H/S/B + secondary Full. Darkness, stroke-level jitter, tilt variants Absent. Pressure/speed variants Partial.
- Dynamics: speed->size/opacity Full; jitter->size/opacity Full; speed/jitter->flow Absent.
- Pencil: pressure curve per brush Full (one curve shared). pressure->size/opacity Full. pressure->flow/bleed, tilt->gradation Absent.
  tilt->size/opacity/angle Partial (one shared slider, fixed x3 size/-60% opacity). Hover Partial.
- Properties: max/min size & opacity Absent (global 1-512px, 1-100%). Sidebar slider range Partial. Preview size Absent (fixed, clamped 48px).
- About: name Partial; author/signature Absent. Dual brush Partial (second brush = preset pick + size + Multiply/Subtract/Add; not live-drawable).
  Paint/Smudge/Erase assignment Absent (eraser = paint-brush params + own size; smudge borrows tip/grain; sidebar opacity never reaches smudge, strength fixed 0.5).
- Library: sets Partial (create/delete/toggle, max 32; no rename/reorder). Recents Absent. Favourites Full. Create/duplicate Partial. Rename/delete Partial
  (saved copies only; 200 built-ins immutable). Reorder Absent. Reset to default Absent. Import .brush/.brushset Partial (~40 keys; bundled shapes/grains lost
  silently PB:89-90 never read; signed speed values make the import fail PB:68-69 vs BLC:63-85). Export .brush/.brushset Absent. Share Partial (.artbrush JSON).
- Engine: flow vs opacity Partial. glazing/build-up Partial (8-bit coverage, very low flow bands/vanishes). velocity from event time Full.
  smoothing quality Partial (linear polyline, no spline; EMA per sample). architecture Partial: CPU 8-bit PixelBuffer (max 8192px/40Mpx), GL ES2 only displays,
  preview throttled 66ms + 16ms prediction overlay. Max brush size 512px.

Stored-but-unread: StrokePoint.azimuth; ProcreateBrush.Settings.bundledShape/bundledGrain; Smudge/Clone/Healing settings (strength, hardness, sampleLag, fingerMode,
opacity) have setters at CVM:584-588 with no UI caller; StrokeReach.kt:31 handles negative speed values but BP never applies them; BrushParams.smoothing read only at ACV:1738.
Legacy Room BrushEntity/BrushDao unused. C++ in app/src/main/jni/brush not built.

GAPS ranked by impact:
1. Brush selection model: persistent per-tool brushes (Paint/Smudge/Erase with "behaves as"), in-editor brush picker with Recents, persist the active brush
   across launches (today default EditorInput(), never stored). Touches EditorInput (ACV), CVM, CS, SettingsRepositoryImpl, BP, BLS, BSD.
2. Latency/render architecture: preview incremental on dirty rect only (deposit loop re-runs grain/wet-edge/blend over whole stroke bbox each frame SR:119-140,187-216),
   drop 66ms throttle (ACV:2975), parallelise capsule/image dabs, cache dab masks, >8-bit coverage, maybe GPU dab path for big brushes.
3. Wet paint + rendering modes: carried per-stroke paint load (footprint-averaged pickup with Dilution, Charge, Attack, Pull, Grade, Blur, Blur jitter) and a RenderingMode enum
   (Light/Uniform/Intense/Heavy glaze, Uniform/Intense blending) replacing buildUp, true flow accumulation in glaze. BP, SR(wetColor, drawDabAt, deposit), ST, AP(BrushWetSettings), BLC, PB.
4. Smudge parity: strength from sidebar opacity+pressure, step the patch along each segment by spacing (PXB:61-105 stamps once per sample so fast drags leave gaps),
   make Smudge/Clone/Heal settings editable.
5. Taper: pressure + touch taper (size, opacity, tip; start/end amounts; style curve) as fixed-distance causal ramps so tapered strokes draw live.
6. Shape and grain sources: ship built-in shape and grain libraries, raise 256x256 8-bit tile to 1-2k with mips+filtering, editors (invert/crop/transform), grain blend mode,
   brightness/contrast, zoom, depth/offset jitter and true per-dab Moving grain.
7. Per-brush stabilisation: StreamLine (amount+pressure), Stabilization, Motion filtering, Expression in BrushParams with a Stabilization section; time-based filters; spline path interpolation;
   same filter in BrushPreview and BrushPractice.
8. Library management: edit built-ins in place with Reset to default, rename/reorder brushes and sets, About metadata, .brush/.brushset export, multi-select share.
9. Import fidelity quick wins: accept signed speed values in the codec (or clamp), warn when a Procreate brush used bundled shapes/grains, map flip/randomise flags.
10. Stylus mapping: independent tilt size/opacity/angle/gradation, pressure->flow/bleed/smoothing with per-property curves, Follow stroke, azimuth amount, record azimuth/tilt on first and
    catch-up points, hover distance.
11. Jitter/dynamics completeness: lateral Jitter, speed/jitter->flow, Count/Rotation/Roundness jitter, stroke-level and Darkness colour jitter, pressure/tilt colour variants.
12. Properties: per-brush max/min size/opacity driving the sidebar sliders, preview size, ceiling above 512px.
13. Dual brush parity: second brush any library brush with full editing, more combine modes, live drawing.

Visual check (done by lead with the real engine, contact sheets at scratchpad/shots/presets-*.png): textured presets look artificial - woven/plaid grids ("Pastel stick",
"Burlap", "Linen"), big pixel blocks ("Charcoal smudge", "Pebble"), regular hatch; per-pixel white-noise "paper"; many Water presets near-identical soft round airbrushes; watercolour
nearly invisible. Grain sampling in BT is integer-lattice hash noise (no spatial correlation), so improving BT.grain() generation improves every textured preset.
