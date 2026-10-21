# REA reference brushes: numeric-range evidence and import loss analysis

## Source and evidence standard

Re-examination on 21 October 2026 of `compound-structures.json` from [successful reference workflow 39179569624](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/39179569624), artifact `12155827024`. The workflow ran REA `rea-agents@6.7.0 inspect-keyed-archive` on samples and `plistlib` on every brush settings archive extracted from the user-supplied hash-pinned IPA (`7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e`). Every count below covers **534 individual settings components** inside 394 logical `.brush` containers (140 with a `Sub01` component), not 534 independent library entries.

This evidence describes serialized settings and deterministic contrasts with the ArtFlow parser. It does not prove why the native code stores a value, or its actual behavior during strokes.

## Observed family sizes

| Prefix | Distinct immediate keys in settings root |
| --- | ---: |
| `dynamicsPressure` | 49 |
| `shape` | 23 |
| `plot` | 18 |
| `texture` | 14 |
| `dynamicsTilt` | 12 |
| `grain` | 6 |
| `rendering` | 3 |
| `dynamicsSpeed` | 2 |

The 264 observed distinct settings keys (217 with multiple serialized values; 23 missing on some components) extend beyond the fields currently translated by `ProcreateBrush.kt`. A missing mapping must not be described as recovered engine behavior.

## Exact counts of serialized values outside importer ranges

| Stored reference predicate | Components (of 534) | ArtFlow reader behavior on those scalar values | Evidence / uncertainty |
| --- | ---: | --- | --- |
| `maxSize > 8` | **83** | `maxSize.coerceIn(0.05, 8)` | Stored max reached 16; downstream engine size also caps 500 pixels. Actual Procreate size mapping is not established. |
| `paintSize > 1` | **1** | `paintSize.coerceIn(0.05, 1)` | Highest stored value ≈4.5802; importer discards that distinction. |
| `textureScale > 8` | **5** | `textureScale.coerceIn(0.25, 8)` | Highest stored value 16. |
| `textureScale < 0.25` | **41** | `textureScale.coerceIn(0.25, 8)` | Includes 23 exact zeros; zero may encode a special semantic, not a literal scale. |
| `plotSpacing < 0.01` | **276** | `plotSpacing.coerceIn(0.01, 1)` | Includes 52 exact zeros; most reference brushes store spacing below ArtFlow's minimum. |
| `minSize > 1` | **3** | Field not translated in current `ProcreateBrush` reader | Upper stored value ≈1.342; no reference semantic equivalence asserted. |
| `shapeScatter > 1` | **62** | `shapeScatter` not separately translated | Upper stored value 2; `plotJitter` currently maps to ArtFlow scatter. |
| `dynamicsPressureSize < 0` | **3** | Signed values preserved during import | Minimum approximately -0.75. |
| `dynamicsPressureOpacity < 0` | **17** | Signed values preserved during import | Minimum approximately -0.4408. |
| `dynamicsSpeedSize < 0` | **6** | Signed values preserved during import | Confirmed previously. |
| `dynamicsSpeedOpacity < 0` | **14** | Signed values preserved during import | Confirmed previously. |

These predicate counts can overlap; they must not be summed to get the number of affected brushes. The numerator is **component count**, not user-visible brush count.

## Structural distinctions needing further reconstruction

- `shapeCount` is stored between 0 and 1 (median 0.0625); treating it as a normalized input agrees with the current `1 + round(value * 15)` mapping but does not prove native count rounding.
- `shapeRotation` is stored between -1 and 1, distinct from `shapeAngle` (observed approximately -7.842 to 1.234). The parser currently interprets `shapeAngle` as radians; this is an assumption to validate with runtime.
- `textureMovement` is continuous from 0 to 1; ArtFlow currently reduces it to `>=0.5`, a boolean. Native grain motion modes and exact midpoint semantics are not recovered.
- `dualBlendMode` contains 13 numeric IDs in the archive; ArtFlow renders three combining behaviors and keeps unknown source IDs with explicit approximation disclosure.
- Curve variants include `dynamicsPressureSmudgePullCurve` (61 components), `dynamicsPressureGrainDepthCurve` (69), `dynamicsPressureDilutionCurve` (35), `dynamicsPressureAttackCurve` (30), `dynamicsPressureStampCountCurve` (19), and `dynamicsPressureWetPullCurve` (15); these archive key presences are not pixel or algorithm specifications.

## Resulting implementation priorities

1. Preserve raw and interpreted settings separately in future import/export work; **do not treat clamped parameters as the original source facts**. A field may need a special state rather than a larger allowed scalar range.
2. Build explicit, fixture-based per-field coverage statuses (`observed`, `parsed`, `preserved`, `applied`, `approximated`, `verified-by-runtime`) rather than a single parity score.
3. Compare brush assets and stroke outputs on a controlled reference runtime before claiming equivalence. Prior REA analysis never decrypted native code or executed the iOS app.
4. Preserve original file SHA-256, sample identities, tool versions, and execution logs for every new conclusion. Do not check the third-party IPA, paid assets, or signing material into Git.

### Already landed related code repairs

- `b75eb18`: ProcreateBrushWriter now retains signed pressure values and independent X/Y mirror jitter instead of flattening them.
- `c1e0a05`: synthetic brush export/import regression checks for those exact data-loss cases; **not yet fully run** against a completed Android CI revision.
- `a8ff390` and `06e924d`: correct the malformed Procreate brush-parser scope and remove an unavailable constructor argument. Other unrelated merged-source compiler failures are tracked in [CI_COMPILER_TRIAGE_2026-10-21.md](CI_COMPILER_TRIAGE_2026-10-21.md).

**Status:** this is a statistically complete audit of the **supplied 534 brush settings components**, but only a **partial** reverse-engineering result for the whole IPA. 
