# REA brush corpus: full-field structural census

## Scope and provenance

This is a **non-executing statistical census** of the complete known brush settings corpus, not recovery of Procreate's native drawing engine. Source: user-supplied IPA ZIP SHA-256 `7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e`, previously inspected via successful GitHub Actions [run 39179569624](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/39179569624). Its artifact `12155827024` held `compound-structures.json` and REA `inspect-keyed-archive` sample results from `rea-agents@6.7.0`.

The verified `compound-structures.json` was processed locally with [`tools/rea_brush_corpus_field_audit.py`](../tools/rea_brush_corpus_field_audit.py). This program validates the reference identity and exact container/component counts, then summarizes each immediate settings field. The script was executed on the recovered corpus; it wrote a JSON report containing all 264 fields. Neither native code nor an iOS application process was executed. Data are *settings-file observations*, not behavioral measurements.

## Coverage totals

| Category | Count |
| --- | ---: |
| Logical brush archive containers | 394 |
| Primary-plus-secondary compound containers | 140 |
| Individual component settings dictionaries | 534 |
| Distinct settings keys | 264 |
| Floating-point keys | 139 |
| Integer keys | 21 |
| Boolean keys | 44 |
| String keys | 7 |
| Object/dictionary keys | 53 |
| Dictionary keys containing parseable point arrays | 33 |

The remaining 20 dictionary fields do not have a parseable `points.NS.objects` list under the bounded analyzer's model. Their semantics remain unknown. No individual reference brush names, proprietary images or complete settings archives are checked into this report.

## Curve structures independently recovered

| Field name | Components with curve | Knot count range | Components with descending response segments | Component point lists with unsorted input x |
| --- | ---: | --- | ---: | ---: |
| `dynamicsPressureOverall` | 534 | 2–5 | 0 | 129 |
| `dynamicsPressureSizeCurve` | 534 | 2–6 | 0 | 47 |
| `dynamicsPressureOpacityCurve` | 534 | 2–5 | 0 | 129 |
| `dynamicsPressureDilutionCurve` | 35 | 2–4 | 29 | 28 |
| `dynamicsPressureGrainDepthCurve` | 69 | 2–5 | 11 | 45 |
| `dynamicsPressureSmudgePullCurve` | 61 | 3 | 0 | 61 |
| `dynamicsPressureStampCountCurve` | 19 | 2–5 | 9 | 10 |
| `dynamicsPressureWetPullCurve` | 15 | 2–4 | 13 | 11 |
| `dynamicsPressureShapeScatterCurve` | 11 | 3–4 | 5 | 11 |
| `dynamicsPressureAttackCurve` | 30 | 2–4 | 0 | 20 |

*Unsorted input x* is a property of serialized point order; it is not an invalid curve or proof of a renderer bug. *Descending response* is measured after sorting coordinates by x, using adjacent y values; it is not the same as a negative scalar pressure multiplier. Count statistics refer to settings components, never distinct brush-library entries.

Other independently recovered curve key families include pressure-driven bleed, brightness, charge, grain jitter, grain movement, grain zoom, hue, opacity jitter, plot jitter, stamp-count jitter and transfer modulation. Presence of a key alone does not prove the algorithm that consumes it.

## Scalar distribution examples

| Setting | Observed values |
| --- | --- |
| `shapeCount` | 0–1, median 0.0625; 240 zero components |
| `maxSize` | ~0.0098–16; median ~2.2447 |
| `plotSpacing` | 0–~0.7342; median ~0.00898, 52 zeros |
| `textureScale` | 0–16; median ~1.3953, 23 zeros |
| `dualBlendMode` | 13 distinct integer IDs from 0–28; 356 zeros |
| `grainBlendModeExtended` | 12 distinct integer IDs from 1–34 |
| `renderingRecursiveMixing` | True on 342 of 534 components |

These ranges reveal where the ArtFlow importer currently clamps reference settings, but **do not specify the native renderer's units, special sentinel semantics, blend equations or exact stroke appearance**.

## Reproducibility

Run this against `compound-structures.json` extracted from the pinned workflow evidence:

```bash
python tools/rea_brush_corpus_field_audit.py compound-structures.json --out field-statistics.json
```

Expected compact terminal output:

```json
{"containers": 394, "components": 534, "fields": 264, "numeric": 160, "curves": 33}
```

The script rejects a wrong reference digest, unexpected number of logical containers/components, duplicate brush container paths and malformed top-level settings roots. The output is a derived reproducible *field census*, not a copy of the reference application. The corpus ZIP and full REA evidence are not redistributed.

## Outstanding reverse-engineering boundaries

Native code, encryption status, Mach-O dependencies, shader behavior, app UI runtime observations, file interchange across modern Procreate versions, stylus physics, GPU color math and pixel-by-pixel equivalence are **not established** by this brush corpus. The queued [full-IPA workflow](../.github/workflows/reference-ipa-static-inventory.yml) must actually finish before its results can be cited.
