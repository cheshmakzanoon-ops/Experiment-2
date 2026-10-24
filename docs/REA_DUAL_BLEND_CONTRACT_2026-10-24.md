# Native compound-brush coverage contracts — 24 October 2026

## Result and scope

The pinned reference contains recoverable compound-brush equations, mode translation, coverage
accumulation and surrounding threshold/edge operations. The Metal library includes LLVM/AIR
bitcode that can be parsed as data. The missing work is connecting a separate source-rendering
path to the verified inputs, numeric stages and control guards, then validating complete strokes.
Calling the algorithms opaque or unavailable would be incorrect.

This inspection does **not** establish whole-stroke pixel parity. No reference executable or shader
was executed. The equations and examples below describe recovered operations; examples are derived
real arithmetic, not measurements from Procreate or a simulation of all binary16/binary32 rounding.
Production compound rendering remains the explicitly approximate local renderer.

The earlier [mode precedence contract](REA_DUAL_MODE_PRECEDENCE_2026-10-24.md) established
`dualBlendModeExtended`, otherwise `dualBlendMode`, otherwise zero. Its import/export correction
is independent of the rendering integration described here.

## Reproducible evidence

The verifier [tools/ipa_brush_blend_contracts.py](../tools/ipa_brush_blend_contracts.py) reads two
bounded ZIP members from the existing reference. It checks the complete IPA hash before parsing,
then checks both member sizes/hashes, selected native instructions/selectors, source/GPU mode
translation, four AIR modules, shared helper bodies and selected operand dataflow. It never loads
target code, creates a JIT, extracts assets to disk or emits recovered instruction/IR bodies.

| Source | Identity |
| --- | --- |
| Reference IPA | SHA-256 `7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e` |
| `Payload/Procreate.app/Procreate` | 19,119,424 bytes; SHA-256 `76d3ff1249a987b8f03ac3ecc64c54e48f618bac467e9fbb1f238b91fab1487b` |
| `Payload/Procreate.app/ValkyrieMetal.metallib` | 1,370,648 bytes; SHA-256 `30ed6b20d7592fe45b4b94c4a2e7e820a1871f705e32ab280fcd0bde7709c6de` |
| Parsers | Repository `ipa_native_contracts.NativeImage`; Capstone 5.0.6; llvmlite 0.44.0 / LLVM 15.0.7 |

With the pinned Python dependencies installed, run from the repository root:

```sh
python tools/ipa_brush_blend_contracts.py /path/to/reference.ipa --out /path/to/dual-contract-evidence.json
```

`--output` is an alias for `--out`; without either option the derived JSON goes to stdout.
The verified run checked 48 native instruction anchors, 22 called selectors, 8 source-key anchors,
35 source/GPU translations, 24 AIR metadata/dataflow checks and five matching main/patch helper
bodies. The JSON contains 96 scalar examples, 12 primary-edge/cap examples and four dry accumulation
examples. Those case counts describe derived expectations, not captured reference screenshots.

The four parsed modules have target `air64-apple-ios16.3.0`:

| Entry point | Library wrapper offset | Bitcode bytes | Bitcode SHA-256 |
| --- | ---: | ---: | --- |
| `ValkyrieFragmentStrokeSynthesize` | `0x14559` | 47,436 | `2d40970aac7e87b2009cd07b6f2dbe79f51eef3cdfd177aa23efdc390a28744c` |
| `ValkyrieFragmentStrokeSynthesizePatch` | `0x86a89` | 31,460 | `82220b546293004530b9355c49d3cc51b288844de8212f6606b139646906fe66` |
| `ValkyrieFragmentParticles` | `0x24a69` | 29,564 | `d6ec40a200e3e6ce882d9d8322dc7a19f8322df535cde9934b2daf293249cc5d` |
| `ValkyrieVertexParticles` | `0xd77a9` | 10,036 | `45a16953635357414cc71b52e4c9fa7b67f34995c50a6f64455d6d2d881912fa` |

Each wrapper declares a 20-byte header before its bitcode. Equality of the five scalar helper
bodies in main and patch ignores module-local LLVM attribute numbering only.

## Source mode is translated before entering the shader

`+[ValkyrieLayer fragmentBlendMode:]` starts at `0x100b35048`. Its byte jump table is at
`0x100c7f804`, relative to target base `0x100b3506c`. Every source identifier 1–34 has an
immediate-return arm; zero uses the default return 11.

| Effective archive identifier | GPU blend identifier |
| --- | --- |
| 0–4 | archive identifier + 11 |
| 5–28 | archive identifier + 16 |
| 29–33 | archive identifier − 13 |
| 34 | 47 |

Source 35 takes a different switch arm and is excluded from this bounded reconstruction.

The main renderer calls `dualBlendMode` at `0x100ac1a94`, passes the result to
`fragmentBlendMode:` at `0x100ac1aa0`, and stores its return at `0x100ac1aa4`.
The patch renderer has the same getter/translation sequence at `0x100ac3188` / `0x100ac3194`.
The native shader initializer uses `setConstantValue:type:atIndex:` at `0x100b5cca8` with
secondary-mode constant index 9. Main AIR consumes index 9; patch AIR consumes index 8.

These are verified source and shader anchors. They do not exhaustively reconstruct every intervening
pipeline/cache forwarding path or prove that all rendering configurations take the same path.

## Recovered scalar blend equations

Let `a` and `b` be the first and second scalar inputs to `blendSecondaryBrush`, respectively,
and let `S(x) = min(1,max(0,x))`. For finite inputs in `[0,1]`, the following real-valued summaries
follow the helper and its channel functions. Actual operations include binary16, binary32,
explicit conversion points and fast-math flags.

`epsilon = 2^-14 = 0.00006103515625` is the explicit half constant `0x0400`.

| Archive ID | GPU ID | Raw scalar result | Result at a=.5, b=.5 |
| --- | --- | --- | ---: |
| 0 | 11 | `b + (1-b)*a` | .75 |
| 1 | 12 | `S(a*b)` | .25 |
| 2 | 13 | `a + (1-a)*b` | .75 |
| 3 | 14 | `S(a+b)` | 1 |
| 4 | 15 | `S(max(a,b))` | .5 |
| 6 | 22 | `S(abs(a-b))` | 0 |
| 7 | 23 | `S(a-b)` | 0 |
| 8 | 24 | `S(a+b-1)` | 0 |
| 9 | 25 | `S(a/max(1-b,epsilon))` | 1 |
| 10 | 26 | `1-S((1-a)/max(b,epsilon))` | 0 |
| 11 | 27 | `S(2*a*b)` when a<.5, otherwise `S(1-2*(1-a)*(1-b))` | .5 |
| 19 | 35 | `S(min(a,b))` | .5 |
| 20 | 36 | `1` when vivid-light result is >=.5, otherwise `0` | 1 |
| 26 | 42 | `S(a/max(b,epsilon))` | 1 |
| 28 | 44 | Height expression below | .75 |
| 34 | 47 | `S(min(a,b))` | .5 |

For source 20, the vivid-light subexpression is:

```text
v = 1-S((1-a)/max(2*b,epsilon))   when b < .5
v = S(a/max(2*(1-b),epsilon))    otherwise
```

For source 28, `c = 0.199951171875` is half constant `0x3266`, not exact decimal .2:

```text
t = a^6 + (2*(.5-abs(a-.5)))^6
u = 40 * max(0,c-b) * t
result = S(max(u+.5*a, 2*a+(u-a)*b))
```

Operand order matters: source 7 yields .5 for `a=.75,b=.25` and zero for the reversed inputs.
Source 3 yields 1 for two .5 inputs, whereas the existing local ADD mode yields .75.
Source 7 yields zero for two .5 inputs, whereas local SUBTRACT yields .25.
Matching the raw scalar expression for two modes does not establish equal complete synthesis:
source 0 has special edge and color handling outside this helper.

## What reaches the dual helper

The ordinary texture path in main AIR samples the **R channel of `amountTexture`** at texture
binding 3 and the **R channel of `secondaryAmountTexture`** at binding 4. It passes those as
the original primary and secondary amount inputs to `synthesizeStroke`. Albedo is a separate
sample/argument. Prediction supplies corresponding amount attachment values instead.

For original primary amount `p` and original secondary amount `s`, the scalar coverage sequence is:

1. If the secondary feature set enables alpha threshold, replace `s` with `1` when it is strictly
   greater than `secondaryAlphaThreshold`, otherwise `0`. Without that flag retain `s`; call this `b`.
2. Form primary wet-edge response `E(p) = 1 - abs(sin(4.5*p)/(4.5*p))` for `p>0`, or `p` at zero.
   Mix `p` toward that response using `wetEdgesAmount`, including the observed half conversions.
3. If per-stroke grain is enabled and the adjusted amount is positive, apply `blendGrain` to that
   adjusted amount. The result, or the wet-edge result when grain is skipped, is `a`.
4. For an enabled, non-disabled secondary brush, compute the raw scalar blend of `(a,b)`.
   With additive-only feature bit 10, cap the result to `a`.
5. Except when a secondary brush is enabled with GPU blend 11, increases over **original `p`**
   receive the primary-edge safeguard described below. The comparison uses original `p`, not `a`.
6. The primary threshold flag can then turn the resulting coverage into zero or one with another
   strict comparison. The coverage factor subsequently includes global stroke opacity and mask.

The primary-edge safeguard for candidate `r` is:

```text
delta = S(r-p)
q = p + min(1,63.75*p)*delta   when delta > 0 and the safeguard is enabled
q = r                        otherwise
```

`63.75` is exactly the observed half constant `0x53F8`. At `p=0` this prevents a non-normal
dual mode from extending coverage into otherwise empty primary pixels. Source 0 / GPU 11 has
a distinct bypass, so a blanket primary-footprint mask would also be wrong.

Parameter metadata identifies `alpha` at byte 28, `wetEdgesAmount` at 32, primary threshold at
52 and secondary threshold at 56. The main CPU renderer fills byte 28 from
`ValkyrieStrokeOptions.strokeOpacity` (`0x100ac19f8`, store `0x100ac1c38`) and byte 32 from
the primary brush's `wetEdgesAmount` (`0x100ac1c44`, store `0x100ac1c54`). In the shader,
the mask and this global opacity multiply synthesized coverage after the dual operation.
That factor participates in later color/canvas mixing; it is not by itself a complete final RGBA result.

### Flags and thresholds have identified producers

`-[ValkyrieStrokeOptions featureSet]` is at `0x100acf4a0`. The relevant inputs are:

| Feature bit | Observed producer or guard |
| --- | --- |
| 0 | `renderingRecursiveMixing`, except tool enum 1 starts with bits 0 and 3 set |
| 1 | `renderingMaxTransfer` on the ordinary brush-feature path |
| 2 | `renderingModulatedTransfer` on that path |
| 6 | `textureApplication == 1` together with modulation bit 15 |
| 10 | Tool enum **3** and `preserveAlpha == false`; this is not a serialized brush scalar |
| 21 | Brush `alphaThreshold`; secondary threshold uses the **secondary** feature set |
| 22 | Brush `legacyDualNormalMode` getter |

The bit-10 branch at `0x100acf548`–`0x100acf564` reads tool field offset `0x60` and
preserve-alpha byte `0xb`; their public getters read those same locations. This report leaves
the numeric tool identifier explicit instead of inferring a user-facing tool label from its number.

Native archive decoding directly connects `alphaThreshold` to byte `0x167` and
`alphaThresholdAmount` to float `0x204` in the brush. Their getters read those same fields.
The renderer multiplies the amount by binary64 constant `0x3fefdfdfdfdfdfe0`, the rounded
representation of **254/255**, then converts back to float. The constant is at `0x100c75d68`.
Primary stores to parameter byte 52 at `0x100ac1cec`; secondary first calls `secondaryBrush`,
reads its amount, and stores to byte 56 at `0x100ac1d28`.
The comparison is strictly greater-than; equality does not pass the threshold.

## Amount accumulation is separate from final opacity

`ValkyrieFragmentParticles` has separate `alpha`, `transfer`, `bleed`, `bleedOffset`, `gradation`
and `depth` inputs. It samples shape R, optionally inverts it, and optionally blends grain per stamp.
Calling that shape/grain result `shape`, its initial dry coverage is summarized by:

```text
c = alpha * S(gradation^3 * bleed * shape + bleedOffset)
```

This is followed by an optional mask. Sampled albedo/wetness paths can further affect the coverage
used for accumulation. This report does not silently treat their inputs as the dry branch.

For the accumulated amount `m`, the resulting coverage `c`, and separate transfer `t`, the AIR
has these amount-channel expressions:

| Max transfer | Modulated transfer | Candidate next amount, subsequently `max(m,candidate)` |
| --- | --- | --- |
| Enabled | Enabled | `mix(m,t,c)` |
| Enabled | Disabled | `c*t` |
| Disabled | Enabled | `d + (1-d)*m`, where `d=c*(1-m)*t` |
| Disabled | Disabled | `d + (1-d)*m`, where `d=c*t` |

For `m=.5,c=.5,t=.75`, the four rows yield .625, .5, .59375 and .6875 respectively.
Their color accumulation has additional recursive/nonrecursive branches; the table describes
only amount. It is not an implementation of the complete wet brush.

The vertex instance metadata separates `alpha` at byte 80 and `transfer` at byte 92.
`ValkyrieVertexParticles` passes converted alpha independently. Transfer is transformed using
`transferModulation` at parameter byte 244; in real arithmetic its expression is
`1-transferModulation*(1-instance.transfer)`, with explicit half conversion/subtraction stages
in the actual shader. CPU call `0x100ac72f4` reads `dynamicsPressureOpacity` and stores it at
`0x100ac72f8` into this parameter slot.

The native instance writer `renderInstancesToBuffer:symmetry:variance:` at `0x100ad3f28`
offers a concrete next trace point: for a point record of stride `0x150`, it emits alpha from
the product of float offsets `0x48` and `0x88`, and transfer from float offset `0xd0`.
Those statements follow the loads/stores at `0x100ad4088`–`0x100ad409c` and the output layout.
The complete upstream producers of those point fields were **not** reconstructed in this pass.
Assigning them to current `flowAt` or effective-opacity values would therefore be an unsupported step.

The sampled amount is represented as half in shader code and some accumulation arithmetic uses
float. `texture2d<half>` does **not** establish a binary16 storage format: normalized integer formats
can also be sampled as half. Physical amount-texture formats, filtering and all conversion points
remain to be checked before claiming numeric equivalence.

## Comparison with the current renderer

`StrokeRasterizer.draw` already sets primary opacity to one while building scratch coverage,
then applies global opacity once in `depositCoverage`. Its scratch alpha should be described
as **pre-global coverage encoded in 8 bits**, not final composited stroke alpha.

The concrete integration differences are:

| Stage | Current ArtFlow path | Recovered native path |
| --- | --- | --- |
| Accumulation | Dab coverage/pressure/flow/rendering choices populate ARGB scratch | Separate alpha and transfer feed feature-dependent amount accumulation; albedo is separate |
| Primary grain/edge order | `DualMarks.combine` runs before primary `grainFactor` and `wetEdgeFactor` | Wet-edge response and applicable per-stroke grain run before the dual helper |
| Secondary operand | 8-bit secondary scratch alpha multiplied by secondary opacity and texture coverage | Secondary amount R with optional threshold using the secondary feature set |
| Dual operation | Three local modes; retained source identifier records the approximation | Source-to-GPU translation, scalar helper, optional cap and primary-edge safeguard |
| Global opacity | Applied after the combine | Applied after synthesis as one factor in subsequent color/canvas mixing |
| Color | Primary color when primary coverage is positive, otherwise secondary color | Additional primary/secondary, legacy-normal, recursive, gamma and canvas paths |

The overlap in global-opacity placement is real. The differences in transfer, operand production,
grain/edge order and color handling prevent a helper-only replacement from establishing parity.
The source archive flags above currently survive in imported raw source data; retaining them does
not mean the local renderer implements them.

## Concrete remaining work and compatibility boundary

There is enough evidence to implement and test the scalar equations, feature guards and the dry
amount expressions as parts of a source renderer. There is not yet a complete supported path from
an imported brush and input stroke to correct final pixels. This pass therefore adds an executable
**analysis verifier**, not an unwired production blend helper or a speculative renderer switch.

The next implementation needs to:

1. Trace the point-record alpha/transfer producers, shape sampling and amount texture formats for
   a bounded dry paint path. Use the recovered vertex and particle contracts as the receiving side.
2. Introduce a separately versioned source rendering contract and coverage representation. Preserve
   existing local-mode behavior and old saved documents; `DualBrush.sourceBlendMode` alone records
   source intent and is not a migration marker for a new renderer.
3. Put primary edge/grain work, secondary threshold, scalar blend and the primary-edge safeguard
   in their observed order. Keep source 0's modern/legacy color and secondary-disabled paths explicit.
4. Validate reconstructed intermediate amounts and final strokes against reference outputs,
   including asymmetric operands, zero primary coverage, threshold equality, repeated low-transfer
   dabs, masked edges, and modern/legacy normal behavior.

CI can continuously verify the pinned static evidence and the local implementation regressions.
Static contracts do not replace reference output captures, stylus measurements or a complete
brush-quality comparison. No reference assets, binary bodies or recovered AIR are committed.
