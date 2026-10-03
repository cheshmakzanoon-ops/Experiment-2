package com.artflow.studio.presentation.ui.components.editor

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Procreate's two-finger tap on a layer: slide anywhere across the canvas to set the layer's
 * opacity, then tap to finish.
 */
@Composable
fun LayerOpacityOverlay(
    opacity: Float,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(opacity)
    var value by remember { mutableFloatStateOf(opacity) }
    Box(
        modifier
            .fillMaxSize()
            .semantics { contentDescription = "Slide to set layer opacity" }
            .pointerInput(Unit) { detectTapGestures { onDone() } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragStart = { value = current }) { change, amount ->
                    change.consume()
                    value = (value + amount / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    onChange(value)
                }
            },
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 4.dp,
            shadowElevation = 6.dp,
            modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Opacity ${(opacity * 100).roundToInt()}% · slide across the canvas",
                    style = MaterialTheme.typography.labelLarge,
                )
                TextButton(onClick = onDone) { Text("Done") }
            }
        }
    }
}
