# Production readiness audit — current integration scope

## November 18, 2026 assessment

ArtFlow has a substantial connected offline editor and automated verification. The audited main
was `d340bc6`; the existing hardening candidate was `0ce19d7` in PR #40. Earlier declarations that
all repository-fixable findings were finished were too broad. The current
[V1 integration](release/V1_INTEGRATION_2026-11-18.md) addresses additional caller-path defects.

## Findings and implementation direction

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

## Verification and limits

The earlier hardening adds atomic font staging, backup coverage, portable grain assets, codec
bounds/overflow checks, action SHA pins, and source/artifact provenance. Its latest historical CI
contains 1,706 passing JVM tests and a full successful emulator matrix. An earlier green run
needed internal framework recovery; both outcomes remain documented in
[test evidence](release/TEST_EVIDENCE.md).

Those historical results do not certify the new integration code. Run the full current CI and
retain all cases and first failures. `detekt` uses a baseline; Android Lint has warnings. The
CycloneDX script inventories direct declarations, not a resolved dependency graph. Repository
timing tests exclude input/display latency. Physical devices and publisher release gates remain
open in [release sign-off](release/RELEASE_SIGNOFF.md).
