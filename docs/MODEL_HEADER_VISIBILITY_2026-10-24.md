# Model header visibility on Android 8

## Observed defect

Original screenshots from Android CI run 312 (`40122287040`), source
`8050121a7a6e3a1d52854d3c398ad1d29a33bcfe`, show a missing Model header on
API 26. The model texture renders and its controls respond to coordinate input,
but the 3D title, mode chips and close icon are invisible. The same states on the
API 36 / 16 KB lane show the controls normally. Passing semantic visibility,
selection and pointer assertions did not establish rendered visibility.

In each of the five API 26 restored, provisional, cancelled, committed and
navigation captures, the interior header region `[10,136,310,172)` contains
10,800 pixels, all RGB `(20,20,23)`, matching the underlying canvas work area.
The corresponding API 36 regions contain the expected light surface, chip fills
and dark glyphs. Inspection of the restored title, Turn, Paint, Light and close
regions finds no paper/ink pair on API 26; API 36 has 18–46 solid glyph pixels per
region and measured contrast from 5.54:1 to 13.84:1.

[The retained evidence metadata](evidence/model-header-before.json) records the
original PNG and artifact hashes, exact inspection regions and pixel counts.
Those fixed regions describe these originals only; the device regression uses
live semantic bounds and actual window-to-screen offsets.

## Mechanism and bounded correction

The platform source explains the observed difference:

- [Android 8 SurfaceView](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-8.0.0_r1/core/java/android/view/SurfaceView.java)
  clears the current canvas clip with `drawColor(CLEAR)` when exposing its
  surface, in both `draw` and `dispatchDraw`.
- [Android 16 SurfaceView](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/core/java/android/view/SurfaceView.java)
  uses a viewport hole bounded by the view's width and height for this case.
- [The official AndroidView documentation correction](https://android.googlesource.com/platform/frameworks/support/+/e00249ed11e0fe15314bf26a2cc289ac58324060%5E%21/)
  states that hosted content is not implicitly clipped to its layout bounds and
  specifically calls out containing SurfaceView content.

`ModelCompanion` draws its Compose header before the `ModelView` AndroidView
inside one rounded Surface. The unbounded older clear can therefore erase the
header within that shared clip. This mechanism is inferred from those sources and
the exact screenshots; the scoped runtime outcome is recorded below.

The correction adds `clipToBounds()` only to the model's AndroidView modifier:

```kotlin
Modifier.fillMaxSize().clipToBounds().testTag("model-view")
```

The clip confines the view's clearing operation to its own viewport. The GL
renderer, model size, parent rounding, input handling and modal ordering remain
the same.

## Regression and evidence status

The existing two `ModelEditorUiTest` identities retain their actual coordinate
overlap, model painting, cancellation, history, focus and pinch assertions.
After real model pixels arrive, and again after navigation, they additionally
sample the presented screen at the title, Turn, Paint, Light and close controls.
Each region must contain at least four expected opaque theme-background pixels,
at least four solid theme-foreground pixels and actual contrast of at least
4.5:1. A bounded wait retains a method-specific timeout screenshot and reports
the live bounds and observed colors. It does not use Compose-only node captures
to infer visibility of a SurfaceView sibling.

The original API 26 screenshots fail that pixel rule; the inspected API 36
regions pass it. No device identity is added or removed. Run 312 remains the
unfixed evidence.

[Run 313](CI_RUN_313_2026-10-24.md), source `8d8d0e6`, passes the Model cases on
API 26 and retains the original restored, provisional, cancelled, committed and
navigation screenshots. Direct inspection shows the title, Turn, Paint, Light
and Close controls in all five header-bearing states. In the same 10,800-pixel
interior region, the old all-work-area count is now zero. The actual device
pixel/contrast assertions also pass. Model paint, cancellation, modal ordering
and navigation remain visible in the retained originals.

This accepts the repaired header within those captured emulator states. It does
not accept the whole run: the wet test fails initialization in five lanes, two
Android 15 lanes terminate earlier, and no minified launch executes. The full
seven-lane and physical-device quality requirements remain unchanged.

## Subsequent complete matrix

[Run 314](CI_RUN_314_2026-10-24.md), source `47612ae`, passes the Model cases in all seven
final attempts. Original Model captures are directly inspected across every lane, including
the corrected minimum-API header and the tablet's larger controls, modal precedence, focus,
painting, cancellation and navigation. Seven raw Model layers independently retain the
expected painted islands and untouched gap. All 203 recorded UI positions have scoped
acceptance: 60 original-resolution direct views and 143 exact prior app-body comparisons.

Main Android 15 / 16 KB passes after one preserved earlier ART crash and verified reboot;
the independent repeat and other five lanes pass without recovery. The complete final matrix
and seven minified launches are accepted with that qualification. This is emulator evidence,
not physical stylus/display or unrestricted artist acceptance.
