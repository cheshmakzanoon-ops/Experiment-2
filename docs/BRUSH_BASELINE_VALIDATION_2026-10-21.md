# Brush regression baseline validation

The first complete Android Gradle JVM test run reported three failures in
`BrushDefaultsGoldenTest`. Before changing any expected hash, the brush and pixel
engine from the starting Git revision
`adf0bc7208974f1579095ea5a890b7c1375f02f9` was independently compiled and run.

Every source in that probe came from `git show HEAD:<path>`, including
`BrushParams`, pressure mappings, all three built-in preset files, `Stroke`,
`DualBrush`, `StrokeRasterizer`, `StrokeReach`, `ReplayableRandom`, `BrushTexture`,
`CustomGrains`, `Color`, `PixelBuffer`, `BlendModes`, `Stamping`, `ImageFilters`,
selection helpers, and the original golden test. No modified working-tree
renderer or model was used. Kotlin 2.0.21, JDK 17.0.20.1, and JUnit 4.13.2 were
used, independently of the ongoing shared Gradle build.

Two compile-only adjustments were necessary:

- Remove the unused `CAPSULE_RENDERINGS` constant, which referenced nonexistent
  enum cases in the starting code. No code reads this constant.
- Omit the unused `Color.toComposeColor` UI adapter to compile this pure renderer
  subset without Compose. The RGB/HSV conversion and all rendering math remained
  verbatim. The `BlendMode` enum was extracted verbatim from `Layer.kt` to avoid
  unrelated layer/editor dependencies.

The independent starting renderer and the modified renderer produced identical
results for all three tests:

| Test | Previous stored expectation | Starting revision and modified renderer |
|---|---:|---:|
| Random brushes | -7086734929218753221 | 6912832811445097011 |
| Eraser stroke | 6766843957270658821 | 2947699143526432517 |
| Built-in presets | 4869832796849639109 | -2657600560538100551 |

The expectations now pin these independently established starting-revision
pixels. This corrects stale test data; it does not accept a rendering change from
the new pressure-flow, wet-control, or source-import work. The checks remain exact
hash comparisons with the same strokes, seeds, busy background, and preset list.

The old `BrushSpeedTest` assertions also contradicted the starting model and its
newer `PressureMappingTest`: negative speed influence reduces slow marks while
positive influence reduces fast marks. The older test expected negative influence
to enlarge fast marks beyond the nominal brush size. Its expected values now
match the documented signed reversal, and the rendered test still requires a fast
stroke to reach pixels that the slow stroke does not. No speed equation changed.
