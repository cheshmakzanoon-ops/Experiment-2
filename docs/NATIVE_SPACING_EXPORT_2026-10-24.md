# Native brush spacing version at export

This correction concerns `.brush` and `.brushset` metadata. It prevents a compound-brush export or an unrelated combine edit from changing the native interpretation of unchanged spacing. It also follows the native spacing setter when spacing itself is edited. ArtFlow's stroke placement, pressure processing and random sequence are unchanged; no reference application was executed for this analysis.

## Pinned evidence

The analysis uses the same required reference as the existing archive, Count, spline and blend checks:

| Input | Bytes | SHA-256 |
| --- | ---: | --- |
| Procreate 5.4.4 IPA | 777,430,382 | `7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e` |
| `Payload/Procreate.app/Procreate` | 19,119,424 | `76d3ff1249a987b8f03ac3ecc64c54e48f618bac467e9fbb1f238b91fab1487b` |

The required [native contract inspector](../tools/ipa_native_contracts.py) verifies the whole IPA before inspecting its ARM64 executable. Its `brush_spacing_version_contract` report links six named methods, 33 instruction anchors, six selector calls, four archive-key references, and the binary32 size-factor constant. It emits addresses and derived findings, without binary assets or instruction bodies. These checks are static evidence for this pinned build, not an assertion that every native release has identical behavior.

| Native method | Address | Verified behavior |
| --- | --- | --- |
| `ValkyrieBrush.initWithCoder:` | `0x100a3f508` | At `0x100a3fc5c`–`0x100a3fc98`, an explicit `plotSpacingVersion` is decoded as an integer. If absent, the result is the inverse of **presence** of `dualBlendMode`. |
| `ValkyrieBrush.setPlotSpacing:` | `0x100a3e2d8` | Stores integer version **1**, then the supplied spacing Float. |
| `ValkyrieBrush.plotSpacingVersion` | `0x100a46054` | Reads the same brush field at offset `0x498`. |
| `ValkyrieBrush.encodeWithCoder:` | `0x100a416e0` | At `0x100a41e50`–`0x100a41e64`, reads that version and writes it with `encodeInteger:forKey:` under `plotSpacingVersion`. |
| `ValkyrieStrokePlot.cacheOptions:size:` | `0x100ad1a88` | Caches whether the version is zero at plot offset `0x158`. |
| `ValkyrieStrokePlot.plotCurve:previousPlot:initialAngle:` | `0x100ad20b8` | Uses that bit in both previous-size and candidate-size paths to select a size factor of 1 for version zero, or binary32 `sqrt(2)` (`0x3fb504f3`) otherwise. |

The archive decoder therefore has this rule:

```text
if plotSpacingVersion is present:
    version = decodeInteger(plotSpacingVersion)
else if dualBlendMode is present:
    version = 0
else:
    version = 1
```

The fallback does not read the value of the legacy combine key. A present `dualBlendMode=0` selects version zero. An archive containing only `dualBlendModeExtended` selects version one. Explicit nonzero versions take the nonzero factor branch; an unchanged imported integer is preserved rather than normalized to one.

## Reproduced export defects

A standalone probe ran the published `e2aa900b91298fe08eca8529d0345220faa9e2eb` writer, opened its actual ZIP entries and decoded their keyed archives. The effective versions below follow the native rule above, independently of ArtFlow's importer, which does not consume this placement-version field.

| Input and operation | Previous exported fields | Native interpretation |
| --- | --- | --- |
| New single brush, spacing 0.25 | No version or legacy combine key | Version 1 |
| Same new brush with a second component | No version; primary gains `dualBlendMode=1` | Primary changes to version 0; secondary remains version 1 |
| Imported single brush with no version; add a second component | No version; primary gains a legacy combine key | Previously version 1 becomes version 0 |
| Imported explicit version 0; edit spacing from 0.25 to 0.5 | New spacing, original version 0 | Does not follow native `setPlotSpacing:`, which sets version 1 |
| Imported extended-only compound; change combine mode | No version; exporter replaces extended key with a legacy key | Previously version 1 becomes version 0 |

The factor difference is consequential before any GPU work. For independently supplied native control size `(10, 10)` and raw spacing `0.25`, the recovered finite spacing expression gives `5.8671875` at version zero and `7.986795902252197` at version one. These are analytically derived binary32 values, **not** measured dab distances or rendered pixels. Native input preprocessing, interpolation and the subsequent placement search determine the actual controls and emitted positions.

## Corrected export contract

[ProcreateBrushWriter](../app/src/main/java/com/artflow/studio/core/export/ProcreateBrushWriter.kt) now writes version one for a newly authored local component. Both components of a local compound brush receive the same explicit rule, independently of the combine key emitted for the primary.

For an imported component whose spacing is unchanged, the writer retains its original raw spacing and explicit version. If the version was absent, it materializes the original implicit version from the **original source map**, before combine-key additions or removals can affect interpretation. This also preserves an originally missing spacing field and an original value outside ArtFlow's editable clamp. The existing source-versus-current comparison, including saved import versions 0, 1 and 2, remains authoritative for deciding whether the artist changed spacing.

When spacing differs from that saved interpretation, the exported spacing and version one become authoritative. Editing one compound component does not change the other component's spacing history. The source maps remain immutable, and saved libraries, `.artbrush` files, historical vector strokes and document checksums receive no new field or migration. Other existing dual-mode interchange approximations are outside this correction.

## Required verification and remaining boundary

The [combined local gate](evidence/preview-model-local.json) passed all nine new JVM cases below as part of 1,511 tests, with no failures, errors or skips. All 139 Python cases passed, including six new native-inspector mutations; the required whole-IPA inspection also passed the spacing-version section. Android compilation, formatting and detekt passed with the existing baseline unchanged. This is local/static evidence; exact published-source emulator results remain separate.

[BrushSpacingExportTest](../app/src/test/java/com/artflow/studio/data/local/BrushSpacingExportTest.kt) adds nine test identities. They inspect actual emitted archives for local single/compound brushes, explicit and omitted versions, a zero-valued legacy key, extended-only imports, adding/removing a secondary, combine edits, component-specific spacing edits, clamped and absent source values, all stored import versions, and library/`.artbrush`/`.brushset` preservation. They do not use the app's unchanged placement implementation as a native oracle.

[The native inspector tests](../tools/test_ipa_native_contracts.py) add six cases using self-authored ARM64 metadata fixtures. Corrupt presence branches, wrong setter versions or fields, an extended key substituted for the legacy key, a floating-point encoder, and either changed factor branch are rejected. The existing required native-inspection invocation covers the new report automatically:

```sh
python3 -m unittest discover -s tools -p 'test_ipa_native_contracts.py'
python3 tools/ipa_native_contracts.py "$ARTFLOW_REFERENCE_IPA" \
  --out app/build/reports/reference/archive-contracts.json
```

This export change does not establish pressure-sensitive spacing or Count parity. The [control-pressure analysis](NATIVE_CONTROL_PRESSURE_2026-10-24.md), [Count contract](NATIVE_BRUSH_COUNT.md) and [spline contract](NATIVE_PRESSURE_SPLINE.md) distinguish recovered scalar rules from the still-unimplemented native pressure, size, streamline/history, spacing-search, input-routing and random-table bridges. No approximate consumer was added to production rendering.
