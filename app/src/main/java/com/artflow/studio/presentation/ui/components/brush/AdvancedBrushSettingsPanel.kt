package com.artflow.studio.presentation.ui.components.brush

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.artflow.studio.domain.model.brush.BrushParams

/**
 * Advanced brush settings panel for configuring detailed brush parameters
 * Implements Phase 9: Advanced Brush Parameters
 */
@Composable
fun AdvancedBrushSettingsPanel(
    brushParams: BrushParams,
    onBrushParamsChanged: (BrushParams) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Brush Preview Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Brush Preview",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                BrushPreviewWidget(
                    brushParams = brushParams,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .background(
                            Color(0xFF2D2D2D),
                            RoundedCornerShape(8.dp)
                        )
                )
            }
        }

        // Stroke Dynamics Section
        BrushSettingsSection(title = "Stroke Dynamics") {
            // Spacing Control
            LabeledSlider(
                label = "Spacing",
                value = brushParams.spacing,
                onValueChange = { onBrushParamsChanged(brushParams.copy(spacing = it)) },
                valueRange = 0.01f..1f,
                valueDisplay = "%.0f%%".format(brushParams.spacing * 100)
            )
            
            // Smoothing Control
            LabeledSlider(
                label = "Smoothing",
                value = brushParams.smoothing,
                onValueChange = { onBrushParamsChanged(brushParams.copy(smoothing = it)) },
                valueRange = 0f..1f,
                valueDisplay = "%.0f%%".format(brushParams.smoothing * 100)
            )
            
            // Flow Control
            LabeledSlider(
                label = "Flow",
                value = brushParams.flow,
                onValueChange = { onBrushParamsChanged(brushParams.copy(flow = it)) },
                valueRange = 0.01f..1f,
                valueDisplay = "%.0f%%".format(brushParams.flow * 100)
            )
        }

        // Tapering Section
        BrushSettingsSection(title = "Tapering") {
            // Start Taper
            LabeledSlider(
                label = "Start Taper",
                value = brushParams.taperStart,
                onValueChange = { onBrushParamsChanged(brushParams.copy(taperStart = it)) },
                valueRange = 0f..1f,
                valueDisplay = "%.0f%%".format(brushParams.taperStart * 100)
            )
            
            // End Taper
            LabeledSlider(
                label = "End Taper",
                value = brushParams.taperEnd,
                onValueChange = { onBrushParamsChanged(brushParams.copy(taperEnd = it)) },
                valueRange = 0f..1f,
                valueDisplay = "%.0f%%".format(brushParams.taperEnd * 100)
            )
        }

        // Pressure Dynamics Section
        BrushSettingsSection(title = "Pressure Dynamics") {
            // Pressure to Size
            LabeledSlider(
                label = "Pressure → Size",
                value = brushParams.pressureToSize,
                onValueChange = { onBrushParamsChanged(brushParams.copy(pressureToSize = it)) },
                valueRange = 0f..1f,
                valueDisplay = "%.0f%%".format(brushParams.pressureToSize * 100)
            )
            
            // Pressure to Opacity
            LabeledSlider(
                label = "Pressure → Opacity",
                value = brushParams.pressureToOpacity,
                onValueChange = { onBrushParamsChanged(brushParams.copy(pressureToOpacity = it)) },
                valueRange = 0f..1f,
                valueDisplay = "%.0f%%".format(brushParams.pressureToOpacity * 100)
            )
            
            // Pressure Curve Selection
            PressureCurveSelector(
                selectedCurve = brushParams.pressureCurve,
                onCurveSelected = { onBrushParamsChanged(brushParams.copy(pressureCurve = it)) }
            )
        }

        // Scatter & Count Section
        BrushSettingsSection(title = "Scatter & Count") {
            // Scatter
            LabeledSlider(
                label = "Scatter",
                value = brushParams.scatter,
                onValueChange = { onBrushParamsChanged(brushParams.copy(scatter = it)) },
                valueRange = 0f..2f,
                valueDisplay = "%.0f%%".format(brushParams.scatter * 100)
            )
            
            // Count
            LabeledSlider(
                label = "Count",
                value = brushParams.count.toFloat(),
                onValueChange = { onBrushParamsChanged(brushParams.copy(count = it.toInt())) },
                valueRange = 1f..5f,
                valueDisplay = "%d".format(brushParams.count),
                isInteger = true
            )
        }

        // Rotation Section
        BrushSettingsSection(title = "Rotation") {
            // Brush Rotation
            LabeledSlider(
                label = "Rotation",
                value = brushParams.rotation,
                onValueChange = { onBrushParamsChanged(brushParams.copy(rotation = it)) },
                valueRange = 0f..360f,
                valueDisplay = "%.0f°".format(brushParams.rotation),
                isInteger = true
            )
        }

        // Wet Mix Section (for watercolor/oil brushes)
        BrushSettingsSection(title = "Wet Paint") {
            // Wet Mix
            LabeledSlider(
                label = "Wet Mix",
                value = brushParams.wetMix,
                onValueChange = { onBrushParamsChanged(brushParams.copy(wetMix = it)) },
                valueRange = 0f..1f,
                valueDisplay = "%.0f%%".format(brushParams.wetMix * 100)
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
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            
            // Draw guide line
            drawLine(
                color = Color.Gray.copy(alpha = 0.3f),
                start = Offset(0f, canvasHeight / 2),
                end = Offset(canvasWidth, canvasHeight / 2),
                strokeWidth = 1f
            )
            
            // Simulate brush stroke with current parameters
            val effectiveSize = brushParams.size.coerceIn(5f, 50f)
            val strokeColor = Color(0xFFFF6B5C)
            
            // Draw multiple sample dabs to show spacing effect
            val numDabs = (canvasWidth / (effectiveSize * (brushParams.spacing + 0.5f))).toInt().coerceAtLeast(3)
            val spacing = canvasWidth / (numDabs + 1)
            
            for (i in 1..numDabs) {
                val x = i * spacing.toFloat()
                val y = canvasHeight / 2
                
                // Apply scatter
                val scatteredY = y + (Math.random().toFloat() - 0.5f) * brushParams.scatter * 30f
                
                // Calculate size with taper simulation (smaller at ends)
                val positionRatio = i.toFloat() / numDabs
                val taperFactor = if (positionRatio < 0.2f) {
                    positionRatio / 0.2f * (1f - brushParams.taperStart) + brushParams.taperStart
                } else if (positionRatio > 0.8f) {
                    (1f - positionRatio) / 0.2f * (1f - brushParams.taperEnd) + brushParams.taperEnd
                } else {
                    1f
                }
                
                val taperedSize = effectiveSize * taperFactor
                
                // Draw brush dab
                drawCircle(
                    color = strokeColor.copy(alpha = brushParams.opacity),
                    radius = taperedSize / 2,
                    center = Offset(x, scatteredY)
                )
            }
            
            // Draw continuous stroke overlay
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        strokeColor.copy(alpha = 0.3f),
                        strokeColor.copy(alpha = brushParams.opacity),
                        strokeColor.copy(alpha = 0.3f)
                    )
                ),
                start = Offset(20f, canvasHeight / 2),
                end = Offset(canvasWidth - 20f, canvasHeight / 2),
                strokeWidth = effectiveSize * 0.5f,
                cap = Stroke.Cap.Round
            )
        }
        
        // Label
        Text(
            text = "Sample Stroke",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
        )
    }
}

/**
 * Collapsible settings section
 */
@Composable
private fun BrushSettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Section Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.rotate(if (expanded) 0f else -90f)
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
 * Slider with label and value display
 */
@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    valueDisplay: String,
    isInteger: Boolean = false
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = valueDisplay,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Pressure curve selector with visual preview
 */
@Composable
private fun PressureCurveSelector(
    selectedCurve: BrushParams.PressureCurve,
    onCurveSelected: (BrushParams.PressureCurve) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Pressure Curve",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            BrushParams.PressureCurve.entries.forEach { curve ->
                FilterChip(
                    selected = selectedCurve == curve,
                    onClick = { onCurveSelected(curve) },
                    label = {
                        Text(
                            text = curve.name.replace("_", " ").lowercase()
                                .replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    modifier = Modifier.width(90.dp)
                )
            }
        }
        
        // Visual curve preview
        PressureCurvePreview(
            curve = selectedCurve,
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(8.dp)
                )
        )
    }
}

/**
 * Visual preview of pressure curve
 */
@Composable
private fun PressureCurvePreview(
    curve: BrushParams.PressureCurve,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
    ) {
        val width = size.width
        val height = size.height
        val padding = 20f
        
        // Draw axes
        drawLine(
            color = Color.Gray,
            start = Offset(padding, padding),
            end = Offset(padding, height - padding),
            strokeWidth = 1f
        )
        drawLine(
            color = Color.Gray,
            start = Offset(padding, height - padding),
            end = Offset(width - padding, height - padding),
            strokeWidth = 1f
        )
        
        // Draw pressure curve
        val points = mutableListOf<Offset>()
        val steps = 50
        
        for (i in 0..steps) {
            val input = i.toFloat() / steps // Input pressure (0-1)
            val output = when (curve) {
                BrushParams.PressureCurve.LINEAR -> input
                BrushParams.PressureCurve.EASE_IN -> input * input
                BrushParams.PressureCurve.EASE_OUT -> input * (2f - input)
                BrushParams.PressureCurve.EASE_IN_OUT -> {
                    if (input < 0.5f) 2f * input * input
                    else -1f + (4f - 2f * input) * input
                }
                BrushParams.PressureCurve.CUSTOM -> input // Simplified for custom
            }
            
            val x = padding + input * (width - 2 * padding)
            val y = height - padding - output * (height - 2 * padding)
            points.add(Offset(x, y))
        }
        
        // Draw curve path
        if (points.size > 1) {
            drawPoints(
                points = points,
                pointMode = androidx.compose.ui.graphics.drawscope.PointMode.Polygon,
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3f
            )
        }
    }
}
