# Production readiness audit

## Current assessment — November 19, 2026

The current engineering candidate starts from main
`54fa5e0a9d8ef8502d637192c9dc9748c2000dfe` and is tracked in
[PR #41](https://github.com/cheshmakzanoon-ops/Experiment-2/pull/41). The
[hardening record](release/PRODUCTION_HARDENING_2026-11-19.md) preserves the three-agent waves,
implemented fixes, local checks and failed or incomplete device runs. The
[readiness matrix](release/READINESS_MATRIX.md) and [risk register](release/RISK_REGISTER.md)
describe the current contract and remaining evidence.

The new review found allocation before capacity checks, partial layered-import publication,
pending-import lifetime gaps, preview-limit/input handling, mixed-revision native exports and
insufficient optimized/upgrade qualification. The candidate adds preallocation bounds, one
document-bound import publication, owned handoffs, primary-preview availability guards, exact
package snapshots with counted raster retention and executable evidence gates. These are scoped
repairs; they do not establish that every corruption, resource-pressure or device failure has been
eliminated. Consult the wave record for what has actually compiled or run at each checkpoint.

The release inventory now resolves `releaseRuntimeClasspath`, locks selected versions and retains
artifact hashes, build-input fingerprints and Maven POM license evidence. It describes selected
build inputs before shrinking, not a complete final-binary inventory or a vulnerability-free
certification. The optimized instrumentation artifact also has additional test ABI retention and
test hosts; its behavior must be qualified separately from the publisher-signed release.

**Public rollout remains NO-GO** until final exact-revision engineering acceptance and the actual
publisher/physical/store gates in [release sign-off](release/RELEASE_SIGNOFF.md) are completed.

## Historical November 18, 2026 assessment

ArtFlow has a substantial connected offline editor and automated verification. The audited main
was `d340bc6`; the existing hardening candidate was `0ce19d7` in PR #40. Earlier declarations that
all repository-fixable findings were finished were too broad. The current
[V1 integration](release/V1_INTEGRATION_2026-11-18.md) addresses additional caller-path defects.

### Findings and implementation direction at that checkpoint

| Finding | Integration work / remaining boundary |
| --- | --- |
| Shared frame/layer duplication could create a saved document rejected by the same device's eager loader | Use a common prospective raster-plane budget at growth operations and loading; test low-budget save/reopen and rejection atomicity. |
| Recovery protection cleared before failed autosave deletion | Clear the repository marker only after deletion succeeds. |
| New streamed import API was unused by the gallery | Connect direct and generic ZIP imports through bounded temporary staging; retain cleanup ownership through cancellation and navigation. |
| Imported dependency warnings were discarded | Deliver missing-font/grain notices after successful import; retain imported grain restoration and existing text-editor font warning. |
| Persistent linking had models/controllers but no complete editor workflow | Connect Link/Unlink and precision transforms; validate all members and commit once with undo/save/reopen coverage. |
| README attributed active sparse tiles/GPU/CMYK/vector-text/multichannel-3D functionality to unintegrated helpers | Correct the runtime capability inventory and preserve separate future milestones. |

## Actual graphics, color, and storage architecture

The editor uses `core/render/Compositor`, `StrokeRasterizer`, full-sized ARGB `PixelBuffer`s,
dirty regions, a full-buffer tile cache, and copy-on-write snapshots. GLES2 presents the result.
`data/renderer/gpu` factories and `VirtualTileCanvas` are not the active document/render path.
The C++ prototype is excluded from the app build. The packaged native object seen in historical
artifacts is AndroidX graphics-path, not a native ArtFlow brush engine.

The active document profiles are sRGB and Display P3. The visible CMYK preview uses fixed ink
approximations; `IccColorEngine`/`CmykBuffer` are not integrated color-managed document storage.
The model editor paints a selected color/metallic/roughness channel; simultaneous atlas stamping
is a separate helper. Text is editable but some transforms rasterize it. Procreate application-level
animation/guide/group metadata, unused brush curves, and broader external fidelity remain open.

## Historical verification and current limits

The earlier hardening added atomic font staging, backup coverage, portable grain assets, codec
bounds/overflow checks, action SHA pins, and source/artifact provenance. Its cited historical CI
contains 1,706 passing JVM tests and a full successful emulator matrix. An earlier green run
needed internal framework recovery; both outcomes remain documented in
[test evidence](release/TEST_EVIDENCE.md).

Those historical results do not certify later integration code. Run the full current CI and
retain all cases and first failures. `detekt` uses a baseline; Android Lint has warnings. The former
direct-declaration-only inventory is superseded by the resolved evidence described above. Repository
timing tests exclude input/display latency. Physical devices and publisher release gates remain
open in [release sign-off](release/RELEASE_SIGNOFF.md).
