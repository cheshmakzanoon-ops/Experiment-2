package com.artflow.studio.presentation.ui.components.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Procreate's QuickMenu: a ring of actions around the middle of the screen. Tapping an action runs
 * it and closes the menu; touching and holding one picks another action for that slot from
 * [choices]; tapping the centre opens the full quick menu, and tapping outside closes it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RadialQuickMenu(
    actions: List<QuickAction>,
    onMore: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    choices: List<QuickAction> = emptyList(),
    onAssign: (Int, QuickAction) -> Unit = { _, _ -> },
) {
    var assigning by remember { mutableStateOf<Int?>(null) }
    Box(
        modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(RING_SIZE.dp), contentAlignment = Alignment.Center) {
            val radius = with(androidx.compose.ui.platform.LocalDensity.current) { RADIUS.dp.toPx() }
            actions.forEachIndexed { index, action ->
                val angle = Math.toRadians(-90.0 + 360.0 / actions.size * index)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier =
                        Modifier
                            .offset { IntOffset((cos(angle) * radius).roundToInt(), (sin(angle) * radius).roundToInt()) }
                            .width(84.dp),
                ) {
                    Box {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier =
                                Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .combinedClickable(
                                        onLongClickLabel = "Change this action",
                                        onLongClick = if (choices.isEmpty()) null else ({ assigning = index }),
                                        onClick = {
                                            action.onClick()
                                            onDismiss()
                                        },
                                    ).semantics { contentDescription = action.label },
                        ) { Box(contentAlignment = Alignment.Center) { Icon(action.icon, contentDescription = null) } }
                        DropdownMenu(expanded = assigning == index, onDismissRequest = { assigning = null }) {
                            choices.forEach { choice ->
                                DropdownMenuItem(
                                    text = { Text(choice.label) },
                                    leadingIcon = { Icon(choice.icon, contentDescription = null) },
                                    onClick = {
                                        onAssign(index, choice)
                                        assigning = null
                                    },
                                )
                            }
                        }
                    }
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)) {
                        Text(
                            action.label,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Surface(
                onClick = onMore,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(64.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("More", style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

private const val RING_SIZE = 300
private const val RADIUS = 104

/** One action on the QuickMenu ring. */
data class QuickAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)
