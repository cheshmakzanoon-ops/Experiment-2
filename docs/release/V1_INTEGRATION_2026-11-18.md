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
| Recovery discard | Keep the unresolved marker if autosave deletion fails or is cancelled. | Failed-delete/cancellation regression. |
| Streaming import | Both direct `.artflow` and generic ZIP routes stage bounded bytes on disk, invoke the streamed repository import, and clean up temporary files on every outcome. | Bounds/ownership tests and gallery import device regression. |
| Dependencies | Imported grain assets are restored; missing fonts/grains are reported after successful import and before editing. Font binaries stay outside packages. | Real package import and notice assertions. |
| Linked layers | Link/unlink controls are reachable; persistent members participate atomically in supported transforms; unavailable/locked members cannot be silently omitted. | Repository, save/reopen, and editor UI regressions. |
| Scope documentation | Every advertised feature has a running caller; prototypes, format limits, and physical-device gaps remain explicit. | Source review and README/plan reconciliation. |

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
