# Model input allocation and geometry validation

Wave 14 independently reviewed the allocations that precede model texture preparation. This
record covers OBJ, glTF/GLB and the shared model ZIP reader. Wave 15 extended the same operation
budget through USD/USDA parsing; its additional scope and evidence are recorded below.

## Confirmed repairs

Three small glTF regressions all failed against the previous implementation: an accessor could
read neighboring bytes beyond its declared buffer view; a sparse accessor silently lost its
declared edits; and a four-index triangle silently dropped its final index. The repaired reader
validates view-relative ranges and declared buffer lengths, refuses unsupported sparse accessors
with an export-dense-model message, and requires complete triangle groups. It also validates
attribute shapes/counts, reads unsigned index values without a lossy float intermediate, checks
GLB version/length/chunk structure, and refuses a declared missing texture instead of presenting
an untextured replacement. These boundaries follow the
[Khronos glTF 2.0 accessor and GLB contracts](https://registry.khronos.org/glTF/specs/2.0/glTF-2.0.html).

`ModelReadBudget` admits allocations before expansion. Its default ceiling uses the existing
heap/headroom-aware import budget. A single model package shares its budget through ZIP
expansion, decoded glTF text/tree, embedded data and generated geometry. The accounting includes
retained entries, stream capacity growth/final copies, JSON node overhead, source/accessor arrays,
StringBuilder growth/final text and subsequent OBJ geometry workspace. Repeated mesh instances
spend the same aggregate budget. JSON nesting is limited before parsing; root-node discovery uses
a child set instead of repeatedly scanning every node's child list.

The OBJ preflight counts records and fan-expanded triangles without retaining token lists,
boxed vertices or corner arrays. It accounts for unused source vertices and the largest token
workspace. A single oversized polygon is refused before parsing its corner indices or expanding
its fan. The 300,000-triangle ceiling remains an absolute upper bound; available memory can require
an earlier refusal. Prepared model validation passes its existing explicit budget into this path.
Native saved-model loading uses the same checked parser and reports a refusal instead of silently
altering geometry. Double-precision centering/radius computation and computing generated normals
after normalization prevent finite extreme coordinates from turning into nonfinite mesh values.

These are conservative operation estimates, not a process-wide OOM guarantee. Other allocations
can occur between runtime measurements. The existing photo/model preparation admission prevents
two cooperating imports from spending the same sampled headroom concurrently; other import
families retain their own scope.

## Execution evidence

- Three original refusal regressions reproduced false acceptance against frozen `af7ff79` classes.
- Standalone compilation of changed production sources, shared atlas/preparation dependencies and
  all current core 3D test sources passed with Kotlin 2.0.21 and the checked-in test resources.
- **95 JVM tests passed: 81 existing cases and 14 new cases.** The new cases exercise the three
  reproduced defects, GLB headers, missing textures, zero-filled accessor admission, repeated mesh
  instances, aggregate triangle limits, oversized polygons, unused vertex records, extreme finite
  coordinates, JSON depth/node costs and compressed ZIP copy peaks.
- Ktlint, changed-file detekt with the existing configuration/baseline, and `git diff --check`
  passed. No Gradle execution, Android runtime execution, baseline change or test skip was used
  for this assignment. Root's integrated Gradle and Android runs are separate acceptance gates.

Detailed transient logs: `tooling/w14-model/baseline-regressions.log`, `compile.log`, `tests.log`,
`ktlint.log` and `detekt-final.log` in the shared review workspace.

## USD follow-through (wave 15)

Raw USD and USD files inside a model package now share the package's `ModelReadBudget`. The
reader charges crate tables, decompression buffers, path/spec copies, USDA token/string/map and
numeric-array growth, expanded face corners and generated OBJ text before allocating them.
Declared section and decompressed lengths are checked; bounded integer decoding retains the
actual written bytes. Nesting and finite integral topology checks reject malformed scenes, and
missing indexed UV data produces an explicit refusal instead of substituted geometry.

Five small old-reader failures were reproduced: a short token block manufactured zero-filled
content, raw USD bypassed the operation budget, a trailing face corner was discarded, fractional
topology was truncated, and missing indexed UVs fell back to replacement values. Seven regression
tests cover these boundaries. The existing excessive-token-length fixture also needed a layout
repair: its token header overlapped its table-of-contents entry. The repaired 130-byte fixture
retains the same oversized decoded claim, tiny compressed block and exact refusal assertion.

The first combined run exposed that fixture defect and remains recorded as a failure. After the
fixture repair, the integrator reran all current core 3D cases: **102 tests passed**, including the
independent Pixar-produced fixtures. Changed-file ktlint and detekt passed with the existing
configuration and baseline. These standalone results do not replace integrated Android builds
or execution. Logs are retained under `tooling/w15-usd` in the shared review workspace.
