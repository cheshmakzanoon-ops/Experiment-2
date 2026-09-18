@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.artflow.studio.presentation.ui.screens.help

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A tutorial step shown in the help screen and the first-run walkthrough. */
data class TutorialStep(
    val title: String,
    val body: String,
    val tipId: String,
)

/**
 * The in-app manual.
 *
 * The steps describe what the app actually does (verified against the implementation), so the
 * documentation cannot quietly drift away from the product.
 */
object Tutorials {
    val GESTURES =
        listOf(
            TutorialStep(
                "Paint with a stylus",
                "Rest your hand on the screen: while a stylus is down, finger touches are ignored as palms. " +
                    "Pressure changes size and opacity, tilt drives the brush rotation and orientation.",
                "gesture.stylus",
            ),
            TutorialStep(
                "Navigate with two fingers",
                "Two fingers pan, pinch to zoom and twist to rotate the canvas. The point between your " +
                    "fingers stays pinned to the artwork while you zoom.",
                "gesture.twoFinger",
            ),
            TutorialStep(
                "Undo with a gesture",
                "A quick two-finger tap undoes. A three-finger tap redoes. The same gestures work while " +
                    "a tool other than the brush is active.",
                "gesture.undo",
            ),
            TutorialStep(
                "Pan without leaving the brush",
                "Zoom in and pan with two fingers, then keep painting: the tool never switches because " +
                    "you navigated.",
                "gesture.keepPainting",
            ),
        )

    val TOOLS =
        listOf(
            TutorialStep(
                "Brush and eraser",
                "Both bake straight into the active layer's pixels as soon as the stroke ends, which is " +
                    "why saving is instant. The eraser removes coverage instead of painting a colour.",
                "tool.brush",
            ),
            TutorialStep(
                "Smudge, clone and heal",
                "Smudge drags colour behind the pointer for streaks, clone stamps a tapped source, and " +
                    "healing samples the surrounding texture automatically to hide blemishes.",
                "tool.pixelBrushes",
            ),
            TutorialStep(
                "Fill and gradient",
                "The paint bucket flood-fills using the tolerance you set, respecting the selection. " +
                    "Gradients use the drag as their axis and honour the selection too.",
                "tool.fill",
            ),
            TutorialStep(
                "Liquify",
                "Liquify builds a displacement map across the gesture so distortion accumulates smoothly. " +
                    "Push, twirl, pinch or bloat, then release to commit one undo step. " +
                    "Reconstruct gradually restores the image before your current liquify sequence. " +
                    "Another edit, undo, reopening, or changing the tool, layer or frame resets that reference.",
                "tool.liquify",
            ),
            TutorialStep(
                "Symmetry and perspective guides",
                "Symmetry replicates the brush motion — not the pixels — so variable width and texture " +
                    "stay correct on every mirrored stroke. Perspective guides snap strokes onto rays.",
                "tool.guides",
            ),
        )

    val WORKFLOW =
        listOf(
            TutorialStep(
                "Layers",
                "Add, reorder, lock, alpha-lock, clip to the layer below, and blend with the full set of " +
                    "blend modes. Masks hide parts of a layer without erasing them.",
                "flow.layers",
            ),
            TutorialStep(
                "Adjustments and filters",
                "Adjustment layers affect everything below them; filter layers can be baked into the " +
                    "layer beneath once you are happy with the result.",
                "flow.adjustments",
            ),
            TutorialStep(
                "Animation",
                "Add or duplicate frames, set per-frame durations, scrub the timeline and toggle onion " +
                    "skinning. Playback uses each frame's own duration, exactly like the export.",
                "flow.animation",
            ),
            TutorialStep(
                "Export",
                "PNG, JPEG, WebP, PDF and layered PSD for stills; GIF, MP4 and a zipped PNG sequence for " +
                    "animations. Exports are written into the project folder and can be shared straight away.",
                "flow.export",
            ),
            TutorialStep(
                "Saving and recovery",
                "You choose how often ArtFlow autosaves. If the app is killed mid-session, reopening the " +
                    "project offers to recover the autosaved version.",
                "flow.saving",
            ),
        )

    val ALL: List<TutorialStep> = GESTURES + TOOLS + WORKFLOW

    /** Tips shown as dismissible cards on first run. */
    fun firstRunTips(): List<TutorialStep> =
        listOf(
            GESTURES[0],
            GESTURES[1],
            WORKFLOW[0],
            WORKFLOW[3],
        )
}

/** Help screen: the manual, grouped the same way as the tutorials. */
@Composable
fun HelpScreen(
    onNavigateBack: () -> Unit,
    dismissedTips: Set<String> = emptySet(),
    onDismissTip: (String) -> Unit = {},
    onResetTips: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Help and tutorials") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TutorialSection("Gestures", Tutorials.GESTURES, dismissedTips, onDismissTip)
            TutorialSection("Tools", Tutorials.TOOLS, dismissedTips, onDismissTip)
            TutorialSection("Workflow", Tutorials.WORKFLOW, dismissedTips, onDismissTip)

            Divider()
            Text("About ArtFlow", style = MaterialTheme.typography.titleMedium)
            Text(
                "ArtFlow Studio is an offline painting and animation studio. Artwork, layers, masks, " +
                    "animation frames and exports stay on this device; there is no account and no upload.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Anything you cannot find here is in the editor's quick menu (the lightning icon), which " +
                    "holds view, history, canvas and selection actions in one place.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onResetTips) { Text("Show the first-run tips again") }
        }
    }
}

@Composable
private fun TutorialSection(
    title: String,
    steps: List<TutorialStep>,
    dismissedTips: Set<String>,
    onDismissTip: (String) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    steps.forEach { step ->
        Card {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(step.title, style = MaterialTheme.typography.titleSmall)
                Text(step.body, style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (step.tipId in dismissedTips) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Dismissed",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        TextButton(onClick = { onDismissTip(step.tipId) }) {
                            Text("Hide this tip", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

/**
 * First-run walkthrough.
 *
 * Shown once (tracked in settings) and skippable; the same steps are always available from Help, so
 * nothing is lost by dismissing it.
 */
@Composable
fun OnboardingDialog(onFinish: (showTips: Boolean) -> Unit) {
    val steps = remember { Tutorials.firstRunTips() }
    var index by remember { mutableStateOf(0) }
    val step = steps[index]

    AlertDialog(
        onDismissRequest = { onFinish(false) },
        title = { Text("Welcome to ArtFlow (${index + 1}/${steps.size})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(step.title, style = MaterialTheme.typography.titleSmall)
                Text(step.body, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (index < steps.lastIndex) index++ else onFinish(true)
            }) {
                Text(if (index < steps.lastIndex) "Next" else "Start painting")
            }
        },
        dismissButton = {
            TextButton(onClick = { onFinish(false) }) { Text("Skip") }
        },
    )
}
