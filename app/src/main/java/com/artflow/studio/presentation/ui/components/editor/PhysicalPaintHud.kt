package com.artflow.studio.presentation.ui.components.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.artflow.studio.domain.model.brush.PhysicalPaintMode
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class PhysicalPaintHudState(
    val mode: PhysicalPaintMode,
    val hasWetPaint: Boolean,
    val isPaused: Boolean,
    val tiltGravityEnabled: Boolean,
    val lightAngle: Float,
    val lightElevation: Float,
    val impastoDepth: Float,
)

data class PhysicalPaintHudActions(
    val onTogglePause: () -> Unit,
    val onToggleTiltGravity: () -> Unit,
    val onDryLayer: () -> Unit,
    val onDryAll: () -> Unit,
    val onRewetLayer: () -> Unit,
    val onLightingChange: (angle: Float, elevation: Float, depth: Float) -> Unit,
    val onOpenMixingTray: () -> Unit,
)

/**
 * Compact collapsible Physical Paint HUD pill.
 *
 * Appears automatically when a Physical Paint brush (Watercolor, Gouache, Oil Impasto) is active
 * or when opened from Actions > Canvas, keeping the top bar uncluttered.
 */
@Composable
fun PhysicalPaintHud(
    state: PhysicalPaintHudState,
    actions: PhysicalPaintHudActions,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.width(if (expanded) 280.dp else 220.dp),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 5.dp,
        shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        state.hasWetPaint && !state.isPaused -> Color(0xFF29B6F6)
                                        state.hasWetPaint -> Color(0xFFFFB74D)
                                        else -> MaterialTheme.colorScheme.outline
                                    },
                                ),
                    )
                    Text(
                        text =
                            when (state.mode) {
                                PhysicalPaintMode.WATERCOLOR -> "Watercolor"
                                PhysicalPaintMode.IMPASTO_OIL -> "Oil Impasto"
                                PhysicalPaintMode.GOUACHE -> "Gouache"
                                PhysicalPaintMode.NONE -> "Physical Paint"
                            },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = actions.onDryLayer, modifier = Modifier.size(30.dp)) {
                        Icon(
                            imageVector = Icons.Default.WaterDrop,
                            contentDescription = "Dry active layer",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    IconButton(onClick = actions.onOpenMixingTray, modifier = Modifier.size(30.dp)) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = "Open Spectral Mixing Tray",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(30.dp)) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "Collapse Physical Paint HUD" else "Expand Physical Paint HUD",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AssistChip(
                            onClick = actions.onDryLayer,
                            label = { Text("Dry", style = MaterialTheme.typography.labelSmall) },
                        )
                        AssistChip(
                            onClick = actions.onDryAll,
                            label = { Text("Dry All", style = MaterialTheme.typography.labelSmall) },
                        )
                        AssistChip(
                            onClick = actions.onRewetLayer,
                            label = { Text("Re-Wet", style = MaterialTheme.typography.labelSmall) },
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = !state.isPaused,
                            onClick = actions.onTogglePause,
                            leadingIcon = {
                                Icon(
                                    imageVector = if (state.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            label = { Text(if (state.isPaused) "Frozen" else "Live Flow", style = MaterialTheme.typography.labelSmall) },
                        )
                        FilterChip(
                            selected = state.tiltGravityEnabled,
                            onClick = actions.onToggleTiltGravity,
                            label = { Text("Tilt Gravity", style = MaterialTheme.typography.labelSmall) },
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // Interactive 3D Impasto Raking-Light Compass
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ImpastoLightCompass(
                            angleDegrees = state.lightAngle,
                            elevationDegrees = state.lightElevation,
                            onChange = { nextAngle, nextElev ->
                                actions.onLightingChange(nextAngle, nextElev, state.impastoDepth)
                            },
                            modifier = Modifier.size(64.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "3D Light ${state.lightAngle.toInt()}° · Depth ${(state.impastoDepth * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Slider(
                                value = state.impastoDepth,
                                onValueChange = { actions.onLightingChange(state.lightAngle, state.lightElevation, it) },
                                valueRange = 0f..1f,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImpastoLightCompass(
    angleDegrees: Float,
    elevationDegrees: Float,
    onChange: (angle: Float, elevation: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant

    Canvas(
        modifier =
            modifier.pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val dx = change.position.x - cx
                    val dy = change.position.y - cy
                    val rad = atan2(dy.toDouble(), dx.toDouble())
                    val deg = ((Math.toDegrees(rad).toFloat() % 360f) + 360f) % 360f
                    val distRatio = (hypot(dx, dy) / (size.width / 2f)).coerceIn(0f, 1f)
                    val elev = (85f - distRatio * 80f).coerceIn(5f, 85f)
                    onChange(deg, elev)
                }
            },
    ) {
        val radius = size.minDimension / 2f - 4f
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(color = outline, radius = radius, center = center, style = Stroke(width = 2f))
        drawCircle(color = outline.copy(alpha = 0.4f), radius = radius * 0.5f, center = center, style = Stroke(width = 1f))
        val rad = Math.toRadians(angleDegrees.toDouble())
        val distRatio = ((85f - elevationDegrees) / 80f).coerceIn(0f, 1f)
        val handle =
            Offset(
                x = center.x + (cos(rad) * radius * distRatio).toFloat(),
                y = center.y + (sin(rad) * radius * distRatio).toFloat(),
            )
        drawLine(color = primary.copy(alpha = 0.5f), start = center, end = handle, strokeWidth = 2f)
        drawCircle(color = primary, radius = 6f, center = handle)
    }
}
