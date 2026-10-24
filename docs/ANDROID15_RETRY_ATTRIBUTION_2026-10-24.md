# Android 15 retry evidence association — 24 October 2026

## Observed classifier defect

In Android CI run 312, revision `8050121a7a6e3a1d52854d3c398ad1d29a33bcfe`, the main
Android 15 / 16 KB lane's first attempt reported 41 passes and one empty process failure, with
211 declared cases missing. Its failed identity was
`com.artflow.studio.CanvasInputDeviceTest#emptyOverlaySelectionDoesNotChangePixelsOrHistory`.
The original per-case log records a `system_server` BinderProxy native crash, followed by the
app's pending DeadSystem/JNI report. It does not contain either supported retry signature.

The old classifier nevertheless permitted recovery. It searched every text log and found a
ReferenceQueue crash from `com.google.android.gms.persistent` in an earlier, passing artwork test.
That unrelated event, at 19:08:49.418, preceded the failed case's 19:10:20.725 start. This is a
demonstrated evidence-association defect, separate from identifying the origin of either crash.

The single automatic retry also failed, after 56 passes, leaving 196 cases missing. This time the
associated application process crashed in ART `Class::GetDescriptor` during generated Hilt injection
while constructing `ArtFlowCanvasView`, before the named test's layer/input body began. The original
failure remains empty and the minified launch was not reached. That native stack identifies where
the process faulted; its originating cause and any application contribution remain unresolved.

The original artifact is `12571988636`, 7,229,448 bytes, SHA-256
`15e4e8a199f751856d44df1af4d1020ae580374745db4fc7ad71a4f64f920837`.
All original reports, first-attempt classification, recovery and final failure remain part of run 312.

## Narrow correction

The classifier now requires:

1. Consistent JUnit counts and exactly one failed identity, retaining failure bodies, types and
   messages. Errors, assertions, duplicate identities and ambiguous outcomes are rejected.
2. Exactly one UTP per-case log for that failed class/method and one matching TestRunner start.
   Only that test's interval is considered, with its observed application process ID.
3. A coherent fatal event from the intended app process or an explicitly identified system process.
   ReferenceQueue markers must belong to the same process, thread and fatal block. A native ART
   tombstone must match the interrupted application's PID/TID and retain the existing SDK, 16 KB,
   signal and frame-zero requirements.
4. No earlier different app/system fatal that a later signature could conceal. Unsupported log
   formats, missing context, conflicting records and duplicated tombstones fail closed.

The supported crash signatures and one-recovery limit are unchanged. The BinderProxy crash is not
added as a retryable signature. No shell runner, recovery script, workflow matrix, assertion or skip
rule is relaxed. An unrelated Google process can no longer justify a retry for ArtFlow's failed case.

## Verification

The classifier suite adds eleven regressions while retaining all existing test identities and
failure-message/error checks. Positive fixtures now include realistic test identities, process IDs,
per-case filenames and log timestamps. New negatives cover passed/foreign cases, split fatal blocks,
events outside the interrupted interval, ambiguous starts/logs/outcomes, mismatched process IDs,
earlier unrelated fatal events and borrowed/duplicated native-dump fields.

A read-only replay over the original run-312 artifact produces:

| Attempt | Original classifier | Corrected classifier | Meaning |
| --- | --- | --- | --- |
| First | Retryable | Not retryable | The only old match belonged to an earlier passing test's GMS process |
| Final | Retryable | Retryable | The interrupted app has the already-supported native ART signature |

The second row is only a classifier result. The runner permits one recovery, which run 312 had
already used; this comparison does not authorize or perform a third attempt. No artifact, XML result
or recorded CI decision is rewritten. The exact script hashes and observations are retained in
[the comparison record](evidence/android15-retry-attribution.json).

Full Python execution for this correction reports 93 CI-script checks and 57 tool checks, all passing.
The final combined source still requires its own published Actions run. The correction establishes
more reliable retry attribution; it does not repair or certify the Android 15 / 16 KB runtime.
