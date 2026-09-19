# Editor colour, metadata and operation-result repairs — September 19, 2026

## Published colour repair

Commit `33026271ea4640478c9f0f490f36baf7bc667633` rounds HSV-to-RGB channels
instead of truncating them and rejects non-finite HSV inputs. A local probe using
the actual production colour model found 12,191,271 changed colours across all
16,777,216 RGB round trips before the repair and zero afterwards, with a 96 MiB
JVM heap. Five JUnit regressions include the exhaustive round trip and repeated
picker reflections. The real-app workflow test now locates the unmerged gallery
button and waits for gallery readiness without removing its paint, save, reopen,
activity-recreation or discard assertions.

In the first attempt of Android CI run `35428501484`, the build/static/artifact
job and API 26, API 36 and API 36 16 KB device jobs passed. Both API 35 16 KB jobs
failed in Android runtime/native code, not an assertion that was subsequently
removed. One log also records a system-server crash before application testing.
These failures remain part of the evidence; an explicit retry of the failed jobs
was requested, not treated as a pass. Consult the run's individual attempts.

## Metadata and operation results

DPI and frame-duration commits now emit the invalidation consumed by the editor's
unsaved-change indicator and undo toolbar. Equal or clamp-equivalent metadata
updates do not dirty the document, clear redo or add history. Non-finite onion-skin
opacity rejects the entire animation-settings edit before mutation. FPS changes
still preserve custom frame holds. Displayed DPI comes from the committed value.

Crop and rotate update dimensions from the repository, rather than the requested
bounds or a second width/height swap. Rejected crop, rotation, flip and adjustment
operations no longer report success or publish invented dimensions.

Ten repository and eight ViewModel JUnit regressions cover these behaviours using
the real implementation. A local, instrumented metadata-method probe reproduced
five failures on the old method bodies and passed all five after repair. It uses
verbatim method bodies with a small state harness, not the complete Android app;
full JUnit execution and device checks remain the exact-commit CI gate. The local
Python verifier suite passed 30 tests. No static-analysis baselines, test assertions
or emulator lanes were disabled for these repairs.

## Release boundary

These repairs do not complete the original 50-phase roadmap or establish a bug-free
Google Play release. The gaps in README.md and RELEASE_READINESS.md remain explicit.
Publisher signing, Play Console declarations and testing, physical stylus/GPU/codec
coverage and low-memory performance evidence remain necessary release work.

## Playback session repair

The editor now publishes playing state independently of document timeline snapshots,
so frame changes do not reset the Pause button back to Play. Forward and ping-pong
playback honor the saved inclusive range and per-frame holds. Disabling looping
ends a forward clip, or one forward/return ping-pong pass, after its final hold.
Looping ping-pong does not duplicate endpoint holds. Single-frame ranges do not
start an idle playback loop.

Pause, backgrounding, opening another project and frame/timing changes cancel the
owned playback session. A cancelled old loop cannot clear a newer session's state,
and a delayed frame advance cannot target another open project. Changing animation
FPS or loop settings no longer rewrites the independent onion-skin preference.
Playback selection itself does not add document edits or history.

Six policy tests and twelve real-repository/ViewModel virtual-time tests cover
ranges, frame holds, looping, ping-pong, cancellation, rapid restart and preferences.
Two Android UI tests exercise the actual animation sheet with the real ViewModel.
The six policy tests passed locally against the actual PlaybackStepper,
AnimationTimeline and animation models, with minimal Layer/serialization/JUnit
bridges; this is not represented as a full Gradle run. The final CI executes the
registered tests without those local bridges. UI timing on loaded hardware is not
a measured frame-rate guarantee.

The metadata commit's run `35429630738` executed all 377 JVM tests, including the
ten repository and eight ViewModel metadata/result cases, with zero failures,
errors or skips. Detekt passed, but ktlint found nine test-formatting findings;
the playback batch fixes their formatting without changing assertions. A failed
static-analysis gate is not a successful full build. Read the final combined
commit's completed CI evidence before deciding whether to distribute it.
