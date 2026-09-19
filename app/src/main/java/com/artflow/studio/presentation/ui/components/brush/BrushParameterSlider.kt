package com.artflow.studio.presentation.ui.components.brush

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.artflow.studio.domain.model.brush.BrushValue
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun BrushParameterSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    valueDisplay: String,
    isInteger: Boolean = false,
) {
    var editing by remember { mutableStateOf(false) }
    val displayScale = if (valueDisplay.endsWith("%")) 100f else 1f
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { editing = true }, modifier = Modifier.semantics { contentDescription = "Exact $label" }) {
                Text(valueDisplay)
            }
        }
        Slider(
            value = value.coerceIn(valueRange),
            onValueChange = { onValueChange(if (isInteger) it.roundToInt().toFloat() else it) },
            valueRange = valueRange,
            steps = if (isInteger) ((valueRange.endInclusive - valueRange.start).toInt() - 1).coerceAtLeast(0) else 0,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        )
    }
    if (editing) {
        var text by remember { mutableStateOf(String.format(Locale.ROOT, "%.4f", value * displayScale).trimEnd('0').trimEnd('.')) }
        val parsed = BrushValue.parse(text, valueRange, isInteger, displayScale)
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Range: ${valueRange.start * displayScale} – ${valueRange.endInclusive * displayScale}" +
                            if (displayScale == 100f) "%" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(16) },
                        singleLine = true,
                        label = { Text("Value") },
                        isError = parsed == null,
                        supportingText = { if (parsed == null) Text("Enter a valid ${if (isInteger) "whole " else ""}number in range") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = parsed != null, onClick = {
                    parsed?.let(onValueChange)
                    editing = false
                }) { Text("Set value") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}
