# Exact opaque-backdrop compositing optimization

## Reason for the change

The existing large-brush fixture still spends substantial time producing previews. In
[run 312](CI_RUN_312_2026-10-24.md), a 300 px soft brush on a 2048 × 2048, six-layer canvas had
combined sample/preview p95 values of 67.733 ms on standard Android 15 and 86.151 ms on Android 16.
Those are elapsed repository calls, including waits; they are not CPU time or pen-to-screen latency.

A bounded local profile of the unchanged `8d8d0e6` source reproduced the same canvas, layers,
three brushes in order and 80 samples per brush. It retained earlier committed strokes. The
HotSpot/JDK 17 harness used an unused mocked Android context, `createCanvas`, an Unconfined Main
test dispatcher, explicit 16 ms input timestamps and JFR sampling. It did not run an emulator.
The captured Kotlin sources match the published revision, and compiled classes stayed unchanged.

Of 106 airbrush execution samples, the top method was round-dab stamping in 48, normal layer
compositing in 24 and coverage deposition in 23. Inlining and statistical sampling prevent assigning
an exact fraction of elapsed time to a single callee. Both compositing paths use `sourceOver`, and
the fixture's lower stack contains opaque Paper. This supports examining its general alpha math.
The [analysis record](evidence/opaque-source-over-before.json) retains the observations, input/output
hashes, raw paired scalar measurements and original-analysis file hashes.

## Production correction

`BlendModes.sourceOver` now has a ten-line branch for a fully opaque backdrop, after its existing
transparent-source and opaque-source early returns. For all 256 possible source alpha bytes,
the original binary32 expression `a + 1f * (1f - a)` is exactly `1f`; so is its reciprocal.
The branch removes those redundant operations and multiplications by one. It keeps the remaining
Float multiplies/addition in their original order and retains `Channels.fromFloats` rounding:

```kotlin
val inverseSource = 1f - asource
val r = Channels.red(source) * asource + Channels.red(backdrop) * inverseSource
val g = Channels.green(source) * asource + Channels.green(backdrop) * inverseSource
val b = Channels.blue(source) * asource + Channels.blue(backdrop) * inverseSource
return Channels.fromFloats(255f, r, g, b)
```

The nonopaque branch and `sourceAtop` are unchanged. Alpha-locked painting benefits through
`sourceAtop`'s existing opaque temporary backdrop and subsequent restoration of the original alpha.
The change preserves the existing blend equations, document versions and stored pixel values.

## Independent pre-patch evidence

Before changing production source, an analyst function was compared with the actual compiled
original `sourceOver`. It matched all 16,777,216 combinations of source alpha, source channel and
backdrop channel over opaque backdrops, all 256 raw binary32 output-alpha identities, and 100,000
seeded mixed-RGB pairs. The exhaustive channel check repeats the channel across RGB; the original
equation is separable once alpha is fixed. This is not an enumeration of all possible pixel pairs.

A separate local scalar comparison used 65,536 fixed pixels, 32 passes per measured invocation,
six warmup pairs and twelve measured pairs with alternating order. Full output arrays and
all-pass checksums matched in every pair:

| Local scalar fixture | Original median | Candidate median | Median paired ratio |
| --- | ---: | ---: | ---: |
| Opaque backdrops | 33.870 ms | 22.547 ms | 1.510× |
| Approximately half opaque backdrops | 34.455 ms | 27.072 ms | 1.276× |

The candidate was faster in all twelve measured pairs for both local fixtures. These timings
motivate the small change; they do not predict Android, full-preview or Procreate performance.

## Regression and emulator evidence contract

Four new JVM identities retain the frozen prior formula and test exhaustive opaque equivalence,
all source/backdrop alpha pairs with mixed colours and alpha-lock behavior, normal/pass-through
composite opacity and source ownership, and clipped alpha/source ownership. All earlier tests remain.

One Android identity measures the actual production scalar function against the prior scalar
body from `8d8d0e6`. It uses two 256 × 256 authored fixtures. The opaque fixture exercises every
source alpha; the mixed fixture exercises every source/backdrop alpha pair. Its mixed backdrop has
only 1/256 opaque rows, so it also captures fallback cost and differs from the local half-opaque mix.

Each fixture has six warmup pairs and twenty measured pairs with alternating order. Destination
reset, exact all-pixel comparisons, caller-ownership checks and retained-output copies occur outside
timing. All 104 calls must preserve their expected pixels and inputs. No speed threshold is added.
The case retains two raw big-endian ARGB outputs and a JSON report containing ordered nanoseconds,
median definitions, runtime, scope and provenance. Expected hashes were captured from the unchanged
compiled original before production edits:

| Fixture | Expected 262,144-byte ARGB SHA-256 |
| --- | --- |
| Opaque | `30171c2b9b346e14fe571dc5f8db1dc63e78327dd8db4336963542a5fc349321` |
| All alpha pairs | `7ddcf8882882064a4968f500a4895c3277b7cf083c40b4a2bd71e3c1e7bf9d06` |

These outputs include hidden RGB beneath zero alpha; a PNG round trip is not substituted for raw
pixel verification. The independent artifact reader checks the final raw files and report data;
the intermediate-call and ownership assertions additionally require the exact device case to pass.

The first complete local gate passed all 1,522 JVM tests / 253 suites and Android compilation,
but reported one test-helper nesting-depth finding. The unchanged alpha-lock oracle was extracted
into a helper, preserving every loop, input and assertion. The complete corrected gate passes
1,522 JVM cases / 253 suites with zero failures, errors or skips, Android compilation, ktlint and
detekt. All 1,518 preceding JVM identities are retained. The [local acceptance record](evidence/opaque-source-over-local.json)
preserves the failed first gate and both subsequent gates with their source and report hashes.

Run 313 independently exposed a non-void return inferred for the preceding wet-preview Android
test. This candidate adds only an explicit `Unit` declaration to that file. Every other byte,
including every assertion, remains unchanged. A subsequent Android-only compilation/static gate
passes and inspection of its actual compiled class confirms public zero-argument `void`, as it
does for the new scalar device case. No device execution is inferred from that declaration check.
Production and JVM source still match the successful complete gate; no redundant JVM rerun or
fresh local Python execution is claimed. A fresh Actions run must exercise both device bodies.
Four JVM identities and one Android identity are added, with no removals; the device inventory is 255.
No workflow, recovery rule, test skip, baseline or native reference contract changes. Physical stylus
feel, display latency, native wet/compound equations and full Procreate parity remain unestablished.

## Executed run 314 results

[Run 314](CI_RUN_314_2026-10-24.md), source `47612ae`, passes all 1,522 JVM tests and all
seven final 255-case device attempts. Every lane retains both exact 262,144-byte raw outputs
and its full alternating-pair report; the passing case verifies all 104 calls and input ownership.
Main Android 15 / 16 KB requires one preserved earlier ART failure and verified reboot before
this final execution. The other six lanes pass without recovery.

The following are the medians of twenty measured calls per path, in milliseconds. Warmups
are excluded. Each value covers 65,536 scalar calls and destination writes, including waits.

| Emulator lane | Opaque prior | Opaque current | Mixed prior | Mixed current |
| --- | ---: | ---: | ---: | ---: |
| Android 8 | 3.244 | 3.086 | 3.262 | 3.286 |
| Android 15 / 16 KB | 232.645 | 223.751 | 232.025 | 231.005 |
| Android 15 / 4 KB | 7.432 | 7.375 | 7.201 | 7.222 |
| Android 15 / 16 KB repeat | 236.985 | 228.593 | 236.665 | 234.660 |
| Android 16 / 4 KB | 8.949 | 8.806 | 9.154 | 8.991 |
| Android 16 / 16 KB | 5.463 | 5.334 | 5.480 | 5.477 |
| Android 16 tablet | 5.427 | 5.290 | 5.435 | 5.449 |

Both Android 15 / 16 KB lanes use the workflow's measured disabled-JIT runtime; the others
use their default runtime. Compare paired paths within a lane, not timings across runtimes
or runners. The opaque medians improve modestly in every lane; the mixed case is slightly
slower on three configurations. The local 1.51× opaque scalar ratio is not reproduced as a
device-wide performance claim. No speed threshold was introduced, and these results establish
neither complete-preview nor physical input-to-display latency nor native Procreate equivalence.
