# Native pressure-curve preservation

Six valid native pressure graphs were silently lost when importing or exporting a brush.
The importer now retains those graphs, and the shared native-export allowlist writes them
back. A complete brush can persist all **35 auxiliary curves**, alongside the four primary
overall, size, flow and opacity mappings. This is source-data preservation; the six new
graphs do not acquire live rendering consumers.

## Proven schema

The existing pinned Procreate 5.4.4 / build 56832 reference is a 777,430,382-byte IPA with
SHA-256 `7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e`.
Its 19,119,424-byte ARM64 executable has SHA-256
`76d3ff1249a987b8f03ac3ecc64c54e48f618bac467e9fbb1f238b91fab1487b`.
No reference code is executed or redistributed.

`ValkyrieBrush.serializedKeyForCurve:` at `0x100a3f274` supplies all 39 archive keys.
The decoder at `0x100a3f508` visits channels 0 through 38, calls
`decodeObjectOfClass:forKey:` at `0x100a400a0`, and stores each curve at
`self + 0x18 + 8*channel`. Its loop bound is 39 at `0x100a400f0`.
The encoder at `0x100a416e0` visits slots 3 through 41, derives `channel = slot−3`,
and writes each non-null curve through `encodeObject:forKey:` at `0x100a42338`.
These are matching serialization paths, not deductions from field names alone.

| Channel | Previously omitted archive key | Key-return address |
|---:|---|---|
| 23 | `dynamicsPressureBlurJitterCurve` | `0x100a3f418` |
| 24 | `dynamicsPressureWetnessCurve` | `0x100a3f430` |
| 30 | `dynamicsPressureSaturationJitterCurve` | `0x100a3f448` |
| 31 | `dynamicsPressureLightnessJitterCurve` | `0x100a3f400` |
| 32 | `dynamicsPressureDarknessJitterCurve` | `0x100a3f388` |
| 33 | `dynamicsPressureSecondaryColorJitterCurve` | `0x100a3f40c` |

Four primary mappings already occupy channels 3, 5, 10 and 38. Therefore the maximum
auxiliary count is 39−4=35. The existing malformed-point, finite-value and key-name
checks remain; 36 auxiliary entries are rejected.

## Reproduction and implementation

The [original loss evidence](evidence/native-pressure-curve-loss.json) records a standalone
probe against unchanged published application classes. An independently authored compound
archive contains 39 distinct, bounded three-point graphs in each component. The old
importer retained only 29 auxiliary graphs, and re-export emitted only 33 total curves per
component. Directly supplying all 35 auxiliary mappings to the old writer also emitted
only 29, independently demonstrating its filtering loss. The old library accepted 30
auxiliary curves and rejected 35.

The production correction adds six names to
[`ProcreateBrush.AUXILIARY_CURVE_KEYS`](../app/src/main/java/com/artflow/studio/core/export/ProcreateBrush.kt)
and changes the bounded capacity in
[`BrushLibraryCodec`](../app/src/main/java/com/artflow/studio/domain/model/brush/BrushLibraryCodec.kt)
from 30 to 35. The writer already uses that allowlist. No renderer, interpolation, model
default, document version or source-import version changes are required.

[`NativeBrushCurvePreservationTest`](../app/src/test/java/com/artflow/studio/data/local/NativeBrushCurvePreservationTest.kt)
contains six regressions: all-channel compound import/re-export, direct writer coverage,
library and `.artbrush` persistence, 35/36 limits in either component, signed-byte/checksum
compatibility, and malformed/unknown graph handling. Expected archive keys are literal
native-schema fixtures, independently specified from the production allowlist.

The signed-byte expectations were captured from the unchanged published v5 codec for
primary source-import versions 0, 1 and 2, with secondary version 0. They already contain
35 auxiliary mappings per component. They are current-v5 compatibility fixtures, not
newly invented historical documents. Existing historical signed-document tests remain
unchanged. Previously dropped data cannot be reconstructed from saved brushes; users
need the original native archive to import those graphs again. Older library readers
retain their 30-curve capacity limit and cannot open a new library exceeding it.

## Verification boundary

The required existing [`ipa_native_contracts.py`](../tools/ipa_native_contracts.py) gate now
checks this complete schema: three method identities, 25 switch/loop/dataflow anchors,
39 returning key arms, four selector calls and 39 exact key strings. Its report adds
`brush_curve_schema_contract`. Six relocated, synthetic parser tests include changed
keys, method kinds, loop bounds, slot offsets, null branches, table targets and selectors.
The [complete local gate](evidence/native-pressure-curves-local.json) passes **1,528 JVM
tests in 254 suites**, retaining all 1,522 preceding identities and adding these six, with
zero failures, errors or skips. Android test compilation, all five ktlint reports and
detekt pass against the unchanged baseline. **All 156 Python checks** pass, including
the six new schema cases. The complete hash-pinned native inspector also passes; its
existing Count, spacing and other static contracts remain present.

One new Android identity uses the real import and saved-brush sharing paths, two actual
Room close/reopen cycles, `.artbrush` interchange and native `.brush` reimport. It authors
all 39 keys independently, checks every point and interpolation marker in both components,
and retains the actual Room row bytes. Its test, setup and teardown declarations were
inspected in the compiled class and are public zero-argument `void` methods.

The device case retains six files: the authored `native-curves-source.brush`, first
`native-curves-library.json` row, `native-curves-shared.artbrush`, exported
`native-curves-exported.brush`, second `native-curves-reimported-library.json` row, and
`native-curves-report.json`. Independent artifact checks must decode their actual keys,
points and identities; the report's expected values alone do not establish correctness.
The inventory is **256 device cases**, with all preceding 255 retained. These Android
lifecycle assertions still require a fresh exact-revision Actions run and its original
artifacts; local compilation does not execute them.

The [Count/control-pressure investigation](NATIVE_CONTROL_PRESSURE_2026-10-24.md) remains
separate. Native processed pressure, four-control interpolation, placement, RNG and the
single-point path are not replaced with ArtFlow's superficially similar dab pressure.
Retaining these graphs does not establish native brush output, feel or full parity.
