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
| Automatic selection | Real MotionEvents, covered active layer, transparent areas, empty layers, obsolete pending work |
| Groups and interchange | PSD import, distinct scopes, real save/reopen, PSD export, enabled/disabled masks and bounded `.procreate` appearance fallback |
| Saved selections | Real file storage, fresh repository/controller, fractional mask coverage, constrained Fill Layer, undo/redo, independent Android PNG decoding, slot deletion and document switching |
| Blend picker | Group-only Pass Through navigation and independently expected pixels captured from the actual Compose previews |
| Existing editor | Full declared instrumentation suite, including GL restoration, pressure/palm input, fills, transforms, recovery, animation, platform export codecs and adaptive controls |
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

The JVM gate also fetches the existing user-selected reference archive and verifies its pinned size
and SHA-256 before running the 394-brush / 534-settings-component corpus check. Gradle tracks the
configured reference inputs so a previously skipped corpus result cannot substitute for execution.
This corpus tests import and serialization contracts, not reference-renderer pixels. Reference
archive bytes and assets are not included in the application or uploaded as CI artifacts.

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

The [comparison register](PROCREATE_PARITY.md) remains open for native wet-paint and compound-brush
equations, 24 retained-only pressure graphs, continuous grain sampling, broader native document
metadata, high-depth/colour-management validation and large-canvas performance. Matching the
reference application's rendering requires paired, versioned reference outputs for identical
inputs. Current self-consistency and external-format tests cannot establish that equivalence.

Physical stylus pressure/tilt, palm rejection, input-to-display latency, sustained memory/thermal
behavior and artist acceptance also need physical devices. The CPU timing test reports evidence;
it does not turn an emulator timing into a production quality guarantee.
