# Reverse-engineered brush corpus: source-value drift risks

Reference: hash-pinned iOS package `7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e`, prior successful [REA sample extraction](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/39179569624), 394 brush containers and 534 settings components.

A new [reproducible drift-risk audit](../tools/rea_scalar_drift_audit.py) compares six source numeric settings against ArtFlow's existing normalization and quantization. It counts how many **component settings records** can change on a naïve import-then-export, *even without editing the brush*. This is a potential metadata interchange problem, **not measured brush-stroke fidelity**.

| Observed setting | Components at risk (of 534) | Approximate mapping source of drift |
| --- | ---: | --- |
| `shapeCount` | 293 | Quantization to 1–16 stamp counts |
| `plotSpacing` | 276 | Minimum spacing 0.01 |
| `textureMovement` | 206 | Boolean moving-versus-fixed grain |
| `maxSize` | 87 | Size-ratio limits 0.05–8 |
| `textureScale` | 46 | Grain scale limits 0.25–8 |
| `paintSize` | 25 | Paint size ratio limits 0.05–1 |

**477 of the 534 components** are at risk for at least one of these six classes of metadata drift. These individual counts overlap: do not sum them to calculate affected brushes. Per-component risk counts: 57 with zero, 166 with one, 174 with two, 129 with three, and eight with four of these six fields at risk.

The exporter fix in [`9dec2c4`](https://github.com/cheshmakzanoon-ops/Experiment-2/commit/9dec2c473702ec6deb113a7b80321fdd58e13b97) uses the **actual ProcreateBrush importer** to reconstruct a baseline from retained typed source scalars. Original source values for these and other mapped fields are now emitted unmodified when the corresponding ArtFlow control is unchanged. If edited, the ArtFlow value wins. This is *source-scalar interchange preservation*; it does not prove the original app will accept an entire exported brush, or that native rendering calculations have been reproduced.

## Reproduce

```bash
python -m unittest discover -s tools -p 'test_rea_scalar_drift_audit.py' -v
python tools/rea_scalar_drift_audit.py compound-structures.json --out drift-risk.json
```

The report contains counts only, validates the original source digest and primary/secondary grouping, and does not redistribute reference imagery or brush settings values.
