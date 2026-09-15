package com.artflow.studio.presentation.ui.components.layer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artflow.studio.domain.model.layer.AdjustmentLayer
import com.artflow.studio.domain.model.layer.AdjustmentType

/**
 * Composable UI component for displaying and managing adjustment layers
 * Implements Phase 25: Adjustment Layers UI Panel
 */
@Composable
fun AdjustmentLayerPanel(
    adjustmentLayers: List<AdjustmentLayer>,
    selectedLayerId: Long?,
    onLayerSelected: (Long) -> Unit,
    onAddAdjustmentLayer: (AdjustmentType) -> Unit,
    onRemoveLayer: (Long) -> Unit,
    onToggleVisibility: (Long) -> Unit,
    onToggleEnabled: (Long) -> Unit,
    onOpacityChanged: (Long, Float) -> Unit,
    onParameterChanged: (Long, String, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddMenu by remember { mutableStateOf(false) }
    var expandedLayerId by remember { mutableStateOf<Long?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Adjustment Layers",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Box {
                IconButton(onClick = { showAddMenu = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Adjustment Layer")
                }
                
                DropdownMenu(
                    expanded = showAddMenu,
                    onDismissRequest = { showAddMenu = false }
                ) {
                    AdjustmentType.entries.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type.displayName) },
                            onClick = {
                                onAddAdjustmentLayer(type)
                                showAddMenu = false
                            },
                            leadingIcon = {
                                Icon(
                                    getAdjustmentIcon(type),
                                    contentDescription = null
                                )
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Adjustment layers list
        if (adjustmentLayers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No adjustment layers\nTap + to add one",
                    fontSize = 14.sp,
                    color = Color.Gray
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(
                    items = adjustmentLayers.sortedByDescending { it.index },
                    key = { it.id }
                ) { layer ->
                    AdjustmentLayerItem(
                        layer = layer,
                        isSelected = selectedLayerId == layer.id,
                        isExpanded = expandedLayerId == layer.id,
                        onSelect = { onLayerSelected(layer.id) },
                        onToggleVisibility = { onToggleVisibility(layer.id) },
                        onToggleEnabled = { onToggleEnabled(layer.id) },
                        onRemove = { onRemoveLayer(layer.id) },
                        onExpand = { 
                            expandedLayerId = if (expandedLayerId == layer.id) null else layer.id 
                        },
                        onOpacityChanged = { opacity -> onOpacityChanged(layer.id, opacity) },
                        onParameterChanged = { param, value -> onParameterChanged(layer.id, param, value) }
                    )
                }
            }
        }
    }
}

/**
 * Individual adjustment layer item composable
 */
@Composable
private fun AdjustmentLayerItem(
    layer: AdjustmentLayer,
    isSelected: Boolean,
    isExpanded: Boolean,
    onSelect: () -> Unit,
    onToggleVisibility: () -> Unit,
    onToggleEnabled: () -> Unit,
    onRemove: () -> Unit,
    onExpand: () -> Unit,
    onOpacityChanged: (Float) -> Unit,
    onParameterChanged: (String, Float) -> Unit
) {
    var sliderValue by remember { mutableFloatStateOf(layer.opacity) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer 
                           else MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp)
        ) {
            // Main row with icon, name, and actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onExpand) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            tint = if (!layer.isEnabled) Color.Gray else Color.Unspecified
                        )
                    }
                    
                    Icon(
                        imageVector = getAdjustmentIcon(layer.adjustmentType),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = if (!layer.isEnabled) Color.Gray else MaterialTheme.colorScheme.primary
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Column {
                        Text(
                            text = layer.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (!layer.isEnabled) Color.Gray else Color.Unspecified
                        )
                        Text(
                            text = layer.adjustmentType.displayName,
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                }
                
                Row {
                    // Visibility toggle
                    IconButton(
                        onClick = onToggleVisibility,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (layer.isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (layer.isVisible) "Hide" else "Show",
                            tint = if (layer.isVisible) Color.Unspecified else Color.Gray
                        )
                    }
                    
                    // Enabled toggle
                    IconButton(
                        onClick = onToggleEnabled,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (layer.isEnabled) Icons.Default.CheckCircle else Icons.Default.Cancel,
                            contentDescription = if (layer.isEnabled) "Disable" else "Enable",
                            tint = if (layer.isEnabled) Color.Green else Color.Gray
                        )
                    }
                    
                    // Remove button
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Remove",
                            tint = Color.Red
                        )
                    }
                }
            }
            
            // Expanded parameters section
            if (isExpanded && layer.adjustmentType.hasParameters()) {
                Spacer(modifier = Modifier.height(8.dp))
                
                // Opacity slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Opacity", fontSize = 12.sp, modifier = Modifier.width(60.dp))
                    Slider(
                        value = sliderValue,
                        onValueChange = { 
                            sliderValue = it
                            onOpacityChanged(it)
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f)
                    )
                    Text("${(sliderValue * 100).toInt()}%", fontSize = 12.sp, modifier = Modifier.width(40.dp))
                }
                
                // Adjustment-specific parameters
                layer.adjustmentType.defaultParameters.keys.forEach { param ->
                    val currentValue = layer.getParameter(param, 0f)
                    val range = layer.adjustmentType.parameterRanges[param] ?: return@forEach
                    
                    ParameterSlider(
                        parameter = param,
                        value = currentValue,
                        range = range,
                        onValueChange = { newValue -> onParameterChanged(param, newValue) }
                    )
                }
            }
        }
    }
}

/**
 * Parameter slider composable for adjustment controls
 */
@Composable
private fun ParameterSlider(
    parameter: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    var sliderValue by remember { mutableFloatStateOf(value) }
    
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = parameter.replace("_", " ").replaceFirstChar { it.uppercase() },
            fontSize = 11.sp,
            modifier = Modifier.width(80.dp)
        )
        Slider(
            value = sliderValue,
            onValueChange = { 
                sliderValue = it
                onValueChange(it)
            },
            valueRange = range,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (range.endInclusive > 100f) sliderValue.toInt().toString() 
                   else sliderValue.toInt().toString(),
            fontSize = 11.sp,
            modifier = Modifier.width(40.dp)
        )
    }
}

/**
 * Get appropriate icon for adjustment type
 */
@Composable
private fun getAdjustmentIcon(type: AdjustmentType): androidx.compose.ui.graphics.vector.ImageVector {
    return when (type) {
        AdjustmentType.BRIGHTNESS_CONTRAST -> Icons.Default.BrightnessHigh
        AdjustmentType.HUE_SATURATION -> Icons.Default.ColorLens
        AdjustmentType.COLOR_BALANCE -> Icons.Default.Palette
        AdjustmentType.CURVES -> Icons.Default.TrendingUp
        AdjustmentType.LEVELS -> Icons.Default.BarChart
        AdjustmentType.INVERT -> Icons.Default.InvertColors
        AdjustmentType.POSTERIZE -> Icons.Default.FilterVintage
        AdjustmentType.SELECTIVE_COLOR -> Icons.Default.Gradient
        AdjustmentType.GRADIENT_MAP -> Icons.Default.Tune
    }
}
