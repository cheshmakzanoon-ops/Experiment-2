# REA source brush schema: complete root field-shape census

**Provenance:** the user-provided iOS IPA with SHA-256 `7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e`, independently verified in prior successful [reference analysis run 39179569624](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/39179569624). The recovered `compound-structures.json` spans **394 logical brushes, including 140 compound brushes, and 534 individual settings dictionaries**.

The new audited `tools/rea_source_field_shape_audit.py` was executed locally against the *full recovered brush settings corpus*. It produced:

| Root setting shape | Distinct keys | Component occurrences |
| --- | ---: | ---: |
| Floating-point real | 139 | 74,226 |
| Boolean | 44 | 23,496 |
| Integer | 21 | 11,214 |
| Text | 7 | 3,738 |
| Magnitudinal/pressure curve dictionary | 33 | 5,740 |
| Nested archived object / saved array | 19 | 10,146 |
| 16-byte binary `color` payload | 1 | 534 |
| **Total** | **264** | **129,094** |

Each component has exactly **211 scalar keys**; the total per component ranges from 241 to 249 because auxiliary pressure curve dictionaries are optional. The longest scalar text is under the importer’s 256-character limit, and the binary color field is exactly 16 bytes in all 534 components. The nested objects comprise 12 roll-parameter records, six saved-size/opacity NSArray fields, and an NSDate creation date.

Current Kotlin implementation in `ProcreateBrush`/`BrushParams` can **capture these 264 observed root key shapes** as typed scalar metadata, validated pressure-curve control points, bounded archived objects/arrays, and Base64-encoded 16-byte data. The writer builds a synthetic keyed archive with observed `SilicaBrush`, `ValkyrieMagnitudinalCurve`, and `ValkyrieBrushRollParameters` class names where applicable. Original source values corresponding to edited brush controls can be replaced with ArtFlow’s approximated values.

**This is source schema/data retention, not 264 recovered algorithms**. Only a subset of fields controls ArtFlow rendering, which has not been compared on real iOS/Android hardware. Unknown native methods, shader behavior, file-level class identity and original app functionality remain unverified.

Reproduce the census:

```bash
python -m unittest discover -s tools -p 'test_rea_source_field_shape_audit.py' -v
python tools/rea_source_field_shape_audit.py compound-structures.json --out brush-shape-census.json
```

The report contains only aggregate counts and root-key names, not any proprietary original brush records, images or executable bytes. The script validates the reference digest, logical primary/secondary grouping, type consistency and scalar safety constraints.
