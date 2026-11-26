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
| 006 | Scatter bounds and thin brush tips | Include longitudinal scatter in damage bounds; integrate subpixel elliptical tips and transition smoothly to the normal rasterizer. |
| 007 | Selected Move/Transform and Reset | Use the existing selection-aware interaction path; identity affine/quad/mesh operations preserve soft-alpha bytes. 39 focused kernel tests passed. |
| 008 | Export ownership review | Review request-generation cancellation; add real ViewModel/export preservation coverage and reliable coroutine-test cleanup. |
| 009 | Clone and Healing taps | Apply one initial dab, preserve empty-source healing destinations, and bound damage from the previous sample. Five kernel and two device regressions. |
| 010 | Text wrapping integrity | Preserve authored spacing and complete grapheme clusters; add pure and API-matrix platform regressions. |
| 011 | Physical transform ownership | Move selected physical scalars with raster transforms, preserve exact Reset, check ownership and allocation before atomic commits, and retain undo/save/reopen coverage. |
| 012 | Feathered layered exports | Export one once-clipped visible appearance for soft-selected PSD/Procreate documents, preserve hidden selected sources/masks, disclose the fallback, and budget it before allocation. |
| 013 | Live export preview | Connect a current-artwork preview, reject stale/recovery/unsafe requests, cap output at 512 pixels, convert display color and decode off the UI thread. |
| 014 | Reference/model preview ownership | Bind every request to generation, content revision and frame; finish color/material work before publication and retain one rendering worker across document resets. |
| 015 | Independent storage review | No additional blocker found in alias retention, snapshot pinning, older recovery, failed discard, or failed-load preservation; source review only. |

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
- Local combined kernel checks executed all eight selected brush/transform classes: **53 tests
  passed**. The next combined retouch/text run executed all five selected classes: **25 passed**.
  The SDK-free harness excludes Android-dependent code; these results do not establish an Android
  build or device pass.
- A third focused run executed all five selected physical-paint/preview classes: **26 tests passed**.
  These batches overlap in existing physical-paint coverage and must not be added as unique tests.
- The local Python tools suite passed **76 tests**. The initial CI-script suite ran 408 cases with
  one environment error (the workspace has a Java runtime but no `javac`) and six external-fixture
  skips. Hosted CI provisions JDK 17 and remains the authority for the complete script suite.
- ktlint and detekt checks are run without expanding the static-analysis baseline. New Android
  and mocked-Android tests require the hosted exact-revision build and emulator matrix.

Further specialist tasks and final exact-revision results will be recorded here as they complete.
