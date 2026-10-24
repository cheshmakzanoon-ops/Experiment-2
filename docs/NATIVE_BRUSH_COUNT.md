# Native brush Count contract

## Finding

The pinned Procreate reference uses **1–16 stamps per Count operation**, before the
plot's overall capacity limit. Its normalized `shapeCount` control is multiplied
by **15 in binary32**, then converted to an unsigned integer by truncation. The
plot adds the mandatory first stamp after applying its other Count modifiers and
clamping the extra count to 0–15.

ArtFlow previously rounded the normalized value when importing a brush. That was
a concrete interpretation error: an unmodified source value of `0.5` became Count
9 in ArtFlow, whereas the native base count is 8. The source range is not 1–32;
changing the archive normalization denominator to 31 would be incorrect.

The corrected importer uses, for finite source controls within its existing
normalized import domain:

```text
baseCount = 1 + truncToInt(float32(shapeCount * 15.0f))
```

The source value is first read as a Float. Multiplication must also use Float
precision before truncation; computing a Double product and flooring it can
misclassify an input next to a count boundary.

| Source `shapeCount` | Native base Count / new import | Previous import |
| --- | ---: | ---: |
| 0 | 1 | 1 |
| 0.0625 | 1 | 2 |
| 0.1f | 2 | 3 |
| 0.5 | 8 | 9 |
| 0.9f | 14 | 15 |
| 1 | 16 | 16 |

These are static arithmetic findings with neutral Count modifiers. They do not
claim an end-to-end native render, a measured jitter distribution, or equivalence
of the remaining pressure, tilt and roll controls.

## Reference identity and evidence

The established user-selected reference is Procreate 5.4.4 / build 56832. No
reference code is executed, and no reference assets or instruction listings are
included in the application or this document.

| Input | SHA-256 |
| --- | --- |
| IPA, 777,430,382 bytes | `7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e` |
| Main ARM64 executable, 19,119,424 bytes | `76d3ff1249a987b8f03ac3ecc64c54e48f618bac467e9fbb1f238b91fab1487b` |

The archive-to-property correspondence and the arithmetic are separate checks:

| Evidence | Pinned address or range | Established fact |
| --- | --- | --- |
| `ValkyrieBrush.initWithCoder:` | `0x100a3f9fc`, `0x100a3fa08`, `0x100a3fa0c` | The key `shapeCount` is decoded as Float and stored at brush offset `0x21c`. |
| `ValkyrieBrush.shapeCount` | `0x100a45f80` | The named getter loads the same Float field. |
| `ValkyrieStrokeOptions.shapeCount` | `0x100acf648`–`0x100acf668` | Calls the brush getter, multiplies by 15.0f, and uses `FCVTZU` to truncate to an unsigned integer. |
| `ValkyrieStrokePlot.plotCurve:previousPlot:initialAngle:` | `0x100ad2950`–`0x100ad29f0` | Consumes the integer Count, applies additional modifiers, clamps extra stamps to 0–15, and truncates. |
| Same plot method | `0x100ad29fc`, `0x100ad2a04`, `0x100ad2a80`–`0x100ad2a94` | Initializes the loop index to zero, adds one to the extra count, and appends points until that bound is reached. A separate capacity check can stop the loop earlier. |

The class method `+[ValkyrieBrush shapeCount]` counts available shape images. It
is unrelated to the instance property used here; the verifier selects instance
methods explicitly.

## Saved-brush compatibility and export

New native imports record `sourceImportVersion = 2`. Version 1 already corrected
the source aliases for flow, opacity and wet controls; version 2 additionally
corrects Count truncation. Existing saved versions 0 and 1 keep their stored
counts and existing painting behavior. The default version remains zero, and its
serialization omission is unchanged, so this correction does not rewrite older
checksummed artwork or reinterpret an existing stroke.

The export writer compares an old imported brush against the historical Count
interpretation for its saved version. If the Count is unchanged, the original
raw `shapeCount` survives. If the artist edits Count, the writer emits the edited
native value. Version 0 retains its earlier channel-alias compatibility as well.
Each component of a compound brush is handled using its own saved version.

For example, an old saved brush with local Count 9 and raw `shapeCount = 0.5`
continues to paint with Count 9 in ArtFlow. Its unchanged native export retains
the raw value and therefore has native Count 8. A new version 2 import of that
same source starts at Count 8. Explicitly editing either saved brush replaces
the Count field using the current encoding. Both preservation and this observable
export difference are intentional.

ArtFlow's local Count range of 1–32 remains available. Counts above 16 cannot be
represented by this native Count path; a `.brush` export caps them at 16, while
`.artbrush` preserves them. The writer's `exportedCount(params)` helper resolves
the same retained source fields and edited settings as actual export, then
decodes that result with the current importer. Export disclosure can therefore
show the actual local-to-native change, including older raw-source mismatches,
instead of assuming every difference is simply `min(count, 16)`.

## Reproduction and regression coverage

The existing native contract CI step now also verifies the Count property
correspondence, **27 arithmetic/loop instruction anchors** and **five named
getter calls**. Its `archive-contracts.json` includes a `brush_count_contract`
section with the factor, truncation, bounds, addresses and scope limitations.
The existing IPA hash check still runs before native parsing.

```sh
python -m unittest discover -s tools -p test_ipa_native_contracts.py
python tools/ipa_native_contracts.py "$ARTFLOW_REFERENCE_IPA" --out archive-contracts.json
```

The Count gate's synthetic tests reject a factor of 31, round-to-nearest
conversion, a cap of 31, an incorrect mandatory-stamp increment, a missing loop
bound and an unrelated getter selector. They do not require the reference IPA.

`BrushCountContractTest` adds ten JVM cases covering:

- Fractional source values that distinguish truncation from rounding, and
  adjacent binary32 inputs around 7/15 and 8/15.
- All 16 supported integer counts through actual `.brush` writing and reading.
- The local 17–32 range's native export limit and preservation in `.artbrush`.
- Saved versions 0 and 1, both untouched and edited, without losing version 0
  channel aliases or retained source values.
- Version 2 edit detection and independent primary/secondary component handling.
- Library, `.artbrush` and checksummed document persistence for versions 0, 1 and 2.

Native Count does not establish equivalence of a complete brush. Source pressure
graphs outside the implemented channels, continuous texture sampling, wet-paint
paths and the full compound coverage pipeline still require their own contracts
and comparative output validation.
