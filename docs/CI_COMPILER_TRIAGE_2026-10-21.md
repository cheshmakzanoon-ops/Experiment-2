# Android compilation audit after main-branch consolidation

## Provenance and severity

The latest inspected completed *failed* Android CI baseline is run [39363981803](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/39363981803), job `118436420108`, triggered by merge commit `7a2375148335a6bb39b907f1135fb7990caf7df4` on 20 October 2026.

The Kotlin compiler emitted **706 error lines spanning 34 production source files**. These are diagnostics, **not 706 independent defects**: one unresolved declaration or malformed scope can cause many subsequent messages. This was an actual CI compilation attempt, unlike static source inspection. At the time this report was generated, the newer commits did not have a completed successful full build; do not interpret this historical baseline as a fresh run on current main.

A first structural repair of `core/export/ProcreateBrush.kt` landed in [a8ff390](https://github.com/cheshmakzanoon-ops/Experiment-2/commit/a8ff3901e85c637c63091da15f1da87d7e16dba1). The original merger introduced an early return closing the read function before the actual settings parser. More repairs may be necessary in that file and throughout the consolidated codebase.

## Kotlin diagnostic counts by source file

| File (under app/src/main/java) | Compiler diagnostic lines | Representative first error |
| --- | ---: | --- |
| `com/artflow/studio/core/export/ProcreateBrush.kt` | 168 | 36: Unresolved reference 'parameters'. |
| `com/artflow/studio/presentation/ui/components/brush/AdvancedBrushSettingsPanel.kt` | 64 | 180: Unresolved reference 'touchTaperStart'. |
| `com/artflow/studio/presentation/ui/components/editor/StudioChrome.kt` | 48 | 248: None of the following candidates is applicable: |
| `com/artflow/studio/domain/model/brush/RenderingMode.kt` | 43 | 15: Redeclaration: |
| `com/artflow/studio/presentation/ui/screens/canvas/CanvasScreen.kt` | 43 | 534: Overload resolution ambiguity between candidates: |
| `com/artflow/studio/presentation/ui/components/brush/BrushFileButtons.kt` | 36 | 46: Cannot infer type for this parameter. Please specify it explicitly. |
| `com/artflow/studio/presentation/ui/components/editor/LayerRow.kt` | 32 | 81: Argument type mismatch: actual type is 'androidx.compose.ui.Modifier', but 'kotlin.collections.Set<com.artflow.studio.core.canvas.TwoFingerLayerGesture.Kind>' was expected. |
| `com/artflow/studio/presentation/ui/screens/gallery/GalleryScreen.kt` | 32 | 264: Overload resolution ambiguity between candidates: |
| `com/artflow/studio/presentation/ui/components/canvas/GestureControlMapping.kt` | 27 | 8: Conflicting overloads: |
| `com/artflow/studio/core/render/StrokeRasterizer.kt` | 25 | 411: No value passed for parameter 'velocity'. |
| `com/artflow/studio/data/repository/canvas/CanvasRepositoryImpl.kt` | 23 | 600: No parameter with name 'dilution' found. |
| `com/artflow/studio/presentation/ui/components/editor/AdjustmentOverlay.kt` | 23 | 120: Unresolved reference 'stringResource'. |
| `com/artflow/studio/presentation/ui/components/canvas/ArtFlowCanvasView.kt` | 18 | 180: Conflicting declarations: |
| `com/artflow/studio/core/perspective/PerspectiveGuide.kt` | 17 | 23: Conflicting declarations: |
| `com/artflow/studio/domain/model/brush/BrushParams.kt` | 17 | 73: Unresolved reference 'pressureToFlow'. |
| `com/artflow/studio/data/repository/settings/SettingsRepositoryImpl.kt` | 11 | 111: No parameter with name 'canvasPresets' found. |
| `com/artflow/studio/domain/model/brush/BrushLibraryCodec.kt` | 10 | 78: Unresolved reference 'dilution'. |
| `com/artflow/studio/presentation/ui/viewmodel/CanvasViewModel.kt` | 9 | 259: Overload resolution ambiguity between candidates: |
| `com/artflow/studio/presentation/ui/components/editor/EditorChrome.kt` | 6 | 110: Unresolved reference 'horizontalScroll'. |
| `com/artflow/studio/presentation/ui/components/editor/TimelapseReplay.kt` | 6 | 113: Unresolved reference 'REPLAY_SPEEDS'. |
| `com/artflow/studio/presentation/ui/screens/canvas/PaletteImports.kt` | 6 | 40: Unresolved reference 'R'. |
| `com/artflow/studio/data/local/BrushArchives.kt` | 5 | 70: Unresolved reference 'SavedBrush'. |
| `com/artflow/studio/presentation/ui/components/brush/BrushTextureSelector.kt` | 5 | 73: Unresolved reference 'Add'. |
| `com/artflow/studio/presentation/ui/components/editor/ActionsPanel.kt` | 4 | 333: Unresolved reference 'twoFingerTapUndo'. |
| `com/artflow/studio/presentation/ui/screens/canvas/CanvasShortcuts.kt` | 4 | 8: Conflicting overloads: |
| `com/artflow/studio/presentation/ui/screens/canvas/ShortcutEnvironment.kt` | 4 | 20: Conflicting overloads: |
| `com/artflow/studio/presentation/ui/viewmodel/MainViewModel.kt` | 4 | 304: Conflicting overloads: |
| `com/artflow/studio/core/render/StrokeReach.kt` | 3 | 45: Unresolved reference 'wetPull'. |
| `com/artflow/studio/presentation/ui/components/canvas/StylusControlMapping.kt` | 3 | 9: Unresolved reference 'stylusPrimaryButton'. |
| `com/artflow/studio/presentation/ui/components/editor/EditorSheets.kt` | 3 | 1122: Unresolved reference 'lineStyle'. |
| `com/artflow/studio/presentation/ui/screens/gallery/NewArtworkDialog.kt` | 3 | 92: Unresolved reference 'canvasPresets'. |
| `com/artflow/studio/core/symmetry/SymmetryEngine.kt` | 2 | 409: No parameter with name 'lineStyle' found. |
| `com/artflow/studio/domain/repository/canvas/CanvasRepository.kt` | 1 | 251: Unresolved reference 'LayerTransform'. |
| `com/artflow/studio/presentation/ui/components/brush/BrushStudioDialog.kt` | 1 | 213: Unresolved reference 'shareBrushes'. |

## Likely defect families

1. **Conflicting brush data models**: `domain/model/brush/BrushParams.kt` references members that its constructor does not declare (`minSize`, `maxSize`, `minOpacity`, `maxOpacity`, `wetCharge`, `wetDilution`, `bleed`, etc.). `RenderingMode` is declared in multiple files with incompatible constructor/property contracts.
2. **Broken source integration**: the branch merge left an extra immediate return and closed scope in `ProcreateBrush.read`, hiding the real parser. The parser scope was repaired on main; an exact-revision compile is still required.
3. **API contract mismatches**: UI, gesture, selection, transform and storage call sites reference parameters, imports or overloads absent from the current unified models.
4. **Missing or inconsistent Compose resources**: various `stringResource`, `R.string`, icons and modifier usages fail compilation. These may be distinct missing imports and lost declarations.
5. **Cascading errors**: errors in the domain-model and core-rendering types can produce secondary errors in dozens of UI components.

## Recovery sequence and acceptance criteria

- Normalize one coherent `BrushParams` and one coherent `RenderingMode` declaration; eliminate duplicate enums only after tracing all call sites and retaining serialized compatibility.
- Fix producer-side contracts in brush, rendering, gesture and persistence before editing cascaded consumer messages.
- Compile production Kotlin; run the whole JVM suite and lint/detekt; correct compiler and test failures on **the exact same commit**.
- Run all Android device lanes and preserve regression artifacts; keep feature-parity and reference-evidence claims separate from app build readiness.
- Confirm that the user-supplied reference IPA identity is checked before any source inference. Do not bundle a proprietary IPA or unrecovered assets into ArtFlow.

The 706 diagnostics establish that the consolidated main baseline was **not build-ready**. There is no claim of complete application reconstruction or passing release gates.
