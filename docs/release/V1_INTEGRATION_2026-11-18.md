# V1 integration — November 18, 2026

## Scope and acceptance

The starting point is `main` `d340bc6f777e9444a07df97760aa4ae290c7c7c1` plus the three
hardening commits ending at `0ce19d704c0fe6dc905ca14674e85c7b54d2a7a8` (PR #40).
The follow-up audit identified mismatches between helper implementations and real editor paths.
This milestone closes practical reliability and editor integration gaps; it does not implement
an entirely new renderer, CMYK document model, or all of the original 50-phase vision.

| Workstream | Required behavior | Verification |
| --- | --- | --- |
| Raster budget | Capacity-increasing operations use the same decoded-plane budget as document loading; rejection occurs before history/document changes. Shared duplicates cannot bypass reopen limits. | Deterministic low-budget regressions plus real Android persistence tests. |
| Recovery discard | Keep the unresolved marker until autosave deletion succeeds. | Failed-delete regression with a subsequent successful retry. |
| Streaming import | Both direct `.artflow` and generic ZIP routes stage bounded bytes on disk, invoke the streamed repository import, and clean up temporary files on every outcome. | Bounds/ownership tests and gallery import device regression. |
| Dependencies | Imported grain assets are restored; missing fonts/grains are reported after successful import and before editing. Font binaries stay outside packages. | Real package import and notice assertions. |
| Shared grains | Validate every asset before publication, reject conflicting identities, and roll back new unadopted assets if project publication fails. | Malformed metadata, collisions, concurrent ownership, and real Room failure/cancellation tests. |
| Linked layers | Link/unlink controls are reachable; persistent members participate atomically in supported transforms; unavailable/locked members cannot be silently omitted. | Repository, save/reopen, and editor UI regressions. |
| Scope documentation | Every advertised feature has a running caller; prototypes, format limits, and physical-device gaps remain explicit. | Source review and README/plan reconciliation. |

## Implemented user paths and regression coverage

- Select layers in the Layers panel, then choose **Link selected**. Rows identify linked layers;
  **Unlink layer** is available in the row menu. Move and Transform resolve persistent peers even
  without temporary multi-selection. Hidden/locked ancestors and protected peers reject the whole
  operation. **Tool options → Whole-layer transform** moves linked pixels and masks together.
- Direct `.artflow` picks and unlabelled ZIPs both use the streamed import path. The staging limit
  is 512 MiB of input, with separate decompressed-entry, metadata, and CRC checks. Missing fonts
  and grains produce one notice after successful import. Database/provider failures are reported
  after staging cleanup; cancellation and fatal runtime errors propagate.
- PSD import preflights raster planes, masks, and layer/group slots. Procreate import includes
  masks and existing document capacity in its layered-versus-flattened decision, and checks that
  fallback pixels exist before changing the destination. Composite-only documents retain fallback
  support. A failed pixel/mask/group operation cannot report successful completion.
- `CanvasReopenBudgetTest`, `CanvasRasterBudgetTest`, and `CanvasReopenBudgetDeviceTest` cover
  independent duplicate storage, exact limits, provisional reservations, rejection without history
  changes, and real PNG save/reopen. `AuthoritativeRecoveryTest` covers failed recovery deletion.
- `StagedImportTest`, `ArtflowPackageTest`, `PackageImportNoticeTest`, and
  `GalleryPackageImportDeviceTest` cover bounds, cancellation, actual gallery/Room routing,
  dependency notices, and failed imports. `LayerImportCapacityTest` covers layered preflight and
  fallback preservation.
- `ProjectStorageGrainImportTest` and `ProjectGrainImportRollbackDeviceTest` preserve existing
  textures across malformed metadata, identity conflicts, publication failure, cancellation, and
  concurrent import adoption. Cleanup failures caused by filesystem errors are logged; cleanup is
  necessarily best effort when the filesystem refuses deletion.
- `LayerLinksTest`, `LinkedLayerTransactionTest`, `LinkedLayersUiTest`, `CanvasInputDeviceTest`,
  and `ResamplingDeviceTest` cover reachable controls, shared transform pivots, atomic undo,
  protection/cancellation, independent duplicate links, masks, and persistence.

## Verification protocol

Run Python script suites locally. Run real Gradle unit tests, formatting, detekt, Android Lint,
APK/AAB builds, instrumentation, page-size checks, and minified launch on GitHub Actions.
The local session has JDK 21 but no installed Android SDK/Gradle distribution; the wrapper's
normal Gradle download is unavailable. No local Android build or emulator pass is claimed.

The build job compiles instrumentation before starting the emulator matrix, so a device-test
compilation failure is reported once without starting seven emulator jobs.
The existing seven device configurations and all declared test identities remain required.
New device tests are discovered by the inventory verifier; do not freeze the inventory at the
historical 270 count or remove cases to get a pass. A framework-recovered pass must retain both
attempts and be identified in the evidence. Workflows do not certify physical pen latency or a
publisher-signed release.

Current execution status is maintained in [TEST_EVIDENCE.md](TEST_EVIDENCE.md). A planned or
running job is pending evidence, not a pass. Source publication and public production release are
separate decisions.

## Next work after this milestone

1. Validate sustained drawing, memory pressure, process death, and upgrade preservation on physical
   low-memory phones, Android 15/16, tablets, and pressure/tilt styluses.
2. Complete independently verified Procreate animation/guide/group metadata interchange through
   the real importer/exporter; preserve documented appearance fallbacks until replacement is tested.
3. Preserve editable text through supported affine transforms, with rendered glyph and save/reopen
   tests; maintain explicit rasterization behavior until that feature is integrated.
4. Implement and verify unused imported brush curves and remaining input mappings by live output.
5. Obtain resolved transitive dependency/license evidence, review remaining static warnings, and
   finish publisher signing and store preparation.
6. Treat sparse/GPU rendering, print-profile CMYK, smart objects, cloud sync, and expanded 3D as
   separately accepted milestones.

## Instructions for the next development session

Read README capability boundaries and this record before changing code. Recheck the latest main
SHA and Actions results; preserve remote changes. Trace the user action through the repository,
renderer, persistence, and export path before marking a feature complete. Pair failure-path tests
with visible integration tests. Keep exact-SHA evidence and update the remaining-work list without
relabeling historical passes as current verification or removing unfulfilled requirements.
