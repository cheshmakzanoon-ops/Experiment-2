package com.artflow.studio.presentation.ui.components.brush

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artflow.studio.core.render.BrushTexture
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.PressureResponse

/**
 * Advanced brush settings panel for configuring detailed brush parameters
 * Implements Phase 9: Advanced Brush Parameters
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedBrushSettingsPanel(
    brushParams: BrushParams,
    onBrushParamsChanged: (BrushParams) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Brush Preview Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Brush Preview",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                BrushPreviewWidget(
                    brushParams = brushParams,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .background(
                                Color(0xFF2D2D2D),
                                RoundedCornerShape(8.dp),
                            ),
                )
            }
        }

        BrushProperties(brushParams, onBrushParamsChanged)

        // Stroke Dynamics Section
        BrushSettingsSection(title = "Stroke Dynamics") {
            // Spacing Control
            BrushParameterSlider(
                label = "Spacing",
                value = brushParams.spacing,
                onValueChange = { onBrushParamsChanged(brushParams.copy(spacing = it)) },
                valueRange = 0.01f..1f,
                valueDisplay = "%.0f%%".format(brushParams.spacing * 100),
            )

            // Smoothing Control
            BrushParameterSlider(
                label = "Smoothing",
                value = brushParams.smoothing,
                onValueChange = { onBrushParamsChanged(brushParams.copy(smoothing = it)) },
                valueRange = 0f..1f,
                valueDisplay = "%.0f%%".format(brushParams.smoothing * 100),
            )

            // Flow Control
            BrushParameterSlider(
                label = "Flow",
                value = brushParams.flow,
                onValueChange = { onBrushParamsChanged(brushParams.copy(flow = it)) },
                valueRange = 0.01f..1f,
                valueDisplay = "%.0f%%".format(brushParams.flow * 100),
            )
        }

        // Tapering Section
        BrushSettingsSection(title = "Tapering") {
            // Start Taper
            BrushParameterSlider(
                label = "Start Taper",
                value = brushParams.taperStart,
                onValueChange = { onBrushParamsChanged(brushParams.copy(taperStart = it)) },
                valueRange = 0f..1f,
                valueDisplay = "%.0f%%".format(brushParams.taperStart * 100),
            )

            // End Taper
            BrushParameterSlider(
                label = "End Taper",
                value = brushParams.taperEnd,
                onValueChange = { onBrushParamsChanged(brushParams.copy(taperEnd = it)) },
                valueRange = 0f..1f,
                valueDisplay = "%.0f%%".format(brushParams.taperEnd * 100),
            )
        }

        // Pressure Dynamics Section
        BrushSettingsSection(title = "Pressure Dynamics") {
            // Pressure to Size
            BrushParameterSlider(
                label = "Pressure → Size",
                value = brushParams.pressureToSize,
                onValueChange = { onBrushParamsChanged(brushParams.copy(pressureToSize = it)) },
                valueRange = 0f..1f,
                valueDisplay = "%.0f%%".format(brushParams.pressureToSize * 100),
            )

            // Pressure to Opacity
            BrushParameterSlider(
                label = "Pressure → Opacity",
                value = brushParams.pressureToOpacity,
                onValueChange = { onBrushParamsChanged(brushParams.copy(pressureToOpacity = it)) },
                valueRange = 0f..1f,
                valueDisplay = "%.0f%%".format(brushParams.pressureToOpacity * 100),
            )

            // Pressure Curve Selection
            PressureCurveSelector(
                brushParams = brushParams,
                onBrushParamsChanged = onBrushParamsChanged,
            )
        }

        BrushSettingsSection(title = "Brush Grain") {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("brush-grain-options"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !brushParams.blendTexture || brushParams.textureId == null,
                    onClick = { onBrushParamsChanged(brushParams.copy(textureId = null, blendTexture = false)) },
                    label = { Text("None") },
                )
                BrushTexture.Kind.entries.forEach { texture ->
                    FilterChip(
                        selected = brushParams.blendTexture && brushParams.textureId == texture.id,
                        onClick = { onBrushParamsChanged(brushParams.copy(textureId = texture.id, blendTexture = true)) },
                        label = { Text(texture.label) },
                    )
                }
            }
            if (brushParams.blendTexture && brushParams.textureId != null) {
                BrushParameterSlider(
                    label = "Grain scale",
                    value = brushParams.textureScale.coerceIn(0.25f, 8f),
                    onValueChange = { onBrushParamsChanged(brushParams.copy(textureScale = it)) },
                    valueRange = 0.25f..8f,
                    valueDisplay = "%.2f×".format(brushParams.textureScale),
                )
                BrushParameterSlider(
                    label = "Grain rotation",
                    value = brushParams.textureRotation.coerceIn(0f, 360f),
                    onValueChange = { onBrushParamsChanged(brushParams.copy(textureRotation = it)) },
                    valueRange = 0f..360f,
                    valueDisplay = "%.0f°".format(brushParams.textureRotation),
                )
            }
        }

        // Scatter & Count Section
        BrushSettingsSection(title = "Scatter & Count") {
            // Scatter
            BrushParameterSlider(
                label = "Scatter",
                value = brushParams.scatter,
                onValueChange = { onBrushParamsChanged(brushParams.copy(scatter = it)) },
                valueRange = 0f..2f,
                valueDisplay = "%.0f%%".format(brushParams.scatter * 100),
            )

            // Count
            BrushParameterSlider(
                label = "Count",
                value = brushParams.count.toFloat(),
                onValueChange = { onBrushParamsChanged(brushParams.copy(count = it.toInt())) },
                valueRange = 1f..5f,
                valueDisplay = "%d".format(brushParams.count),
                isInteger = true,
            )
        }

        // Jitter Section - Phase 9: Advanced Brush Parameters
        BrushSettingsSection(title = "Jitter & Randomization") {
            // Size Jitter
            BrushParameterSlider(
                label = "Size Jitter",
                value = brushParams.sizeJitter,
                onValueChange = { onBrushParamsChanged(brushParams.copy(sizeJitter = it)) },
                valueRange = 0f..1f,
                valueDisplay = "%.0f%%".format(brushParams.sizeJitter * 100),
            )

            // Opacity Jitter
            BrushParameterSlider(
                label = "Opacity Jitter",
                value = brushParams.opacityJitter,
                onValueChange = { onBrushParamsChanged(brushParams.copy(opacityJitter = it)) },
                valueRange = 0f..1f,
                valueDisplay = "%.0f%%".format(brushParams.opacityJitter * 100),
            )

            // Hue Jitter
            BrushParameterSlider(
                label = "Hue Jitter",
                value = brushParams.hueJitter,
                onValueChange = { onBrushParamsChanged(brushParams.copy(hueJitter = it)) },
                valueRange = 0f..1f,
                valueDisplay = "%.0f%%".format(brushParams.hueJitter * 100),
            )

            // Saturation Jitter
            BrushParameterSlider(
                label = "Saturation Jitter",
                value = brushParams.saturationJitter,
                onValueChange = { onBrushParamsChanged(brushParams.copy(saturationJitter = it)) },
                valueRange = 0f..1f,
                valueDisplay = "%.0f%%".format(brushParams.saturationJitter * 100),
            )

            // Brightness Jitter
            BrushParameterSlider(
                label = "Brightness Jitter",
                value = brushParams.brightnessJitter,
                onValueChange = { onBrushParamsChanged(brushParams.copy(brightnessJitter = it)) },
                valueRange = 0f..1f,
                valueDisplay = "%.0f%%".format(brushParams.brightnessJitter * 100),
            )
        }

        // Rotation Section
        BrushSettingsSection(title = "Rotation") {
            // Brush Rotation
            BrushParameterSlider(
                label = "Rotation",
                value = brushParams.rotation,
                onValueChange = { onBrushParamsChanged(brushParams.copy(rotation = it)) },
                valueRange = 0f..360f,
                valueDisplay = "%.0f°".format(brushParams.rotation),
                isInteger = true,
            )
        }

        // Wet Mix Section (for watercolor/oil brushes)
        BrushSettingsSection(title = "Wet Paint") {
            // Wet Mix
            BrushParameterSlider(
                label = "Wet Mix",
                value = brushParams.wetMix,
                onValueChange = { onBrushParamsChanged(brushParams.copy(wetMix = it)) },
                valueRange = 0f..1f,
                valueDisplay = "%.0f%%".format(brushParams.wetMix * 100),
            )
        }
    }
}

/**
 * Brush preview widget that renders sample strokes
 */
@Composable
private fun BrushPreviewWidget(
    brushParams: BrushParams,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        BrushSample(brushParams, Modifier.fillMaxSize().padding(8.dp))
    }
}

/**
 * Collapsible settings section
 */
@Composable
private fun BrushSettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Section Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.rotate(if (expanded) 0f else -90f),
                    )
                }
            }

            // Section Content
            if (expanded) {
                content()
            }
        }
    }
}

/**
 * Pressure curve selector with visual preview
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PressureCurveSelector(
    brushParams: BrushParams,
    onBrushParamsChanged: (BrushParams) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Pressure Curve",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BrushParams.PressureCurve.entries.forEach { curve ->
                FilterChip(
                    selected = brushParams.pressureCurve == curve,
                    onClick = { onBrushParamsChanged(brushParams.copy(pressureCurve = curve)) },
                    label = {
                        Text(
                            text =
                                curve.name
                                    .replace("_", " ")
                                    .lowercase()
                                    .replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    modifier = Modifier.width(90.dp),
                )
            }
        }

        if (brushParams.pressureCurve == BrushParams.PressureCurve.CUSTOM) {
            CustomPressureControls(brushParams, onBrushParamsChanged)
        }

        // Visual curve preview uses exactly the engine's pressure function.
        PressureCurvePreview(
            brushParams = brushParams,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp),
                    ),
        )
    }
}

/**
 * Visual preview of pressure curve
 */
@Composable
private fun PressureCurvePreview(
    brushParams: BrushParams,
    modifier: Modifier = Modifier,
) {
    // Resolved outside the draw lambda: MaterialTheme is @Composable and cannot be
    // read from inside DrawScope.
    val curveColor = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = modifier,
    ) {
        val width = size.width
        val height = size.height
        val padding = 20f

        // Draw axes
        drawLine(
            color = Color.Gray,
            start = Offset(padding, padding),
            end = Offset(padding, height - padding),
            strokeWidth = 1f,
        )
        drawLine(
            color = Color.Gray,
            start = Offset(padding, height - padding),
            end = Offset(width - padding, height - padding),
            strokeWidth = 1f,
        )

        // Draw pressure curve
        val points = mutableListOf<Offset>()
        val steps = 50

        for (i in 0..steps) {
            val input = i.toFloat() / steps // Input pressure (0-1)
            val output = brushParams.pressureResponse(input)

            val x = padding + input * (width - 2 * padding)
            val y = height - padding - output * (height - 2 * padding)
            points.add(Offset(x, y))
        }

        // Draw curve path
        if (points.size > 1) {
            drawPoints(
                points = points,
                pointMode = PointMode.Polygon,
                color = curveColor,
                strokeWidth = 3f,
            )
        }
    }
}

@Composable
private fun CustomPressureControls(
    brushParams: BrushParams,
    onBrushParamsChanged: (BrushParams) -> Unit,
) {
    val response = brushParams.customPressure
    BrushParameterSlider(
        label = "Output at 25% pressure",
        value = response.low,
        onValueChange = { value ->
            val curve = PressureResponse(value, maxOf(value, response.middle), maxOf(value, response.high))
            onBrushParamsChanged(brushParams.copy(customPressure = curve))
        },
        valueRange = 0f..1f,
        valueDisplay = "%.0f%%".format(response.low * 100),
    )
    BrushParameterSlider(
        label = "Output at 50% pressure",
        value = response.middle,
        onValueChange = { value ->
            val curve = PressureResponse(minOf(response.low, value), value, maxOf(value, response.high))
            onBrushParamsChanged(brushParams.copy(customPressure = curve))
        },
        valueRange = 0f..1f,
        valueDisplay = "%.0f%%".format(response.middle * 100),
    )
    BrushParameterSlider(
        label = "Output at 75% pressure",
        value = response.high,
        onValueChange = { value ->
            val curve = PressureResponse(minOf(response.low, value), minOf(response.middle, value), value)
            onBrushParamsChanged(brushParams.copy(customPressure = curve))
        },
        valueRange = 0f..1f,
        valueDisplay = "%.0f%%".format(response.high * 100),
    )
}

@Composable
private fun BrushProperties(
    brushParams: BrushParams,
    onBrushParamsChanged: (BrushParams) -> Unit,
) {
    BrushSettingsSection(title = "Brush Properties") {
        BrushParameterSlider(
            label = "Brush size",
            value = brushParams.size,
            onValueChange = { onBrushParamsChanged(brushParams.copy(size = it)) },
            valueRange = 1f..512f,
            valueDisplay = "%.1f px".format(brushParams.size),
        )
        BrushParameterSlider(
            label = "Brush opacity",
            value = brushParams.opacity,
            onValueChange = { onBrushParamsChanged(brushParams.copy(opacity = it)) },
            valueRange = 0.01f..1f,
            valueDisplay = "%.0f%%".format(brushParams.opacity * 100),
        )
    }
}
