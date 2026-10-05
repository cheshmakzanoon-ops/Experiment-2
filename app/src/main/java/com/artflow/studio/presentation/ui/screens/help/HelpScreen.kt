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
                    "Pressure changes size and opacity; brushes with tilt settings paint wider and lighter as the pen leans. " +
                    "Hovering a stylus outlines the brush, and its side button samples colour.",
                "gesture.stylus",
            ),
            TutorialStep(
                "Navigate with two fingers",
                "Two fingers pan, pinch to zoom and twist to rotate the canvas. The point between your " +
                    "fingers stays pinned to the artwork while you zoom. A quick pinch fits the canvas to the screen.",
                "gesture.twoFinger",
            ),
            TutorialStep(
                "Undo with a gesture",
                "A quick two-finger tap undoes and a three-finger tap redoes; hold the fingers down to keep " +
                    "undoing or redoing. A four-finger tap hides the " +
                    "interface, a three-finger swipe down opens Copy & Paste and a three-finger scrub clears the layer. " +
                    "Each of these can be switched off in Actions > Prefs.",
                "gesture.undo",
            ),
            TutorialStep(
                "Sample and snap",
                "Touch and hold the canvas to pick a colour (a ring shows the new colour over the old one), " +
                    "or hold the square modify button for the QuickMenu. " +
                    "Hold the pen still at the end of a stroke to turn " +
                    "it into a clean line, ellipse or polygon, and keep holding to adjust it before you lift. " +
                    "Tap Edit Shape on the banner afterwards to drag its points.",
                "gesture.keepPainting",
            ),
        )

    val TOOLS =
        listOf(
            TutorialStep(
                "Brushes and Brush Studio",
                "Tap the brush again to open the library: search, star favourites and save your own copies. " +
                    "Brush Studio edits every setting on a draft you can try on the drawing pad, including grain and " +
                    "shape images, wet paint, tilt and a second brush that combines with the first.",
                "tool.brush",
            ),
            TutorialStep(
                "Smudge, clone and heal",
                "Smudge drags colour behind the pointer for streaks, clone stamps a tapped source, and " +
                    "healing samples the surrounding texture automatically to hide blemishes.",
                "tool.pixelBrushes",
            ),
            TutorialStep(
                "Fill, ColorDrop and selections",
                "Drag the colour swatch onto the canvas to fill an area, then slide along the bar to change " +
                    "the threshold. Selections can be automatic, freehand, rectangular or elliptical; save them for later, " +
                    "fill them with colour, or copy and paste them as a new layer.",
                "tool.fill",
            ),
            TutorialStep(
                "Transform and Liquify",
                "Transform moves, scales and rotates the active layer, or every layer you swiped right in the " +
                    "Layers panel. Use Freeform, Uniform, Distort or Warp, with Magnetics for straight moves and " +
                    "Snapping to the canvas edges and centre. Liquify pushes, twirls, pinches, bloats, crystallises or " +
                    "creases the paint, and Reset puts it back.",
                "tool.liquify",
            ),
            TutorialStep(
                "Drawing guides",
                "Grid, isometric, perspective and symmetry guides live in Actions > Canvas > Drawing Guide. " +
                    "They mirror and snap strokes only on layers with Drawing Assist, which turning a guide on " +
                    "enables for the current layer. Symmetry replicates the brush motion, so width and texture stay correct.",
                "tool.guides",
            ),
        )

    val WORKFLOW =
        listOf(
            TutorialStep(
                "Layers",
                "Tap the selected layer for its options. Swipe left for Lock, Duplicate and Delete; swipe right " +
                    "to select several layers, then group, delete or transform them together. Touch and hold to drag a " +
                    "layer into a new place. Swipe right with two fingers to toggle Alpha Lock, or tap with two " +
                    "fingers and slide across the canvas to set the layer's opacity. Groups can hold groups: " +
                    "select a group with other layers and group them, or drag a layer or group into another.",
                "flow.layers",
            ),
            TutorialStep(
                "Adjustments",
                "Each adjustment can change the whole layer or only where you paint with Pencil mode. Drag " +
                    "across the canvas to set the amount; adjustment layers affect everything below them.",
                "flow.adjustments",
            ),
            TutorialStep(
                "Animation and pages",
                "Animation Assist adds frames with their own durations, onion skins and playback. Page Assist " +
                    "uses the same frames as pages, with a strip of thumbnails to add, reorder and pick pages.",
                "flow.animation",
            ),
            TutorialStep(
                "Export",
                "PNG, JPEG, WebP, TIFF, PDF, layered PSD and layers as PNG files for stills; GIF, animated PNG, " +
                    "MP4, a zipped PNG sequence and a multi-page PDF for animations and pages. Display P3 canvases " +
                    "keep their colour profile in PNG and JPEG and are converted to sRGB for other formats. " +
                    "Actions > Video replays and exports your drawing's timelapse.",
                "flow.export",
            ),
            TutorialStep(
                "Gallery and saving",
                "Import brings in a photo, a layered PSD, a Procreate document (its layers, groups, blend modes and masks) " +
                    "or a 3D model (OBJ, glTF/GLB, USDZ or USD with texture coordinates, or a zip with its textures) " +
                    "to paint on it in the 3D window: Paint mode paints " +
                    "the model with the current brush, Turn mode or two fingers spin and zoom it. " +
                    "Touch and hold an artwork and drop it on another to make a stack, or use Select to stack, " +
                    "duplicate or delete several at once. ArtFlow autosaves as you work; if the app is killed, " +
                    "reopening the artwork offers to recover the autosaved version.",
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
                "In the editor, the Actions panel holds Add, Canvas, Share, Video, Prefs and Help, and the " +
                    "quick menu holds view, history, canvas and selection actions in one place.",
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
