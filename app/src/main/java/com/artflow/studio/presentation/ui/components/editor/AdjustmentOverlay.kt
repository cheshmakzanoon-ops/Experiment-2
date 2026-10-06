package com.artflow.studio.presentation.ui.components.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.artflow.studio.core.color.GradientMaps
import com.artflow.studio.core.pixels.AdjustmentProcessor
import com.artflow.studio.core.pixels.FractalNoise
import com.artflow.studio.core.pixels.LiveAdjustments
import com.artflow.studio.presentation.ui.viewmodel.AdjustmentSessionController
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Covers the canvas while an adjustment is open. Amount effects follow Procreate: slide a finger
 * left or right anywhere on the canvas (Motion Blur also takes the slide's direction). Colour
 * adjustments show their sliders in a bar at the bottom.
 */
@Composable
fun AdjustmentOverlay(
    state: AdjustmentSessionController.State,
    actions: AdjustmentOverlayActions,
    modifier: Modifier = Modifier,
) {
    val latest by rememberUpdatedState(state)
    val latestActions by rememberUpdatedState(actions)
    Box(modifier.fillMaxSize()) {
        if (state.pencil || state.kind.usesPoint) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { latestActions.onPaint(it, size.width.toFloat(), size.height.toFloat()) },
                        ) { change, _ ->
                            change.consume()
                            latestActions.onPaint(change.position, size.width.toFloat(), size.height.toFloat())
                        }
                    },
            )
        } else if (state.kind.slidesAmount) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(state.kind) {
                        var startAmount = 0f
                        var travel = Offset.Zero
                        detectDragGestures(
                            onDragStart = {
                                startAmount = latest.settings.amount
                                travel = Offset.Zero
                            },
                        ) { change, delta ->
                            change.consume()
                            travel += delta
                            if (latest.kind == LiveAdjustments.Kind.MOTION_BLUR) {
                                latestActions.onAngle(Math.toDegrees(atan2(travel.y, travel.x).toDouble()).toFloat())
                                latestActions.onAmount(hypot(travel.x, travel.y) / size.width)
                            } else {
                                latestActions.onAmount(startAmount + travel.x / size.width)
                            }
                        }
                    },
            )
        }
        Surface(
            shape = RoundedCornerShape(50),
            tonalElevation = 4.dp,
            modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
        ) {
            val text =
                if (state.kind == LiveAdjustments.Kind.RECOLOR) {
                    "${state.kind.displayName} — touch the colour to replace"
                } else if (state.kind.usesPoint) {
                    "${state.kind.displayName} — touch to place the focus point"
                } else if (state.pencil) {
                    "${state.kind.displayName} — paint where it applies"
                } else if (state.kind.slidesAmount) {
                    "${state.kind.displayName} ${(state.settings.amount * 100).toInt()}% — slide across the canvas"
                } else {
                    state.kind.displayName
                }
            Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 4.dp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).widthIn(max = 560.dp),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.kind == LiveAdjustments.Kind.GRADIENT_MAP) GradientRamps(state, actions.onParameters)
                if (state.kind == LiveAdjustments.Kind.CURVES) CurvesEditor(state, actions.onParameters)
                if (state.kind == LiveAdjustments.Kind.NOISE) NoiseOptions(state, actions.onParameter)
                if (state.kind == LiveAdjustments.Kind.HALFTONE) HalftoneOptions(state, actions.onParameter)
                if (state.kind == LiveAdjustments.Kind.BLOOM) BloomOptions(state, actions.onParameter)
                if (state.kind == LiveAdjustments.Kind.GLITCH) GlitchOptions(state, actions.onParameter)
                if (state.kind == LiveAdjustments.Kind.PERSPECTIVE_BLUR) PerspectiveOptions(state, actions.onParameter)
                if (state.kind.adjustmentType != null) ParameterSliders(state, actions.onParameter)
                if ((state.kind.slidesAmount && state.pencil) || state.kind.usesPoint) {
                    val label = if (state.kind == LiveAdjustments.Kind.RECOLOR) "Flood" else "Amount"
                    Text("$label ${(state.settings.amount * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                    Slider(value = state.settings.amount, onValueChange = actions.onAmount)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                    FilterChip(selected = !state.pencil, onClick = { actions.onPencil(false) }, label = { Text("Layer") })
                    FilterChip(selected = state.pencil, onClick = { actions.onPencil(true) }, label = { Text("Pencil") })
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = actions.onCancel) { Text("Cancel") }
                    Button(onClick = actions.onApply) { Text("Apply") }
                }
            }
        }
    }
}

@Composable
private fun ParameterSliders(
    state: AdjustmentSessionController.State,
    onParameter: (String, Float) -> Unit,
) {
    val type = state.kind.adjustmentType ?: return
    visibleParameters(state.kind).forEach { (key, label) ->
        val range = type.parameterRanges[key] ?: return@forEach
        val value = state.settings.parameters[key] ?: type.defaultParameters[key] ?: range.start
        Text("$label ${value.toInt()}", style = MaterialTheme.typography.labelMedium)
        Slider(value = value, onValueChange = { onParameter(key, it) }, valueRange = range)
    }
}

/**
 * Procreate-style Curves for Gamma (all channels), Red, Green or Blue; channel curves apply on top
 * of the Gamma curve. Drag a point to move it between its neighbours, tap the curve to add a point
 * and tap a point to remove it (two always stay).
 */
@Composable
private fun CurvesEditor(
    state: AdjustmentSessionController.State,
    onParameters: (Map<String, Float>) -> Unit,
) {
    var channel by remember { mutableStateOf("") }
    val parameters by rememberUpdatedState(state.settings.parameters)
    val tint =
        when (channel) {
            "red_" -> Color(0xFFE5484D)
            "green_" -> Color(0xFF30A46C)
            "blue_" -> Color(0xFF3E63DD)
            else -> MaterialTheme.colorScheme.onSurface
        }
    val identity = (0..4).map { i -> i * CURVE_STEP to i * CURVE_STEP }

    fun points(): List<Pair<Float, Float>> = AdjustmentProcessor.curvePoints(parameters, channel).ifEmpty { identity }

    fun write(points: List<Pair<Float, Float>>) = onParameters(AdjustmentProcessor.withCurvePoints(parameters, channel, points))

    fun lutOf(points: List<Pair<Float, Float>>) =
        AdjustmentProcessor.buildCurveLut(AdjustmentProcessor.withCurvePoints(parameters, channel, points), channel)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf("" to "Gamma", "red_" to "Red", "green_" to "Green", "blue_" to "Blue").forEach { (prefix, name) ->
            FilterChip(selected = channel == prefix, onClick = { channel = prefix }, label = { Text(name) })
        }
        TextButton(onClick = { write(identity) }) { Text("Reset") }
    }
    var dragging by remember { mutableStateOf(-1) }
    Canvas(
        Modifier
            .size(200.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            .semantics { contentDescription = "Curve" }
            .pointerInput(channel) {
                detectTapGestures { at ->
                    val current = points()
                    val reach = CURVE_TOUCH.dp.toPx()
                    val hit =
                        current.indexOfFirst { (x, y) ->
                            hypot(x / 255f * size.width - at.x, (1f - y / 255f) * size.height - at.y) <= reach
                        }
                    if (hit >= 0) {
                        if (current.size > 2) write(current.filterIndexed { i, _ -> i != hit })
                    } else if (current.size < AdjustmentProcessor.MAX_CURVE_POINTS) {
                        // A new point sits on the curve where it was tapped, so the curve keeps its shape.
                        val x = (at.x / size.width * 255f).coerceIn(0f, 255f)
                        val y = lutOf(current)[x.toInt()]
                        write((current + (x to y.toFloat())).sortedBy { it.first })
                    }
                }
            }.pointerInput(channel) {
                detectDragGestures(
                    onDragStart = { at ->
                        // Grab the point nearest the touch.
                        dragging =
                            points().indices.minByOrNull { i ->
                                val (x, y) = points()[i]
                                hypot(x / 255f * size.width - at.x, (1f - y / 255f) * size.height - at.y)
                            } ?: -1
                    },
                    onDragEnd = { dragging = -1 },
                ) { change, _ ->
                    change.consume()
                    val current = points()
                    val i = dragging.takeIf { it in current.indices } ?: return@detectDragGestures
                    // Points keep their order: each moves only between its neighbours.
                    val low = current.getOrNull(i - 1)?.first?.plus(1f) ?: 0f
                    val high = current.getOrNull(i + 1)?.first?.minus(1f) ?: 255f
                    val x = (change.position.x / size.width * 255f).coerceIn(low, maxOf(low, high))
                    val y = ((1f - change.position.y / size.height) * 255f).coerceIn(0f, 255f)
                    write(current.mapIndexed { index, point -> if (index == i) x to y else point })
                }
            },
    ) {
        val grid = Color.Gray.copy(alpha = 0.35f)
        state.histogram?.let { bins ->
            // The layer's brightness spread, scaled to its tallest bar, behind the curve.
            val tallest = bins.maxOrNull()?.takeIf { it > 0 } ?: return@let
            val bar = size.width / bins.size
            bins.forEachIndexed { i, count ->
                val barHeight = count.toFloat() / tallest * size.height
                drawRect(grid, Offset(i * bar, size.height - barHeight), Size(bar, barHeight))
            }
        }
        for (k in 1..3) {
            drawLine(grid, Offset(size.width * k / 4f, 0f), Offset(size.width * k / 4f, size.height))
            drawLine(grid, Offset(0f, size.height * k / 4f), Offset(size.width, size.height * k / 4f))
        }
        val shown = points()
        val lut = lutOf(shown)
        val path = Path()
        lut.forEachIndexed { x, y ->
            val px = x / 255f * size.width
            val py = (1f - y / 255f) * size.height
            if (x == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, tint, style = Stroke(width = 2.dp.toPx()))
        shown.forEach { (x, y) ->
            drawCircle(tint, radius = 5.dp.toPx(), center = Offset(x / 255f * size.width, (1f - y / 255f) * size.height))
        }
    }
}

/** Noise type (Grain, Clouds, Billows, Ridges) and, for the fractal types, their size. */
@Composable
private fun NoiseOptions(
    state: AdjustmentSessionController.State,
    onParameter: (String, Float) -> Unit,
) {
    val parameters = state.settings.parameters
    val type = FractalNoise.Type.entries.getOrElse(parameters[LiveAdjustments.NOISE_TYPE]?.toInt() ?: 0) { FractalNoise.Type.GRAIN }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FractalNoise.Type.entries.forEach { entry ->
            FilterChip(
                selected = type == entry,
                onClick = { onParameter(LiveAdjustments.NOISE_TYPE, entry.ordinal.toFloat()) },
                label = { Text(entry.displayName) },
            )
        }
    }
    if (type != FractalNoise.Type.GRAIN) {
        val scale = parameters[LiveAdjustments.NOISE_SCALE] ?: LiveAdjustments.DEFAULT_NOISE_SCALE
        Text("Size ${scale.toInt()} px", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = scale,
            onValueChange = { onParameter(LiveAdjustments.NOISE_SCALE, it) },
            valueRange = FractalNoise.MIN_SCALE..FractalNoise.MAX_SCALE,
            modifier = Modifier.semantics { contentDescription = "Noise size" },
        )
    }
}

/** Halftone style: dots in each area's colour, CMY screen print, or black newspaper dots. */
@Composable
private fun HalftoneOptions(
    state: AdjustmentSessionController.State,
    onParameter: (String, Float) -> Unit,
) {
    val style = LiveAdjustments.halftoneStyle(state.settings.parameters)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LiveAdjustments.HalftoneStyle.entries.forEach { entry ->
            FilterChip(
                selected = style == entry,
                onClick = { onParameter(LiveAdjustments.HALFTONE_STYLE, entry.ordinal.toFloat()) },
                label = { Text(entry.displayName) },
            )
        }
    }
}

/** Bloom's Transition (where the glow begins), Size and Burn, as in Procreate. */
@Composable
private fun BloomOptions(
    state: AdjustmentSessionController.State,
    onParameter: (String, Float) -> Unit,
) {
    val parameters = state.settings.parameters
    listOf(
        Triple("Transition", LiveAdjustments.BLOOM_TRANSITION, LiveAdjustments.DEFAULT_BLOOM_TRANSITION),
        Triple("Size", LiveAdjustments.BLOOM_SIZE, 1f),
        Triple("Burn", LiveAdjustments.BLOOM_BURN, 0f),
    ).forEach { (label, key, default) ->
        val value = parameters[key] ?: default
        Text("$label ${(value * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = value,
            onValueChange = { onParameter(key, it) },
            modifier = Modifier.semantics { contentDescription = "Bloom ${label.lowercase()}" },
        )
    }
}

/** Glitch style: Signal, Artifact, Wave or Diverge. */
@Composable
private fun GlitchOptions(
    state: AdjustmentSessionController.State,
    onParameter: (String, Float) -> Unit,
) {
    val style = LiveAdjustments.glitchStyle(state.settings.parameters)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LiveAdjustments.GlitchStyle.entries.forEach { entry ->
            FilterChip(
                selected = style == entry,
                onClick = { onParameter(LiveAdjustments.GLITCH_STYLE, entry.ordinal.toFloat()) },
                label = { Text(entry.displayName) },
            )
        }
    }
}

/** Perspective Blur: Positional or Directional, with the Directional mode's angle. */
@Composable
private fun PerspectiveOptions(
    state: AdjustmentSessionController.State,
    onParameter: (String, Float) -> Unit,
) {
    val parameters = state.settings.parameters
    val directional = (parameters[LiveAdjustments.PERSPECTIVE_DIRECTIONAL] ?: 0f) >= 0.5f
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Positional" to false, "Directional" to true).forEach { (label, value) ->
            FilterChip(
                selected = directional == value,
                onClick = { onParameter(LiveAdjustments.PERSPECTIVE_DIRECTIONAL, if (value) 1f else 0f) },
                label = { Text(label) },
            )
        }
    }
    if (directional) {
        val angle = parameters[LiveAdjustments.PERSPECTIVE_ANGLE] ?: 0f
        Text("Direction ${angle.toInt()}°", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = angle,
            onValueChange = { onParameter(LiveAdjustments.PERSPECTIVE_ANGLE, it) },
            valueRange = 0f..360f,
            modifier = Modifier.semantics { contentDescription = "Blur direction" },
        )
    }
}

/** Gradient Map ramps, each chip showing its colours from shadows to highlights. */
@Composable
private fun GradientRamps(
    state: AdjustmentSessionController.State,
    onParameters: (Map<String, Float>) -> Unit,
) {
    val current = GradientMaps.fromParameters(state.settings.parameters)
    Text("Gradient", style = MaterialTheme.typography.labelMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GradientMaps.PRESETS.forEach { ramp ->
            val colors = ramp.stops.map { Color(0xFF000000.toInt() or it.rgb) }
            FilterChip(
                selected = current == ramp.stops,
                onClick = { onParameters(GradientMaps.toParameters(ramp)) },
                label = { Text(ramp.name) },
                leadingIcon = {
                    Box(Modifier.size(width = 28.dp, height = 14.dp).background(Brush.horizontalGradient(colors), RoundedCornerShape(3.dp)))
                },
            )
        }
        // Reverse swaps shadows and highlights in whichever ramp is showing.
        if (current != null) {
            val reversed = GradientMaps.Ramp("Reversed", current.map { GradientMaps.Stop(1f - it.position, it.rgb) }.reversed())
            AssistChip(onClick = { onParameters(GradientMaps.toParameters(reversed)) }, label = { Text("Reverse") })
        }
    }
}

private const val CURVE_STEP = 63.75f
private const val CURVE_TOUCH = 14

/** Parameter keys shown for each colour adjustment, with friendly labels. */
private fun visibleParameters(kind: LiveAdjustments.Kind): List<Pair<String, String>> =
    when (kind) {
        LiveAdjustments.Kind.HUE_SATURATION_BRIGHTNESS ->
            listOf("hue" to "Hue", "saturation" to "Saturation", "lightness" to "Brightness")
        LiveAdjustments.Kind.COLOR_BALANCE ->
            listOf("cyan_red" to "Cyan ↔ Red", "magenta_green" to "Magenta ↔ Green", "yellow_blue" to "Yellow ↔ Blue")
        else -> emptyList()
    }
