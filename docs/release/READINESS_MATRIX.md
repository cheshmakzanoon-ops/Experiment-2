# Release readiness matrix

The current runtime scope is defined in [README](../../README.md). Historical qualification
reports do not close new integration findings. Results must identify the tested revision.

| Area | Current disposition | Acceptance evidence |
| --- | --- | --- |
| Earlier font/codec/backup/CI hardening | Included in integration baseline; historical CI exists | `0ce19d7` runs in [test evidence](TEST_EVIDENCE.md). |
| Save/reopen raster budget and failed recovery discard | Integration milestone; new verification required | Low-budget failure/roundtrip regressions and current Actions reports. |
| Gallery streaming import and dependency notices | Integration milestone; new verification required | Real gallery path, cancellation cleanup, missing-dependency notices. |
| Persistent layer linking and precision transforms | Integration milestone; new verification required | Reachable editor controls, atomic transforms, save/reopen tests. |
| Complete Gradle and emulator matrix | Pending for new integration revision | All build gates and seven full device configurations; disclose any recovery. |
| Runtime capability descriptions | Reconciled to traced production callers | README, current audit, original plan status. |
| Physical device/stylus/performance/upgrade | OPEN | Actual hardware and release-owner records. |
| Publisher-signed AAB and store submission | OPEN | Signing, artifact digest, store/prelaunch/rollout evidence. |
| Advanced professional feature ambitions | OPEN, separate scope | Integrated GPU/sparse canvas, CMYK, typography/interchange/3D acceptance. |

No required matrix configuration or test coverage is waived by this table. Emulator qualification
supports engineering acceptance; public production requires the remaining release sign-off.
