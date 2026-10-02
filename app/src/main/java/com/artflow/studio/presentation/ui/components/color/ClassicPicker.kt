package com.artflow.studio.presentation.ui.components.color

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.artflow.studio.core.color.ColorHarmony
import com.artflow.studio.core.color.ColorWheelState
import kotlin.math.roundToInt

/** Procreate's Classic picker: a saturation × brightness square with hue, saturation and brightness sliders. */
@Composable
internal fun ClassicPicker(
    color: Int,
    onColorSelected: (Int) -> Unit,
) {
    var state by remember { mutableStateOf(ColorWheelState.fromArgb(color)) }
    var external by remember { mutableIntStateOf(color) }
    if (external != color) {
        external = color
        if (state.argb != color) state = state.withArgb(color)
    }
    val latest by rememberUpdatedState(state)
    val callback by rememberUpdatedState(onColorSelected)

    fun publish(next: ColorWheelState) {
        state = next
        external = next.argb
        callback(next.argb)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.4f)
                .testTag("classic-square")
                .pointerInput(Unit) {
                    fun pick(offset: Offset) {
                        val s = (offset.x / size.width).coerceIn(0f, 1f)
                        val v = (1f - offset.y / size.height).coerceIn(0f, 1f)
                        publish(latest.copy(saturation = s, value = v))
                    }
                    detectTapGestures { pick(it) }
                }.pointerInput(Unit) {
                    detectDragGestures(onDragStart = { start ->
                        val s = (start.x / size.width).coerceIn(0f, 1f)
                        val v = (1f - start.y / size.height).coerceIn(0f, 1f)
                        publish(latest.copy(saturation = s, value = v))
                    }) { change, _ ->
                        change.consume()
                        val s = (change.position.x / size.width).coerceIn(0f, 1f)
                        val v = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                        publish(latest.copy(saturation = s, value = v))
                    }
                },
        ) {
            val hue = Color(ColorHarmony.fromHsv(state.hue, 1f, 1f))
            drawRect(Brush.horizontalGradient(listOf(Color.White, hue)))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val marker = Offset(state.saturation * size.width, (1f - state.value) * size.height)
            drawCircle(Color.White, radius = 11.dp.toPx(), center = marker, style = Stroke(3.dp.toPx()))
            drawCircle(Color.Black, radius = 12.5f.dp.toPx(), center = marker, style = Stroke(1.dp.toPx()))
        }
        LabeledSlider("Hue", state.hue, 0f..360f, "${state.hue.roundToInt()}°") { publish(latest.copy(hue = it)) }
        LabeledSlider("Saturation", state.saturation, 0f..1f, "${(state.saturation * 100).roundToInt()}%") {
            publish(latest.copy(saturation = it))
        }
        LabeledSlider("Brightness", state.value, 0f..1f, "${(state.value * 100).roundToInt()}%") { publish(latest.copy(value = it)) }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    readout: String,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(76.dp))
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        Text(readout, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(44.dp))
    }
}
