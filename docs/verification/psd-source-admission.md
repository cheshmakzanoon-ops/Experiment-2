# PSD source memory admission

## Reproduced shipped failure

The final data-safety review began at local revision
`f94edbbc4181883e256038700223608dcb55af2d`, corresponding to published revision
`628466ec0727c56317c8e4094a493f68dcf13e96`. Both actual PSD entry points buffered
their entire source through `readAtMost(256 MiB)` before `PsdCodec` inspected its
dimensions or admitted decoded layers:

- `GalleryImports.importPsd`, used by the gallery picker and incoming-file flow.
- The PSD activity-result callback in `CanvasScreen`, delivered through the
  destination-bound `CanvasImportController` request.

A generated stream reproduced the source allocation failure without constructing
a large source array or invoking the PSD parser. The original compiled
`BoundedReadKt.readAtMost` was called in a child JVM with `-Xmx64m`. Its 1,024-byte
control succeeded, but a 96 MiB source exhausted the heap after reading
16,842,752 bytes. The raw 256 MiB ceiling therefore did not protect this
pre-parser allocation boundary.

## Repair

Both entry points now call `PsdImportSource.read`. It acquires the existing
nonblocking import admission before opening the full provider stream, resamples
current heap headroom, reserves 1 MiB for read workspace, and allows at most one
quarter of the remaining import budget for encoded bytes. The existing
`ImportMemoryBudget` limits that budget to a third of the maximum heap or half
of its presently available headroom, whichever is smaller. The absolute 256 MiB
source ceiling is retained.

Four source lengths cover the `ByteArrayOutputStream` capacity, transient growth
and final returned copy. Reads stop at the first byte beyond the admitted source
limit; an oversized source is refused before that extra data grows the output
buffer. A provider returning zero bytes uses a one-byte progress fallback. The
source closes on success, refusal, I/O failure, cancellation exception or VM
failure. Unexpected allocation failure is converted to a readable memory error
after the stream and admission unwind. The gallery does not create a project or
pending PSD handoff when source admission fails.

The existing PSD format, decoded-layer and mask budgets are unchanged. The new
admission covers source buffering; it does not retain a lock across suspending
navigation or PSD layer installation.

## Verification

The same generated 96 MiB stream under the same 64 MiB heap now refuses normally
after 5,330,262 bytes, with `closed=true`, no `OutOfMemoryError` cause and exit 0.
The 1,024-byte control still returns every byte and closes its source.

Eight new `PsdImportSourceTest` cases pass under `-Xmx64m`. They cover the exact
admitted size, oversize refusal before growth, insufficient headroom before
provider opening, competing admission, I/O failure and cancellation identity,
zero-progress providers, unexpected allocation failure cleanup and missing
provider sources.

A new `GalleryIncomingImportTest` case exercises the actual gallery caller while
another preparation owns admission. Against the old gallery source, it fails
because the provider opens twice: once for the small classification header and
again for the full PSD. With the repair, classification is the only provider
open, the user receives the retry message, and the project repository is never
called. The original gallery PSD parser/handoff test also preserves the exact
source bytes and leaves the next incoming URI queued.

The combined standalone Kotlin/JUnit run passed **42 tests** across
`PsdImportSourceTest`, `GalleryIncomingImportTest`, `BoundedReadTest`,
`BoundedImportTest`, `PsdReadBudgetTest`, `PsdImportNoticeTest` and
`ImportPreparationAdmissionTest`. The eight low-heap tests are a repeated subset
of those 42 cases. Full `CanvasScreen` also compiled with the Compose plugin,
the palette changes and this shared reader in the coordinated final UI check.
Scoped ktlint, detekt with the unchanged baseline, and `git diff --check` passed.
No parser limit, existing assertion or gate was weakened.

Detailed reproduction sources, runners and logs are retained in the shared
review workspace at `tooling/w20-data`: `PsdReadProbe.java`,
`PsdReadProbeAfter.java`, `psd-read-small-before.log`,
`psd-read-large-before.log`, `psd-read-small-after.log`,
`psd-read-large-after.log`, `source-tests.log`,
`gallery-caller-before-tests.log` and `caller-tests.log`. The coordinated full
Compose compilation is in `tooling/w19-palette/integrated-ui-compile.log`.

## Evidence limits and final data review

The final source review traced the shipped save/autosave and recovery callers,
provider staging, committed storage, package snapshots, gallery handoffs and
project deletion. The review found no additional demonstrated blocker in those
inspected paths after this source-buffering repair. In particular, save metadata
comes from the committed snapshot; recovery checks project/session ownership;
staging retains cleanup ownership across cancellation; package snapshots keep
their referenced assets; and deletion first removes the project row before
discarding pending imports and private files. This is scoped source acceptance,
not proof of every Android lifecycle or storage failure mode.

The constrained-heap probe uses a host JVM and a generated stream. It does not
prove real Android provider interruption, native decoder allocation, physical
low-memory behavior or process-wide freedom from allocation failure. A stalled
provider can retain admission until it returns. Existing Android acceptance
must still exercise real PSD gallery/picker imports, provider refusal and
cancellation, save/reopen and process-death recovery, native artwork package
round trips, and deletion while navigating. Integrated checks must run on the
final frozen revision; publisher signing, store qualification and physical-device
approval remain separate release requirements.
