# REA-derived brush compatibility

This is an incremental compatibility implementation, **not a declaration of 1:1 Procreate parity**.
The source inspected before this change was `f312926474a68278f9b03b17fc2999124f0cb0b1`.

## Evidence and provenance

The user-supplied IPA has SHA-256
`7b1dad3ac95f8aeca3c22b432b5d6fd94182a3bf9f84fb93588adb126368b54e`.
The existing reference inspection workflow used `morluto/rea`, packaged as
`rea-agents@6.7.0`, with `inspect-keyed-archive --json`. Its successful run
`39179569624` preserved the matching source snapshot and inspection evidence in artifact
`12155827024`. The compact factual inventory is in [REA_BRUSH_EVIDENCE.json](REA_BRUSH_EVIDENCE.json).
No reference executable was run and no binary decompilation result is claimed here.

There are 394 brush containers: 254 single brushes and 140 compound brushes. The compound
layout is a primary `Brush.archive` plus `Sub01/Brush.archive`, not two independent library
entries. Together they contain 534 settings archives. Negative size/opacity pressure and speed
values occur in the supplied settings. Pressure graphs use independent overall, size and opacity
point lists, including uneven node positions, descending outputs and nonzero endpoints.

## Integrated behavior

- `.brush` and `.brushset` imports keep `Sub01` attached to its primary. Brush-set order applies to
  primaries only. Shape and grain storage includes both components. `.artbrush` image ID remapping
  also traverses the secondary component, so shared custom assets remain connected.
- Imported pressure graphs preserve valid node positions and outputs. The overall graph feeds the
  size and opacity graphs. Negative pressure settings reverse the response; negative speed settings
  reduce the slow end rather than being ignored. Existing positive-speed and default-brush behavior
  is retained. New fields have defaults so older saved libraries remain readable.
- Fixed shape mirroring and seeded per-dab mirror variation are separate parameters. The latter
  interpretation follows the archive's jitter flags but is not certified as the reference renderer's
  exact stochastic algorithm. The live preview and final stroke use the same seeded sequence.
- A secondary brush's opacity and moving grain affect both painting and erasing. Moving grain uses
  the correct canvas origin when the engine paints cropped regions, including live frames.
- The saved library accepts signed dynamics, up to 16 dabs and fine grain scales down to 0.05.
  Brush Studio exposes those ranges, separate random-mirror switches, and a staged reset of imported
  pressure curves. Apply/Cancel semantics are unchanged.
- Input reading is bounded before decoding. ZIP member counts, compressed/expanded sizes and
  settings sizes are limited; even ignored members consume the expansion budget. Traversal and
  case-ambiguous duplicate paths, orphaned/nested secondary components and unreadable secondary
  components are rejected. These checks do not constitute a complete parser security audit.

## Explicit approximation boundaries

ArtFlow linearly interpolates the imported pressure nodes. The native interpolation, response-speed
filtering, input calibration, spacing, grain mapping, wet mixing and pressure-to-size units have not
been recovered sufficiently to establish identical pixels or feel.

The reference has more compound combine IDs than ArtFlow's three alpha-combine operations.
The original numeric ID is retained as `DualBrush.sourceBlendMode`; Brush Studio explicitly labels
its current approximation. Choosing an ArtFlow combine mode clears that source marker. This avoids
silently claiming that an unknown source mode was reproduced. The implementation currently selects
Add for source ID 3, Subtract for 7 and Multiply otherwise; those selections are approximations,
not a complete mapping of the source renderer.

Bundled reference textures are not copied into the application. Importing a brush that points to
an unavailable bundled image therefore does not reproduce that brush's original appearance.
Malformed imported pressure graphs are ignored rather than allowed to inject nonfinite values.
The complete reference application, UI workflows, physical stylus behavior, latency, signing and
release readiness remain separate, unverified gates. See [PROCREATE_PARITY.md](PROCREATE_PARITY.md).

## Verification

The standalone production Kotlin check executed 37 cases with zero failures, including existing
brush/archive/live-stroke regressions and the new parser/pressure/rendering cases. It uses minimal
framework/serialization annotation and assertion shims, not Android or the Gradle serialization
runtime; it is not a substitute for the following full checks.

The candidate workflow applies an identity-checked patch to the inspected source, formats only its
changed files, runs **all** `testDebugUnitTest` cases with cache bypass for that task, then
`ktlintCheck detekt lintDebug lintRelease` and `assembleDebug`. Only a successful run publishes the
actual source commit to `main`, using a non-forced push. Reports, the exact revision and the debug
APK are preserved in `brush-compatibility-verification`.

`ReferenceBrushCorpusTest` verifies the supplied IPA hash and checks all 394 brush containers,
140 compound brushes and 534 settings components. Every imported logical brush must survive the
real library serializer round trip. It checks metadata and finite dynamics, **not image decoding,
reference rendering or pixel parity**. Without `ARTFLOW_REFERENCE_IPA` the optional corpus case is
skipped, explicitly; synthetic tests remain required. A passing build alone is not a device test.

Test sources:
`PressureMappingTest`, `BrushArchiveCompatibilityTest`, `ImportedBrushRenderingTest`,
`ImportedBrushPersistenceTest`, and `ReferenceBrushCorpusTest`.
