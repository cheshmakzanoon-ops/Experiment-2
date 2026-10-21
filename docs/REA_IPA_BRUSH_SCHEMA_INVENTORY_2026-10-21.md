# REA brush-schema inventory — 21 October 2026

## Evidence and scope

This is a **static, package-data inventory**, not full decompilation or runtime parity. It extends [REA_BRUSH_COMPATIBILITY.md](REA_BRUSH_COMPATIBILITY.md) without replacing that report.

- User-supplied reference: [Google Drive archive](https://drive.google.com/file/d/1xPr6e53L76Cq8exGFWOSvq96wt-NlLVC/view)
- Verified original ZIP SHA-256: `7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e`.
- Reproducible GitHub Actions analysis: successful run [39179569624](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/39179569624), artifact ID `12155827024`, `reference-compound-brush-evidence`. It includes `compound-structures.json` (8,803,300 bytes), REA `inspect-keyed-archive` evidence for three compound samples, and the exact source-commit file.
- The expanded dataset was examined **without running an iOS executable**, using Python `json` plus the prior REA/keyed-archive extraction. No source code, proprietary textures, signature material, or raw app binary is redistributed here.

## Observed schema

| Inventory property | Verified value |
| --- | ---: |
| Logical `.brush` ZIP containers | 394 |
| Single brushes | 254 |
| Compound brushes (`Brush.archive` plus `Sub01/Brush.archive`) | 140 |
| Total settings archives/components | 534 |
| Distinct immediate settings fields across components | **264** |
| Fields with more than one observed serialized value | **217** |
| Fields present in fewer than all 534 components | **23** |
| Fields per component, minimum–maximum | **241–249** |
| Brush containers under `Classic Library` | 214 |
| Brush containers under `Procreate Library` | 180 |

These counts describe **keys and values present in this exact bundled corpus**, not a comprehensive list of every property that the reference application's code could interpret. In particular, 47 fields with a single observed value may still have active semantics in other files.

### Selected non-universal settings

The following settings are present only in a subset of the 534 components; these are **archive key names**, not recovered implementation contracts:

| Archive key | Components carrying it |
| --- | ---: |
| `dynamicsPressureSmudgePullCurve` | 61 |
| `dynamicsPressureGrainDepthCurve` | 69 |
| `dynamicsPressureCharge` | 39 |
| `dynamicsPressureDilutionCurve` | 35 |
| `dynamicsPressureAttackCurve` | 30 |
| `dynamicsPressureStampCountCurve` | 19 |
| `dynamicsPressureWetPullCurve` | 15 |
| `dynamicsPressurePlotJitterLongitudinalCurve` | 14 |
| `dynamicsPressureSizeJitterCurve` | 12 |
| `dynamicsPressureShapeScatterCurve` | 11 |

Additional fields with `Extended` suffixes occur on every component: `burntEdgesBlendModeExtended`, `dualBlendModeExtended`, and `grainBlendModeExtended`. Their numerical domains require explicit mapping and cannot be assumed equivalent to the ArtFlow enums.

## Recovered structural constraints

1. The `Sub01/Brush.archive` is a **secondary component of one logical brush**, not another brush-library entry. Any import, persistence, editing, or export path needs to preserve its association.
2. Serialized pressure curves include independent response graphs and may have nonlinear or descending outputs. Validity is a property of the stored graph, **not** evidence that ArtFlow's interpolation matches Procreate.
3. Signed dynamics and unknown blend/combine IDs must survive round trips even when the rendering engine only approximates them. Never silently discard or normalize to a claimed-equivalent mode.
4. The corpus has 264 setting keys, but merely accepting or storing a key does not prove an engine applies it, and applying it does not prove pixel-equivalent behavior.
5. Bundled images, shape/grain assets, native brush code, input calibration, shader implementations, artwork formats, UI workflows, entitlements, application signatures, runtime behavior and pixel comparisons **were not established by this dataset**.

## Next verification boundaries

- Establish a complete archive member inventory and primary executable architecture/encryption status; this requires inspection of the **full 777,430,382-byte original**. A generic connected-Drive fetch is limited to 268,435,456 bytes, so local/CI extraction is required.
- Map all 264 observed settings fields to the importer, model, renderer and tests with separate statuses for parsed, preserved, applied, approximated, and unknown.
- Obtain rendering/brush-stroke benchmarks on a lawfully accessible reference runtime and physical stylus before any claim of brush fidelity.
- Compare layered-document, colour, animation, gestures, gallery and export behavior against reproducible reference test cases; identify and preserve unknowns.

The overall application is **not fully reverse-engineered** on this evidence. Treat any unsupported 1:1 parity or recovered-native-source claim as unverified.
