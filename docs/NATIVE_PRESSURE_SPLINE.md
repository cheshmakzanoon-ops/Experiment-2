# Native imported pressure spline

## Result and evidence boundary

The pinned reference implements **natural cubic splines**, sampled into a 1,024-entry
float table, followed by linear interpolation between neighboring table entries.
Its preparation has a source-specific scale: input control coordinates are multiplied
by **1,024**, while sampled output coordinates are divided by **1,023**. New native
brush imports use this recovered behavior. Previously saved graphs and locally authored
pressure responses retain their existing interpolation.

These conclusions come from reading and disassembling the reference executable as
data. No reference application code, shader code, or instruction stream was executed.
The included expected values come from independently written equations with binary32
rounding. They are **derived static expectations, not measurements from a running
Procreate app**. The critical 1,024 versus 1,023 scale and its two-point consequences received an
independent second static review.

Provenance:

- Source IPA SHA-256: `7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e`.
- Extracted `Payload/Procreate.app/Procreate`: 19,119,424 bytes.
- Executable SHA-256: `76d3ff1249a987b8f03ac3ecc64c54e48f618bac467e9fbb1f238b91fab1487b`.
- Disassembler: Capstone 5.0.6, ARM64 little endian.
- Structural reader: repository `tools/ipa_native_contracts.py`, `NativeImage`.
- `tools/native_pressure_spline.py --binary PATH` checks the hash, method addresses,
  constants, instruction anchors and Capstone version. It never loads the reference
  as code. Ordinary fixture generation uses only the Python standard library.

## Relevant routines and anchors

| Routine or observation | Address |
|---|---|
| Brush passes 39 channels and 1,024 samples | `0x100a3df2c`, `0x100a3df30` |
| Instance prepared-curve wrapper | `0x100b7ffc8` |
| Class `getPreparedSplineCurve:count:stride:buffer:` | `0x100b8000c` |
| Count converted to float32, preserved across calls | `0x100b80054`, `0x100b80140`, `0x100b801c8` |
| Both point coordinates multiplied by that count | `0x100b801cc` |
| Final sample output divided by count minus one | `0x100b80228` through `0x100b80248` |
| `sortPoints:` plus comparison/filter blocks | `0x100b802cc` through `0x100b80504` |
| `splineCurve:pointsCount:count:buffer:` | `0x100b80504` |
| `secondDerivative:numberOfPoints:outputBuffer:` | `0x100b806a0` |
| Interleaved `tableForCurves:curveCount:resolution:` | `0x100b80934` |
| Vector result lookup | `0x100b80a7c` |
| Single-channel `lookup:atIndex:` | `0x100b80aec` |
| `flatCurve` factory, identity points | `0x100b7f9e0` |

The interleaved row stride rounds the channel count up to a multiple of eight floats:
39 channels use a stride of 40. Each actual channel uses the same curve preparation.
Channel names and later pressure/scalar composition were not reconstructed in this pass.

## Point preparation and degenerate cases

For the finite, in-range controls already accepted by ArtFlow:

1. Sort by x ascending, then y ascending when x values match.
2. Retain only the first point with x exactly zero and only the first point with x
   exactly one. Because of the secondary sort, this keeps the smallest y at each
   duplicated endpoint. This filter does **not** remove duplicate interior x values.
3. Clamp both x and y into `[0, 1]`. In the native routine this happens after sorting
   and endpoint filtering. No new `(0, 0)` or `(1, 1)` endpoints are inserted.
4. Convert the surviving points to `X_i = N*x_i`, `Y_i = N*y_i`, with `N = 1024`.

A non-null curve with fewer than two surviving points leaves a prefilled identity
table `T[j] = j/(N-1)`. By contrast, the table constructor fills a **null channel with
1.0 everywhere**. The class's `flatCurve` factory supplies points `(0,0),(1,1)` and
therefore evaluates as the identity ramp. These three cases should not be conflated.

Interior duplicate x values cause zero interval widths and a singular/divide-by-zero
path. Sorting also does not provide a useful contract for arbitrary non-finite data.
The fixture model deliberately rejects those inputs, consistent with ArtFlow's current
strictly increasing, finite point validation. There is no evidence here to justify
relaxing that validation.

## Natural spline equations

Let `h_i = X_(i+1) - X_i`, and let `M_i` be the second derivative at control point i.
The endpoints are natural:

```text
M_0 = 0
M_(m-1) = 0
```

Each interior row solves:

```text
(h_(i-1)/6) * M_(i-1)
  + ((X_(i+1)-X_(i-1))/3) * M_i
  + (h_i/6) * M_(i+1)
  = (Y_(i+1)-Y_i)/h_i - (Y_i-Y_(i-1))/h_(i-1)
```

The native code builds a tridiagonal matrix with endpoint rows `[0,1,0]` and zero
endpoint right-hand sides, then performs forward and backward elimination and divides
each remaining right-hand side by its diagonal. Arithmetic is float32. Fused
multiply-add operations occur during elimination and evaluation.

For an integer sample j in interval i:

```text
b = (j-X_i)/h_i
a = 1-b
Y(j) = a*Y_i + b*Y_(i+1)
       + (h_i*h_i/6) * ((a*a*a-a)*M_i + (b*b*b-b)*M_(i+1))
```

This is a natural cubic spline, with no monotonicity-preserving adjustment or
tension parameter in the recovered routine. Overshoot is possible and the table
preparation clamps it afterward. The `FNMSUB` instructions supply the cubic-minus-
linear factors; the fourth operand is the minuend in the Arm A64 instruction
definition, so the negated subtraction yields multiplication minus that operand.
Reference for this instruction convention: Arm DUI 0802B, section 7.35,
<https://documentation-service.arm.com/static/5f3fa899428f7a6b3328fd44>.

## Integer sampling and endpoints

The exact native interval iteration is material:

- Interval i writes integer indices `trunc(X_i) <= j < trunc(X_(i+1))`.
- Since prepared x values are nonnegative, truncation is floor.
- At a noninteger interior knot, the right interval consequently supplies the
  sample at `floor(X_i)`, even though that integer is just left of the knot.
- After all intervals, each integer `j < X_0` is overwritten with `Y_0`.
  For a noninteger first x this includes `floor(X_0)`.
- Every `j >= trunc(X_last)` is overwritten with `Y_last`, up to `N-1`.
- When the last x equals one, `X_last` is 1,024 and that suffix is empty: the final
  sample still evaluates the last segment at j=1,023 rather than at its endpoint.

Finally:

```text
T[j] = clamp(Y(j)/(N-1), 0, 1)
```

This is why a constant half-height curve yields `512/1023`, while the descending
two-point curve ends at `1/1023`. Identity points produce `Y(j)=j`, so the identity
table remains `j/1023`.

## Table lookup

For finite pressure p:

```text
q = clamp(p,0,1) * (N-1)
lo = floor(q)
hi = ceil(q)
t = q-lo
result = (1-t)*T[lo] + t*T[hi]
```

The actual final operation is fused after separately multiplying `t*T[hi]`.
Both single-channel and all-channel methods use this lookup. At integral q, hi
equals lo; the implementation never reads an extra sample beyond index 1,023.

## Reproducible derived fixtures

Regenerate the committed fixtures from the independently written mathematics:

```bash
python tools/native_pressure_spline.py --check app/src/test/resources/brush/native-spline-fixtures.json
python -m unittest discover -s tools -p test_native_pressure_spline.py
```

Neither command requires an IPA or external Python packages. Optional static source
verification requires Capstone 5.0.6 and the separately supplied, hash-pinned executable:

```bash
python tools/native_pressure_spline.py --ipa /path/to/reference.ipa \
  --check app/src/test/resources/brush/native-spline-fixtures.json \
  --report /tmp/native-spline-report.json
```

`--binary /path/to/Procreate` can verify an already extracted executable instead.
`--check` never changes the committed file. Regeneration requires an explicit
`--output PATH`; no output path is assumed. Reports contain only small derived metadata.

The committed `app/src/test/resources/brush/native-spline-fixtures.json` has 13 cases. Each case includes input controls,
the SHA-256 of all 1,024 little-endian float table entries, representative entry values
and raw binary32 bits, and lookup values below/inside/above the pressure range. Cases
cover null, empty and one-point graphs, identity, descending, constant, bent, hump,
nonuniform spacing, clipping, interior endpoints, noninteger knots and sorted duplicate
endpoints. The script models binary32 rounding with exact rational arithmetic,
including fused multiply-add, without depending on host `libm` or target execution.

Representative lookup results from that model:

| Controls | p=.25 | p=.5 | p=1 |
|---|---:|---:|---:|
| `(0,0),(1,1)` | 0.25 | 0.5 | 1.0 |
| `(0,1),(1,0)` | 0.7509775162 | 0.5009775162 | 0.0009775171 |
| `(0,.5),(1,.5)` | 0.5004887581 | 0.5004887581 | 0.5004887581 |
| `(0,0),(.5,.25),(1,1)` | 0.0780946314 | 0.2497563362 | 0.9992668629 |
| `(0,0),(.5,1),(1,0)` | 0.6876214743 | 1.0 | 0.0029325476 |
| `(.2,.3),(.8,.7)` | 0.3334962726 | 0.5001629591 | 0.7006842494 |

At 4,097 uniformly spaced pressure probes, the bent example differs from legacy
piecewise linear mapping by up to approximately 0.04865; the hump example by 0.19459. Those are
substantial shape differences, unlike the smaller scale bias in a two-point curve.

Five independent model checks passed:

- All 1,024 identity entries match `float32(j/1023)`.
- Every constant-half entry matches `float32(512/1023)`.
- Descending entries 0 and 1 equal one, and entry 1,023 equals `float32(1/1023)`.
- The natural spline through `(0,0),(.5,.25),(1,1)` has normalized interior second
  derivative 3 and y(.25)=.078125, so native table entry 256 is `float32(80/1023)`.
- Binary32 ties-to-even and a fused multiplication cancellation case match their
  independently known exact values.

These checks validate the reconstructed model and catch key scale/sign/rounding
mistakes. They do not establish empirical pixel equivalence with a running reference.

## Integration guidance and confidence

**High confidence:** natural spline family, zero endpoint second derivatives,
sorting/filter behavior for finite valid controls, 39-channel/1,024-sample shape,
scale asymmetry, integer interval selection, endpoint extension, clipping, and
linear table lookup. The scale asymmetry has independent review and analytic
two-point fixtures that do not rely on spline/FMA interpretation.

**Bounded confidence:** exact binary32 fixture bits assume round-to-nearest-even.
The application's runtime floating-point control register was not observed. The
chosen finite fixture values avoid denormal edge cases, but live device comparison
is still needed before claiming full numerical or rendered pressure parity. Channel
ordering, time-dependent pressure response and later scalar composition are outside
this reconstruction.

## Implementation and compatibility

`PressureMapping.interpolation` defaults to `LINEAR`. `@EncodeDefault(NEVER)` omits
that default even where canonical document JSON encodes other defaults. New imports
through `ProcreateBrush.curve()` explicitly select `NATIVE_SPLINE_1024`; the parser
still requires finite, in-range, strictly increasing controls. The mode applies to
primary and supported auxiliary graphs and is preserved by ArtFlow persistence.
The Procreate writer continues to emit editable control points, without proprietary
ArtFlow metadata. Reimport selects the native evaluator again.

Existing graphs without a marker remain linear, including historical native imports.
There is no document, library or source scalar-contract version bump. `PressureResponse`,
the local three-control response editor, is unchanged. Explicit reimport is how an
older native brush acquires the new interpolation behavior.

`NativePressureSpline` prepares one immutable 1,024-float table per curve. The cache is
transient body state outside the data-class constructor, equality and serialization;
a copied or decoded curve prepares its own table. Publication uses synchronization
only when the cache is first needed. Painting performs bounded table lookup without
rebuilding spline coefficients or allocating temporary objects.

The native table's null/degenerate cases are covered in the pure helper fixtures.
They do not relax the public model's requirement for two to sixteen valid points or
change application-level missing-curve defaults. Near-coincident valid controls can
overflow intermediate derivatives; final preparation explicitly maps NaN to zero,
positive infinity to one and negative infinity to zero, matching the recovered native
clamp. Every cached entry and subsequent finite-pressure lookup remains in `[0,1]`.
Non-finite pressure inputs continue to fail explicitly.

### API 26-safe fused arithmetic

Android's [`Math.fma(float,float,float)`](https://developer.android.com/reference/java/lang/Math#fma(float,float,float))
is available only from API 33. Production code instead uses `FloatFma`:

1. Multiply binary32 operands in binary64. A float product needs at most 48 significand
   bits, and its exponent range fits in a double, so the product is exact.
2. Add the third float in double precision and use TwoSum to recover the exact residual.
3. Round to float. Double rounding can affect the result only when the intermediate
   double lands exactly on a float midpoint; use the residual's sign to select the
   correct adjacent float in that case.
4. Treat the virtual neighbor beyond maximum finite float as signed `2^128` when
   checking the overflow midpoint. Raw float bits provide adjacent subnormal values
   and preserve signed zero. Non-finite operations follow IEEE arithmetic.

The helper allocates nothing and requires no API 33 call, big-number operation, native
library or platform branch on the drawing path. Rational arithmetic is confined to the
independent Python fixture generator.

`FloatFmaTest` compares 100,000 deterministic random triples and 24 cubed boundary
triples with JDK 17's independent `Math.fma`. Additional cases deliberately distinguish
single rounding from ordinary float arithmetic and naive double-then-float rounding,
including both signs of the overflow midpoint. `NativePressureSplineTest` checks all
13 complete table hashes and lookup bits against the derived fixture file, old versus
new curve behavior, strict validation and near-coincident controls. Device import tests
exercise constant, descending and bent graphs through the real importer and persisted
ArtFlow interchange on the supported emulator APIs.

The test expectations establish consistency with the recovered contract. They remain
static-derived fixtures, not captured native-app output, and do not establish full
rendered pressure or stylus-feel equivalence.
