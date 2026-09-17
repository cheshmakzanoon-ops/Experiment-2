# Google Play store listing — ArtFlow

Copy and assets for the Play Console listing. Everything below describes only features that are
wired into the app (see the README's project-status table); do not market the unreachable modules
(layer groups, rotate/scale transform, texture brushes, native engine) until they ship.

## App details

| Field | Value |
|---|---|
| App name | ArtFlow: Digital Art Studio |
| Category | `ART_AND_DESIGN` |
| Tags | Drawing, Painting, Illustration, Animation |
| Application ID | `com.artflow.studio` |

## Short description (80 chars max)

> A pressure-sensitive digital art studio: layers, masks, brushes, animation and export.

## Full description

ArtFlow is a digital painting studio built for tablets and stylus-first devices. It starts with a
blank canvas and gives you real tools: a pressure-sensitive brush engine, a full layer stack with
masks and adjustment layers, flood fills, gradients, symmetry and perspective guides — and it saves
everything as an open document you can export in the format you need.

**Paint**
- Pressure-sensitive brushes with size, opacity and colour dynamics; works with a stylus and falls
  back to finger pressure
- Smudge, clone stamp, healing and liquify tools
- Paint bucket with tolerance and gap closing; linear, radial, angular and diamond gradients
- Text and shapes rasterised onto their own layers

**Layers that behave like layers**
- Unlimited layer stack with blend modes, opacity, lock and alpha lock
- Layer masks, clipping masks and non-destructive adjustment layers (curves, levels, hue, colour
  balance and more)
- Reorder, duplicate, merge, flatten — with thumbnails

**Draw with structure**
- Vertical, horizontal, quadrant and radial symmetry
- One-, two- and three-point perspective plus isometric guides with draggable vanishing points
- Magic-wand and lasso selections that combine with add, subtract and intersect

**Animate**
- Frame-by-frame timeline with onion skinning and adjustable FPS
- Export animations as GIF, MP4 or PNG frame sequences

**Your files, your way**
- Autosave with crash recovery; documents are open `.artflow` files (JSON + per-layer rasters)
- Export PNG, JPEG, WebP, PDF, PSD, GIF, MP4 or a PNG frame-sequence zip — at scale, with
  transparency, trimmed to content if you want
- Publish straight to your device gallery or share anywhere

**Made for the studio**
- Full undo/redo history, two-finger tap to undo, three-finger tap to redo
- Dark studio chrome that stays out of your artwork's way; light mode and high-contrast themes
- Colour picker with harmonies and saved palettes

ArtFlow runs entirely on your device: no account, no network access, no data collection.

## Content rating questionnaire summary

- No violence, sex, language, gambling or user-generated sharing — a single-user offline creative
  tool. Rating comes out `EVERYONE` / `3+`.
- "Does this app collect or share user data?" → **No** (see `docs/privacy-policy.md`; the app has
  no network permission, so there is nothing to declare in the Data safety form either).

## Screenshot checklist (device, 16:9 or 9:16)

| # | Screen | Show |
|---|---|---|
| 1 | Editor, blank canvas | Tool strip + brush options panel open, a fresh stroke visible |
| 2 | Layer sheet | ≥3 layers with a mask and an adjustment layer, thumbnails rendered |
| 3 | Colour picker | Colour wheel + harmonies visible |
| 4 | Symmetry/perspective | A radial-symmetry or 2-point-perspective guide overlay |
| 5 | Animation timeline | Onion-skin ghosts enabled, ≥4 frames |
| 6 | Export sheet | Format list (PNG/PSD/GIF/MP4…) with preview rendered |
| 7 | Gallery | Grid of project thumbnails |

Feature graphic (1024×500): dark `#1A1A1A` background, the brush-and-dab launcher mark, app name,
tagline "A digital art studio for tablets".

Phone screenshots: minimum 2, max 8. 7-inch and 10-inch tablet sets are strongly recommended — this
app's target hardware is tablets.

## Release notes (first release)

> First public release: painting, layers with masks and adjustments, selections, symmetry and
> perspective guides, animation timeline, and export to PNG/JPEG/WebP/PDF/PSD/GIF/MP4.
