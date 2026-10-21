# REA brush coverage — keyed settings compared to ArtFlow reader

This report is derived from the original 777,430,382-byte user-supplied iOS ZIP (SHA-256 `7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e`). The earlier successful [reference analysis run 39179569624](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/39179569624) produced `compound-structures.json` from 394 brush containers and 534 settings archives; REA 6.7.0 inspected compound sample archives. The new `.github/scripts/rea_brush_coverage.py` cross-references those facts with the Kotlin reader's **literal field names**.

| Verified static property | Result |
| --- | ---: |
| Logical brushes | 394 |
| Compound brushes | 140 |
| Serialized setting components | 534 |
| Distinct top-level setting keys | 264 |
| Keys visibly referenced by the current reader | 52 |
| Keys not visibly referenced by that reader | 212 |
| Bundled shape path references | 534 component references, 297 distinct paths |
| Bundled grain path references | 504 component references, 234 distinct paths |
| Embedded preview thumbnails | 394 |
| Separate image files inside brush containers | 6 |

**Important:** 52/264 is a syntactic key-reference measure, **not** a rendering-parity percentage. Some of the 52 fields may be approximated or unused at runtime; some of the remaining 212 may be irrelevant to ArtFlow. These counts are a precise map of what is and isn't visibly interpreted by the current reader.

The 297 shape and 234 grain references refer to filenames of potential external bundled resources. Most reference brushes contain only a preview and keyed settings, not the shape/grain image. Resource location and actual loading remain **unknown** without the entire IPA's resource inventory, and the program must not assert that an asset was recovered merely because a path was identified.

## Reproduction

Extract `compound-structures.json` from the pinned prior workflow artifact and run:

```bash
python .github/scripts/rea_brush_coverage.py compound-structures.json app/src/main/java/com/artflow/studio/core/export/ProcreateBrush.kt --out brush-coverage.json
python -m unittest .github/scripts/test_rea_brush_coverage.py
```

The script fails closed on a changed evidence digest, unexpected container/component cardinality, duplicate logical paths and improperly associated `Sub01` components. Its JSON contains setting-key names and aggregate counts, not proprietary brush settings, textures or executable code.

The [full-IPA inventory workflow](../.github/workflows/reference-ipa-static-inventory.yml) has not yet yielded a completed full executable evidence record. **Native source, algorithms, shaders, iOS UI behavior, and 1:1 Android parity remain unverified.**
