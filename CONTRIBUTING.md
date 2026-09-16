# Contributing to ArtFlow

Thanks for helping improve ArtFlow. This document covers the workflow that keeps the project
buildable and reviewable; the technical design lives in [`agent.md`](agent.md) and the feature
inventory in [`README.md`](README.md).

## Before you start

- Read the ["Project status"](README.md#-project-status) table. It lists what is wired into the app
  and what is not, so you do not spend time on a module that is already known to be unused.
- Anything described as *not implemented* in that table is a welcome target.

## Requirements

| Tool | Version |
|------|---------|
| JDK | 17 |
| Android SDK | API 34 (`compileSdk`/`targetSdk`) |
| NDK | 25.1.8937393 — only for the native `libartflow-brush` module |
| Gradle | via the committed wrapper (`./gradlew`) |

`local.properties` needs `sdk.dir=…` and is git-ignored.

## Build and test

```bash
./gradlew testDebugUnitTest     # JVM unit tests (no device, no NDK needed)
./gradlew assembleDebug         # debug APK, builds the native module too
./gradlew assembleDebug -PnoNativeBuild   # same, without the NDK/CMake step
./gradlew lintDebug             # Android lint
```

Release builds need signing credentials in a git-ignored `keystore.properties` at the repository
root; without it `./gradlew assembleRelease` produces an unsigned APK. See
["Building for release"](README.md#-building-for-release).

## Code style

- Kotlin official coding conventions, 4-space indents, no wildcard imports.
- `ktlint` and `detekt` are **not** configured, so there is no `./gradlew ktlintCheck`. Run
  `./gradlew lintDebug` for platform lint and keep your editor set to the Kotlin style.
- Prefer editing existing files over adding new ones, and keep new abstractions proportional to the
  duplication they remove.

## Tests

`app/src/test` holds JVM unit tests for the pure-Kotlin engines (`core/pixels`, `core/render`,
`core/tool`, `core/canvas`, `core/symmetry`, `core/export`, `core/animation`). New logic in those
modules should come with tests.

Two things to know about unit tests here:

- The pixel engines are deliberately free of `android.*` imports, which is what keeps them testable
  on the JVM. Do not introduce an Android dependency into `core/pixels` or `core/render`.
- Types that need `android.graphics` (canvas view, OpenGL renderer, `VectorShape`, `Selection`) are
  covered by instrumentation tests instead, which do not exist yet — see the status table. Adding
  `app/src/androidTest` is an open task.

```bash
./gradlew testDebugUnitTest --tests 'com.artflow.studio.core.pixels.*'
```

## Commit messages

Conventional commits: `feat:`, `fix:`, `docs:`, `style:`, `refactor:`, `test:`, `chore:`.

```
feat: add radial symmetry presets

Adds four presets to SymmetryEngine and exposes them in the guides sheet.
```

## Pull requests

1. Branch off `main`.
2. Keep the change focused; one behaviour change per pull request.
3. Add or update tests.
4. Make sure `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` pass locally — CI runs
   both on every pull request.
5. Update `README.md`/`agent.md` if your change alters what the app actually does.

## Reporting bugs

Include the Android version, device (or emulator) model, the exact steps to reproduce, and a logcat
excerpt. Crashes in the GL canvas are easiest to diagnose with `adb logcat -s ArtFlow:V AndroidRuntime:E`.

## License

By contributing you agree that your work is released under the project's
[GPL-3.0 license](LICENSE).
