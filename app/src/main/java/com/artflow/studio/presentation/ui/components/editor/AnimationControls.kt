@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.artflow.studio.presentation.ui.components.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.artflow.studio.R
import com.artflow.studio.core.animation.AnimationPlayback
import com.artflow.studio.core.animation.AnimationTimeline
import com.artflow.studio.domain.model.animation.AnimationSettings
import com.artflow.studio.presentation.ui.components.color.ColorWheel
import kotlin.math.roundToInt

/** Frame roles use stable identities, so reordering the timeline cannot move a pin to another drawing. */
@Composable
internal fun AnimationFrameSettings(
    timeline: AnimationTimeline.State,
    onSettings: (AnimationSettings) -> Unit,
) {
    val frame = timeline.activeFrame ?: return
    val ids = timeline.frames.map { it.id }
    val settings = timeline.settings
    val role = AnimationPlayback.role(ids, settings, timeline.activeIndex)
    val visibilityLabel = stringResource(R.string.animation_frame_visible)
    Text(stringResource(R.string.animation_frame_role), style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AnimationPlayback.Role.entries.forEach { choice ->
            FilterChip(
                selected = role == choice,
                onClick = { onSettings(AnimationPlayback.withRole(ids, settings, timeline.activeIndex, choice)) },
                label = { Text(choice.label) },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            modifier = Modifier.semantics { contentDescription = visibilityLabel },
            checked = frame.id !in settings.hiddenFrameIds,
            onCheckedChange = { visible ->
                val hidden = if (visible) settings.hiddenFrameIds - frame.id else settings.hiddenFrameIds + frame.id
                onSettings(settings.copy(hiddenFrameIds = hidden))
            },
        )
        Spacer(Modifier.width(8.dp))
        Text(visibilityLabel, style = MaterialTheme.typography.bodySmall)
    }
    Text(stringResource(R.string.animation_pins_explanation), style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun AnimationRangeSettings(
    timeline: AnimationTimeline.State,
    onSettings: (AnimationSettings) -> Unit,
) {
    if (timeline.frames.size < 2) return
    val settings = timeline.settings
    val range = AnimationTimeline.playbackRange(timeline)
    val maximum = timeline.frames.lastIndex.toFloat()
    Text(stringResource(R.string.animation_playback_range), style = MaterialTheme.typography.labelMedium)
    FrameRangeSlider(
        label = stringResource(R.string.animation_range_start),
        value = range.first.toFloat(),
        range = 0f..maximum,
        onChange = { value ->
            val start = value.roundToInt()
            onSettings(
                settings.copy(
                    playbackRangeStart = start,
                    playbackRangeEnd = if (settings.playbackRangeEnd < 0) -1 else maxOf(start, range.last),
                ),
            )
        },
        valueText = (range.first + 1).toString(),
    )
    FrameRangeSlider(
        label = stringResource(R.string.animation_range_end),
        value = range.last.toFloat(),
        range = 0f..maximum,
        onChange = { value ->
            val end = value.roundToInt()
            onSettings(settings.copy(playbackRangeStart = minOf(range.first, end), playbackRangeEnd = end))
        },
        valueText = (range.last + 1).toString(),
    )
    OutlinedButton(onClick = { onSettings(settings.copy(playbackRangeStart = 0, playbackRangeEnd = -1)) }) {
        Text(stringResource(R.string.animation_full_range))
    }
    val count = AnimationPlayback.forwardIndices(timeline).size
    Text(
        stringResource(R.string.animation_playback_summary, count, AnimationPlayback.durationMs(timeline)),
        style = MaterialTheme.typography.bodySmall,
    )
    if (count == 0) {
        Text(stringResource(R.string.animation_no_playable_frames), color = MaterialTheme.colorScheme.error)
    }
}

/** Both ghost colors are edited independently; cancelling a picker leaves the document unchanged. */
@Composable
internal fun AnimationOnionColors(
    settings: AnimationSettings,
    onSettings: (AnimationSettings) -> Unit,
) {
    var previous by remember { mutableStateOf<Boolean?>(null) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { previous = true }) {
            OnionSwatch(settings.onionSkinPreviousColor)
            Text(stringResource(R.string.animation_previous_color))
        }
        OutlinedButton(onClick = { previous = false }) {
            OnionSwatch(settings.onionSkinNextColor)
            Text(stringResource(R.string.animation_next_color))
        }
    }
    previous?.let { before ->
        val initial = if (before) settings.onionSkinPreviousColor else settings.onionSkinNextColor
        var draft by remember(before) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { previous = null },
            title = { Text(stringResource(if (before) R.string.animation_previous_color else R.string.animation_next_color)) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    ColorWheel(color = draft, onColorSelected = { draft = it })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onSettings(
                        if (before) {
                            settings.copy(onionSkinPreviousColor = draft)
                        } else {
                            settings.copy(onionSkinNextColor = draft)
                        },
                    )
                    previous = null
                }) { Text(stringResource(R.string.animation_use_color)) }
            },
            dismissButton = {
                TextButton(onClick = { previous = null }) { Text(stringResource(R.string.animation_cancel)) }
            },
        )
    }
}

@Composable
private fun OnionSwatch(color: Int) {
    Box(Modifier.size(20.dp).clip(CircleShape).background(Color(color)))
    Spacer(Modifier.width(8.dp))
}

@Composable
private fun FrameRangeSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    valueText: String,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(valueText, style = MaterialTheme.typography.labelSmall)
        }
        Slider(
            modifier = Modifier.semantics { contentDescription = label },
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            steps = (range.endInclusive.toInt() - 1).coerceAtLeast(0),
        )
    }
}
