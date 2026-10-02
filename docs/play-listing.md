# Google Play store listing — ArtFlow

Draft listing for the implemented offline editor. Publish only after the release gates in
[RELEASE_READINESS.md](RELEASE_READINESS.md) are satisfied. The development goals in `agent.md`
remain goals, not a claim that every phase has been delivered.

## App details

| Field | Draft value |
|---|---|
| App name | ArtFlow: Digital Art Studio |
| Category | ART_AND_DESIGN |
| Suggested tags | Drawing, Painting, Illustration, Animation |
| Application ID | com.artflow.studio |

Before creating the Play application, confirm ownership and availability of the application ID,
app name, icon and store artwork. This repository cannot establish those account-level facts.

## Short description

Paint, layer, transform and animate offline. Time-lapse replays, PSD and video export.

## Full description

ArtFlow is an offline digital painting studio for Android. Create a canvas, paint with a stylus
or your finger, build up layers, and export a separate copy to share or keep.

PAINT AND EDIT
Use brushes with pressure-sensitive size and opacity on supported styluses, plus StreamLine
stroke stabilisation and QuickShape (hold at the end of a stroke to snap a clean line, ellipse or
circle), ColorDrop (drag the colour swatch onto the canvas to fill), colour dynamics, smudge, clone, healing and liquify tools. Fill areas, add gradients, draw shapes
and rasterise text. Choose colours with the picker, harmonies and saved palettes.

WORK WITH LAYERS
Reorder, duplicate, group and merge layers, insert photos as new layers, import the layers of a
PSD file, and cut, copy and paste selections onto new layers. Adjust opacity and
blend modes, protect alpha, and use
clipping masks, layer masks, adjustments and filters. The layer count and undo history depend on
canvas size and available memory; they are not unlimited.

DRAW WITH GUIDES
Use symmetry, perspective, isometric and 2D grid guides with snapping. Select areas with geometric, lasso and magic-wand tools,
and combine or feather selections. Transform a layer or just the selected pixels: move, uniform
or freeform scale, rotate and flip. Perspective and warp transforms are not included in this
release.

ANIMATE
Arrange frames in a timeline, use onion skinning and preview playback. Export GIF animation,
MP4 video or a ZIP of PNG frames. MP4 needs a compatible device encoder and uses an opaque
background; unsupported dimensions or encoder failures are reported rather than silently saved.

SAVE AND EXPORT
Every edit is recorded automatically; export a time-lapse replay of the artwork as an MP4 video.
Projects are stored privately with autosave and recovery. Duplicate creates independent project
files. Export PNG, JPEG, WebP, PDF, PSD, GIF, MP4 or PNG sequences. Save an export using the Android
file picker, share it with another app, or publish an image or video to the gallery.

PNG and PSD support transparency; JPEG and MP4 use an opaque background. PDF keeps the artwork's
aspect ratio on the chosen page. PSD preserves raster layers where possible, with masks and
filters baked into pixels. Unsupported adjustment stacks use a visible composite plus hidden
source layers and show a warning. PSD import brings in raster layers with their names, offsets,
opacity, blend modes and visibility; Photoshop adjustment-layer editing is not included.

Undo and redo are available through buttons and two- and three-finger taps. Settings include
themes, brush preferences and an offline privacy policy.

No account, advertising, analytics or developer network connection is required. Android backup
and device transfer follow your device settings and may use your cloud account. Exported or
shared copies are handled by the app or document provider you choose. Keep separate copies of
important artwork; Android backup is not guaranteed for large projects.

## Console declarations — publisher review required

Complete the Data safety form even when the app reports no developer collection. Review the
final merged manifest, packaged SDKs, backup behaviour and user-initiated exports before answering.
An absent INTERNET permission is evidence about this app's networking, not an exemption from the
form. Internal-test-only apps have different form requirements from closed/open/production tracks.

Provide an accessible public privacy-policy URL in Play Console and verify its text matches
[privacy-policy.md](privacy-policy.md) and the in-app Settings policy. A repository file is not
proof that a Play Console field has been completed. Supply the publisher's actual contact and
identity information; do not invent it from a GitHub account name.

Complete the content-rating and target-audience questionnaires accurately. The repository does
not assign an IARC rating, establish eligibility for child-directed distribution, or guarantee
approval. There is no in-app social feed; exports can be shared to other installed apps.

## Store assets

Capture screenshots from the release candidate on actual supported devices: gallery, painting,
layers and masks, colours, guides, animation and export. Do not use invented screenshots or show
unimplemented features. Prepare the store icon, feature graphic and device-specific screenshot
sets against the current Console asset rules. Include accessible text and show real artwork that
you own or have permission to distribute.

## Release notes draft

Offline painting with layers, masks, guides and frame animation. Independent project duplication,
autosave recovery, and image, document and video exports with Android save/share controls.

## Publisher references (checked September 17, 2026)

- Target API: https://support.google.com/googleplay/android-developer/answer/11926878
- Data safety: https://support.google.com/googleplay/android-developer/answer/10787469
- Privacy policy: https://support.google.com/googleplay/android-developer/answer/10144311
- Android backup: https://developer.android.com/identity/data/autobackup

Recheck these policies at submission; this document does not guarantee Play approval.

## Store graphics

Generated from the launcher icon's vector paths (`app/src/main/res/drawable/ic_launcher_foreground.xml`):

| Asset | File | Play requirement |
|---|---|---|
| Hi-res icon | [`docs/store/icon-512.png`](store/icon-512.png) | 512 × 512, 32-bit PNG |
| Feature graphic | [`docs/store/feature-graphic-1024x500.png`](store/feature-graphic-1024x500.png) | 1024 × 500, no alpha |

Phone and tablet screenshots must be captured from the signed release build on real devices.
