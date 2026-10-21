# REA brush coverage — keyed settings compared to ArtFlow reader

This report is derived from the original 777,430,382-byte user-supplied iOS ZIP (SHA-256 `7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e`). The earlier successful [reference analysis run 39179569624](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/39179569624) produced `compound-structures.json` from 394 brush containers and 534 settings archives; REA 6.7.0 inspected compound sample archives. The new `.github/scripts/rea_brush_coverage.py` cross-references those facts with the Kotlin reader's **literal field names**.

| Verified static property | Result |
| --- | ---: |
| Logical brushes | 394 |
| Compound brushes | 140 |
| Serialized setting components | 534 |
| Distinct top-level setting keys | 264 |
| Fields directly mapped into ArtFlow parameters | 60 |
| Additional typed pressure-curve fields retained for interchange | 30 |
| All reader-referenced fields (direct + retained) | 90 |
| Settings keys without an importer mapping | 174 |
| Bundled shape path references | 534 component references, 297 distinct paths |
| Bundled grain path references | 504 component references, 234 distinct paths |
| Embedded preview thumbnails | 394 |
| Separate image files inside brush containers | 6 |

**Important:** 90/264 is the subset with a direct parameter mapping or explicitly typed pressure-curve retention, **not** a rendering-parity percentage. The later [complete schema census](REA_BRUSH_FULL_SCHEMA_CENSUS_2026-10-21.md) separately records the importer’s typed pass-through retention of the remaining scalar, structured-object and 16-byte data shapes. Only 60 keys are mapped directly to ArtFlow parameters, and 30 extra REA-observed pressure curves are decoded and retained for brush save/export without claiming the native rendering equations. There are 174 keys with no such mapping. Some directly mapped settings are approximations; some unmapped fields may be irrelevant.

The counts were recomputed against the **current** ProcreateBrush.kt reader on main. Six signed scalar settings (`dynamicsPressureHue/Saturation/Brightness` and `dynamicsTiltHue/Saturation/Brightness`) map to ArtFlow's HSV approximation. The supplementary 30 pressure curves are supported as bounded `PressureMapping` dictionaries for import, library persistence and synthetic `.brush` export, not as complete source rendering behavior. The 297 shape and 234 grain references refer to filenames of potential external bundled resources. Most reference brushes contain only a preview and keyed settings, not the shape/grain image. Resource location and actual loading remain **unknown** without the entire IPA's resource inventory, and the program must not assert that an asset was recovered merely because a path was identified.

## Reproduction

Extract `compound-structures.json` from the pinned prior workflow artifact and run:

```bash
python .github/scripts/rea_brush_coverage.py compound-structures.json app/src/main/java/com/artflow/studio/core/export/ProcreateBrush.kt --out brush-coverage.json
python -m unittest .github/scripts/test_rea_brush_coverage.py
```

The script fails closed on a changed evidence digest, unexpected container/component cardinality, duplicate logical paths and improperly associated `Sub01` components. Its JSON contains setting-key names and aggregate counts, not proprietary brush settings, textures or executable code.

The [full-IPA inventory workflow](../.github/workflows/reference-ipa-static-inventory.yml) has not yet yielded a completed full executable evidence record. **Native source, algorithms, shaders, iOS UI behavior, and 1:1 Android parity remain unverified.**
