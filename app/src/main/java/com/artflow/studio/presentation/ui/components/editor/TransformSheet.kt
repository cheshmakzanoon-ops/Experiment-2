package com.artflow.studio.presentation.ui.components.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.artflow.studio.core.canvas.LayerTransform
import kotlinx.coroutines.launch

/** Exact affine controls. Drafts are local: opening, editing and cancelling do not touch artwork. */
@Composable
fun TransformSheet(
    layerName: String,
    hasSelection: Boolean,
    onApply: suspend (LayerTransform.Parameters) -> Boolean,
    onClose: () -> Unit,
) {
    var width by remember { mutableStateOf("100") }
    var height by remember { mutableStateOf("100") }
    var rotation by remember { mutableStateOf("0") }
    var skew by remember { mutableStateOf("0") }
    var offsetX by remember { mutableStateOf("0") }
    var offsetY by remember { mutableStateOf("0") }
    var uniform by remember { mutableStateOf(true) }
    var flipX by remember { mutableStateOf(false) }
    var flipY by remember { mutableStateOf(false) }
    var interpolation by remember { mutableStateOf(LayerTransform.Interpolation.BILINEAR) }
    var applying by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val parsedWidth = finiteNumber(width)?.takeIf { it in 1f..1600f }
    val parsedHeight = finiteNumber(if (uniform) width else height)?.takeIf { it in 1f..1600f }
    val parsedRotation = finiteNumber(rotation)
    val parsedSkew = finiteNumber(skew)?.takeIf { it in -80f..80f }
    val parsedX = finiteNumber(offsetX)
    val parsedY = finiteNumber(offsetY)
    val valid = listOf(parsedWidth, parsedHeight, parsedRotation, parsedSkew, parsedX, parsedY).all { it != null }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(layerName, style = MaterialTheme.typography.titleLarge)
        Text(
            "Entire layer and mask · pivot at canvas centre. Off-canvas pixels are clipped; Undo restores them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hasSelection) {
            Text("Deselect before transforming the entire layer.", color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TransformNumber("Width %", width, parsedWidth != null, !applying, { width = it }, Modifier.weight(1f))
            TransformNumber(
                "Height %",
                if (uniform) width else height,
                parsedHeight != null,
                !applying && !uniform,
                { height = it },
                Modifier.weight(1f),
            )
        }
        FilterChip(
            selected = uniform,
            onClick = {
                height = width
                uniform = !uniform
            },
            enabled = !applying,
            label = { Text("Keep proportions") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TransformNumber("Rotation °", rotation, parsedRotation != null, !applying, { rotation = it }, Modifier.weight(1f))
            TransformNumber("Horizontal skew °", skew, parsedSkew != null, !applying, { skew = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { rotation = ((parsedRotation ?: 0f) - 90f).toString() }, enabled = !applying) { Text("−90°") }
            OutlinedButton(onClick = { rotation = ((parsedRotation ?: 0f) + 90f).toString() }, enabled = !applying) { Text("+90°") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TransformNumber("Move X (px)", offsetX, parsedX != null, !applying, { offsetX = it }, Modifier.weight(1f))
            TransformNumber("Move Y (px)", offsetY, parsedY != null, !applying, { offsetY = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = flipX, onClick = { flipX = !flipX }, enabled = !applying, label = { Text("Flip horizontal") })
            FilterChip(selected = flipY, onClick = { flipY = !flipY }, enabled = !applying, label = { Text("Flip vertical") })
        }
        Text("Resampling", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LayerTransform.Interpolation.entries.forEach { mode ->
                FilterChip(
                    selected = interpolation == mode,
                    onClick = { interpolation = mode },
                    enabled = !applying,
                    label = { Text(mode.label) },
                )
            }
        }
        Text(
            "All changes are combined in one resampling pass and one undo step. No change is made until Apply.",
            style = MaterialTheme.typography.bodySmall,
        )
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onClose, enabled = !applying) { Text("Cancel") }
            Spacer(Modifier.weight(1f))
            Button(
                enabled = valid && !hasSelection && !applying,
                onClick = {
                    val parameters =
                        LayerTransform.Parameters(
                            translationX = requireNotNull(parsedX),
                            translationY = requireNotNull(parsedY),
                            scaleX = requireNotNull(parsedWidth) / 100f,
                            scaleY = requireNotNull(parsedHeight) / 100f,
                            rotationDegrees = requireNotNull(parsedRotation),
                            skewXDegrees = requireNotNull(parsedSkew),
                            flipHorizontal = flipX,
                            flipVertical = flipY,
                            interpolation = interpolation,
                        )
                    applying = true
                    failure = null
                    scope.launch {
                        try {
                            if (onApply(parameters)) {
                                onClose()
                            } else {
                                failure = "No change applied. Close this panel and check the active layer."
                            }
                        } catch (error: IllegalArgumentException) {
                            failure = error.message ?: "This transform is not supported on this device."
                        } finally {
                            applying = false
                        }
                    }
                },
            ) { Text(if (applying) "Applying…" else "Apply transform") }
        }
    }
}

private fun finiteNumber(text: String): Float? = text.trim().replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() }

@Composable
private fun TransformNumber(
    label: String,
    value: String,
    valid: Boolean,
    enabled: Boolean,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 16) onChange(it) },
        label = { Text(label) },
        isError = !valid,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}
