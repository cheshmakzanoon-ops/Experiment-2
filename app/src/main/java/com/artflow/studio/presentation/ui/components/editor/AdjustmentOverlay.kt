package com.artflow.studio.presentation.ui.components.editor

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.artflow.studio.core.pixels.LiveAdjustments
import com.artflow.studio.presentation.ui.viewmodel.AdjustmentSessionController
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Covers the canvas while an adjustment is open. Amount effects follow Procreate: slide a finger
 * left or right anywhere on the canvas (Motion Blur also takes the slide's direction). Colour
 * adjustments show their sliders in a bar at the bottom.
 */
@Composable
fun AdjustmentOverlay(
    state: AdjustmentSessionController.State,
    actions: AdjustmentOverlayActions,
    modifier: Modifier = Modifier,
) {
    val latest by rememberUpdatedState(state)
    val latestActions by rememberUpdatedState(actions)
    Box(modifier.fillMaxSize()) {
        if (state.kind.slidesAmount) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(state.kind) {
                        var startAmount = 0f
                        var travel = Offset.Zero
                        detectDragGestures(
                            onDragStart = {
                                startAmount = latest.settings.amount
                                travel = Offset.Zero
                            },
                        ) { change, delta ->
                            change.consume()
                            travel += delta
                            if (latest.kind == LiveAdjustments.Kind.MOTION_BLUR) {
                                latestActions.onAngle(Math.toDegrees(atan2(travel.y, travel.x).toDouble()).toFloat())
                                latestActions.onAmount(hypot(travel.x, travel.y) / size.width)
                            } else {
                                latestActions.onAmount(startAmount + travel.x / size.width)
                            }
                        }
                    },
            )
        }
        Surface(
            shape = RoundedCornerShape(50),
            tonalElevation = 4.dp,
            modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
        ) {
            val text =
                if (state.kind.slidesAmount) {
                    "${state.kind.displayName} ${(state.settings.amount * 100).toInt()}% — slide across the canvas"
                } else {
                    state.kind.displayName
                }
            Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 4.dp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).widthIn(max = 560.dp),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!state.kind.slidesAmount) ParameterSliders(state, actions.onParameter)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                    TextButton(onClick = actions.onCancel) { Text("Cancel") }
                    Button(onClick = actions.onApply) { Text("Apply") }
                }
            }
        }
    }
}

@Composable
private fun ParameterSliders(
    state: AdjustmentSessionController.State,
    onParameter: (String, Float) -> Unit,
) {
    val type = state.kind.adjustmentType ?: return
    visibleParameters(state.kind).forEach { (key, label) ->
        val range = type.parameterRanges[key] ?: return@forEach
        val value = state.settings.parameters[key] ?: type.defaultParameters[key] ?: range.start
        Text("$label ${value.toInt()}", style = MaterialTheme.typography.labelMedium)
        Slider(value = value, onValueChange = { onParameter(key, it) }, valueRange = range)
    }
}

/** Parameter keys shown for each colour adjustment, with friendly labels. */
private fun visibleParameters(kind: LiveAdjustments.Kind): List<Pair<String, String>> =
    when (kind) {
        LiveAdjustments.Kind.HUE_SATURATION_BRIGHTNESS ->
            listOf("hue" to "Hue", "saturation" to "Saturation", "lightness" to "Brightness")
        LiveAdjustments.Kind.COLOR_BALANCE ->
            listOf("cyan_red" to "Cyan ↔ Red", "magenta_green" to "Magenta ↔ Green", "yellow_blue" to "Yellow ↔ Blue")
        LiveAdjustments.Kind.CURVES ->
            listOf("point_1_y" to "Shadows", "point_2_y" to "Midtones", "point_3_y" to "Highlights")
        LiveAdjustments.Kind.GRADIENT_MAP ->
            listOf("gradient_start_hue" to "Shadow hue", "gradient_end_hue" to "Highlight hue")
        else -> emptyList()
    }
