# Optimized instrumentation R8 annotation audit

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
`proguard-rules.pro`, preserving the tested application's release rules, optimization, resource
shrinking, and dependency graph. The default debug matrix and publisher release configuration
retain their existing behavior.

## Verification and remaining gate

The assigned build script passed the standalone ktlint check, and `git diff --check` passed for
the configuration change. The integration owner runs Gradle centrally, so this audit did not
start a competing Gradle process or claim an unexecuted build as passing.

The integrator subsequently completed `assembleBenchmark`, `assembleBenchmarkAndroidTest`, and
`verifyReleaseQualificationBuildType` with `-PartflowTestBuildType=benchmark` locally. Both R8
tasks passed. The generated configuration includes `proguard-android-test.pro` only in
`benchmarkAndroidTest`; `benchmark` retains its preexisting Media3 consumer rule mentioning
`javax.lang.model.element.Modifier` independently of this change. The existing qualification
workflow still requires actual obfuscated production classes and resource-shrinker output, and all
43 selected cases across ten classes to pass in one attempt, along with installed APK hashes,
runtime identity, and paint/save/reopen/export evidence. Publisher signing and physical-device
acceptance remain separate gates.

If dependency updates introduce a new missing type or change these references to runtime use,
inspect the new artifact before changing the rules. These two inspected annotation descriptors
do not justify suppressing additional missing classes.
