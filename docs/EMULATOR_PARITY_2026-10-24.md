# Emulator acceptance and parity corrections — 24 October 2026

This work starts from `169473f80b1097663e65bd8359d6d3bd1dc8b83f` on `main`.
It repairs demonstrated drawing and document behavior, and strengthens the evidence required
from GitHub Actions. It does not establish complete Procreate feature or rendering equivalence.

## Drawing and selection corrections

- **Stroke endpoints:** spacing now remembers the last dab's distance along the entire path.
  A short final segment no longer loses its closing dab when its length happens to match an
  earlier segment's local dab offset. Live preview, erasing and committed painting agree.
- **Zero spacing:** intense/uniform rendering modes use the same opacity and edge rules as
  ordinary dabs. Blending retains paint accumulation. Tilt-dependent colour changes no longer
  use a shortcut that omits those controls.
- **Pen orientation:** flat and imported tips interpolate their orientation between samples,
  using the shortest angular turn across the minus-pi/plus-pi boundary. This follows [Android's stylus orientation representation](https://developer.android.com/reference/android/view/MotionEvent#AXIS_ORIENTATION); it is not recovered Procreate filtering.
- **Count Jitter:** the documented Count 10 / 50% setting can now reach every count from 5 to 10.
  The previous exclusive upper bound on the reduction made the minimum 6. The 0% and 100%
  contracts are preserved. See the [reference handbook](https://help.procreate.com/procreate/handbook/5.4/brushes/brush-studio-settings).
- **Imported pressure curves:** new imports opt into the [inspected natural spline and 1,024-sample lookup](NATIVE_PRESSURE_SPLINE.md), including its scaling, endpoint and rounding rules. Existing stored curves keep their explicit legacy linear behavior, and the omitted default preserves historical signed-document checksums. The numerical fixtures are derived from static native instructions, not captured reference-app output.
- **Brush interchange:** export retains independent tilt opacity, including explicit zero and
  edits to imported settings. PNG shape/grain and Photoshop ABR mask imports preserve authored
  coverage instead of stretching contrast; white tips remain visible and subtle grains retain
  their intensity. Photo-derived grain creation still has its separate normalization behavior.
  Gallery/external-open and Brush Studio imports share the same path, preserving secondary
  components and remapping both components' image identities when importing `.artbrush` files.
  [Native compound-mode precedence](REA_DUAL_MODE_PRECEDENCE_2026-10-24.md) establishes that the
  extended mode field wins over the legacy field. Import follows that precedence, while unchanged
  export retains both original fields and an edited mode removes the stale override.
- **Automatic selection:** the colour source is the active layer, including transparency.
  Covering artwork, the canvas background and a fill-reference layer do not substitute their
  pixels. The existing transaction rejects work made obsolete by a layer/document change.
- **Blend picker:** every mode demonstrates the same two colours using the production blend
  equations. The previous arbitrary per-mode tints and development-phase badge are removed.
- **Guide status:** the canvas reminder uses the same active-guide and visibility conditions as
  the drawing overlay. It no longer announces guides when both guide types are off. An opaque
  themed background keeps it readable over artwork and the dark work area, opposite the brush
  sidebar, and focus mode hides it with the other controls.
- **Zoom tool:** the actual tool strip now includes the previously omitted Zoom entry. Its
  dedicated pointer path supports tapping to zoom in and dragging vertically to zoom in or out
  around the starting point, using the existing viewport limits. Navigation does not paint or
  add undo entries. This completes an ArtFlow tool interaction; it is not a claim that Procreate
  uses the same gesture for a dedicated Zoom tool.

## Group compositing and file compatibility

Explicit **Normal** groups now composite their members against transparency before blending
with the surrounding stack. An opaque, unmasked **Pass Through** group lets its members blend
with outside layers. Pass Through is available in the group picker and excluded for pixel layers.
New organizational groups default to Pass Through so grouping alone does not alter the picture.
PSD import/export preserves the distinction.

The `.artflow` format is now **v3**. Version 1 and 2 documents are read, their original checksum
is verified, then previously passing Normal groups are converted to explicit Pass Through in
every animation frame. Previously isolated faded/masked groups retain their behavior. Existing
artwork is migrated in memory and written in v3 on the next save. Older application builds reject
new v3 files instead of silently rendering their Normal groups incorrectly.

Native `.procreate` group blend/mask metadata remains unverified. Exporting an isolated or
actively masked group therefore uses the existing rendered-appearance fallback, with editable
source layers retained hidden below it. Ordinary Pass Through groups remain on the editable
path, and disabled masks alone do not trigger the fallback. This preserves appearance within
the supported exporter; it is not fully editable native group interchange or validation inside
the Procreate application.

## What the emulator must exercise

| Acceptance area | Production path and evidence |
| --- | --- |
| Stroke sampling | Short resampled stroke, visible endpoint, live preview, commit, undo/redo, save and reopen |
| Brush import | Production import route, Android image decoding, compound settings, authored mask coverage, real image files and storage reload |
| Imported pressure | Real `.brushset` import, constant/descending/curved native lookup values on Android, and exact `.artbrush` persistence |
| Automatic selection | Real MotionEvents, covered active layer, transparent areas, empty layers, obsolete pending work |
| Groups and interchange | PSD import, distinct scopes, real save/reopen, PSD export, enabled/disabled masks and bounded `.procreate` appearance fallback |
| Saved selections | Real file storage, fresh repository/controller, fractional mask coverage, constrained Fill Layer, undo/redo, independent Android PNG decoding, slot deletion and document switching |
| Blend picker | Group-only Pass Through navigation and independently expected pixels captured from the actual Compose previews |
| Existing editor | Full declared instrumentation suite, including GL restoration, pressure/palm input, Zoom pointer behavior, all 20 tools through the actual large-text editor route, active guide status, fills, transforms, recovery, animation, platform export codecs and adaptive controls |
| Packaged application | Native library loading, measured process page size and sustained launch of the R8-minified benchmark build |

The six existing lanes remain: API 26/4 KB, API 35/16 KB, an independent API 35/16 KB repeat,
API 36/4 KB, API 36/16 KB and API 36 tablet. The Android 15 lanes retain the existing verified
JIT-disabled emulator mitigation; they do not certify normal-runtime Android 15 performance.

Each lane now publishes `verification.json` and `verification.md` in its device artifact and
a table in the Actions job summary. The gate compares **every declared device test** against
the JUnit results, rejects missing/duplicate/stale cases and unexpected skips, verifies measured
API/page size, and requires the minified launch record. The only permitted skips are the existing
Android 13 cancelled-pointer contract below API 33 and scoped MediaStore publication below API 29.
Unsupported test-declaration syntax requires an explicit parser update rather than reducing coverage.
Dark, light and large-text workspace screenshots now use the runner-collected output directory;
the previous app-owned directory was removed during uninstall before the workflow could collect it.
Brush Studio theme captures additionally wait for actual dialog pixels to reach the expected light
or dark surface. An already visible practice stroke alone cannot satisfy the theme assertion.
Workspace captures also require known artwork pixels from the real GL surface; Compose idleness
alone allowed an empty canvas in the earlier dark screenshot. The standalone legacy tool-palette
fixture now uses the themed Surface supplied by the real activity. Its component coverage is
separate from the added large-text test that selects every tool through the actual editor's
All tools strip. The guide reminder is tested through active, hidden and focus-mode transitions.

The JVM gate also fetches the existing user-selected reference archive and verifies its pinned size
and SHA-256 before running the 394-brush / 534-settings-component corpus check. Gradle tracks the
configured reference inputs so a previously skipped corpus result cannot substitute for execution.
This corpus tests import and serialization contracts, not reference-renderer pixels. Reference
archive bytes and assets are not included in the application or uploaded as CI artifacts.
The same pinned input also gates the recovered archive/property mappings, native spline anchors
and every committed spline fixture, plus the compound source-mode and shader dataflow contracts.
These checks inspect reference bytes as data; they do not execute the reference application.
CI uses Python 3.11, Capstone 5.0.6 and llvmlite 0.44.0 and retains only small derived JSON reports
inside `verification-reports`. Fixture checking is read-only and cannot silently regenerate the
expected results to match changed implementation.

The narrow Android 15 runtime-crash classifier now examines JUnit failure attributes as well as
stack text and rejects test errors or application assertions. Its new regressions reproduced seven
incorrect retry decisions before the repair. Original failure artifacts and the single-retry limit
remain intact. Suite-level error counters also fail closed when detail is missing or invalid.

## Execution evidence

Use the [Android CI workflow](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/workflows/android-ci.yml)
for the exact published commit, then inspect each lane's generated verification summary.
A queued, skipped, cancelled or failed run is not a completed gate. An artifact from an earlier
commit does not validate later source changes.

For context, baseline [run 301](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/40056947818)
executed 215 tests on API 26: 213 passed and two API-limited cases skipped, followed by a successful
minified launch. Its measured CPU preview p95 was 10.3 ms for a 12 px pen, 8.8 ms for an 80 px
textured brush and 39.3 ms for a 300 px soft airbrush on a 2048-square, five-layer document.
Those are baseline emulator measurements, not physical input-to-display latency or Procreate
comparisons.

## What still prevents a full-parity claim

The [comparison register](PROCREATE_PARITY.md) remains open for the native wet-paint and compound-brush
coverage pipeline, 24 retained-only pressure graphs, continuous grain sampling, broader native document
metadata, high-depth/colour-management validation and large-canvas performance. Matching the
reference application's rendering requires paired, versioned reference outputs for identical
inputs. Current self-consistency and external-format tests cannot establish that equivalence.

[Compound scalar equations and source-to-shader mode translation](REA_DUAL_BLEND_CONTRACT_2026-10-24.md)
have now been reconstructed. They are not substituted into the current three-mode renderer: native
synthesis uses separate amount/transfer data, different accumulation, primary edge/grain processing
before combination, a secondary threshold and a primary-edge safeguard. Connecting a scalar helper
to the current buffer without that surrounding pipeline would not reproduce the inspected behavior.

Physical stylus pressure/tilt, palm rejection, input-to-display latency, sustained memory/thermal
behavior and artist acceptance also need physical devices. The CPU timing test reports evidence;
it does not turn an emulator timing into a production quality guarantee.
