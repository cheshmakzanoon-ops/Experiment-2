package com.artflow.studio.presentation.ui.components.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.artflow.studio.domain.model.layer.Layer
import com.artflow.studio.domain.model.layer.LayerEffects

/**
 * Outline and drop shadow for one layer. Each switch, colour or finished slider drag is applied at
 * once as its own undo step, so the canvas shows the result while the dialog stays open.
 */
@Composable
fun LayerEffectsDialog(
    layer: Layer,
    currentColor: Int,
    onApply: (LayerEffects?) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(layer.id) { mutableStateOf(layer.effects ?: LayerEffects()) }

    fun commit(next: LayerEffects) {
        draft = next
        onApply(next.takeUnless { it.isEmpty })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Effects: ${layer.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EffectSwitch("Outline", draft.outline != null) { on ->
                    commit(draft.copy(outline = if (on) LayerEffects.Outline(color = currentColor) else null))
                }
                draft.outline?.let { outline ->
                    EffectColor(outline.color) { commit(draft.copy(outline = outline.copy(color = currentColor))) }
                    EffectSlider("Outline width", outline.width, 1f..LayerEffects.MAX_OUTLINE_WIDTH, "px", {
                        draft = draft.copy(outline = outline.copy(width = it))
                    }) { commit(draft) }
                }
                EffectSwitch("Drop shadow", draft.shadow != null) { on ->
                    commit(draft.copy(shadow = if (on) LayerEffects.Shadow() else null))
                }
                draft.shadow?.let { shadow ->
                    EffectColor(shadow.color) { commit(draft.copy(shadow = shadow.copy(color = currentColor))) }
                    EffectSlider("Shadow opacity", shadow.opacity * 100f, 0f..100f, "%", {
                        draft = draft.copy(shadow = shadow.copy(opacity = it / 100f))
                    }) { commit(draft) }
                    EffectSlider("Shadow distance", shadow.distance, 0f..LayerEffects.MAX_SHADOW_DISTANCE, "px", {
                        draft = draft.copy(shadow = shadow.copy(distance = it))
                    }) { commit(draft) }
                    EffectSlider("Shadow angle", shadow.angleDegrees, 0f..360f, "°", {
                        draft = draft.copy(shadow = shadow.copy(angleDegrees = it))
                    }) { commit(draft) }
                    EffectSlider("Shadow softness", shadow.blur, 0f..LayerEffects.MAX_SHADOW_BLUR, "px", {
                        draft = draft.copy(shadow = shadow.copy(blur = it))
                    }) { commit(draft) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun EffectSwitch(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun EffectColor(
    color: Int,
    onUseCurrent: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(24.dp)
                .background(Color(color), CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onUseCurrent) { Text("Use current colour") }
    }
}

@Composable
private fun EffectSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text("${value.toInt()}$unit", style = MaterialTheme.typography.labelSmall)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = range,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        )
    }
}
