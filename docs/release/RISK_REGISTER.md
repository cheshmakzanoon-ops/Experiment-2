# Release risk register

Current source behavior includes the [November 21 hardening record](PRODUCTION_HARDENING_2026-11-21.md)
and the earlier [November 19 hardening](PRODUCTION_HARDENING_2026-11-19.md).
Implemented mitigations still require their exact candidate's execution evidence. This register
does not turn a test's presence or an earlier successful revision into release acceptance.

| Risk | Current treatment | Remaining evidence/work |
| --- | --- | --- |
| Gallery duplication races admitted background autosave/pruning | Capture exact saved/recovery metadata, hold counted raster leases, and retain mutable asset descriptors until copying finishes | Final-revision concurrent save/prune/copy, autosave-only, cancellation and recovery-ordering results. |
| Long MP4 publication allocates a second full encoded video | Stream completed encoder files through an 8 KiB buffer into atomic publication; retain original exports on copy refusal/cancellation | Actual encoder/timelapse sample timing, byte integrity and cleanup, plus physical long-recording/low-storage measurements. |
| Timelapse work crosses artwork or export ownership | Cancel recording before identity changes, bind each collector to one artwork, use unique export snapshots and cancellation-safe cleanup | Delayed and suspended composition, failed-open/retry, overlapping exports, and actual device encoding results. |
| External file shares accumulate while Gallery is closed | Preserve a bounded 32-file FIFO, reject excess work explicitly, and show retry guidance | Queue saturation/resume and real intent/device execution; process-local intake is not durable storage. |
| Saved document exceeds reopen raster budget | Common prospective budget checks growth before mutation and eager loading | Regression plus real device persistence verification. |
| Archive heap spikes / orphan temporary files | Bounded disk staging, streamed repository operations and owned pending-import cleanup | Oversize, cancellation, damaged archive, navigation and cleanup results; bounded encoded bytes alone do not cover decoded allocations. |
| Failed package changes existing brush textures | Prevalidate metadata and all grains; reject differing existing IDs; retain ownership through Room publication | File/cache collision, failed/cancelled publication, and concurrent-adoption regressions. |
| External layered import fails after work starts | Prepare layers/masks away from live state, revalidate the original destination and publish once with one undo boundary | Late failure, cancellation, stale project/frame/revision, profile and existing-layer preservation results; no partial publication is the intended contract. |
| Photo decode exceeds available heap | One bounded encoded snapshot, preallocation sampling and conservative bitmap/copy/array accounting | Actual Android decoder cases and low-memory hardware; this estimate cannot guarantee allocation under all concurrent memory pressure. |
| Native export mixes save revisions or loses rasters | Capture exact manifest bytes once and hold reference-counted raster leases until export finishes | Concurrent save/prune/export, cancellation and failed-publication regressions plus final candidate execution. |
| Canvas edits continue while primary preview is unavailable | Track primary preview generation/availability, cancel active input and require a fresh pointer stream after recovery | GLES and real-View failure/recovery tests; physical GPU/driver coverage remains open. |
| Linked operations silently affect only some members | Require complete eligible membership and transactional commit | Masks, locks, selection conflicts, undo, save/reopen, UI tests. |
| Android15 16 KB emulator ART instability | Keep independent lanes, runtime identity, full logs, and narrow one-recovery contract | First-invocation outcomes remain distinct; physical ARM64 campaign still open. |
| CPU/full-buffer painting limits | Describe actual architecture and device-dependent limits | Real pen latency, frame times, thermal/long-session/low-memory workloads. |
| Format/brush fidelity exceeds integrated scope | Document supported settings, 8-bit import output, approximate CMYK, unapplied ICC, unused auxiliary channels and flattened appearance fallbacks | Receiving-application tests; finish metadata and curve consumers before expanding claims. |
| Prototype classes mistaken for completed features | Require a real editor caller and integration evidence | Separate sparse/GPU, CMYK, typography, 3D milestones. |
| Supply-chain evidence overstates coverage | Pinned actions, secret scan, strict release-runtime lock and resolved graph/artifact/POM evidence tied to build inputs | Ongoing vulnerability review; POM declarations and pre-shrink hashes do not certify final binary contents or absence of vulnerabilities. |
| Optimized test success mistaken for publisher acceptance | Disclose benchmark's generated external test ABI keeps, reflection keeps, hosts and disposable signing | Actual optimized execution plus separate final publisher artifact validation. |
| Same-code package replacement mistaken for a release update | Require explicit campaign classification and a candidate version selected before source freeze | Unused increasing store version and approved signer; retained real old-app upgrade data. |
| Unsigned or unqualified public build | Signing guard, executable evidence tools and explicit production NO-GO | Actual publisher, physical device, upgrade and store acceptance. |
