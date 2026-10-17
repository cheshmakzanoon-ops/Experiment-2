#!/bin/bash
# inject.sh -- prove that compile.sh catches real mistakes.  Copies app/src into work/inject (never touches the
# repository), injects deliberate errors, runs compile.sh against the copy and checks every expected location is reported.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AC_ROOT="$(dirname "$HERE")"
REPO="${ARTFLOW_REPO:-/home/user/Experiment-2}"
W="$AC_ROOT/work/inject"
rm -rf "$W" && mkdir -p "$W/repo/app"
cp -r "$REPO/app/src" "$REPO/app/build.gradle.kts" "$W/repo/app/"

python3 -I - "$W/repo/app/src/main/java/com/artflow/studio/presentation/ui" > "$W/expected.txt" <<'PY'
import os, sys
base = sys.argv[1]
pending = []
def edit(rel, old, new, marker, label):
    """Replace `old` by `new` in rel; the line holding `marker` (after ALL edits) is reported at the end."""
    p = os.path.normpath(f"{base}/{rel}")
    s = open(p, encoding="utf-8").read()
    assert s.count(old) == 1, (rel, old, s.count(old))
    open(p, "w", encoding="utf-8").write(s.replace(old, new))
    pending.append((p, marker, label))

# 1. wrong argument type (String where Long is expected) in a presentation file
edit("ArtFlowApp.kt", "                projectId = projectId,\n                onNavigateBack",
     "                projectId = \"not-a-long\",\n                onNavigateBack", 'projectId = "not-a-long"', "wrong argument type")
# 2. call to a nonexistent function inside a @Composable
edit("screens/settings/PrivacyPolicyEntry.kt", "    var visible by rememberSaveable { mutableStateOf(false) }\n",
     "    var visible by rememberSaveable { mutableStateOf(false) }\n    thisFunctionDoesNotExist(visible)\n",
     "thisFunctionDoesNotExist(visible)", "nonexistent function in Composable")
# 3. @Composable invoked from a non-composable lambda
edit("ArtFlowApp.kt", "onNavigateBack = { navController.popBackStack() },\n                onOpenSettings = { navController.navigate(Routes.SETTINGS) },\n                onOpenHelp = { navController.navigate(Routes.HELP) },\n            )\n        }\n        composable(Routes.SETTINGS)",
     "onNavigateBack = { androidx.compose.material3.Text(\"x\"); navController.popBackStack() },\n                onOpenSettings = { navController.navigate(Routes.SETTINGS) },\n                onOpenHelp = { navController.navigate(Routes.HELP) },\n            )\n        }\n        composable(Routes.SETTINGS)",
     'Text("x"); navController', "@Composable call from non-composable lambda")
# 4. unknown string resource
edit("screens/settings/PrivacyPolicyEntry.kt", "R.string.privacypolicyentry_close", "R.string.no_such_string_anywhere",
     "no_such_string_anywhere", "unknown R.string")
# 5. unknown named parameter on a Compose function
edit("screens/settings/PrivacyPolicyEntry.kt", "OutlinedButton(onClick = { visible = true })", "OutlinedButton(onClick = { visible = true }, colour = 3)",
     "colour = 3", "unknown named parameter")
# 6. wrong arity on an app view-model method
edit("ArtFlowApp.kt", "onDismissTip = { viewModel.dismissTip(it) }", "onDismissTip = { viewModel.dismissTip(it, 3) }",
     "dismissTip(it, 3)", "too many arguments to app function")

# 7. experimental-API opt-ins (the real build fails where the experimental API is used)
edit("components/canvas/ImageDrop.kt", "@OptIn(ExperimentalFoundationApi::class)\n@Composable\nfun Modifier.imageDropTarget", "@Composable\nfun Modifier.imageDropTarget",
     "return dragAndDropTarget(", "removed @OptIn(ExperimentalFoundationApi) -> error at the experimental call")
edit("components/editor/CropOverlay.kt", "@OptIn(ExperimentalLayoutApi::class)\n", "", "FlowRow(", "removed @OptIn(ExperimentalLayoutApi) -> error at FlowRow")
edit("components/brush/BrushPracticePad.kt", "@OptIn(ExperimentalComposeUiApi::class)\n", "", ".pointerInteropFilter {",
     "removed @OptIn(ExperimentalComposeUiApi) -> error at pointerInteropFilter")
edit("screens/settings/PrivacyPolicyEntry.kt", "    OutlinedButton(onClick = { visible = true }, colour = 3)", "    androidx.compose.material3.TopAppBar(title = {})\n    OutlinedButton(onClick = { visible = true }, colour = 3)",
     "androidx.compose.material3.TopAppBar(title = {})", "experimental Material3 API (TopAppBar) without @OptIn(ExperimentalMaterial3Api)")
# 8. Android framework call with a wrong argument type (checked against the API 36 framework classes)
edit("../../data/renderer/opengl/ModelRenderer.kt", "Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)", "Bitmap.createBitmap(1, 1, 7)",
     "Bitmap.createBitmap(1, 1, 7)", "framework call with wrong argument type")
for p, marker, label in pending:
    lines = open(p, encoding="utf-8").read().split("\n")
    line = next(i for i, l in enumerate(lines, 1) if marker in l)
    print(f"app/src/{p.split('/app/src/')[1]}:{line}\t{label}")
PY
echo "injected:"; cat "$W/expected.txt"
ARTFLOW_REPO="$W/repo" AC_OUT="$W/out" bash "$AC_ROOT/compile.sh" 2>&1 | grep -v '^Picked up' | tee "$W/result.txt"
echo "---- verification (each injected line must be reported at its exact line)"
status=0
while IFS=$'\t' read -r loc label; do
  if grep -q -F -- "$loc:" "$W/result.txt"; then echo "FOUND   $loc  ($label)"; else echo "MISSING $loc  ($label)"; status=1; fi
done < "$W/expected.txt"
exit $status
