# Native Count and control-pressure investigation

## Finding and scope

The pinned reference consumes `dynamicsPressureStampCountCurve` and
`dynamicsPressureStampCountJitterCurve` in its **general moving-plot Count loop**.
ArtFlow retains these graphs but does not consume them in `StrokeRasterizer.drawDab`.
The Count arithmetic, the modulation-bit-8 temporal pressure filter and the
four-control interpolation kernel have now been reconstructed. Their input
construction is only partly established.

This extends [the implemented base Count correction](NATIVE_BRUSH_COUNT.md) and
[the implemented native spline evaluator](NATIVE_PRESSURE_SPLINE.md). It does
**not** add a pressure consumer or change saved brush behavior. The remaining
work is a specific processed-control and plot-placement contract; applying a
retained graph directly to Android's existing per-dab pressure would not
implement the recovered native pipeline.

This record describes read-only analysis on 24 October 2026. Reference code was
never executed. Native addresses were checked as data, mathematical fixtures were
derived independently, and a separate probe ran ArtFlow's importer/rasterizer.
None is a captured Procreate stroke, a new emulator result, or full brush parity.
The application boundary was rechecked against published `e2aa900b91298fe08eca8529d0345220faa9e2eb`.

## Source identity and evidence levels

The source is the established Procreate 5.4.4 / build 56832 reference:

| Input | Bytes | SHA-256 |
| --- | ---: | --- |
| IPA | 777,430,382 | `7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e` |
| `Payload/Procreate.app/Procreate`, ARM64 executable | 19,119,424 | `76d3ff1249a987b8f03ac3ecc64c54e48f618bac467e9fbb1f238b91fab1487b` |

The investigation used the repository's
[`NativeImage` structural reader](../tools/ipa_native_contracts.py) and
Capstone 5.0.6. No binary, instruction listing, shader IR or proprietary image
is included here.

| Evidence | What was checked | What it does not establish |
| --- | --- | --- |
| Count static verifier | Whole-file hashes; 11 method identities, 24 selector-call anchors and 38 instruction anchors; existing base-Count gate's 27 instructions and five calls | Every branch, RNG distribution or native pixels |
| **55 Count fixtures** | Binary32/FMA composition with the existing independently reconstructed spline, explicit query pressure and random inputs | Where a real stroke obtains those inputs |
| **25 ArtFlow tap probes** | Five self-authored graphs at five pressures through the actual importer and renderer | Native tap behavior or Android input/display behavior |
| Control static verifier | Whole-file hashes; 23 methods, 61 calls, 100 instructions, seven Float constants and three imported-symbol resolutions | The complete prediction, spacing or device-input state machine |
| **62 control fixtures** | Six time-dilation, 17 temporal-filter, four status/scale, five weight, 27 interpolation/Count and three extrapolation cases | Exhaustive inputs or arbitrary Apple `powf` bits |

These were separate analyst checks, not additional required CI test identities.
A further arithmetic counterexample distinguishes separate multiplication/addition
from an incorrectly fused expression. The selected finite fixture domain assumes
binary32 round-to-nearest-even, positive time dilation/rate and nonnegative
distance/index; no runtime floating-point control register was observed.

For identification, the retained analysis outputs have these hashes:

| Derived output | SHA-256 |
| --- | --- |
| `count-pressure-fixtures.json` | `c3f8270745345d1d034adc04a610c56a68945a29d637e0097a87505023286c02` |
| `control-pressure-fixtures.json` | `8c29a3df0e81f02550da44e7c4783be72ddc05f30ab0143b0afae344a0552c53` |
| `count-probe-report.txt` | `f293d995d648eae8ee6e0c70ccdb427ce278378b134b88f538dec57fc916dfee` |
| `count-graph-corpus.json` | `a429dc3e4f76ca6c85b7cd6f0e6df1fb2170ffb7614bb74a4b824187ca60d9db` |

These analysis artifacts are not shipped fixtures. The equations and small
reproduction below preserve the useful contract without requiring their large
scratch reports.

## Archive, host and plot linkage

Addresses refer to the exact executable above; selector names alone were not
treated as proof of dataflow.

| Stage | Method or checked address | Recovered relationship |
| --- | --- | --- |
| Curve key | `ValkyrieBrush.serializedKeyForCurve:`, `0x100a3f274`; decoder `0x100a40084`–`0x100a400b0` | Count is channel 1, Count Jitter 2, overall pressure 38; curve slots are `self+0x18+8*index`. |
| Table | `ValkyrieBrush.curveTable`, `0x100a3df00`; `tableForCurves:curveCount:resolution:`, `0x100b80934` | 39 channels, 1,024 rows, stride 40 floats. Missing channels fill one. |
| Platform pressure | `DocumentValkyrieController.pressureForTouchObject:originatingTouch:onView:`, `0x10070b430`; calls `0x10070b4d0`, `0x10070b4dc`, `0x10070b4ec` | Supported touch path divides Double force by maximum force, converts to Float, then calls global `lookupPressure:`. |
| Live options | `DocumentValkyrieViewController.updateBrushForGesture:`, `0x100727784`; calls `0x1007278cc`, `0x100727bb4`, `0x100727bc0` | Rate comes from `pointsPerSecondUsingStylus:`; pressure scale comes from gesture `maximumPossibleForce`, not a fixed factory value. |
| Gesture scale | `SiGestureRecognizer.maximumPossibleForce`, `0x10040446c`; `0x1004044d0`–`0x100404530` | Maximum of Double one and active touches' maximum-force values. |
| Per-point overall | Segment initializer `0x100ad5bec`; `0x100ad5cbc`–`0x100ad5cd8` | Copies the point and replaces its pressure with table channel 38 before constructing controls. |
| Time/filter/control | `timeDilationWithOtherControl:`, `0x100ad6cc4`; `softenedPressure:speed:timeDilation:otherControl:otherPressure:`, `0x100ad6f3c`; `controlWithTimeDilation:speed:otherControl:`, `0x100ad70a4` | Applies mapped pressure × live scale, temporal response and pressure-status handling; decisive pressure block is `0x100ad7400`–`0x100ad747c`. |
| Four-control input | Plot initializer `0x100ad0f74`; pressure calls `0x100ad14e0`, `0x100ad14ec`, `0x100ad14f8`, `0x100ad1504` | Packs future, leading, trailing and historical pressures at curve-buffer offsets `0xb0`–`0xbc`. |
| Lookup and Count | `plotCurve:previousPlot:initialAngle:`, `0x100ad20b8`; interpolation `0x100ad26a0`–`0x100ad2814`, lookup `0x100ad288c`, Count `0x100ad2934`–`0x100ad2a04` | Interpolated control pressure feeds all-channel lookup; stack results `0x13c` and `0x140` feed Count and Count Jitter. |

The vector lookup at `0x100b80a7c` clamps its input and interpolates adjacent
table rows, using the same reconstructed Float/FMA contract as scalar lookup.
A missing graph means one; an explicit identity graph means pressure. Those
defaults are materially different.

## Count composition and ordering

Let `f(x)` mean one binary32 rounding and `fma(a,b,c)` one fused binary32
rounding. Let `q` be the processed/interpolated native pressure, `C(q)` the
Count graph, `G(q)` the Count Jitter graph, `J` the jitter amount and `u` an
explicit native random sample. Let `Ktilt` and `Kroll` be their separate
modifiers; the derived fixtures use both equal to one.

```text
N = unsigned_truncate(f(f(shapeCount) * 15))
F = fma(f(-f(J) * f(u)), G(q), 1)
E = f(F * f(N))
E = f(C(q) * E)
E = f(Ktilt * E)
E = f(Kroll * E)
stampsBeforeCapacity = 1 + unsigned_truncate(clamp(E, 0, 15))
```

There is no intermediate integer truncation after applying the Count graph.
The mandatory first stamp remains when that graph is zero. A zero jitter graph
suppresses jitter. The later capacity guard can stop emission before this bound.

For `shapeCount=1`, zero jitter and neutral tilt/roll:

| Count graph | q=0 | q=.25 | q=.5 | q=.75 | q=1 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Constant zero | 1 | 1 | 1 | 1 | 1 |
| Constant half | 8 | 8 | 8 | 8 | 8 |
| Identity | 1 | 4 | 8 | 12 | 16 |
| Descending | 16 | 12 | 8 | 4 | 1 |
| Constant one or absent | 16 | 16 | 16 | 16 | 16 |

The half graph evaluates to `float32(512/1023)`, not exactly .5. With absent
graphs and no jitter, all 16 supported base Counts remain unchanged even at
query pressure zero.

Two explicit-input cases distinguish algorithms. Base Count 8, a half graph,
`J=.2` and `u=.5` produce four stamps; premature graph truncation would
produce three. Base Count 10, absent graphs, `J=.5` and `u=.25` produce
eight; ArtFlow's current inclusive integer-removal rule produces nine for the
same supplied sample. Both fit the handbook endpoint range, so endpoints alone
do not establish the equation.

In the inspected general Count path, random consumption occurs even with zero
jitter or zero extra count.
ArtFlow currently consumes the Count sample only when jitter is positive and
Count exceeds one. The native cyclic 4,096-entry sample table is not established
as equivalent to ArtFlow's seeded Java random stream. Changing this consumption
also changes later scatter/colour draws. No random-probability claim follows
from the supplied-sample fixtures.

## Temporal control pressure

The point's overall graph is evaluated **before** control filtering. The control
builder then multiplies that result by the live pressure scale. Relative point
time is stored in Double; sample-time calls are `0x10070bd34` and
`0x10070bd40`. Dilation `T` is:

```text
other exists and currentTime != other.interval:
    T = f(abs(doubleCurrentTime - doubleOtherTime) * double60)
otherwise:
    T = f(float32(60) / uint64ToFloat32(pointsPerSecond))
```

Equal timestamps use the rate fallback; reversed times use an absolute delta.
Fallback rate 60 gives T=1 and rate 120 gives T=.5. These are supplied-input
examples, not inferred device sample rates.

Let `P` be current mapped/scaled pressure, `Q` previous processed pressure,
`R` brush `dynamicsPressureResponse`, `D` segment distance and `I` segment
index. Despite the filter selector's parameter name `speed`, the general
pressure call passes **response amount R**, not calculated movement speed.

With modulation bit 8 clear, the filter returns P. With it set:

```text
if otherControl exists:
    B = f(.2) if D <= 0 else clamp(f(T/D), f(.2), 1)
    A = external_powf(B, f(R+R))
    M = f(f(f(P-Q) * A) + Q)             # separate multiply and add
else:
    Q = P
    M = P                              # no powf call

L = f(2/T)
V = f(f(f(uint64ToFloat32(I)-L)/L)+1)
W = f(L * clamp(V, 0, 1))
filtered = f(fma(Q, W, M) / f(W+1))
```

The caller then substitutes one for `pressureStatus==0`, otherwise clamps the
result into [0,1]. `ValkyrieStrokeOptions.optionsForBrush:tool:viewport:` at
`0x100acf66c` stores factory modulation `0x3fffff` at `0x100acf74c`–`0x100acf750`,
including bit 8; this does not prove every live configuration keeps it enabled. The D=0 branch selects .2,
not the one that a plain clamp of positive infinity would select. Preserve the
written order of V rather than simplifying it algebraically.

For P=1, Q=0, T=1, D=1 and enabled bit 8:

| Change | Derived output | Float bits |
| --- | ---: | --- |
| R=0, I=0 | 1 | `3f800000` |
| R=0, I=1 | .5 | `3f000000` |
| R=0, I=2 | .3333333432674408 | `3eaaaaab` |
| R=.5, D=0, I=0 | .20000000298023224 | `3e4ccccd` |
| R=.5, D=2, I=2 | .1666666716337204 | `3e2aaaab` |
| R=1, D=2, I=2 | .0833333358168602 | `3daaaaab` |

Response zero therefore does not neutralize all temporal behavior. Separately,
P bits `3f712156`, Q bits `3e88cbcd` and A bits `3e4ccccd` yield M bits
`3ecde394`; incorrectly fusing the multiply/add yields `3ecde393`.

`_powf` resolves through stub `0x100bb60e0` to
`/usr/lib/libSystem.B.dylib`; `_rand` and `_srand` resolve there through
`0x100bb6218` and `0x100bb629c`. That external library is not supplied by the
IPA. The fixtures use elementary power cases or explicitly supplied Float
power results; they do not manufacture arbitrary Apple `powf` equivalence.

## Four-control interpolation and boundary controls

The normal initializer shifts previous future → leading, previous leading →
trailing, previous trailing → historical, and supplies a new future control.
`derivedFromOther:` at `0x100ad46e8` extrapolates pressure as
`f(own + f(own-other))` without clamping: .25 relative to .75 becomes −.25.
The terminating initializer at `0x100ad0730` derives a future control before
calling the normal plot evaluator. Missing early history is also extrapolated;
it cannot simply be replaced by two neighboring Android samples.

For a **supplied** plot parameter t in [0,1], use these exact Float bit patterns:
H=`3fc90fdb`, pi=`40490fdb`, twoPi=`40c90fdb`,
A=`3fa2f983`, B=`becf817b`, C=`3e666666`.

```text
sineApprox(x):
    y = fma(x, A, f(f(x*B)*abs(x)))
    return fma(fma(y, abs(y), -y), C, y)

a = f(t*H)
b = f(a+H)
b = f(b - (twoPi if b > pi else 0))
u = f(sineApprox(a)*.25)
v = f(sineApprox(b)*.25)
w = [f(.25-v), f(.25+u), f(.25+v), f(.25-u)]

q = f(w[1]*leading)
q = fma(future, w[0], q)
q = fma(trailing, w[2], q)
q = fma(historical, w[3], q)
```

This polynomial is not a call to the platform sine function. At t=0, weight
bits are `33000000,3e800000,3effffff,3e800000`, independently matching the
hardcoded initial-position weights at `0x100ad21a4`–`0x100ad21c0`. Controls
[0,1,1,0] at t=.5 give q bits `3f5a9998`, approximately .853906155, and
13 stamps with identity Count/base16/neutral modifiers in the general loop.

The missing producer matters: `0x100ad2394`–`0x100ad2668` chooses t using
processed positions/sizes, cached `plotSpacingVersion`, `plotSpacing`,
`plotSpacingJitter`, `plotSpacingSpeed`, another lookup and random input.
Its state machine is not yet reconstructed. The above example does not prove
where a real native stroke chooses t=.5.

## Small reproducible checks

From the repository root, this runs only the committed independent mathematics;
it needs neither an IPA nor Android:

```sh
PYTHONPATH=tools python - <<'PY'
from native_pressure_spline import prepare, lookup, f32, mul, add, sub, div, fma, bits

half = lookup(prepare([[0, .5], [1, .5]]), .5)
assert bits(half) == "3f002008"
assert 1 + int(mul(15.0, half)) == 8

# R=0 gives A=1. P=1,Q=0,T=1,I=2 gives L=W=2 and M=1.
L = div(2.0, 1.0)
V = add(div(sub(2.0, L), L), 1.0)
W = mul(L, min(1.0, max(0.0, V)))
filtered = div(fma(0.0, W, 1.0), add(W, 1.0))
assert bits(filtered) == "3eaaaaab"

# Native general Count, base10, absent graphs, J=.5, supplied u=.25.
factor = fma(mul(-f32(.5), f32(.25)), 1.0, 1.0)
assert 1 + int(mul(factor, 9.0)) == 8
print("Three selected static-derived checks passed; no reference code executed.")
PY
```

## Tap caveat and genuine corpus relevance

The ArtFlow probe imported zero, half, one, identity and descending Count graphs,
then painted one point at pressures 0, .25, .5, .75 and 1. It used a 32×32 target,
point (16.5,16.5), stroke ID 412, colour `FF114477`, Count16, size8, flow.2,
opacity1, neutral size/opacity/flow pressure amounts and `BLEND_UNIFORM`.
All 25 runs produced 16 dabs, center alpha248 and big-endian ARGB SHA-256
`2161f93c9f73f19c0a16ced97acc2a1a1a721ac1c15a195ac6da81758867ed3c`.
The imported graph values differed correctly; their rendering contribution was
absent. Direct source inspection confirms the general dab loop also omits them.

**This is not a native tap oracle.** The native helper
`singlePointPlotFromPoints:tapered:attributes:` at `0x100ace3ec` creates one
segment/control and invokes `initWithSinglePoint:…` at `0x100ad0004`.
That initializer allocates one 0x150-byte record and sets plotCount1 at
`0x100ad03f0`–`0x100ad03f4`, without the general Count loop. The stroke
wrapper at `0x100acc660` and ended fallback at `0x100ace3d0` route here.
Additional GPU replication or final tap appearance was not established. The
moving-loop table above must not be presented as native expected tap pixels.

The bounded metadata census covered all **394 brush containers / 534 primary
and secondary archive components**. It found **19 Count graphs**, 16 attached
to base Counts above one, and **seven Count Jitter graphs**. Relevant examples
include Wildgrass, Swordgrass, Juniper, both Miena components, Fossil Cove's
secondary component and Ragged Lane. These are component occurrences, not 26
distinct brush files. Graph presence does not measure visual impact: three Count
graphs have base Count one, and some jitter amounts are zero.

## Remaining implementation and compatibility work

The source establishes the following concrete prerequisites:

| Boundary | Required work before a general native Count hookup |
| --- | --- |
| Android inputs | Define force normalization, availability/estimate status, hover and live scale. Native status routing at `0x10070bb78`–`0x10070bc64` includes phone/estimated distinctions; it does not justify a universal finger→status0 rule. |
| Time and point conditioning | Preserve the distinction between native relative Double times and ArtFlow's Long milliseconds. Recover or explicitly gate FFT, streamline, moving-average and prediction paths selected in `strokeMoved:attributes:slab:` at `0x100acc7e0`. |
| Termination | `applyTerminationToAccumulatedSegments:lastCommittedSegment:` at `0x100ad6908` can call `blastPressureInTheFaceWithMyOwnLerpedValuesOnSegments:from:to:` at `0x100ad6a84` to rewrite point pressures. Its complete OS estimate/commit state machine remains open. |
| Plot placement | Establish the spacing/geometry producer of t and its beginning/end transitions; ArtFlow's carried-distance fraction is not a proven substitute. |
| Tilt, roll and random stream | Count has separate `shapeCountTilt` / `shapeCountTiltAngle` and `shapeCountRoll` / `shapeCountRollParameters` paths. Current size/opacity tilt gains and missing persisted roll cannot silently supply them. Preserve explicitly neutral branches until mapped; establish RNG sequence/consumption separately. |
| Numerical and rendered output | Specify arbitrary-power behavior, then independently compare reconstructed control timelines and strokes. CPU equations do not establish GPU half precision, continuous grain sampling, wet transfer or physical stylus/display latency. |

Current application boundaries are visible in
[`PointerPressure`](../app/src/main/java/com/artflow/studio/core/canvas/PointerPressure.kt),
[`ArtFlowCanvasView.pressureOf`](../app/src/main/java/com/artflow/studio/presentation/ui/components/canvas/ArtFlowCanvasView.kt),
[`StrokePoint`](../app/src/main/java/com/artflow/studio/domain/model/brush/Stroke.kt),
[`BrushParams`](../app/src/main/java/com/artflow/studio/domain/model/brush/BrushParams.kt),
[`ProcreateBrush`](../app/src/main/java/com/artflow/studio/core/export/ProcreateBrush.kt)
and [the rasterizer](../app/src/main/java/com/artflow/studio/core/render/StrokeRasterizer.kt).
ArtFlow linearly interpolates two sample pressures before applying the brush
overall response. Native applies overall per point before scale/filter/four-control
interpolation. Nonlinear mappings do not commute with that reordering. Moreover,
`channelPressure` falls back to overall pressure when a graph is absent, whereas
native Count/Jitter defaults are one.

An eventual implementation must retain existing source-import versions 0/1/2
and historical vector-stroke pixels. A new explicit control/render contract must
persist through both brush components, libraries, `.artbrush`, copy/edit/undo
and document replay. An omitted default can preserve old signed JSON, but a new
numeric `sourceImportVersion` alone cannot protect old document readers that
do not interpret it. The current [document codec](../app/src/main/java/com/artflow/studio/data/local/CanvasDocumentCodec.kt)
does not validate brush source versions as the [library codec](../app/src/main/java/com/artflow/studio/domain/model/brush/BrushLibraryCodec.kt)
does. Current documents are v5; a later replay contract needs an appropriate
future version barrier and validation against hiding it in older headers.
Verify original checksums before migration; retain group<3 and secondary-grain<4
cutoffs and the existing writer's historical Count/alias comparison baselines.

The next bounded native investigation is the plot-parameter/spacing producer.
Constant graphs with neutral modifiers are input-independent only within the
general loop; shipping that narrow subset would still leave meaningful
nonconstant corpus graphs unresolved. No Count consumer, pressure approximation
or new compatibility migration is introduced by this analysis.
