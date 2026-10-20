# Audit: layers, selection, transform, adjustments, fill, smudge/liquify/clone vs Procreate 5.3 (read-only audit of HEAD 6217937; model output, verify before relying)

Abbrev: CS=presentation/ui/screens/canvas/CanvasScreen.kt, CV=presentation/ui/components/canvas/ArtFlowCanvasView.kt, VM=presentation/ui/viewmodel/CanvasViewModel.kt,
LR/ES/SC/AO/EC=presentation/ui/components/editor/{LayerRow,EditorSheets,StudioChrome,AdjustmentOverlay,EditorChrome}.kt, RP=data/repository/canvas/CanvasRepositoryImpl.kt,
LG=core/render/LayerGroups.kt, CMP=core/render/Compositor.kt, BM/AP/LA/TQ/WM=core/pixels/{BlendModes,AdjustmentProcessor,LiveAdjustments,TransformQuad,WarpMesh}.kt,
PB/LQ/GT=core/tool/{PixelBrushes,LiquifyTool,GradientTool}.kt, BS=presentation/ui/components/layer/BlendModeSelector.kt, PS=data/local/ProjectStorage.kt.
Access note: paint bucket, Lasso Select, Move, Eyedropper and the Liquify/Clone/Gradient/Bucket option panels are reachable only via tapping Erase twice (CS:970-972
opens EditorPanel.TOOLS) then "All tools". The Adjustments menu (CS:987-1000) switches tools but shows no option bar.

LAYERS
- Duplicate group: copies only the empty header, not members (LR:178, RP:1124,3218). Delete group = ungroup (Procreate deletes contents) (RP:1080-1098).
- Group blend modes: Pass Through filtered out of picker (BS:75,272), Normal@100% already behaves as pass-through so an isolated Normal group cannot exist (LG:32-34).
- Collapse state is `remember` only (lost when panel closes) ES:520-531. Combine Down = groupWithLayerBelow (makes a 2-layer group; not a merge) LR:369-374,CS:630.
- Flatten: whole stack only, no per-group Flatten. Layer thumbnails raw pixels only (no mask/opacity), refreshed only while panel open.
- 26 blend modes Full; dialog shows a "Phase 12" dev badge (strings.xml blendmodeselector_phase_12) and decorative previews.
- Layer Mask: Full = paint reveal/hide, invert, from selection (+alpha/gradients), enable, density, feather, remove. Absent: Clear, Apply/Merge mask, show-mask overlay, mask thumbnails (only a "mask" text tag LR:262).
- Reference layer: ColorDrop + bucket honour it; Automatic selection samples the full composite (CV:2668). "Exclude from export" flag is confusingly also called reference.
- Copy uses an in-app buffer only (no system clipboard). Layer limit flat 1024 (PS:650, RP:2988, RP:1009); no counter; not tied to canvas size.
- Multi-select: Group/Delete OK; Merge needs adjacent layers; Transform carries picked layers (not while a selection is active, groups ignored); Move tool and drag-reorder are single-layer.
- Layer linking Absent (RP:1458, VM:971: linkGroupId saved but no UI, nothing reads it).
- (FIXED by lead in commit 1dccd40: layer ⋮ menu Select/Copy/Fill/Clear/Mask/Invert acted on the ACTIVE layer rather than the tapped row.)

SELECTION
- Automatic: Partial (tap or slide; computed on release, no live preview).
- Freehand: bar "Freehand" = SELECT_FREEHAND (SC:348-355, CV:2631 vs 2627) = a 12 px brush-stroke band (size slider ignored), NOT a closed lasso. Closed lasso SELECT_LASSO (CV:2636) is not on the bar. Tap-polygon mode Absent. (verify!)
- Rect/Ellipse Full. Add/Remove/Invert Full (Intersect only in panel ES:638).
- Copy & Paste menu: in-app clipboard; paste does not auto-enter Transform.
- Feather: numeric radius 0-64 + Apply (SC:366, ES:655-670, VM:715); no live slide.
- Save & Load: 8 slots, memory only (SavedSelections.kt), lost on close, not in the document.
- Colour Fill Full. Clear: chip only DESELECTS (SC:397,CS:1807); Procreate erases selected pixels.
- Selection mask visibility: red 28% tint + dashed bounding box (EC:296-303,340-351,434-450); no marching ants on contour, no stripe mask, no visibility slider.
- Magnetics/selection snapping Absent. Selection-edge transform: selected pixels float but the selection outline stays behind (CV:947-973).

TRANSFORM
- Freeform/Uniform/Distort Full. Warp Partial: one 4x4 Bezier patch (WM:14-25), no grid density/advanced mesh. Snapping & Magnetics Full (canvas edge/centre only).
- Interpolation Nearest/Bilinear only (no Bicubic). Numeric entry and pivot Absent (numeric exists only in Crop). Apply/cancel: every drag commits as its own undo step; no Done/Cancel (CV:2346-2435).
- Pasted layer stays in place, tool not switched.

ADJUSTMENTS (Layer vs Pencil, slide, live preview exist: AO:34-117, AdjustmentSessionController.kt, AdjustmentPaint.kt; Pencil = fixed soft round dab, re-mixes the whole layer per dab)
- Hue/Sat/Brightness Full. Recolor Full. Gaussian/Motion/Sharpen Full.
- Colour Balance Partial: one global RGB shift (AP:81-93, AO:142); no Shadows/Midtones/Highlights.
- Curves Partial: engine has 5 control points with monotone cubic LUT (AP buildCurveLut keys point_N_x/y, N 0..4) but UI = 3 y-sliders on fixed x=64/128/192 (AO:144-145); no draggable points; no Gamma/RGB/R/G/B channels.
- Gradient Map Partial: two hue sliders (AP:138-152, AO:146); no gradient library/editor (GradientTool presets unused).
- Perspective Blur Partial (positional only, LA:139-155). Bloom Partial: single amount (LA:194-212; Procreate has Transition/Size/Opacity). Glitch Partial: one band-shift + red split (LA:230-247; Procreate has Artifact/Wave/Displace/Signal/Hue-Sat).
  Halftone Partial: one mono-dot style (LA:250-301; Procreate has Full Colour/Screen/Newspaper/Mono). Chromatic Aberration Partial: radial from canvas centre only. Noise Partial: monochrome white noise (no Perlin/Scale/Octaves).
- Liquify Partial: Push/Twirl R+L/Pinch/Bloat/Crystals/Edge/Reconstruct/Reset present; no Pressure/Momentum; panel "Size" slider is dead (overridden by sidebar size, CV:2188); Reset compares undoDepth which saturates at 30.
- Clone Partial: source set once, never moved/reset; no source marker (VM.cloneSource unread); aligned/opacity/rotation/scale/sample-all no UI; no brush choice.

OTHER TOOLS
- ColorDrop Full. Paint bucket Full (extra; tolerance+contiguous only; FillTool gapClose/pattern/sample-all unreachable). Gradient tool Partial: 4 types + 7 presets; no stop editor (core insert/moveStop unused); no handles.
- Smudge Partial: tip/grain borrowed from the active paint brush; strength fixed 0.5 (VM:584 setSmudgeSettings never called; opacity slider ignored); 2nd tap opens Brush Studio and Apply switches to Brush tool.
- Eraser Partial: paint-brush params + own size; no eraser brush/library. QuickShape/Edit Shape Full.

SYSTEM
- Undo/redo depth: 30 steps (MAX_HISTORY RP:3306) and <= min(192 MB, heap/4) vs Procreate 250. Slider edits push one step per tick (mask density/feather RP:1704-1735, adjustment params RP:1788, filter amount RP:1848; only opacity coalesces), so one drag can flush history. Depth-keyed tools (VM:466 liquify reset, CV:811 ColorDrop re-threshold) misbehave at the cap.
- Memory: per-layer full-canvas ARGB IntArray on the Java heap (<=8192px/40MP); COW undo; CPU compositor + pool + tile cache; raster tools copy the full canvas twice per gesture; guards at load/create only.

GAPS ranked by impact: 1 undo depth/history cost (tile/delta snapshots; coalesce slider edits; historyMark for depth-keyed tools); 2 selection correctness (closed lasso + polygon mode + lasso on bar; Clear erases pixels; live feather;
marching ants/stripe + visibility; persist saved selections); 3 layer-menu safety + group semantics (group duplicate clones members, group delete deletes contents, per-group Flatten, real Combine Down); 4 layer-mask completeness
(thumbnail row, Clear, Apply, show-mask overlay, grey painting); 5 adjustment depth; 6 per-tool brush slots/settings (independent Paint/Smudge/Erase, Smudge strength, Clone marker/move/aligned/brush); 7 surface tool options
(contextual bars for Liquify (+Pressure/Momentum, remove dead Size slider), Clone, Gradient (+stop editor), Bucket); 8 Transform (numeric entry + pivot, Bicubic, warp grid/advanced mesh, selection outline follows pixels, auto-enter Transform after paste);
9 Groups (selectable Pass Through; Normal isolates; persist collapse state; multi-selection drag together); 10 layer cap tied to canvas size/RAM with n/max indicator; 11 system clipboard interop; 12 reference layer for Automatic selection,
wire or delete layer linking; 13 sparse/tiled layer storage; 14 drop the "Phase 12" badge and decorative blend previews.
Note: docs/PROCREATE_PARITY.md overstates several rows (freehand selection, Curves, Gradient Map, Liquify Reset, layer organisation).
