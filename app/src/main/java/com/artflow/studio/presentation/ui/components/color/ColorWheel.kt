package com.artflow.studio.presentation.ui.components.color

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.artflow.studio.core.color.ColorHarmony
import com.artflow.studio.core.color.ColorWheelGeometry
import com.artflow.studio.core.color.ColorWheelState
import kotlin.math.cos
import kotlin.math.sin

/** One pointer owns one region until release; colour recomposition must not restart the gesture. */
@Composable
internal fun ColorWheel(
    color: Int,
    onColorSelected: (Int) -> Unit,
) {
    var wheel by remember { mutableStateOf(ColorWheelState.fromArgb(color)) }
    var externalColor by remember { mutableIntStateOf(color) }
    val callback by rememberUpdatedState(onColorSelected)
    if (externalColor != color) {
        externalColor = color
        // A reflected callback must not quantize away the hue of a grey/black wheel selection.
        if (color != wheel.argb) wheel = wheel.withArgb(color)
    }
    val select: (ColorWheelState) -> Unit = { next ->
        val previous = wheel.argb
        wheel = next
        if (next.argb != previous) callback(next.argb)
    }
    val selectLatest by rememberUpdatedState(select)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .testTag("color-wheel")
                    .semantics { contentDescription = "Hue and saturation picker; channel sliders are available below" }
                    .pointerInput(Unit) {
                        trackColorWheel({ wheel }) { selectLatest(it) }
                    },
        ) {
            val geometry = ColorWheelGeometry(size.width, size.height)
            if (!geometry.valid) return@Canvas
            val center = Offset(geometry.centerX, geometry.centerY)
            val radius = geometry.radius
            val segments = 180
            for (i in 0 until segments) {
                val startAngle = i * (360f / segments)
                drawArc(
                    color = Color(ColorHarmony.fromHsv(startAngle, 1f, 1f)),
                    startAngle = startAngle,
                    sweepAngle = 360f / segments + 1f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = geometry.ringStroke),
                )
            }

            val inner = geometry.inner
            val topLeft = Offset(center.x - inner, center.y - inner)
            val squareSize = Size(inner * 2, inner * 2)
            drawRect(
                brush =
                    Brush.horizontalGradient(
                        listOf(Color.White, Color(ColorHarmony.fromHsv(wheel.hue, 1f, 1f))),
                        startX = topLeft.x,
                        endX = topLeft.x + squareSize.width,
                    ),
                topLeft = topLeft,
                size = squareSize,
            )
            drawRect(
                brush =
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black),
                        startY = topLeft.y,
                        endY = topLeft.y + squareSize.height,
                    ),
                topLeft = topLeft,
                size = squareSize,
            )

            val angle = Math.toRadians(wheel.hue.toDouble())
            val marker =
                Offset(
                    center.x + cos(angle).toFloat() * radius,
                    center.y + sin(angle).toFloat() * radius,
                )
            drawCircle(Color.White, radius = 9f, center = marker)
            drawCircle(Color.Black, radius = 9f, center = marker, style = Stroke(2f))
            val saturationPoint =
                Offset(
                    topLeft.x + wheel.saturation * squareSize.width,
                    topLeft.y + (1f - wheel.value) * squareSize.height,
                )
            drawCircle(Color.White, radius = 7f, center = saturationPoint)
            drawCircle(Color.Black, radius = 7f, center = saturationPoint, style = Stroke(2f))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Brightness", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(72.dp))
            Slider(
                value = wheel.value,
                onValueChange = { selectLatest(wheel.withValue(it)) },
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f).semantics { contentDescription = "Colour brightness" },
            )
        }
    }
}

private suspend fun PointerInputScope.trackColorWheel(
    current: () -> ColorWheelState,
    select: (ColorWheelState) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown()
        val geometry = ColorWheelGeometry(size.width.toFloat(), size.height.toFloat())
        val region = geometry.hitTest(down.position.x, down.position.y) ?: return@awaitEachGesture
        down.consume()
        select(current().pick(down.position.x, down.position.y, geometry, region))
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (change.isConsumed) return@awaitEachGesture
            if (size.width.toFloat() != geometry.width || size.height.toFloat() != geometry.height) return@awaitEachGesture
            select(current().pick(change.position.x, change.position.y, geometry, region))
            change.consume()
            if (!change.pressed) return@awaitEachGesture
        }
    }
}
