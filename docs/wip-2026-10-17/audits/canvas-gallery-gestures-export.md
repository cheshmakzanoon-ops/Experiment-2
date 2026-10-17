# Audit: gallery, canvas setup, actions menu, prefs, gestures/input, share/export/import vs Procreate 5.3 (read-only audit of HEAD 6217937; model output, verify before relying)

Paths under app/src/main/java/com/artflow/studio/.

GALLERY / DOCUMENTS
- New canvas: presets Partial (15 fixed px presets, CanvasOperations.kt:291-308; GalleryScreen.kt:899-918; no device "Screen size", no 4x6, no saved custom presets; default pref "FHD 1080p" AppSettings.kt:98 matches no preset).
  Custom size/units/DPI Partial (px only, DPI 36-1200; no in/cm/mm; widthInches has no caller). Colour profile Partial (sRGB or Display P3 only; CMYK is a display proof). Time-lapse settings/background in dialog Absent.
  Size limits vs layer-count display Absent (fixed 8192 px / 40 MP, flat 1024-layer cap CanvasOperations.kt:313-322; ProjectStorage.kt:650; CanvasRepositoryImpl.kt:2988; rejects Procreate docs over 8192 px although ProcreateReader.kt:22 allows 16384).
- Stacks: create/open/preview Full (GalleryLayout.kt, GalleryDrag.kt). Stacks rename/reorder/merge/unstack-all/share Absent (stack is a String column Project.kt:19). Manual drag-reorder of artworks Absent (sorts only).
  Multi-select actions Partial (Stack/Duplicate/Delete; no Share/Export/Favourite/Select all). Gallery share Partial (flat PNG of last save only).
- Import photo/PSD/.artflow/3D Full (GalleryScreen.kt:286-317,380-435,451-478,517-543; hand-off in-memory PendingImports lost on process death). Import .procreate Partial (no text layers, animation, guides, time-lapse, ICC).
  Import brushes/palettes/fonts only inside editor, not from the gallery. "Open with"/share-to intent filters Absent (manifest only MAIN/LAUNCHER; no onNewIntent). Drag-and-drop onto the app Partial (image/* onto editor becomes a layer only).
  Export native .procreate Absent. Export .brushset Absent. Export palettes UI Absent at audit time (lead added .swatches share). Backup/restore Partial (Android Auto Backup only).

ACTIONS MENU
- Add: Insert a file Partial (PSD only). Cut/Copy/Copy canvas/Cut&Paste/Paste/Duplicate Partial (in-app buffer only; no system ClipboardManager).
- Canvas: Crop & Resize Partial (no snapping/aspect lock/units). Animation Assist Partial (frames are whole layer stacks; no thumbnails). Drawing Guide Partial (no on-canvas handles; grid size via 2 presets; no colour/opacity/thickness).
  Reference Companion Partial (Canvas + Image; no Face). Canvas information Partial (one text line). Tracing Assist Absent. Background colour Partial (no picker).
- Share formats Partial (PNG/JPEG/WebP/TIFF/PDF/PSD/layer-PNG zip/.artflow; GIF/APNG/MP4/frames when >1 frame; OBJ zip with model). No .procreate, HEVC, USDZ. Print Absent.
- Video: replay/export full/30s Full (JPEG snapshot per settled edit, max 3600 frames, not stroke replay). Recording on/off Absent (TimelapseRecorder.isEnabled never set).
- Prefs: Light, Right-hand, Dynamic brush scaling Full. Brush cursor Partial (stylus hover only). Projector / Rapid-undo delay (fixed 650/220 ms) / Selection-mask visibility Absent. Pressure and Smoothing Partial (no curve editor).
  Gesture controls Partial (3 on/off switches; no per-gesture assignment or practice pad; QuickShape / hold-eyedropper / finger-paint toggles are session-only and finger toggle is overwritten by next settings emission). Stylus options Partial (stylus-only + 1.5 s palm window; no button mapping).
  Help Partial (gesture list static; About one paragraph).

GESTURES / INPUT
- Full: 2/3-finger tap undo/redo, 4-finger hide UI, hold = rapid undo/redo (fixed delay), pinch zoom/rotate/pan, quick pinch fit, 3-finger swipe/scrub, layer-row gestures.
- Absent: 45-degree rotate snap, zoom % HUD (% only as static text in Quick menu sheet), layer select by touching canvas, "Disable touch actions", mouse/trackpad support (no scroll wheel zoom, AXIS_VSCROLL, secondary button, hover; a mouse paints as ~0.35-pressure finger), shortcut customization.
- Partial: touch-and-hold eyedropper (finger only, fixed 500 ms); QuickMenu (6 slots, global, 12-action catalogue, opens only by long-press on a 28dp square); QuickShape (fixed 650 ms hold, no second-finger constrain);
  palm rejection (pen-lift window; touch size unused); stylus side button (BUTTON_STYLUS_PRIMARY=eyedropper hard-wired; no mapping UI); eraser end works; hover cursor stylus-only.
- (Lead added hardware keyboard shortcuts in commit 5f15305.)

EXPORT
- Raster formats + metadata Full. Layered PSD Partial (masks baked, text/adjustment baked, 8-bit, no ICC). ICC embedding Partial (Display P3 only in PNG/JPEG/layer-PNG; others converted to untagged sRGB). PDF Partial (raster pages only). GIF/APNG/MP4/PNG frames Full (H.264 baseline only; HEVC/transparent video Absent).
  Time-lapse export resolution Partial (fixed at record time).

OTHER
- Localization Absent (English only, no locales_config.xml; strings.xml 506 entries; ~85 literal UI strings in ActionsPanel/GalleryScreen/SettingsScreen + ~50 enum labels in AppSettings/ExportOptions).
- Accessibility Partial. Tablet vs phone Partial (no WindowSizeClass/androidx.window/fold posture). ChromeOS/DeX/multi-window Partial: configChanges omits keyboard|navigation|smallestScreenSize|density|uiMode (attaching KB/mouse or resizing can recreate the activity); uses-feature multitouch required=true hides app from non-touch Chromebooks.
- Cloud/Drive backup Absent. Undo history persistence Partial (30 steps/192MB; in-memory).

GAPS ranked: 1 undo depth; 2 incoming files (VIEW/SEND intent filters + import router + gallery drop target); 3 native .procreate export (writer for Document.archive plist/KeyedArchive, LZ4/LZO tiles, thumbnail; put time-lapse+fonts in .artflow);
4 new-canvas parity (one dialog: px/in/cm/mm + DPI, Screen-size/4x6 presets, saved custom presets, RGB/CMYK/ICC profile, background, time-lapse quality, max-layers readout from size x heap replacing flat 1024 cap);
5 Gesture Controls sheet (per-gesture assignment: QuickMenu trigger, eyedropper hold, QuickShape delay, rapid-undo delay, Disable touch actions, practice pad, 45-degree rotate snap + zoom % HUD, QuickShape second-finger constrain, layer-select-by-touch, stylus-button mapping; persist session-only toggles);
6 mouse/trackpad + keyboard completion (scroll-wheel zoom, middle-drag pan, right-click, hover cursor; relax manifest for ChromeOS: touchscreen required=false, add keyboard|navigation|smallestScreenSize|density|uiMode to configChanges);
7 gallery management (stacks first-class: rename/reorder/merge/dissolve/share; manual artwork order; Share/Export in multi-select; Room migration without destructive fallback); 8 system clipboard (publish copies as image URI, paste from ClipboardManager);
9 export gaps (Print via PrintHelper, HEVC, USDZ export, ICC in PSD/TIFF/PDF, vector PDF); 10 Canvas info stats/metadata, Reference Face, Tracing Assist, guide editing, Animation Assist thumbnails; 11 brush/palette interchange (.brushset export, palette export UI, multi-palette .swatches import);
12 time-lapse controls; 13 localization (locales_config.xml + values-xx, move ~135 literals/enum labels into resources); 14 in-app backup/restore of gallery (zip of .artflow); 15 prefs leftovers (pressure-curve editor, selection-mask visibility, external-display mirroring, About with version/licences).
