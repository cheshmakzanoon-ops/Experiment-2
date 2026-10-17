# Audit: colour, drawing guides, animation, text, reference, page assist, 3D, time-lapse vs Procreate 5.3 (read-only audit of HEAD 6217937 + lead's WIP; model output, verify before relying)

Paths under app/src/main/java/com/artflow/studio/.

COLOUR
- Disc Full (hue ring + S/V square + brightness slider; not Procreate's hue-sat disc with inner value ring). Classic Full. Harmony Partial (tappable swatch rows under a plain wheel; no draggable handles; ColorHarmony.wheelPosition/hueAt unused).
  Value Partial: HSV/RGB/CMYK/Hex chips + sliders + hex field; no typed numbers; readout uses roundToInt so S/V/CMYK (0..1) print as 0 or 1; Hex chip is a dead 0..0 slider (ColorPanel.kt:192-218,245; ColorHarmony.kt:394-449).
- Palettes: create Partial (only "history -> 'My palette'" <=16, name fixed, file import, photo import). Rename/reorder/default/duplicate Absent (SettingsRepositoryImpl.kt:172 renamePalette has no caller; built-ins read-only; isFavorite unused).
  Swatch add/move/duplicate/delete Absent (Palette.withColor/withoutColor no callers; swatches tap-to-select only). Import file/photo Partial (photo: median-cut 12 colours; no camera/clipboard). Export: lead added .swatches share; ASE/GPL/hex/JSON encoders have no UI caller.
- Colour history Full (24 recents). Secondary colour Full (swap-only). Eyedropper Partial (always flattened composite, single pixel: no current-layer mode, no sample size/average).
- ColorDrop Full. Profiles Partial (sRGB + Display P3 only; P3 shown clamped to sRGB; ICC embedded in PNG/JPEG/layer-PNG only; CMYK = on-screen soft proof; no Adobe RGB, no ICC import, no assign-vs-convert). HDR Absent. Colour-blind-safe options Absent. Colour Companion (floating picker) Absent.

DRAWING GUIDE
- 2D Grid Partial (chip + 2 presets 100/40 px; gridSpacing bound to no slider; no thickness/opacity/colour/mask; snap lines round(x/spacing) differ from drawn lines ((w/2)%spacing) unless canvas size fits).
- Isometric Partial (4 line families 0,±30,90; angle fixed; size = 2 presets; snap lattice (0.866 rows) does not match drawn lines).
- Perspective 1/2/3-pt Partial (type chips, horizon + ray density + snap sliders, presets; VPs not draggable, no handles drawn, no add/remove (PerspectiveGuide.movePoint/ensurePoints :315-339 unused; showHorizon and VP.enabled unexposed); snap = per-sample pull to nearest ray, no stroke lock).
- Symmetry V/H/Quadrant/Radial Full (+radial 2-32, second axis, 8 presets). Rotational/move axes Partial (Axis X/Y sliders for mirror types; Radial ignores them; no rotational toggle; independentColour unused).
- Drawing Assist (layer) Full. Edit Drawing Guide mode Absent (no guide pointer handling in ArtFlowCanvasView; sheet sliders only). Guide opacity/thickness/colour/mask Absent (EditorChrome.kt:315-337 hard-codes 0xCC2D9CDB / 0x9988CC66 hairline).
  Guide persistence Absent (settings live in VM input, reset on reopen; only layer flag + on/off prefs persist).

ANIMATION
- Frames add/duplicate/delete/reorder Full (reorder by arrow buttons; caps 240 frames / 1024 layers). Hold per frame 16-2000 ms, FPS 1-60 Full. Onion skin frames/opacity Full (0-5 each side; on/off is an app-wide pref not per file).
  Onion skin COLOURS Absent (AnimationFrame.kt:47-50 fields unused; OpenGLCanvasRenderer.kt:212 tint (1,1,1,1)); docs claim it.
- Loop/Ping-Pong/One Shot Partial (two switches, both off = one-shot; no 3-way control; playbackRangeStart/End no UI). Background/Foreground frame Partial (global "first frame behind / last frame in front"; no per-layer marking).
  Frame = layer/group? Absent (every frame owns a full layer stack; addLayerToAllFrames unused; duplicate deep-copies). Playback controls Partial (play/pause only; no step/first/last).
  Timeline strip, scrubbing, thumbnails Absent (text cards inside a modal sheet; AnimationTimeline.kt:385,432 unused). Export GIF/APNG/MP4/PNG sequence Full (>1 frame; MP4 H.264 baseline no alpha). HEVC/alpha video Absent.

TEXT
- Add text/edit later Full (tool -> tap -> sheet -> Place; any raster write turns text into pixels; no explicit Rasterize). Font picker Partial (9 generic families + imported TTF/OTF stored by absolute path, not packed into .artflow; no system-font list/preview).
  Size/tracking/leading Full. Kerning/baseline/word spacing Absent (TextLayout.wordSpacing unused). Alignment L/C/R/Justify Partial (Justify chip shown but renders left; TextLayout.layout()/justify() never called by TextRasterizer).
  Bold/italic/underline/strike/uppercase Full (+outline, vertical). Small caps Partial (only via "sans-serif-smallcaps" family). Per-selection styling/colour per range Absent (one style + one colour per layer). Box resize handles Absent ("Wrap width" slider only).
  Style/Design/Attributes panels Absent (one scrolling sheet). OpenType features/variable fonts/RTL/emoji Absent.

REFERENCE: Canvas mode Full; Image mode Partial (one image, OpenDocument only, decoded <=1024 px, remembered per file); Face mode Absent; window resize/opacity/flip Absent (fixed <=300x320dp); zoom/pan/fit/colour pick Full.
PAGE ASSIST: pages = animation frames; add/duplicate/delete/reorder with arrows; multi-page PDF export; PDF import Absent.
3D: Import OBJ/glTF/USDZ Partial (gallery only; <=64 MB; all converted to OBJ text so only geometry+UV+base-colour texture survive); paint on model Partial (albedo only, 360dp window); lighting Partial (key light + ambient, 6 presets); materials/PBR map painting Absent; export OBJ+MTL+texture zip only.
TIME-LAPSE: recording on/off Absent (TimelapseRecorder.isEnabled never set); quality Full; replay Partial (slideshow of <=3600 JPEG snapshots, fixed 20 s, no speed); export full/30s Full but the replay window's Export button is always the 30 s version (CanvasScreen.kt:1472-1478); private layers Full.

GAPS ranked: 1 editable persistent Drawing Guides (draggable VPs/horizon/axes, add/remove VP, grid-size slider, colour/opacity/thickness, saved with document; fix grid/iso snap vs drawn lines and Radial ignoring axis);
2 text engine (per-range spans, on-canvas box with caret + resize/move handles, working Justify, kerning/baseline/small-caps/OpenType, system fonts, fonts packed in .artflow, text stays editable after move); 3 palette management (named create, rename, reorder, default, duplicate, add current colour, swatch move/duplicate/delete, camera import, ASE/GPL export);
4 Animation Assist (bottom timeline strip with thumbnails + scrubbing, Loop/Ping-Pong/One-Shot picker, onion-skin tint colours, shared/pinned layers or layer-as-frame model, HEVC/alpha export); 5 time-lapse controls (recording on/off, replay speed, resolution/export options, 30 s vs full-length choice in replay window);
6 colour-picker fidelity (Harmony handles on wheel, typed numeric Value fields fix 0/1 readouts + Hex chip, Disc inner brightness ring, eyedropper sample source layer/composite + size); 7 Reference (resizable/opaque/flippable window, Face (camera) mode, camera/clipboard/multiple images, full-res sampling);
8 Page Assist PDF import (PdfRenderer) + drag-reorder; 9 colour management (Adobe RGB/CMYK/ICC import, real wide-gamut output, colour-blind simulation, HDR); 10 3D (PBR channels, keep materials on import, USDZ/glTF export, environment lighting/shadows).
Cheap real defects: Justify no-op; Value tab 0/1 readouts and dead Hex slider; grid/isometric snap misaligned with drawn lines; Radial ignores Axis X/Y; TimelapseRecorder.isEnabled has no setter; replay "Export MP4" always yields the 30 s cut.
docs/PROCREATE_PARITY.md overstates: lists "onion skin colours" (absent in code) and calls the reference image session-only (code persists the URI per artwork).
