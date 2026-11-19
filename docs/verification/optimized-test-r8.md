# Optimized instrumentation R8 audit

## Observed failure

[Optimized qualification run 44534277761, job 135595457674](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44534277761/job/135595457674)
at revision `1402c7e752a50861a35d7f502534cd023846f59a` failed in
`:app:minifyBenchmarkAndroidTestWithR8`, before any instrumentation case ran. R8 reported two
missing types:

- `com.google.auto.value.AutoValue`, referenced by
  `dagger.hilt.android.testing.OnComponentReadyRunner$EntryPointListener`.
- `javax.lang.model.element.Modifier`, referenced by
  `com.google.errorprone.annotations.IncompatibleModifiers.value()` and one other context.

This is a failed qualification attempt. Repairing its build inputs does not convert that run into
a pass; a subsequent exact-revision run must build both APKs and execute the selected inventory.

## Inspected dependency bytecode

The November 19 investigation used JDK 17 `javap -v -p` against the cached published artifacts,
including `classes.jar` extracted from the Hilt AAR. The inspection covered annotation retention,
the missing descriptors, and the generated implementation called by Hilt:

| Artifact | Observed bytecode |
| --- | --- |
| `com.google.dagger:hilt-android-testing:2.54` | `EntryPointListener` carries `com.google.auto.value.AutoValue` in `RuntimeInvisibleAnnotations`. Its factory constructs `AutoValue_OnComponentReadyRunner_EntryPointListener`, whose class is present in the same AAR. |
| `com.google.auto.value:auto-value-annotations:1.6.3` | `AutoValue` declares `RetentionPolicy.CLASS`. This locally cached annotation JAR was inspected as evidence; it was not added as an application or test dependency. |
| `com.google.errorprone:error_prone_annotations:2.30.0` | Both `IncompatibleModifiers` and `RequiredModifiers` declare `RetentionPolicy.CLASS`. Each has a legacy `value()` method with descriptor `()[Ljavax/lang/model/element/Modifier;` and a separate `modifier()` using Error Prone's own enum. |

A descriptor scan of the Hilt `classes.jar` found the missing AutoValue annotation only on
`EntryPointListener`; the generated implementation's name is a different, present class. The
Error Prone JAR contained the missing JDK enum descriptor only in the two meta-annotation
interfaces above. No application or instrumentation source references these compiler types.

Artifact SHA-256 identities for reproducing this inspection:

| File | SHA-256 |
| --- | --- |
| `hilt-android-testing-2.54.aar` | `f861be2f5ccbfab4bf94c83a6b8a67afb1259aad9598628dfca365008bad4b5b` |
| Its extracted `classes.jar` | `993abba3f23922eda75f1cabf8a7e512a450666abf8b83ad1f99b55797f8df47` |
| `error_prone_annotations-2.30.0.jar` | `144f3aefbd6e27daec55d3753b2c6b13c1afdaf0cf04816cdb564588ed92f1bd` |
| `auto-value-annotations-1.6.3.jar` | `0e951fee8c31f60270bc46553a8586001b7b93dbb12aec06373aa99a150392c0` |

The [JDK retention contract](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/annotation/RetentionPolicy.html)
distinguishes class-file annotation metadata from annotations retained for runtime reflection.
These inspected references serve host compiler analysis and generation; the generated Hilt
implementation is already available to Android. The Error Prone project also records the
[Android incompatibility of this legacy compiler-enum dependency](https://github.com/google/error-prone/issues/2122).

## Scoped repair

The `benchmark` build type adds `testProguardFiles("proguard-android-test.pro")` after inheriting
the release build type. The [AGP 8.10 DSL](https://developer.android.com/reference/tools/gradle-api/8.10/com/android/build/api/dsl/VariantDimension)
defines this collection for processing test code. The new file has exactly two class-specific
rules, matching the two inspected missing types:

```proguard
-dontwarn com.google.auto.value.AutoValue
-dontwarn javax.lang.model.element.Modifier
```

No package wildcard, runtime annotation, missing generated implementation, or application type
is covered. Adding a JDK compiler implementation to an Android runtime would not address a
runtime need here. The rules deliberately remain outside `proguardFiles` and
`proguard-rules.pro`. This annotation repair did not change the tested application's R8 rules,
resource shrinking, or dependency graph. The later test ABI repair below introduces separately
audited benchmark-only constraints; the publisher release still retains its production rules.

## Verification and remaining gate

The assigned build script passed the standalone ktlint check, and `git diff --check` passed for
the configuration change. The integration owner runs Gradle centrally, so this audit did not
start a competing Gradle process or claim an unexecuted build as passing.

The integrator subsequently completed `assembleBenchmark`, `assembleBenchmarkAndroidTest`, and
`verifyReleaseQualificationBuildType` with `-PartflowTestBuildType=benchmark` locally. Both R8
tasks passed. The generated configuration includes `proguard-android-test.pro` only in
`benchmarkAndroidTest`; `benchmark` retains its preexisting Media3 consumer rule mentioning
`javax.lang.model.element.Modifier` independently of this change. At that checkpoint the qualification
workflow required actual obfuscated production classes and resource-shrinker output, and all
43 selected cases across ten classes to pass in one attempt, along with installed APK hashes,
runtime identity, and paint/save/reopen/export evidence. Publisher signing and physical-device
acceptance remain separate gates.

If dependency updates introduce a new missing type or change these references to runtime use,
inspect the new artifact before changing the rules. These two inspected annotation descriptors
do not justify suppressing additional missing classes.

## Observed runtime linkage failure after the annotation repair

[Run 44540625809, job 135617657073](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44540625809/job/135617657073)
at revision `65798d80f0c515e5040a253f7419c18ae6fc7726` built both benchmark APKs, then failed before
executing any of its 43 selected cases. `AndroidJUnitRunner.onCreate` failed with
`NoClassDefFoundError: androidx.tracing.Trace`. This is a failed runtime qualification, not a
successful test run.

The retained evidence ZIP, artifact `14538722804`, has SHA-256
`69f62bde5b6fe508919f78f637c6681cf63c2320c6436de9df950c2a621cba6b`.
Inspection of the actual APK DEX class definitions found `androidx.tracing.Trace` in neither APK.
The app's R8 usage/mapping records show removal/inlining of tracing entry points. The test APK
still called them. Shared runtime dependencies are app-owned, so the instrumentation cannot
assume that a second copy will be packaged with the tests.

Inspection of the pinned AGP 8.10.1 implementation also establishes the relevant contract:
test R8 applies the app mapping to tested code supplied as a classpath resource. It keeps test
code, but it does not automatically retain every application entry point used by that separate
code. A mapping can adapt a rename; it cannot recreate a removed class or member. This affects
direct app calls and shared Compose/Room/Kotlin dependencies as well as the first failing runner
call. Keeping only `Trace` would leave the rest of that boundary unexamined.

## Generated benchmark test ABI

The app build uses the public AGP
[`ScopedArtifact.CLASSES`](https://developer.android.com/reference/tools/gradle-api/8.10/com/android/build/api/artifact/ScopedArtifact.CLASSES)
and [`Variant.proguardFiles`](https://developer.android.com/reference/tools/gradle-api/8.10/com/android/build/api/variant/Variant)
APIs to wire `generateBenchmarkTestAbiRules` before benchmark R8. It does not depend on the
already-minified APK, so the test compile/app shrink dependency graph remains acyclic.

`tools/optimized-test-abi/src/artflow/build/TestAbiRules.java` invokes the `TraceReferences` API
from the exact R8 JAR bundled with the selected AGP. The generator performs these operations:

1. Collect the app and instrumentation class artifacts, including their dependencies, before R8.
2. Partition their definitions by app ownership. A shared class is removed from the tracing
   source, so a duplicate test-side definition cannot conceal calls into the app. Conflicting
   definitions within one owner fail the build.
3. Trace test bytecode into application classes and members, including inherited member
   definitions, using Android's boot classpath to resolve platform types.
4. Ask R8 for exact keep rules with stable external names. The tool emits `-keep` for the
   traced classes/interfaces/enums and their exact referenced members. These prevent removal,
   incompatible rewriting, and renaming of the external test ABI while allowing app shrinking,
   renaming, and optimization outside that boundary. No wildcard keep or global optimization
   switch is added. The report explicitly records `allowObfuscation: false`; the reason for
   this constraint is the reproduced mapping failure below.
5. Fail if the test bytecode refers to a missing member of an app-owned class. Record unresolved
   references outside the app separately for audit; this extractor does not replace the normal
   app/test R8 diagnostics or device execution. It adds no warning suppressions.

Android's platform build describes the same
[external test reference tracing strategy](https://source.android.com/docs/core/perf/r8-optimization)
for its Soong build system. This project supplies its Gradle integration explicitly; it does not
claim that Soong properties are AGP options.

The generator retains normalized `app-input.jar`, `test-input.jar`, `keep-rules.pro`, and
`report.json` under `app/build/reports/release-qualification/test-abi/`. The report contains the
R8 version and JAR identity, input and rule hashes, exact retained symbol inventories, input
class counts, and unresolved external references. The qualification verifier checks hashes,
app/test ownership separation, exact-rule syntax, symbol counts, and inclusion of the generated
rules in R8's actual `configuration.txt`. The release dependency inventory also fingerprints
the helper, its executable fixture, and the reflection rule file as build support inputs.

The mandatory fixture task consumes that same `app-input.jar`. It retains the exact `kotlin/`
and `org/jetbrains/annotations/` class subsets under `test-abi-fixtures/`, together with the
application-input hash, subset hashes, R8 tool identity, and actual unskipped test result counts.
Qualification verifies these subset bytes against the traced application archive, checks the
same R8 tool identity, and rejects absent, failed, skipped, substituted, or incomplete fixtures.
There is no independently downloaded Kotlin test implementation or optional fixture bypass.

The first local generation diagnostic completed successfully against 31,007 app input classes
and 2,592 test input classes. It retained 893 classes, 241 fields, and 1,993 methods, including
the runner's three calls to `Trace`. R8 preserved the names of 54 of those classes as enums and
two exact package names needed for access. These are diagnostic counts from the integration
tree; the final exact-revision report remains authoritative. The 20 unresolved external
references are Android hidden/legacy test APIs, Hamcrest JavaBeans APIs, and the two audited
compile annotation dependencies. They remain visible in the report and are not converted into
new suppression rules. Those counts predate stable-name retention; later reports remain the
source of truth for the exact boundary in each APK pair.

## Kotlin method mapping failure and repair

Optimized run
[44549867524](https://github.com/cheshmakzanoon-ops/Experiment-2/actions/runs/44549867524)
started instrumentation but failed after two of 43 selected cases. In the actual app DEX,
`ContinuationInterceptor.DefaultImpls.releaseInterceptedContinuation` was present as
`n3.e$a.c(n3.e, n3.d)`. The separately remapped test wrapper instead called
`n3.e$a.releaseInterceptedContinuation(n3.e, n3.d)`, causing `NoSuchMethodError`. The sibling
`get` and `minusKey` calls were correctly remapped. This was a member-name mismatch, not a
missing method caused by shrinking.

The bundled R8 8.10.24 mapping represented this method with a repackaged synthetic original
identity. Applying that mapping to a separately compiled caller did not find the original
Kotlin-qualified static method. A reduced executable JVM reproduction using actual
Kotlin 2.1.20 bytecode reproduced the same `NoSuchMethodError`; preserving the exact traced
boundary names made the same caller execute successfully. The mandatory regression now uses
the actual app's Kotlin class bytes and executes the separately optimized app/test pair.
It also requires unreferenced Kotlin methods to disappear and an internal Kotlin helper to
be obfuscated. A separate Java fixture verifies that an untraced helper and unused API are
removed while the stable external call still works.

The repair applies only to the generated benchmark boundary. It does not add publisher R8
rules or turn off application minification, resource shrinking, or optimization globally.
The [normal release payload UI lane](release-payload-ui.md) separately tests the actual
release optimization output without application instrumentation ABI keeps. This failed run
and the host reproducer do not establish a successful later Android execution; the final
APK pair still needs its complete one-attempt device qualification.

## Reflection boundary

Bytecode tracing cannot infer arbitrary string-based reflection. The current instrumentation
source audit found two explicit field lookups. `app/proguard-benchmark-test-reflection.pro`
preserves only these exact fields and names:

| App-owned field | Test use |
| --- | --- |
| `ArtFlowCanvasView.selectionJob: kotlinx.coroutines.Job` | `SelectionGestureDeviceTest` awaits the real asynchronous selection operation before assertions. |
| `OnBackPressedDispatcher.onBackPressedCallbacks: kotlin.collections.ArrayDeque` | `ArtworkWorkflowTest` includes registered Back callbacks in navigation failure diagnostics. |

The current rendering and preview failure tests access their renderer directly, which the
tracer can see. Generated Hilt test wiring and existing production consumer rules retain their
own responsibilities. New reflective test access must be reviewed explicitly instead of
expanding these two rules to a package or all members. The verifier rejects an expanded
reflection file and records its exact digest alongside the generated boundary evidence.

## Qualification scope and mandatory checks

The schema 2 build contract explicitly distinguishes the optimized benchmark from the publisher
release. The benchmark remains non-debuggable, minified, resource-shrunk, and signed with the
disposable debug key. It inherits the publisher's base R8 rules, then adds the generated test
ABI with stable external names and two reflection fields. The publisher `release` variant
receives neither addition. `testAbiAllowsObfuscation` is explicitly false in the build contract.
Both the configured rule lists and the resulting R8 configuration are checked.

Consequently, a passing benchmark run validates an optimized instrumented artifact with those
declared constraints. It is not evidence that the exact publisher optimization output, signing
identity, package upgrade path, or physical-device behavior passed. Those gates remain separate.
The existing requirements for renamed production classes, resource-shrinker output, installed
APK hashes, a complete first attempt with no skips, and real paint/save/reopen/export evidence
remain in force. The workflow also retains the instrumentation's R8 mapping/configuration.

`verifyBenchmarkTestAbiGenerator` executes six JVM fixtures with the real bundled R8. It
checks app-owned duplicates, conflicting definitions, inherited fields/methods, and rejection
of missing app members without stale success evidence. The Java and actual-Kotlin fixtures
retain only the traced external names, require shrinking and internal obfuscation outside that
boundary, apply the app mapping to the separately compiled test through R8's classpath input,
and execute the resulting pairs. These fixtures check linkage mechanics; actual Android runtime
execution of the final APK pair is still required.
