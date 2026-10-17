# ArtFlow offline verification harness

Stand-ins for `./gradlew compileDebugKotlin`, `ktlintCheck detekt` and `testDebugUnitTest` for a sandbox that has **no Android SDK
and no access to Google Maven** (`maven.google.com` answers every artifact request with a 301 to `dl.google.com`, which the
sandbox policy blocks; nothing here tries to get around that). The repository is only ever **read**; everything this harness
writes lives under this directory.

```
bash <dir>/compile.sh                                  # type-check ALL Kotlin of the debug variant  (≈ compileDebugKotlin)
bash <dir>/lint.sh [--ktlint|--detekt] [FILE.kt ...]   # ktlint 1.3.1 + detekt 1.23.6, whole tree or just the given files
bash <dir>/unit.sh [--no-compile] [-v] [-k SUBSTR]... [pkg.TestClass[#method]]   # JUnit 4 tests (≈ testDebugUnitTest)
bash <dir>/tests/inject.sh ; bash <dir>/tests/inject_lint.sh     # self-tests: inject mistakes into a COPY, expect them reported
bash <dir>/setup.sh [compile] [lint] [unit]            # downloads/prepares the libraries (called automatically, idempotent)
```
`<dir>` = `tools/offline-check` of this repository (built in the 17 October 2026 session; it is a development aid, not part of the app, and CI does not run it)

Output is `path:line:col: error: message` (compile) / `path:line:col: message (rule)` (lint), paths relative to the repository.
Exit status: `0` clean, `1` findings/errors, `2` harness problem (download, missing tool).

## Environment variables

| variable | meaning | default |
|---|---|---|
| `ARTFLOW_REPO` | repository checkout to verify (only read; needs `app/` with `build.gradle.kts`, `src/`, plus `.editorconfig`, `config/detekt/` for lint) | `/home/user/Experiment-2` |
| `AC_OUT` | output directory (classes, logs, result cache, lock) | `<dir>/out` |
| `KC_XMX` | heap of the Kotlin compiler JVM | `3g` |
| `KC_EXTRA` | extra `kotlinc` flags | – |
| `AC_NO_CACHE=1` | recompile even if the sources are byte-identical to an earlier run | – |
| `GRADLE_LIB` | `lib/` of a Gradle 8.14.x distribution (supplies the embeddable Kotlin 2.0.21 compiler and ASM) | derived from `gradle` on `PATH` (`/opt/gradle-8.14.3/lib`) |

Concurrency: runs sharing one `AC_OUT` are **serialised by a lock** (a compile needs ~3 GB and 2 cores), and a run whose
sources/resources/shims are byte-identical to an earlier one **replays that run's output** instead of recompiling. Give
independent runs their own `AC_OUT` if you really want them in parallel. Do not edit the scripts in place while they run
(replace them atomically with `mv`).

## What the three tools do

**compile.sh** – generates `R` (every resource name found in `app/src/{main,debug}/res`, so a typo in `R.string.x` is an
"unresolved reference" exactly as in the real build) and `BuildConfig` (DEBUG, APPLICATION_ID, VERSION_*; read from
`build.gradle.kts`); runs `tools/check_res.py` (aapt2-style resource checks, below); compiles `src/main` + `src/debug` with
Kotlin **2.0.21 K2** (`-jvm-target 17 -Xjdk-release=17`, i.e. the JDK 17 API surface CI uses, warnings off) and the **Compose** and
**kotlinx-serialization** compiler plugins. Classes go to `out/classes`.
Reproduces: type errors, unresolved references, wrong signatures/named arguments, nullability, `when` exhaustiveness,
experimental-API opt-in errors (Foundation / Layout / ComposeUi / Material3 markers), `@Composable` invocation errors, calls into
the Android framework (checked against the API 36 framework classes), R/BuildConfig references, missing/duplicate/ill-formatted
string resources (see below).

**lint.sh** – `ktlint-cli 1.3.1 -all` with the repo's `.editorconfig` (the Gradle plugin's `android.set(true)` is a no-op with
ktlint 1.x, the code style stays `ktlint_official`; the CLI has no `--android` flag and none is needed) over `app/src/**/*.kt`
(main, test, androidTest, debug) and the `*.kts` build scripts; `detekt-cli 1.23.6 -all` with `config/detekt/detekt.yml`,
`--build-upon-default-config`, `config/detekt/baseline.xml`, no type resolution, over `src/main` and `src/test` (like the Gradle task).
With file arguments only those files are checked (detekt skips files outside `src/main|test`). `--format` runs ktlint's
auto-correct on the given files **in place** (the only mode that writes to the repository).

**unit.sh** – compile.sh, then compiles `src/test` against the compiled classes (`-Xfriend-paths`, like AGP) and runs every class
with `@Test` methods through `tools/RunTests.java` (JUnitCore). Runtime classpath mimics AGP: test classes, main classes,
`src/test/resources`, a **mockable android jar** built once from android-all by `tools/Mockable.java` (ASM; every method returns
0/false/null, `final` removed, = `isReturnDefaultValues = true`), shims, libraries, JUnit/Mockito 5.7.0/Turbine/coroutines-test.
Status: working. Last full run: 784 tests in 132 classes, 0 failed; 1 test (`StudioColorSchemeTest`) is reported as
"SKIP (harness limitation)" because the Compose runtime needs `androidx.collection`, which only exists on Google Maven.

## How the classpath is built (and why it is approximate)

No androidx artifact is reachable, so the `compileDebugKotlin` classpath is **reconstructed**, from Maven Central only:

| part | source | fidelity |
|---|---|---|
| Android framework | `org.robolectric:android-all:16-robolectric-13921718` (Android 16 / API 36, = compileSdk 36) | real classes, but includes hidden APIs (calls to `@hide` APIs are not flagged) and carries no nullability annotations |
| Kotlin / coroutines 1.10.2 / serialization 1.7.3 / Hilt 2.54 (+Dagger) / Coil 2.5.0 / Timber 5.0.1 / OkHttp 4.12 | real jars (AARs unpacked) | real |
| Compose UI, foundation, layout, animation, Material3, icons, ripple | **JetBrains Compose Multiplatform 1.7.3 desktop jars** (same `androidx.compose.*` common API as Jetpack 1.7.x / M3 1.3.x; the `-android` artifacts on Central are 6 KB redirects to Google Maven) | real common API; Android-only API is shimmed |
| activity(-compose), core(-ktx), lifecycle(-runtime/-viewmodel/-compose), navigation-compose, hilt-navigation-compose, room, sqlite, media3-muxer, annotation, Android-only Compose API (`stringResource`, `LocalContext`, `AndroidView`, `pointerInteropFilter`, `@Preview`, dynamic colour schemes, drag-and-drop interop, `ComposeView`, …) | **hand-written shims** in `shims/src` (Kotlin, compiled to `libs/shims.jar` automatically) | written from the public API and from this repo's usage; they can be too permissive or incomplete |

Pinned: Kotlin 2.0.21 (from `/opt/gradle-8.14.3`), JDK 21 running with `-Xjdk-release=17`, android-all 16-robolectric-13921718,
Compose Multiplatform 1.7.3, Hilt/Dagger 2.54, Coil 2.5.0, kotlinx-coroutines 1.10.2, serialization 1.7.3, ktlint 1.3.1,
detekt 1.23.6, Mockito 5.7.0, Turbine 1.1.0, JUnit 4.13.2. All downloads are SHA-1-verified against Maven Central; the JetBrains
jars are resolved by a throw-away Gradle build in `resolve/` (its Gradle home is inside this directory).

## What is NOT verified

* **No KSP**: no Hilt graph validation (missing bindings, scope errors), no Room SQL/DAO validation or generated code (nothing in the
  sources references generated classes today, so compilation is unaffected).
* No resource merging/linking beyond the checks below, no manifest merge/validation (only: well-formed, `android:name`
  classes exist), no AGP/R8/packaging, no Android Lint (`lintDebug`/`lintRelease`), no instrumentation/device tests, no `androidTest` compile.
* Compose/androidx API differences between the harness and the real libraries (see gaps). A clean run is strong evidence, not a proof.

`check_res.py` (aapt2 subset): XML well-formedness, duplicate resources across files, unescaped apostrophes, bad `\u` escapes, several
non-positional `%s` in one string, `@type/name` references that do not exist, missing implicit style parents, manifest classes.

## Known gaps / divergences (all would show as a *false error*, except where noted)

* Android-only Compose API with a *different type on desktop* cannot be shimmed: `Canvas.nativeCanvas`, `Paint.asFrameworkPaint`,
  `KeyEvent.nativeKeyEvent` (java.awt there), `DialogProperties(securePolicy=…, decorFitsSystemWindows=…)`, `SecureFlagPolicy`.
  The app uses none of these today.
* An unresolved `androidx.*` symbol that really exists in the library = a missing shim. The error carries a note; add the
  declaration under `shims/src/…` (same package as the real one) – `shims.jar` rebuilds on the next run.
* Opt-in markers follow the CMP 1.7.3 jars; a marker that exists only on Android (or only there) can be missed/over-reported.
* Shims accept some things the real libraries reject (false negatives), e.g. Room/Hilt/Navigation argument details.
* Tests that touch the Compose runtime/graphics colour spaces cannot run (`androidx.collection`); the Mockito/ASM "mockable jar"
  approximates AGP's but is not byte-identical.

## How it was validated

* compile.sh: 0 errors on the repository (231 files at the last run), while the working tree was changing under it; `tests/inject.sh`
  injects 11 mistakes into a copy of `app/src` (wrong argument type, nonexistent function inside a Composable, `@Composable` call from a
  non-composable lambda, unknown `R.string`, unknown named parameter, too many arguments, removed Foundation/Layout/ComposeUi opt-ins,
  experimental Material3 API without opt-in, wrong argument to an `android.graphics.Bitmap` call) – all reported at the exact line.
* lint.sh: 0 ktlint + 0 detekt findings on a `git archive` of the last CI-green commit `6217937`; `tests/inject_lint.sh` shows
  injected violations are reported at the right lines. On the later work-in-progress tree it correctly reports new findings.
* unit.sh: 784 tests green; an edited assertion in a copy is reported with expected/actual and line.
* `setup.sh` was run from an empty copy of the harness (≈6 min incl. the 190 MB android-all jar; Maven Central rate-limits with 429 and
  bogus 404s – `tools/fetch.sh` retries).

## Layout

`compile.sh lint.sh unit.sh setup.sh` – entry points · `tools/` – env.sh (paths, kotlinc wrapper), fetch.sh/mvn.sh (downloads),
gen_r.py, check_res.py, report.py (error formatter, `baseline.txt` suppressions), Mockable.java, RunTests.java · `shims/` – androidx
stand-ins · `resolve/` – scratch Gradle build for the Compose jars · `libs/` – prepared jars · `dl/` – raw verified downloads ·
`out/` – build output, result cache · `tests/` – self-tests.
