# REA auxiliary pressure curves — 21 October 2026

**Reference identity:** user-supplied IPA SHA-256 `7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e`. Source: successful [REA archive analysis run 39179569624](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/39179569624), `compound-structures.json`, 394 logical brushes and 534 component settings archives.

A fresh offline schema scan identified **33 distinct pressure-curve dictionary keys**. Three (`dynamicsPressureOverall`, `dynamicsPressureSizeCurve`, `dynamicsPressureOpacityCurve`) were already imported. The other **30 observed fields** now have explicit names in `ProcreateBrush.AUXILIARY_CURVE_KEYS` and are retained as typed `PressureMapping` values in `BrushParams.importedAuxiliaryCurves`.

Across all 534 settings components, these 30 keys occur **4,138 times**. An offline Python check found 4,138 point arrays satisfying the `PressureMapping` input constraints: 2–16 finite control points, positions and outputs within 0–1, strictly increasing input positions after sorting. This validates the *reference corpus shape*, not native brush semantics or an executed Android test.

The full 30-key list includes pressure-driven attack, bleed, blur, brightness, charge, dilution, grade, grain depth/movement/scale/zoom, hue, saturation, secondary colour, scatter, size jitter, opacity jitter, stamp count, transfer modulation and wet pull. Keys preserve the original archive spelling even when it is unusual (e.g. `ShapeRoundessHorizontalJitterCurve`).

The implementation now attempts three separate operations: importing source curve objects; retaining them when saved in the ArtFlow library (with an explicit **30-field bound** and validated names); and writing them as synthetic keyed arrays on brush export. Synthetic round-trip and malformed-name tests were added.

**Limitations:** Not all imported curve fields are applied by ArtFlow's renderer. The native interpolation, pressure filtering, stroke outcome, resource linkage and cross-application Procreate file acceptance have not been verified. Thus *90/264* is only source-key coverage (60 directly mapped scalars/settings + 30 preserved auxiliary curves); 174 reference setting keys remain unhandled. A completed build and device test result for these revisions is still required. The original IPA, its textures and native executable are not redistributed.
