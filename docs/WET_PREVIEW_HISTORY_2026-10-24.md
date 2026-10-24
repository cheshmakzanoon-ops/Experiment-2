# Wet preview pickup history — 24 October 2026

## Reproduced defect

Published revision `8050121a7a6e3a1d52854d3c398ad1d29a33bcfe` can show a different colour from
the committed stroke when a wet brush has to replay through a cropped preview. The defect was
reproduced against that revision's compiled repository and rasterizer, before changing production
code. The [original probe record](evidence/wet-preview-before.json) retains the complete output,
source and compiled-class hashes. These local probes did not launch an emulator or execute the
native reference application.

The smallest fixture is a 512 by 128 opaque red layer, fresh blue paint, and a compound brush.
Its primary has size 20, spacing 0.25, neutral pressure size/opacity, no smoothing, `wetMix=0.1`
and `pull=1`; its secondary is a plain brush of the same size and spacing, combined with Multiply.
Samples are `(30.5, 60.5, 1000)`, `(190.5, 60.5, 1016)` and `(350.5, 60.5, 1032)`, with pressure 1.
There is an initial frame before painting and another after the second sample. Half-pixel positions
and five-pixel dab steps avoid incidental crop-coordinate rounding.

The appended-tail frame differs from a complete preview at 3,835 pixels; 3,809 have a channel error
greater than two and the largest error is 215. At `(190, 60)`, the correct colour is `FFDF0020`,
but the frame contains `FF3800C7`. A separate repository that commits without rendering previews
matches the complete preview byte for byte. Stored source pixels and undo history remain intact.

Additional probes on the same unchanged revision establish the related paths:

| Preview path | Different pixels | Maximum channel error | Independent result |
| --- | ---: | ---: | --- |
| Compound wet appended tail | 3,835 | 215 | Full preview equals commit |
| Sealed wet segment followed by a small dry stroke | 864 | 232 | Full preview equals sequential commits |
| Mirrored wet appended tail | 4,599 | 197 | Full preview equals mirrored commit |
| Historical wet vector on the painted layer | 864 | 232 | Full preview equals commit |
| Historical wet vector on a lower layer | 864 | 147 | Full preview equals commit |
| Active wet stroke over historical dry vectors | 3,839 | 193 | Full preview equals commit |

Cancelling the small edit over either historical wet-vector fixture also leaves 864 wrong display
pixels in the old cached frame. The underlying document is still correct. Two controls — a solo wet
brush that can render live and a dry compound brush — match full preview and independent commit
exactly through both regional updates and cancellation.

## Cause and correction

An appended preview normally redraws only the stroke's new tail. A live stroke retains all preceding
dabs and samples the full source layer. Compound brushes, wet symmetry, historical vector layers,
sealed segments and insufficient live-buffer memory can require a replay instead. That replay used
the cropped layer as its sampler, so earlier off-crop dabs picked up transparent pixels. Their changed
carried colour then reached the visible tail.

Preview planning now uses the same ownership, brush, mirror-count, historical-vector and memory
eligibility predicate as live-buffer allocation. A wet stroke that must replay requests a complete
frame. Historical wet vectors anywhere in the preview's layer stack also require a complete frame,
including pinned layers and layers below a dry edit. Existing dependencies on earlier provisional
paint remain covered. Eligible solo wet brushes and dry brushes retain regional rendering.

The rendering path checks eligibility again. If cancellation or sealing revokes ownership between
snapshot capture and painting, a private signal stops the cropped fallback before it can replay wet
pickup. The caller discards the regional attempt, resets the lower-layer cache and renders the
unchanged full snapshot. Regional attempts only mutate private crop copies; this restart does not
modify stored paint or history.

For the smallest fixture alone, replaying the entire stroke's bounds is also sufficient and was
proved with the old renderer. That is not a general repair for sealed segments, wet historical lower
layers or a live-ownership change during rendering. The correction therefore takes the complete
frame for these fallback paths. This has a performance cost for affected wet brushes; it preserves
correctness while a future full-source replay design could recover smaller updates.

The wet repair changes no wet-paint equation, brush random sequence, stored document format or
native spacing contract. The same batch separately fixes [Model visibility](MODEL_HEADER_VISIBILITY_2026-10-24.md)
and [retry attribution](ANDROID15_RETRY_ATTRIBUTION_2026-10-24.md), and removes the new optional-parameter
ordering warning. No required lane, skip, recovery allowance or static-analysis baseline changes.

## Verification boundary

The [combined local gate](evidence/wet-model-local.json) passes 1,518 JVM tests in 252 suites,
with no failures, errors or skips, Android test compilation, ktlint and detekt. Seven new JVM cases
retain all 1,511 earlier identities. Against the unchanged preceding production classes, the new
class first failed six defect cases while its solo-live/dry controls passed. The corrected full suite
passes; no expected pixels or comparison tolerance was weakened. The initial local gate passed
the JVM suite but reported one detekt condition-complexity issue. Splitting the condition resolved
it without suppression or a baseline change; the complete follow-up gate passed.

The separate Android case retains exact first/final hashes and colour probes, cancellation,
independent no-preview commits, undo/redo, raw signed storage, saved pixels and a fresh repository
reopen. Its nine retained files must be checked after actual execution. It added one identity for a
total of 254, retaining every previous device test.

[Run 313](CI_RUN_313_2026-10-24.md) passed the full build and 1,518 JVM cases, but exposed a defect
in this new Android declaration. The expression-bodied coroutine inferred `JSONObject` from its
last report update. JUnit requires a `void` method and rejected the class before running the body;
five lanes report `initializationError` instead of the declared method. Its nine files are absent.
The other two Android 15 lanes terminate earlier. This is a test-initialization failure, not an
executed result for the wet assertions. All seven jobs and the run remain failed.

The follow-up adds an explicit `: Unit` return type. Every other byte in the published test file,
including fixtures, exact pixels, cancellation, undo/redo and disk assertions, remains unchanged.
The [local follow-up](evidence/opaque-source-over-local.json) passes Android compilation, ktlint
and detekt, and inspection of the compiled class confirms a public zero-argument `void` method.
This declaration check does not execute its body. A fresh Actions run must retain and verify all
nine files. The same candidate adds a separate scalar compositing case, making 255 device identities.

The compound fixture's complete final pixels, encoded as big-endian ARGB, have SHA-256
`27acdb833c4464b0cec7703c2b52d8443dd5bd3da6746add63cd0719119f12da`.
Its first in-progress frame is
`3722afbb26761662e7c8eb6c4f4ecaf46011eb88712811742c4a3bc175fcc340`.
The incorrect old tail frame is
`a0c99e33273ecb2c9c5f328f48b369fc9012b217a5b4e809d39cc257502b61f8`.
The regressions retain exact all-pixel checks and selected fixed colour probes; no tolerance is
introduced to hide this discrepancy.

The executed JVM evidence establishes consistency between ArtFlow preview, cancellation and
committed pixels for the covered paths; the Android storage lifecycle remains pending execution.
Native Procreate wet equations, compound rendering, physical stylus feel and display latency remain
separate requirements in the [parity register](PROCREATE_PARITY.md).
