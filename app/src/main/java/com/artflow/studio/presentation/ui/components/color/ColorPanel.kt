package com.artflow.studio.presentation.ui.components.color

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.artflow.studio.core.color.ColorHarmony
import com.artflow.studio.core.color.Palette
import com.artflow.studio.core.color.PaletteLibrary
import kotlin.math.roundToInt

/**
 * Colour picker (Phases 31-32).
 *
 * Procreate's Disc, Classic, Harmony, Value and Palettes modes as tabs, with the colour history
 * below. Everything writes through a single `onColorSelected` callback, so the editor has exactly
 * one place that reacts to a colour change.
 */
@Composable
fun ColorPanel(
    color: Int,
    recentColors: List<Int>,
    palettes: List<Palette>,
    onColorSelected: (Int) -> Unit,
    onClearRecents: () -> Unit,
    onSavePalette: (String, List<Int>) -> Unit,
    onRemovePalette: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var mode by remember { mutableStateOf(ColorHarmony.ColorMode.HSV) }
    var harmony by remember { mutableStateOf(ColorHarmony.Harmony.COMPLEMENTARY) }
    var tab by rememberSaveable { mutableStateOf(ColorTab.DISC) }
    // The colour when the panel opened, like Procreate's secondary swatch: tap to go back to it.
    val previous = remember { color }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Colours", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${ColorHarmony.nameOf(color)} · ${ColorHarmony.toHex(color)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Swatch(color = previous, onClick = { onColorSelected(previous) }, description = "Previous colour")
            Box(
                modifier =
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(color))
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            )
        }

        when (tab) {
            ColorTab.DISC -> ColorWheel(color = color, onColorSelected = onColorSelected)
            ColorTab.CLASSIC -> ClassicPicker(color = color, onColorSelected = onColorSelected)
            ColorTab.HARMONY -> HarmonyTab(color, harmony, { harmony = it }, onColorSelected)
            ColorTab.VALUE -> ValueTab(color, mode, { mode = it }, onColorSelected)
            ColorTab.PALETTES -> PaletteList(palettes, color, onColorSelected, onRemovePalette)
        }

        TabRow(selectedTabIndex = tab.ordinal) {
            ColorTab.entries.forEach { entry ->
                Tab(selected = tab == entry, onClick = { tab = entry }, text = { Text(entry.label, maxLines = 1) })
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("History")
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    val name = "My palette"
                    onSavePalette(name, recentColors.take(16))
                }) { Text("Save as palette", style = MaterialTheme.typography.labelSmall) }
                IconButton(onClick = onClearRecents) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear recent colours")
                }
            }
        }
        if (recentColors.isEmpty()) {
            Text(
                "Colours you use will show up here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recentColors) { swatch ->
                    Swatch(color = swatch, onClick = { onColorSelected(swatch) })
                }
            }
        }
    }
}

/** Procreate's colour modes, as tabs. */
private enum class ColorTab(
    val label: String,
) {
    DISC("Disc"),
    CLASSIC("Classic"),
    HARMONY("Harmony"),
    VALUE("Value"),
    PALETTES("Palettes"),
}

@Composable
private fun HarmonyTab(
    color: Int,
    harmony: ColorHarmony.Harmony,
    onHarmony: (ColorHarmony.Harmony) -> Unit,
    onColorSelected: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ColorWheel(color = color, onColorSelected = onColorSelected)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ColorHarmony.Harmony.entries.toList()) { entry ->
                FilterChip(
                    selected = harmony == entry,
                    onClick = { onHarmony(entry) },
                    label = { Text(entry.displayName, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        Text(harmony.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ColorHarmony.harmony(color, harmony)) { swatch ->
                Swatch(color = swatch, onClick = { onColorSelected(swatch) })
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ColorHarmony.tintsAndShades(color)) { swatch ->
                Swatch(color = swatch, onClick = { onColorSelected(swatch) })
            }
        }
    }
}

@Composable
private fun ValueTab(
    color: Int,
    mode: ColorHarmony.ColorMode,
    onMode: (ColorHarmony.ColorMode) -> Unit,
    onColorSelected: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ColorHarmony.ColorMode.entries.forEach { entry ->
                FilterChip(
                    selected = mode == entry,
                    onClick = { onMode(entry) },
                    label = { Text(entry.displayName, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        ColorHarmony.formatChannels(color, mode).forEachIndexed { index, (label, value) ->
            ChannelSlider(
                label = label,
                value = value,
                range = ColorHarmony.channelRange(mode, index),
                onChange = { newValue -> onColorSelected(ColorHarmony.withChannel(color, mode, index, newValue)) },
            )
        }
        HexField(color = color, onColorSelected = onColorSelected)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ChannelSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(34.dp), style = MaterialTheme.typography.labelMedium)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value.roundToInt().toString(),
            modifier = Modifier.width(40.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun HexField(
    color: Int,
    onColorSelected: (Int) -> Unit,
) {
    var text by remember(color) { mutableStateOf(ColorHarmony.toHex(color).removePrefix("#")) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.take(8) },
        label = { Text("Hex") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        keyboardActions =
            KeyboardActions(onDone = {
                ColorHarmony.parseHex(text)?.let(onColorSelected)
            }),
        trailingIcon = {
            TextButton(onClick = { ColorHarmony.parseHex(text)?.let(onColorSelected) }) { Text("Apply") }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Swatch(
    color: Int,
    onClick: () -> Unit,
    description: String? = null,
) {
    Box(
        modifier =
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(color))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
    )
}

@Composable
private fun PaletteList(
    palettes: List<Palette>,
    currentColor: Int,
    onColorSelected: (Int) -> Unit,
    onRemovePalette: (Long) -> Unit,
) {
    val grouped = remember(palettes) { palettes.groupBy { it.category } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        grouped.forEach { (category, entries) ->
            SectionLabel(category)
            entries.forEach { palette ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(palette.name, style = MaterialTheme.typography.bodyMedium)
                        if (palette.category == "Custom") {
                            IconButton(onClick = { onRemovePalette(palette.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove ${palette.name}")
                            }
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(palette.colors) { entry ->
                            val closest = ColorHarmony.distance(entry, currentColor) < 24f
                            Box(
                                modifier =
                                    Modifier
                                        .size(if (closest) 40.dp else 32.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(entry))
                                        .clickable { onColorSelected(entry) },
                            )
                        }
                    }
                }
            }
        }
        SectionLabel("Named colours")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ColorHarmony.NAMED_COLORS) { (name, value) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Swatch(color = value, onClick = { onColorSelected(value) })
                    Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}

/** Small helper used by the brush sheet: a row of the built-in palettes. */
@Composable
fun BuiltInPaletteRow(
    onColorSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = PaletteLibrary.BUILT_IN.firstOrNull()
    LazyRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(palette?.colors ?: emptyList()) { entry ->
            Box(
                modifier =
                    Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(entry))
                        .clickable { onColorSelected(entry) },
            )
        }
    }
}

/** Converts an ARGB int to a Compose colour; kept here so UI files share one conversion. */
fun Int.toComposeColor(): Color = Color(this)
