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
| 025 | Liquify freeze workflow | Connect selected Freeze/Unfreeze taps and drags, immutable accepted coverage, clear/reset controls, cached percentage and document/layer/frame/revision ownership. Admit mask copies before allocation. Six kernel and three device regressions added. |
| 026 | Tagged raster insertion review | Confirm existing sRGB decoded-pixel to destination-profile conversion. Document ordinary-photo gamut clipping and add a real URI/file, sRGB/P3, alpha, undo/redo and save/reopen regression. Do not relabel decoded sRGB channels as P3. |
| 027 | Onion-skin memory and lifetime | Admit full composition, accumulated ghost buffers and old/new renderer ownership before allocation; reduce output or refuse safely. Serialize across cancellation and view recreation. Four budget, six ownership and one GL regression added. |
| 028 | Fill at canvas boundaries | Use safe neighbor lookup on every row/column, including one-pixel-wide canvases; preserve reference pixels and once-applied selection coverage. Three regressions added. |
| 029 | Gradient editing and numeric stability | Remove only one repeated stop occurrence; widen only overflowing linear/reflected projections while preserving finite Float output. Two regressions added. |
| 030 | Pixel clipboard ownership and privacy | Cancel obsolete queued edits without clearing valid cross-artwork clipboard contents. Keep cut commit/publication contiguous. Capture merged pixels, selection and privacy from one frozen layer set, including pinned frames. Controller, repository and device regressions added. |
| 031 | Release UI observation | Diagnose the retained export-result failure as a detached accessibility handle. Treat only a stale read as unobserved under existing bounds, without action replay or test-attempt retry. Seven helper regressions and original failure evidence retained. |
| 032 | Ellipse selection coverage | Exclude exact left-boundary samples consistently with the other ellipse boundaries; retain polygon half-open spans. Add exact coverage, transposition and reversed-winding regressions. |
| 033 | Hard color-selection threshold | Use the same squared-distance comparison for contiguous, global and range selections; avoid a square-root rounding disagreement at a real two-pixel threshold. One regression added. |
| 034 | Clipped Pass Through groups | Retain group isolation when clipping is enabled, so members respect translucent, empty, hidden or absent bases and group opacity applies once. Two regressions added. |
| 035 | Brush model/serialization review | No concrete defect found in detached mutable snapshots, bounded dual graphs, supported import numeric handling, exact-value entry or migration defaults; source/test review only. |
| 036 | Pressure-curve knots | Return exact stored values at linear curve knots instead of losing tiny outputs through floating-point cancellation. Preserve native calibration; 13 native fixtures and four Python checks passed. |
| 037 | Text word spacing | Share grapheme-safe runs and rendered advances across wrap, alignment, justification and curves; combine style/layer pixel offsets once. Eight kernel and five platform regressions added. |
| 038 | Stabilized lift endpoints | Preserve subpixel residual movement and use the exact lift coordinate after catch-up interpolation. Three regressions added; caller issues forwarded to 041. |
| 039 | Pointer gesture state | Separate tap consumption from held gestures, track live contacts, reset reused pointer IDs and scrub history on membership changes. Four regressions added. |
| 040 | Independent input-filter review | Task 039's separate `input_filter_review` specialist found stale palm lifecycle state, mixed filter clocks and filtered completion gaps. Review only; 041/042 implement the caller fixes. |
| 041 | Assisted and filtered completion | Finish at the accepted snapped endpoint with captured filtered sample metadata; catch up filter-only strokes while preserving pulled-string lag. Three real-view regressions added. |
| 042 | Input lifecycle and sample clocks | Observe pen terminal events while preview input is blocked; reset palm state at actual lifecycle boundaries; initialize filtering with captured DOWN acquisition time. Three real-view regressions added. |
| 043 | Symmetry geometry and directions | Use configured radial pivots, share active transform maps, transform pen/tip directions and handedness once, draw all mirrored axes, and report full copy counts. Nine regressions added. |
| 044 | Crop and size arithmetic | Handle tiny/narrow crops and overlapping grips, preserve aspect anchors within bounds, avoid empty rounded crops, reject overflow/unsafe helper growth, and correct exact half-pixel size rounding. Fifteen regressions added. |
| 045 | Outline alpha in shadows | Apply outline color alpha before shadow casting while preserving visible outline output. Two regressions added. Shadow clipping outside the canvas is a separate follow-up in 046. |
| 046 | Off-canvas shadow support | Keep the finite support through all three blur passes, crop in place, reuse one line, and charge halo storage in artwork/onion preview admission. Ten analytical, crop-equivalence and memory regressions added. |
| 047 | Layer parameter gesture history | Give opacity, mask density and feather explicit synchronous pointer sessions with one bounded undo entry per drag. Reject stale tokens and seal on cancellation/navigation while preserving accepted values and ordinary keyboard coalescing. Twenty repository/controller/ViewModel and five device regressions added. |
| 048 | Transparent adjustment samples | Alpha-weight full-color halftone averages and exclude hidden RGB when sampling separate glitch channels. Three source-preservation and transparent-color regressions added. |
| 049 | Grain consistency and preview damage | Pass the same mean pressure and post-dab random sequence through live drawing, erasing and secondary grain. Redraw the full growing stroke when pressure grain can change earlier pixels. Cross-path and cache regressions added; existing captured brush outputs remain unchanged. |
| 050 | Physical grain and dual coverage | Resolve the same grain/secondary masks before physical deposition; reject invisible cutouts and scale partial material coverage. Reuse masks without another full-canvas plane, with one additional geometry-only traversal for masked physical strokes. Nine regressions added. |
| 051 | PNG encoder/caller review | No concrete defect found in channel/filter/chunk/CRC/ICC/density encoding, bounded rows or failure propagation. Production decoding remains platform-owned. Four existing kernel classes passed 17 tests, including independent ImageIO channel decoding. |
| 052 | TIFF encoder/caller review | No concrete defect found in little-endian directory/strip offsets, straight RGBA alpha declaration, density or size arithmetic. Actual export prepares sRGB before writing untagged TIFF. Review does not establish external application interoperability. |
| 053 | Stable Android text boundaries | Replace runtime-dependent regex clusters with a pinned Unicode 17/UAX 29 forward iterator, including restricted emoji joining, RI parity and Indic conjuncts. Retain 766 official cases, compact generated properties, exact source provenance/license and two additional platform regressions. |
| 054 | Published bitmap lifetime | Independently confirm the export-preview ownership fix and remove immediate recycling of published projection frames. Clear the projection drawable on stop. Add a real VirtualDisplay pixel-output lifecycle regression; platform execution remains pending. |
| 055 | PSD metadata section bounds | Reject mask/range/name reads that cross the current layer's declared extra-data section, including truncated length fields and Pascal-name padding. Preserve supported empty metadata. Seven independent PSD/PSB regression methods added. |
| 056 | Complete Procreate zlib tiles | Require stream completion, bounded decoded size and no trailing input; reject missing checksums and plausible truncated prefixes through the existing damaged-tile path. Add independent zlib/edge-placement regressions. Flattened import caller protection is tracked in 057. |
| 057 | Atomic damaged-composite refusal | Check flattened Procreate damage before profile/layer/history publication for every fallback reason. Five real-repository tests and one staged-archive device regression preserve pixels, undo/redo, saved files and unrelated open imports. |
| 058 | Complete ABR sample rows | Require every PackBits row to produce its declared width; preserve legal no-ops and reject a damaged later tip before batch installation. Nine independent valid/malformed regressions added. Optional metadata scan allocation is a separate follow-up. |
| 059 | Compressed stream termination | Require exact Apple LZ4/LZO stream termination; accept the documented Apple `bv4-` stored-block header alongside existing `bvx-`. Preserve overlap/dictionary and raw-block behavior. Fourteen independent codec/caller regressions added. |
| 060 | Device failure diagnostics | Preserve the primary throwable and attach diagnostic/cleanup failures without masking it; attempt settings restoration after other cleanup failures. Simplify traversal/contrast checks without changing observations, thresholds or the 12 declared test identities. Four broader static findings resolved. |
| 061 | ABR metadata admission | Charge decoded names/keys before allocation, including discarded/duplicate candidates, within the shared decode allowance. Bound optional strings to their descriptor section and preserve complete valid names. Twelve caller and independent metadata regressions added. |
| 062 | Binary plist structural boundaries | Bound object payloads before the offset table and reject extended lengths/UIDs that discard nonzero high bytes or use the wrong marker. Preserve supported scalar semantics and zero-padded values. Fourteen handcrafted parser/caller methods added. |

| 063 | ZIP payload integrity | Verify actual entry CRC after the existing bounded read; reject exact-size, still-decodable metadata/tile corruption. Independent JRE proof and a staged-import device regression preserve destination history/files and unrelated imports. Directory preflight review found no additional defect. |
| 064 | Native package publication | Check export cancellation during streaming and before atomic replacement; enforce existing member, aggregate and staged-archive import limits before publishing. Preserve prior exports and source snapshot ownership. Six pure and three real-exporter regressions added. |
| 065 | Mixed-normal model imports | Track OBJ normal indices separately from positions across glTF/GLB primitives and USD meshes. Three preparation-path regressions cover alternating missing/present normals without changing model-format admission. |

| 066 | GIF quantization fidelity and memory | Widen channel-weight products before multiplication to prevent large bright regions changing color. Replace an unbounded full-color map with an exact-match, fixed 256 KiB lookup; collisions preserve nearest-color output. Independent ImageIO regressions added. |
| 067 | APNG frame metadata | Retain the first PNG frame's generated color profile and pixel density once before image data, preserving selected DPI and profile inheritance. Keep animation chunks regenerated. Three independent framing/metadata regressions added. |

| 068 | Shared export commit cancellation | Check active ownership during chunked byte writes and after sync/close before atomic replacement for both byte and streamed exports. Keep ordinary document commit semantics unchanged. Four actual animation/storage regressions preserve previous outputs, sources and staging cleanup. |
| 069 | MP4 background color profile | Matte prepared animation pixels and pad odd dimensions with the converted output-profile background. Add an actual exported-video decode test comparing transparent, partial-alpha, opaque and padded regions against Android's independent color connector. |

| 070 | Timelapse capture permission | Recheck recording permission, master switch, artwork marker and coroutine ownership after encoding and before atomic publication. Avoid caching skipped frames. Three actual-recorder regressions preserve source/recording/save/recovery data at the tested boundaries; broader decimation/replay retention is separate. |

| 072 | MP4 cleanup failure retention | Preserve original codec failure/cancellation while releasing output buffers, muxer and codec in order. Use the existing secondary-failure retention mechanism; successful work still fails if finalization fails. Eleven pure lifecycle regressions passed, including explicitly enabled coroutine recovery. |

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
- `6e645e70c5a9420acc7f9a06a24a704a81f2b48c` publishes tasks 018–024 and the export
  test-signature correction. Hosted Android compilation found a missing Compose `mutableStateOf`
  import in the new clone view state. `6466b40127dc6595145ff2f79f5d034d00428a95`
  supplies that import. The failed source revision remains a failed checkpoint; runtime results
  are recorded only against the revision that actually ran them.
- At `6466b401`, [Android CI](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/45769476819)
  executed 2,493 JVM tests with one failure: the historical compound-brush capture also uses an
  elliptical secondary tip. A separate comparison reproduced all six original hashes with the
  previous ellipse helpers before recording the intentional new captures. Original fixtures and
  their provenance remain unchanged; all eight secondary-grain tests then passed locally.
  [Optimized qualification](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/45769476793)
  and [installed upgrade](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/45769476849)
  passed. [Normal release UI](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/45769476784)
  failed on a stale accessibility node while the export result appeared; task 031 addresses the
  observation failure without accepting or replaying that failed attempt.
- `2d5977e8ea8a47017cdf1e9bbf70b089b5ff9296` publishes tasks 025–035 and the
  retained compound-brush capture follow-up. Its
  [optimized qualification](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/45773877789),
  [normal release UI](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/45773877799), and
  [installed upgrade](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/45773877704)
  passed. The [Android CI](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/45773877676)
  build job passed its JVM tests, instrumentation compilation, static analysis, Android lint,
  debug APK, release bundle and script checks. The first completed device lane, API36/16 KB,
  executed all 363 declared tests: 362 passed and one failed. The PSD selection test assumed a
  single exported layer despite task 012's visible-appearance plus hidden-source contract. The
  follow-up preserves the original source-alpha/mask assertions and additionally checks exact
  layer count, hidden source, visible alpha, merged composite and the export warning. All six
  API35/36 device configurations reported 362 passes and that same failure. API26 reported 358 passes,
  two declared API-limited skips, and three failures: the same PSD test, older-runtime emoji
  segmentation, and a published export-preview bitmap recycled while Compose still drew it.
  The preview ownership correction retains manual cleanup only for unpublished decodes; task 053
  addresses stable grapheme boundaries. All eight workflow jobs have completed: the build passed
  and all seven device jobs failed. These corrections await a subsequent hosted run; failed
  attempts remain failed.
- `3354025c50f8229d6b9000a0ce80f89aa2829a80` publishes tasks 036–058 together with
  the PSD selection-test contract correction and export-preview ownership repair. Its
  [Android CI](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/45781349947),
  [optimized qualification](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/45781349996),
  [normal release UI](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/45781349986), and
  [installed upgrade](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/45781350111)
  have started. The build, optimized qualification and normal release UI passed. The seven-device
  matrix is partially complete: API26, API35/4 KB, API36/4 KB and API36/16 KB each finished all
  383 declared tests with the same single PSD fixture failure; API26 retained its two declared
  API-limited skips. The new fixture searched for a layer named Ink, but its setup used Ink only
  as an undo description. The follow-up explicitly names the fixture layer and preserves all
  source/mask/visible/composite assertions. The earlier API26 segmentation and recycled-preview
  failures no longer occur in this run. The other three lanes are pending.
  Installed upgrade failed during the historical app's seed: the test asserted
  the Stylus only switch immediately after an asynchronous Room-backed preference update, before
  the UI changed. The candidate had not been installed. The retained first attempt remains failed;
  the test-overlay correction observes both stored preference and rendered state under the existing
  timeout before the next single action. It changes no historical production source or campaign gate.
  Its frozen 71-file tree matches the locally calculated Git
  tree `e06d511185bb875041681aef961c6f785f8efb95`. Changed-file ktlint (65 Kotlin files),
  detekt across main/unit sources and resource checks passed locally without baseline changes.
  A broader Android-test detekt scan also found four pre-existing diagnostic/style issues;
  task 060 owns that follow-up rather than treating the broader scan as a pass.
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
- A fourth focused run executed all 13 requested Liquify, onion, fill, gradient, color-selection
  and secondary-grain classes: **103 tests passed**. Later selection/group additions require
  their own subsequent execution; they are not included in this result.
- A fifth focused run compiled the frozen selection/color/group corrections with their nine
  selected test classes: **84 tests passed**. A sixth input/pressure/text run executed all eleven
  selected classes: **90 tests passed**. These local kernel runs reuse the compiled pure dependency
  graph and are not substitutes for the full Android build.
- A seventh focused symmetry/crop/size run executed all six requested classes: **98 tests passed**.
  An eighth shadow, preview-memory and adjustment run executed all five requested classes:
  **41 tests passed**. Both compiled the changed sources with the retained pure dependency graph;
  Android caller and device checks remain separate.
- Task 049's saved prior renderer failed four of its five new grain path comparisons. The corrected
  renderer then passed **31 tests in five classes**, including live replay, wet pickup, secondary
  grain and existing brush-default captures. No expected captures were changed. The separate
  cache regression failed with the previous invalidation logic and the corrected renderer;
  all **six PreviewCacheTest cases** then passed with corrected invalidation.
- Task 050's preserved prior renderer failed eight of nine new physical grain/dual cases. The
  corrected renderer passed **36 tests in six classes**, including those cases, existing scalar
  admission, live/grain rendering and unchanged captures. These overlap earlier brush batches
  and are not 36 additional unique tests. The extra geometry traversal has no device performance
  qualification yet.
- PNG review checks executed **17 tests in four existing classes**; the TIFF byte-level test also
  passed. These kernel checks include independent PNG decoding but do not qualify platform
  malformed-input behavior or external application interoperability.
- The final packed Unicode table passed **34 tests in five classes**, including all **766 official
  Unicode 17 conformance cases** and existing wrapping/spacing/justification/style checks. The
  reproducible table carries 6,664 bytes of primitive payload and no Android or new runtime-library
  dependency. This is segmentation evidence, not font coverage or platform shaping qualification.
- Task 055's prior PSD codec failed five of seven new independent boundary methods. The corrected
  codec passed **86 tests across ten PSD classes**, including corruption, allocation, masks,
  groups, compression, channels and import-source checks.
- Task 056's previous reader failed the three new malformed-zlib rejection methods. The corrected
  reader passed **52 tests across eight classes**, including corruption, hierarchy, node/opening
  budgets, masks, profiles and valid edge placement. Flattened caller publication is separately
  checked by task 057 and is not established by these pure reader tests.
- Task 058's prior ABR decoder failed all seven malformed-row/batch regressions. The corrected
  decoder passed **15 tests in four classes**, preserving literal, repeated, no-op and 16-bit
  decoding while rejecting incomplete rows before installation.
- Task 059's isolated prior codec run failed nine of fourteen completion/compatibility cases;
  the corrected run passed all fourteen. The integrated current LZ4/LZO/Procreate reader batch
  then passed **85 tests across ten classes**, including native mask export, writer and USD paths.
- Task 060's source-extracted error aggregation check passed **33 cases** and confirmed first-error
  identity, ordered secondary errors, continued cleanup and self-suppression protection. Its
  scoped ktlint and expanded detekt passed; this is not Android/Compose execution evidence.
- Task 061's preserved reader/budget failed nine of twelve new metadata tests. The corrected
  implementation passed **45 tests in seven classes**, including whole-batch brush archive
  admission, valid Unicode names, pixel allowances and the earlier PackBits row constraints.
- Task 062's preserved parser failed nine of fourteen new boundary methods. Its corrected parser
  passed **49 tests in seven classes**, including writer, budget, brush, profile and node admission.
- Task 065's prior readers failed all three mixed-normal preparation cases. The corrected readers
  passed **43 tests in five classes**, including existing glTF, USD, geometry bounds and prepared
  texture admission. An initial local compile omitted the shared TestPng helper; the corrected
  invocation compiled all selected classes before executing them.
- Task 064's package batch passed **32 tests in five classes**, including stream/final-close
  cancellation, existing size ceilings, CRC/corruption, collection and ZIP admission. The actual
  exporter tests use a valid deterministic 64×64 PNG and require the hosted Android unit build.
- Task 066's prior GIF encoder failed the independent 9-megapixel dominant-color decode. The
  corrected encoder passed **ten tests in four classes**, including independent black/green cache
  collision decoding and existing palette, alpha, delay and edge cases. The fixed cache has 262,144
  bytes of primitive payload; the full export's device peak memory remains a separate qualification.
- Task 067's prior APNG encoder failed two of three metadata cases. The corrected encoder and
  current PNG encoder passed **four tests in two classes**, retaining per-frame payloads, CRCs,
  sequence order, disposal/blend, timing and loop assertions.
- A full local detekt scan across main, unit and Android-test sources passed after task 060,
  without changing the baseline. The historical-app overlay's changed source passed ktlint;
  all **14 build-provenance tests** passed. Overlay compilation/runtime remain hosted gates.
- Seven new accessibility-observation functional cases passed under the available JDK 21 compiler.
  Its Java 17 release symbols are absent, so the required `--release 17` helper harness remains a
  hosted check rather than a claimed local pass. No test or required compiler flags were relaxed.
- The local Python tools suite passed **76 tests**. The initial CI-script suite ran 408 cases with
  one environment error (the workspace has a Java runtime but no `javac`) and six external-fixture
  skips. Hosted CI provisions JDK 17 and remains the authority for the complete script suite.
- ktlint and detekt checks are run without expanding the static-analysis baseline. New Android
  and mocked-Android tests require the hosted exact-revision build and emulator matrix.

Further specialist tasks and final exact-revision results will be recorded here as they complete.
