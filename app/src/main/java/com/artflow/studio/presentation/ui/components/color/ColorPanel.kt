package com.artflow.studio.presentation.ui.components.color

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.artflow.studio.core.color.ColorHarmony
import com.artflow.studio.core.color.Palette
import com.artflow.studio.core.color.PaletteLibrary
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Fraction of the ring radius occupied by the saturation/brightness square. */
private const val SQUARE_FRACTION = 0.66f

/**
 * Colour picker (Phases 31-32).
 *
 * Wheel, channel sliders, hex entry, harmonies, recents and palettes all write through a single
 * `onColorSelected` callback, so the editor has exactly one place that reacts to a colour change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorPanel(
    color: Int,
    recentColors: List<Int>,
    palettes: List<Palette>,
    onColorSelected: (Int) -> Unit,
    onClearRecents: () -> Unit,
    onSavePalette: (String, List<Int>) -> Unit,
    onRemovePalette: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var mode by remember { mutableStateOf(ColorHarmony.ColorMode.HSV) }
    var harmony by remember { mutableStateOf(ColorHarmony.Harmony.COMPLEMENTARY) }
    var palettePickerVisible by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color(color))
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("Colour", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${ColorHarmony.nameOf(color)} · ${ColorHarmony.toHex(color)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { palettePickerVisible = true }) { Text("Palettes") }
        }

        ColorWheel(color = color, onColorSelected = onColorSelected)

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ColorHarmony.ColorMode.entries.forEach { entry ->
                FilterChip(
                    selected = mode == entry,
                    onClick = { mode = entry },
                    label = { Text(entry.displayName, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        val channels = ColorHarmony.formatChannels(color, mode)
        channels.forEachIndexed { index, (label, value) ->
            val range = ColorHarmony.channelRange(mode, index)
            ChannelSlider(
                label = label,
                value = value,
                range = range,
                onChange = { newValue ->
                    onColorSelected(ColorHarmony.withChannel(color, mode, index, newValue))
                }
            )
        }

        HexField(color = color, onColorSelected = onColorSelected)

        SectionLabel("Harmony")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ColorHarmony.Harmony.entries.toList()) { entry ->
                FilterChip(
                    selected = harmony == entry,
                    onClick = { harmony = entry },
                    label = { Text(entry.displayName, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ColorHarmony.harmony(color, harmony).drop(1)) { swatch ->
                Swatch(color = swatch, onClick = { onColorSelected(swatch) })
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ColorHarmony.tintsAndShades(color)) { swatch ->
                Swatch(color = swatch, onClick = { onColorSelected(swatch) })
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionLabel("Recent")
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
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recentColors) { swatch ->
                    Swatch(color = swatch, onClick = { onColorSelected(swatch) })
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

        if (palettePickerVisible) {
            PalettePickerDialog(
                palettes = palettes,
                currentColor = color,
                onDismiss = { palettePickerVisible = false },
                onColorSelected = {
                    onColorSelected(it)
                    palettePickerVisible = false
                },
                onRemovePalette = onRemovePalette
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ChannelSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(34.dp), style = MaterialTheme.typography.labelMedium)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value.roundToInt().toString(),
            modifier = Modifier.width(40.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun HexField(color: Int, onColorSelected: (Int) -> Unit) {
    var text by remember(color) { mutableStateOf(ColorHarmony.toHex(color).removePrefix("#")) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.take(8) },
        label = { Text("Hex") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            ColorHarmony.parseHex(text)?.let(onColorSelected)
        }),
        trailingIcon = {
            TextButton(onClick = { ColorHarmony.parseHex(text)?.let(onColorSelected) }) { Text("Apply") }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun Swatch(color: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(color))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    )
}

/** Hue ring with a saturation/brightness square; dragging anywhere picks a colour. */
@Composable
private fun ColorWheel(color: Int, onColorSelected: (Int) -> Unit) {
    val hsv = remember(color) { argbToHsv(color) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .pointerInput(hsv[0], hsv[1], hsv[2]) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = minOf(size.width, size.height) / 2f - 12f
                    if (radius <= 0f) return@pointerInput
                    detectDragGestures(
                        onDragStart = { offset -> onColorSelected(pickPoint(offset, center, radius, hsv)) },
                        onDrag = { change, _ -> onColorSelected(pickPoint(change.position, center, radius, hsv)) }
                    )
                }
                .pointerInput(hsv[0], hsv[1], hsv[2]) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = minOf(size.width, size.height) / 2f - 12f
                    if (radius <= 0f) return@pointerInput
                    detectTapGestures { offset -> onColorSelected(pickPoint(offset, center, radius, hsv)) }
                }
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = minOf(size.width, size.height) / 2f - 12f
            if (radius <= 0f) return@Canvas

            // Hue ring, drawn as short arcs so it stays crisp at any size.
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
                    style = Stroke(width = radius * 0.22f)
                )
            }

            // Saturation / brightness square inside the ring.
            val inner = radius * SQUARE_FRACTION
            val topLeft = Offset(center.x - inner, center.y - inner)
            val squareSize = Size(inner * 2, inner * 2)
            drawRect(
                brush = Brush.horizontalGradient(
                    listOf(Color.White, Color(ColorHarmony.fromHsv(hsv[0], 1f, 1f)))
                ),
                topLeft = topLeft,
                size = squareSize
            )
            drawRect(
                brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)),
                topLeft = topLeft,
                size = squareSize
            )

            // Hue marker on the ring.
            val angle = Math.toRadians(hsv[0].toDouble())
            val marker = Offset(
                center.x + cos(angle).toFloat() * radius,
                center.y + sin(angle).toFloat() * radius
            )
            drawCircle(Color.White, radius = 9f, center = marker)
            drawCircle(Color.Black, radius = 9f, center = marker, style = Stroke(2f))

            // Saturation / brightness marker in the square.
            val saturationPoint = Offset(
                topLeft.x + hsv[1] * squareSize.width,
                topLeft.y + (1f - hsv[2]) * squareSize.height
            )
            drawCircle(Color.White, radius = 7f, center = saturationPoint)
            drawCircle(Color.Black, radius = 7f, center = saturationPoint, style = Stroke(2f))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Brightness", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(72.dp))
            Slider(
                value = hsv[2],
                onValueChange = { onColorSelected(ColorHarmony.fromHsv(hsv[0], hsv[1], it)) },
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** Maps a wheel touch to a hue (ring) or a saturation/brightness pair (square). */
private fun pickPoint(offset: Offset, center: Offset, radius: Float, current: FloatArray): Int {
    val inner = radius * SQUARE_FRACTION
    val dx = offset.x - center.x
    val dy = offset.y - center.y
    val distance = hypot(dx, dy)
    return if (distance > inner) {
        var hue = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (hue < 0f) hue += 360f
        ColorHarmony.fromHsv(hue, current[1].coerceAtLeast(0.15f), current[2])
    } else {
        val saturation = (dx / inner + 1f) / 2f
        val brightness = 1f - (dy / inner + 1f) / 2f
        ColorHarmony.fromHsv(current[0], saturation.coerceIn(0f, 1f), brightness.coerceIn(0f, 1f))
    }
}

/** Platform hue conversion; the pure core only needs the inverse direction. */
private fun argbToHsv(argb: Int): FloatArray {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb, hsv)
    return hsv
}

@Composable
private fun PalettePickerDialog(
    palettes: List<Palette>,
    currentColor: Int,
    onDismiss: () -> Unit,
    onColorSelected: (Int) -> Unit,
    onRemovePalette: (Long) -> Unit
) {
    val grouped = remember(palettes) { palettes.groupBy { it.category } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Palettes") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                grouped.forEach { (category, entries) ->
                    Text(
                        category.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    entries.forEach { palette ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(palette.name, style = MaterialTheme.typography.bodyMedium)
                                if (palette.category == "Custom") {
                                    IconButton(onClick = { onRemovePalette(palette.id) }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Remove ${palette.name}"
                                        )
                                    }
                                }
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(palette.colors) { entry ->
                                    val closest = ColorHarmony.distance(entry, currentColor) < 24f
                                    Box(
                                        modifier = Modifier
                                            .size(if (closest) 40.dp else 32.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(entry))
                                            .clickable { onColorSelected(entry) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

/** Small helper used by the brush sheet: a row of the built-in palettes. */
@Composable
fun BuiltInPaletteRow(onColorSelected: (Int) -> Unit, modifier: Modifier = Modifier) {
    val palette = PaletteLibrary.BUILT_IN.firstOrNull()
    LazyRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(palette?.colors ?: emptyList()) { entry ->
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(entry))
                    .clickable { onColorSelected(entry) }
            )
        }
    }
}

/** Converts an ARGB int to a Compose colour; kept here so UI files share one conversion. */
fun Int.toComposeColor(): Color = Color(this)
