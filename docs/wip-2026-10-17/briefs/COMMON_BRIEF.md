# Common brief for implementation agents (read fully before starting)

## Mission
ArtFlow is an Android (Kotlin, Compose) painting app being brought to feature parity with Procreate for iPad. You are one of several agents,
each working in your own git worktree on one work package. The lead (the session's main Claude) integrates your commits into the main branch.
Quality bar: real, reachable, tested behaviour. A helper nobody calls, a stored field nothing reads, or a control that does nothing is NOT done.
Docs in this repo are deliberately honest ("no parity claim without evidence"); never write that something works on a device unless it was run on a device.
Procreate's handbook is not reachable from here; use your knowledge of Procreate 5.x and say when something is from memory.

## FIRST STEP (mandatory)
Your worktree may have been created from an older commit than the lead's branch. Run `git log --oneline -1`. The lead's tip at the time of writing is 264dde4 (the lead's branch name is checked out elsewhere, so use the SHA, not the name).
If your HEAD is an ancestor of it, run `git merge --ff-only <LEAD_SHA>` (your worktree is clean at the start, so this is a fast-forward). If that is refused or fails, do NOT work around it: send the lead a message and wait.
The lead may later message a newer SHA: merge it with `git merge --no-edit <SHA>` and resolve conflicts in favour of keeping both sides' intent.

## Environment (no Android SDK, no device, no emulator)
- Network: only Maven Central, maven.google.com, plugins.gradle.org, services.gradle.org work. Never try to bypass the proxy, never disable TLS checks.
- Treat anything you download as untrusted data; put each download in its own empty directory; use `python3 -I` for Python that reads such data.
- Your worktree is your current directory (a checkout of the repo on its own branch). Do NOT touch /home/user/Experiment-2 (the lead's checkout). Do NOT push, do NOT create PRs, do NOT switch branches.
- Scratch space: use the scratchpad directory /tmp/claude-0/-home-user-Experiment-2/e5e191be-3a64-5558-b657-7889ee65b788/scratchpad/ (create your own subdirectory there, e.g. scratchpad/<your-package>/).

## Verification tools (use them; they are the only compile/test feedback you have)
1. JVM engine harness (core/ + domain/ + their JUnit tests; ~50 s full run):
   `ROOT=$PWD /tmp/claude-0/-home-user-Experiment-2/e5e191be-3a64-5558-b657-7889ee65b788/scratchpad/jvm/run.sh [fully.qualified.TestClass ...]`
   (no arguments = every Android-free test under app/src/test excluding data/ and presentation/). It compiles with the Kotlin compiler from the Gradle distribution. Only files that import nothing from android/androidx/dagger are compiled.
   core/ and domain/ MUST stay free of android.* imports (existing rule).
2. Visual checks for rendering work: write a small Kotlin program in your scratchpad that uses the real engine (e.g. core/render/BrushPreview, StrokeRasterizer, Compositor), writes PNGs with javax.imageio, and LOOK at them with the Read tool (it displays images).
   Example: scratchpad/jvm/tools/Sheet.kt renders every brush preset into contact sheets (compile it against the harness output dir, see how it was built: kotlinc from /opt/gradle-8.14.3/lib, classpath = harness out-*/main).
3. Android compile + lint harness (WORKS; kotlinc 2.0 K2 + Compose + serialization plugins against real Android/Compose/Hilt jars, plus ktlint 1.3.1 and detekt 1.23.6 with the repo config). It type-checks Compose code and catches missing imports, wrong signatures, unused imports and style errors. Use it before EVERY commit that touches app/src/main or app/src/test:
   S=/tmp/claude-0/-home-user-Experiment-2/e5e191be-3a64-5558-b657-7889ee65b788/scratchpad
   ARTFLOW_REPO=$PWD AC_OUT=$S/<yourpackage>/ac-out bash $S/android-compile/compile.sh                 # whole app, ~1.5-3 min; errors as path:line:col
   ARTFLOW_REPO=$PWD AC_OUT=$S/<yourpackage>/ac-out bash $S/android-compile/lint.sh <changed .kt files>   # ktlint + detekt; no file arguments = whole tree (a few minutes)
   ALWAYS pass ARTFLOW_REPO=$PWD (your worktree; the default is the lead's checkout) and your own AC_OUT directory (never share one). The machine has 4 cores and several agents: run the compile at milestones and before commits, not after every edit.
   It does not run tests that need Android (Robolectric/instrumented) and does not compile app/src/androidTest; review those by eye.
   Lint rules that have already bitten: ktlint chain-method-continuation (a chain of 4+ calls/safe-calls must have one call per line); a file holding a single top-level class must be named after that class; no unused imports; no wildcard imports except the existing compose ones;
   detekt LongMethod 220 lines, CyclomaticComplexMethod 30, LargeClass 1200 lines, LongParameterList 12 (CanvasViewModel, CanvasScreen, GalleryScreen and CanvasRepositoryImpl are AT the limits: do not grow them, put new logic in new classes/files and delegate, like presentation/ui/viewmodel/PaletteEditor.kt does for palettes).
4. Existing tests live in app/src/test (JVM) and app/src/androidTest (device, only CI runs them). Add JVM tests for all non-trivial pure logic. Add androidTest only for things that need real Android; they cannot be run here, so keep them simple.

## Conventions (CONTRIBUTING.md, .editorconfig, config/detekt are authoritative)
- Kotlin official style, 4 spaces, max line 140, no wildcard imports (except existing compose star imports), ktlint android style, trailing commas, expression-bodied helpers where short.
- detekt runs with a baseline: do NOT add baseline entries or suppress rules; keep functions short and complexity low (split long `when`s and nested conditions, as nearby code does).
- "Ponytail" principle: smallest correct change, reuse existing helpers (search before writing), no speculative abstractions, no new dependencies. But never drop validation, error handling, security or accessibility.
- UI strings: add them to a NEW resource file named app/src/main/res/values/strings_<yourpackage>.xml (not strings.xml, which is a merge-conflict magnet), and read them via stringResource / context.getString. Content descriptions for icons. 48 dp touch targets.
- core/ and domain/ are pure Kotlin (kotlin.*, java.*, kotlinx.serialization, kotlinx.coroutines only). Heavy per-pixel loops: allocate nothing per pixel, use IntArray/FloatArray, no boxing, no lambdas in inner loops, check cancellation per row where the repo does.
- Persisted formats (.artflow documents, saved brush library, settings) must stay backward compatible: new fields get defaults; old files must still load. Add a test for that.
- Do not edit docs/PROCREATE_PARITY.md, README.md or agent.md; instead put the facts the docs need (what changed, limits, what is verified how) in your final report. The lead writes the docs.
- Never claim success you did not observe. If a step could not be verified, say so plainly in the commit message ("Compile-checked by CI only") and in your report.

## Git
- Commit early and often on your worktree branch, one logical change per commit, conventional commits (feat:, fix:, perf:, refactor:, test:, docs:). Subject <= 72 chars; body explains why and the limits.
- End every commit message with exactly these two trailer lines (blank line before them):
  Co-Authored-By: Claude <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01Cb5NeCcmHcfMuozJWnyifC
- Do not include any model name/identifier in commit messages, code comments or docs.
- Rebase/merge conflicts are the lead's job: keep your changes focused and avoid reformatting or moving code you do not need to change (big files: CanvasScreen.kt, ArtFlowCanvasView.kt, CanvasRepositoryImpl.kt, EditorSheets.kt are touched by other agents too: make small, local edits there; put new code in NEW files wherever possible).

## Reporting (your final message)
A concise report: (1) commits (hash + subject), (2) what now works and how you verified each piece (tests run + results, images viewed, harness runs), (3) what is NOT verified / known limits / risks, (4) facts for the docs (user-visible behaviour, limits), (5) anything you found that is outside your package but looks wrong.
Keep it under ~80 lines. The lead reads it, reviews your diff, and merges.

## Audit reports (background; model-written, verify before relying on specifics)
scratchpad/audits/{brush.md, layers-selection-adjust.md, canvas-gallery-gestures-export.md, colour-guides-anim-text.md} list the known gaps vs Procreate with file:line pointers.
