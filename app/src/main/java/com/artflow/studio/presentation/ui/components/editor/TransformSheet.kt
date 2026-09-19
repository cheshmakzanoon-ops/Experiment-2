@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

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
    var draft by remember { mutableStateOf(TransformDraft()) }
    var applying by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val parsedWidth = finiteNumber(draft.width)?.takeIf { it in 1f..1600f }
    val parsedHeight = finiteNumber(if (draft.uniform) draft.width else draft.height)?.takeIf { it in 1f..1600f }
    val parsedRotation = finiteNumber(draft.rotation)
    val parsedSkew = finiteNumber(draft.skew)?.takeIf { it in -80f..80f }
    val parsedX = finiteNumber(draft.offsetX)
    val parsedY = finiteNumber(draft.offsetY)
    val valid = draft.parametersOrNull() != null

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
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
            TransformNumber("Width %", draft.width, parsedWidth != null, !applying, { draft = draft.copy(width = it) }, Modifier.weight(1f))
            TransformNumber(
                "Height %",
                if (draft.uniform) draft.width else draft.height,
                parsedHeight != null,
                !applying && !draft.uniform,
                { draft = draft.copy(height = it) },
                Modifier.weight(1f),
            )
        }
        FilterChip(
            selected = draft.uniform,
            onClick = {
                draft = draft.copy(height = draft.width, uniform = !draft.uniform)
            },
            enabled = !applying,
            label = { Text("Keep proportions") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TransformNumber(
                "Rotation °",
                draft.rotation,
                parsedRotation != null,
                !applying,
                { draft = draft.copy(rotation = it) },
                Modifier.weight(1f),
            )
            TransformNumber(
                "Horizontal skew °",
                draft.skew,
                parsedSkew != null,
                !applying,
                { draft = draft.copy(skew = it) },
                Modifier.weight(1f),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(
                onClick = { draft = draft.copy(rotation = ((parsedRotation ?: 0f) - 90f).toString()) },
                enabled = !applying,
            ) { Text("−90°") }
            OutlinedButton(
                onClick = { draft = draft.copy(rotation = ((parsedRotation ?: 0f) + 90f).toString()) },
                enabled = !applying,
            ) { Text("+90°") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TransformNumber(
                "Move X (px)",
                draft.offsetX,
                parsedX != null,
                !applying,
                { draft = draft.copy(offsetX = it) },
                Modifier.weight(1f),
            )
            TransformNumber(
                "Move Y (px)",
                draft.offsetY,
                parsedY != null,
                !applying,
                { draft = draft.copy(offsetY = it) },
                Modifier.weight(1f),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(selected = draft.flipX, onClick = {
                draft = draft.copy(flipX = !draft.flipX)
            }, enabled = !applying, label = { Text("Flip horizontal") })
            FilterChip(selected = draft.flipY, onClick = {
                draft = draft.copy(flipY = !draft.flipY)
            }, enabled = !applying, label = { Text("Flip vertical") })
        }
        Text("Resampling", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LayerTransform.Interpolation.entries.forEach { mode ->
                FilterChip(
                    selected = draft.interpolation == mode,
                    onClick = { draft = draft.copy(interpolation = mode) },
                    enabled = !applying,
                    label = { Text(mode.label) },
                )
            }
        }
        Text(
            "All changes are combined in one resampling pass and one undo step. No change is made until Apply.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Scale: 1–1600% · Skew: −80° to 80°", style = MaterialTheme.typography.bodySmall)
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = onClose, enabled = !applying) { Text("Cancel") }
            Button(
                enabled = valid && !hasSelection && !applying,
                onClick = apply@{
                    // Validate the latest draft again at the mutation boundary, not just in UI semantics.
                    val parameters = draft.parametersOrNull()
                    if (parameters == null || hasSelection || applying) {
                        failure = "Enter valid values and deselect before applying."
                        return@apply
                    }
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

private fun finiteNumber(text: String): Float? =
    text
        .trim()
        .replace(',', '.')
        .toFloatOrNull()
        ?.takeIf { it.isFinite() }

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
        // Decimal-only IMEs may omit a minus key, making negative movement/skew impossible.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        modifier = modifier,
    )
}
