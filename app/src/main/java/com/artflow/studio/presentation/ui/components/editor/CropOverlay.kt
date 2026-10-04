package com.artflow.studio.presentation.ui.components.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.artflow.studio.core.canvas.CropBox
import kotlin.math.roundToInt

/**
 * Crop & Resize on the canvas: drag a corner or edge to resize the crop, or inside it to move it.
 * The area outside is dimmed and a rule-of-thirds grid helps composition.
 */
@Composable
fun CropOverlay(
    box: CropBox.Box,
    canvasWidth: Int,
    canvasHeight: Int,
    view: ViewTransform,
    onChange: (CropBox.Box) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(box)
    val accent = MaterialTheme.colorScheme.primary
    Canvas(
        modifier
            .semantics { contentDescription = "Crop box" }
            .pointerInput(view, canvasWidth, canvasHeight) {
                var grip: CropBox.Grip? = null
                var start = current
                var origin = Offset.Zero

                fun toCanvas(point: Offset) = view.toCanvas(point, size.width.toFloat(), size.height.toFloat(), canvasWidth, canvasHeight)
                detectDragGestures(
                    onDragStart = { point ->
                        start = current
                        origin = toCanvas(point)
                        grip = CropBox.grip(start, origin.x, origin.y, GRIP_PX / view.scale.coerceAtLeast(0.01f))
                    },
                    onDragEnd = { grip = null },
                    onDragCancel = { grip = null },
                ) { change, _ ->
                    val held = grip ?: return@detectDragGestures
                    change.consume()
                    val point = toCanvas(change.position)
                    onChange(CropBox.drag(start, held, point.x - origin.x, point.y - origin.y, canvasWidth, canvasHeight))
                }
            },
    ) {
        val scale = view.scale.coerceAtLeast(0.01f)
        withTransform({
            translate(size.width / 2f + view.offsetX, size.height / 2f + view.offsetY)
            rotate(view.rotationDegrees, pivot = Offset.Zero)
            scale(scale, scale, pivot = Offset.Zero)
            translate(-canvasWidth / 2f, -canvasHeight / 2f)
        }) {
            val w = canvasWidth.toFloat()
            val h = canvasHeight.toFloat()
            val shade = Color.Black.copy(alpha = 0.5f)
            drawRect(shade, Offset.Zero, Size(w, box.top))
            drawRect(shade, Offset(0f, box.bottom), Size(w, h - box.bottom))
            drawRect(shade, Offset(0f, box.top), Size(box.left, box.height))
            drawRect(shade, Offset(box.right, box.top), Size(w - box.right, box.height))
            val line = 1.5f / scale
            val thirds = Color.White.copy(alpha = 0.6f)
            for (i in 1..2) {
                val x = box.left + box.width * i / 3f
                val y = box.top + box.height * i / 3f
                drawLine(thirds, Offset(x, box.top), Offset(x, box.bottom), strokeWidth = line)
                drawLine(thirds, Offset(box.left, y), Offset(box.right, y), strokeWidth = line)
            }
            drawRect(accent, Offset(box.left, box.top), Size(box.width, box.height), style = Stroke(line * 2))
            val arm = CORNER_ARM_PX / scale
            val thick = 4f / scale
            listOf(box.left to box.top, box.right to box.top, box.right to box.bottom, box.left to box.bottom).forEach { (x, y) ->
                val sx = if (x == box.left) 1f else -1f
                val sy = if (y == box.top) 1f else -1f
                drawLine(Color.White, Offset(x, y), Offset(x + sx * arm, y), strokeWidth = thick)
                drawLine(Color.White, Offset(x, y), Offset(x, y + sy * arm), strokeWidth = thick)
            }
        }
    }
}

/** The bar shown while cropping: the new size, numeric settings, reset, cancel and done. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun CropBar(
    box: CropBox.Box,
    onSettings: () -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 4.dp, shadowElevation = 6.dp, modifier = modifier.padding(12.dp)) {
        // Wraps on narrow phones so Done never ends up off-screen.
        FlowRow(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "${box.width.roundToInt()} × ${box.height.roundToInt()} px",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.CenterVertically).padding(horizontal = 8.dp),
            )
            TextButton(onClick = onSettings) { Text("Crop settings") }
            TextButton(onClick = onReset) { Text("Reset") }
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = onDone) { Text("Done") }
        }
    }
}

private const val GRIP_PX = 32f
private const val CORNER_ARM_PX = 18f
