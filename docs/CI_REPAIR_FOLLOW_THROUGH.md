# Run 77: remaining failures and evidence-preserving repairs

Baseline: `34b0269bdb4a5c16ba7663b43d1aa35640a40521`.
Run: [35482869771](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/35482869771).
This record supersedes earlier claims that tab widths and emulator `-prop` flags had fixed CI.

## Observed result, not a proposed outcome

The build job passed JVM tests, ktlint, Detekt, debug/release lint, APK/AAB assembly and artifact
checks. All six device jobs failed. Five completed all 172 cases: the unchanged tab-label overflow
assertion and two selection-race timeouts failed in each. API 26 retained its two existing skips.
The independent API 35/16 KB repeat stopped after 18 cases with the tab failure and a native crash
while testing exact brush values. Its log also contains system-server and other system-app crashes.

Retained artifacts: source `10597095278`; API 26 `10596212234`; API 35 `10596346767`;
API 35 repeat `10596796345`; API 36 `10596920917`; API 36/16 KB `10596232128`;
tablet `10596362122`. GitHub artifact retention is finite; these IDs identify the original evidence.

## Repairs

### Text layout, not another parent-width guess

Increasing a tab's minimum width from 112 to 144 dp did not change the `Library` text measurement:
47 x 20 pixels on API 36, still reporting width overflow. The label was content-sized independently
of its parent. `BrushStudioTabs` now measures each label with the actual text style and density,
then constrains the **Text** width to that result plus two physical pixels. Tabs remain scrollable,
centered and accessible, without clipping/ellipsis to conceal a failed assertion.

The original three-label overflow test is unchanged. Three additional device tests use the same
production tab component at default/large fonts, fractional density, right-to-left layout and
font-scale changes; each also checks selection. Device results are required to validate the repair.

### Selection tests: repair the overload interception

The earlier compositing API change added a no-argument overload. Kotlin `by` delegation forwards
each overload separately: overriding only the two-argument overload does not intercept a delegated
no-argument call. The selection test's pause hook consequently never signalled, causing its existing
10-second waits to time out before either race assertion could execute.

The test decorator now forwards its no-argument overload into its own intercepted policy overload.
Both original race scenarios, exact selection assertions and deadlines remain. An additional check
requires the first snapshot to still be paused before introducing the competing edit. This repairs
the harness, not a newly proven selection-engine defect. A standalone Kotlin/JVM probe reproduced
the old bypass and the repaired single interception; it is not an Android test result.

### Android 15/16 KB: verify the intended runtime mitigation

Run 77's repeat crash still executed `dalvik-jit-code-cache` frames despite its emulator `-prop`
arguments. Those arguments were not evidence that ART started with JIT disabled. The new setup
uses AOSP's documented **adb root -> stop -> setprop -> start** sequence before test processes
start. It verifies emulator/debuggable identity, root access, bounded reboot completion, and both
JIT-property readbacks, retaining before/after runtime metadata in the job artifact. Any setup
failure prevents instrumentation; the harness cannot report success after rejected configuration.

Only the two existing API 35 lanes request this mitigation. Other lanes retain the default runtime,
including API 36/16 KB. All six device lanes, page-size checks, original test cases and minified
launch checks remain enabled. This is an **environment mitigation**, not a proven permanent ART or
application fix. Passing it does not establish production API 35 + JIT or physical-device readiness.

## Verification boundaries

Local: 38 Python tests passed (the original 30 plus eight runtime/harness checks); shell syntax and
`git diff --check` passed. The stateful adb fixture exercises ordering, unchanged default mode,
physical-device refusal, root failure, rejected/ignored properties, failed boot and failure
propagation. It does not substitute for running the actual emulator.

Local Gradle could not bootstrap: `java.net.UnknownHostException: services.gradle.org`.
Do not count the local probe, new test source, or an enqueued workflow as a device/build pass.
The published revision must have its own completed full CI result. Old failed runs remain intact.

## Primary references

- [Kotlin delegation and overridden members](https://kotlinlang.org/docs/delegation.html).
- [AOSP: turn off JIT](https://source.android.com/docs/core/runtime/jit-compiler#turn-off-jit).
