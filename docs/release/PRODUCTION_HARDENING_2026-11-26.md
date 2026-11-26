# Production hardening — November 26, 2026

This work starts from `main` at `9f8c0604fc0bfd28f2e31db701cca4d907e56901`.
The requested scope is a production-quality Android painting application. The session uses
successive specialist tasks with three concurrent workers and one integrator. Task count is a
record of work, not a substitute for application, artifact, or physical-device evidence.

## Release decision

The application remains an **engineering candidate** until the exact final source passes the
engineering requirements and the publisher, physical-device, and store gates in
[release sign-off](RELEASE_SIGNOFF.md). This effort does not remove those requirements or claim
complete Procreate interchange, GPU painting, or physical pen-latency qualification.

## Integrated work and evidence

| Task | Scope | Implementation / review outcome |
| --- | --- | --- |
| 001 | Referenced raster retention | Normalize safe parent-segment aliases consistently across saved documents, recovery, and export pins; retain raster, mask, and physical assets. Three regression cases added. |
| 002 | Physical stroke clipping | Deposit wetness/pigment/impasto only under actual stamped, selected, alpha-locked and opacity-scaled coverage. Six regressions; 24 focused kernel tests passed. |
| 003 | Brush workflow CI failure | Preserve independent Paint and Smudge presets in the real editor workflow. Original test expected applying Smudge to switch back to Paint. |
| 004 | Damaged-primary recovery | Offer and protect an older autosave when a newer primary's raster or physical assets cannot load. Five failure/preservation regressions. |
| 005 | Palette management | Connect named creation, rename, duplication and swatch replacement/removal to the active color panel; preserve concurrent-edit checks and durable default updates. |
| 006 | Scatter bounds and thin brush tips | Include longitudinal scatter in damage bounds and integrate subpixel elliptical tips. Independent task 016 found a density defect in this initial transition; task 020 replaces the transition. |
| 007 | Selected Move/Transform and Reset | Use the existing selection-aware interaction path; identity affine/quad/mesh operations preserve soft-alpha bytes. 39 focused kernel tests passed. |
| 008 | Export ownership review | Review request-generation cancellation; add real ViewModel/export preservation coverage and reliable coroutine-test cleanup. |
| 009 | Clone and Healing taps | Apply one initial dab, preserve empty-source healing destinations, and bound damage from the previous sample. Five kernel and two device regressions. |
| 010 | Text wrapping integrity | Preserve authored spacing and complete grapheme clusters; add pure and API-matrix platform regressions. |
| 011 | Physical transform ownership | Move selected physical scalars with raster transforms, preserve exact Reset, check ownership and allocation before atomic commits, and retain undo/save/reopen coverage. |
| 012 | Feathered layered exports | Export one once-clipped visible appearance for soft-selected PSD/Procreate documents, preserve hidden selected sources/masks, disclose the fallback, and budget it before allocation. |
| 013 | Live export preview | Connect a current-artwork preview, reject stale/recovery/unsafe requests, cap output at 512 pixels, convert display color and decode off the UI thread. |
| 014 | Reference/model preview ownership | Bind every request to generation, content revision and frame; finish color/material work before publication and retain one rendering worker across document resets. |
| 015 | Independent storage review | No additional blocker found in alias retention, snapshot pinning, older recovery, failed discard, or failed-load preservation; source review only. |
| 016 | Independent brush review | Found a density decrease between minor radii 0.5 and 0.75 in the new thin-tip transition. The endpoint test did not cover the complete growth interval; task 020 addresses it. |
| 017 | Independent palette review | No additional blocking defect in captured palette/swatch callbacks and default updates. Duplicate palette names remain a model limitation; source review only. |
| 018 | Grapheme whitespace follow-up | Found and fixed splitting of a space-plus-combining-mark cluster during soft wrapping and justification. Shared grapheme-aware separator boundaries; three pure and one platform regression added. |
| 019 | Clone sources and alignment | Connect source reselection/cancellation, Aligned strokes and Sample all layers controls. Preserve offsets only for accepted strokes, invalidate stale choices, and retain no-op stroke alignment without undo entries. Six kernel, four device and one Compose regression added. |
| 020 | Consistent elliptical brush coverage | Use the same geometric profile at every size, integrate boundary pixels, and skip resolved interiors. Independent area tests cover 980 growing stamps and 81 size/hardness combinations. Refresh two intentional fingerprints; round eraser and all round preset outputs stay unchanged. |
| 021 | Gallery share failures | Report preparation and platform share failures, preserve cancellation, and stream saved PNGs instead of allocating the full encoded image. Five regression tests added. |
| 022 | Animation playback review | No additional blocking defect found in ping-pong boundaries, bounded holds, preview/export order, stop/start generation or structural-edit cancellation; source review and scoped formatting only. Physical timing remains unqualified. |
| 023 | Frame clipboard ownership | Cancel queued work and clear Paste on document changes; check project/generation before commands and feedback; clear repository clipboard when creating a fresh canvas. Five controller tests and one real ViewModel/repository regression added. |
| 024 | Onion-skin color profiles | Encode chosen sRGB tints into the document profile and invalidate retained ghosts when that profile changes. Add a P3 alpha/source-preservation regression. Combined compositor/ghost memory admission remains a follow-up. |

The integrator also binds export completion and cancellation to the initiating artwork/request,
so an old operation cannot replace a newer result. Opening another artwork cancels pending
exports and clears the prior result without deleting completed export files.

## Verification checkpoints

- The initial head failed Android CI and optimized qualification on the same stale Smudge
  assertion. Normal-release UI and installed-upgrade workflows passed at that initial head.
- `24ccf112cc748bf5a554d7b7f17f825e888d222a` publishes the first UI workflow correction.
  [Normal release UI](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/45763157599)
  and [installed upgrade](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/45763157652)
  passed. [Optimized qualification](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/45763157737)
  failed because a new text matcher selected both a brush and its search field. The follow-up
  matcher identifies the brush's radio-button role. This failure is retained; it is not a pass.
- `7f3da6e2f3d25fe7c5bec6d1f8e69c5eeb102dbe` publishes tasks 001–015 and the
  corrected role matcher. Its [optimized qualification](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/45766565893),
  [normal release UI](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/45766565952),
  and [installed upgrade](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/45766565972)
  passed. [Android CI](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/45766566094)
  ran 2,458 JVM tests and failed three: two brush fingerprints changed with the corrected
  rasterization, and a new export test inferred a non-void return type that JUnit rejected.
  The seven emulator configurations did not run after this build-stage failure.
- Local combined kernel checks executed all eight selected brush/transform classes: **53 tests
  passed**. The next combined retouch/text run executed all five selected classes: **25 passed**.
  The SDK-free harness excludes Android-dependent code; these results do not establish an Android
  build or device pass.
- A third focused run executed all five selected physical-paint/preview classes: **26 tests passed**.
  These batches overlap in existing physical-paint coverage and must not be added as unique tests.
- Task 020's final focused run executed six brush, text and clone classes: **33 tests passed**.
  Comparing separately compiled prior/corrected ellipse renderers across 200 preset outputs found
  50 changed nonround presets, no changed round presets and no vanished presets. For a hard tip
  of roundness 0.05, increasing radius 10 to 15.01 now increases painted area from 15.702 to 35.404
  pixels (geometric expectations 15.708 and 35.390), instead of decreasing to 9.569. The geometric
  regressions remain independent of the refreshed captured hashes. This is kernel evidence,
  not an Android performance benchmark.
- The local Python tools suite passed **76 tests**. The initial CI-script suite ran 408 cases with
  one environment error (the workspace has a Java runtime but no `javac`) and six external-fixture
  skips. Hosted CI provisions JDK 17 and remains the authority for the complete script suite.
- ktlint and detekt checks are run without expanding the static-analysis baseline. New Android
  and mocked-Android tests require the hosted exact-revision build and emulator matrix.

Further specialist tasks and final exact-revision results will be recorded here as they complete.
