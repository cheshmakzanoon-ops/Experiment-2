@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.artflow.studio.presentation.ui.components.shape

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.artflow.studio.domain.model.Color as ArtFlowColor
import com.artflow.studio.domain.model.shape.LineCap
import com.artflow.studio.domain.model.shape.ShapeType

/**
 * Shape Tools Panel for Phase 24: Shape Tools
 * Provides UI for creating and configuring vector shapes
 */
@Composable
fun ShapeToolsPanel(
    selectedShapeType: ShapeType?,
    onShapeTypeSelected: (ShapeType) -> Unit,
    onShapeCreated: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Text(
            text = "Shape Tools",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Shape type selector grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item {
                ShapeToolButton(
                    icon = Icons.Default.Rectangle,
                    label = "Rectangle",
                    isSelected = selectedShapeType == ShapeType.RECTANGLE,
                    onClick = { onShapeTypeSelected(ShapeType.RECTANGLE) }
                )
            }
            item {
                ShapeToolButton(
                    icon = Icons.Default.Circle,
                    label = "Ellipse",
                    isSelected = selectedShapeType == ShapeType.ELLIPSE,
                    onClick = { onShapeTypeSelected(ShapeType.ELLIPSE) }
                )
            }
            item {
                ShapeToolButton(
                    icon = Icons.Default.Pentagon,
                    label = "Polygon",
                    isSelected = selectedShapeType == ShapeType.POLYGON,
                    onClick = { onShapeTypeSelected(ShapeType.POLYGON) }
                )
            }
            item {
                ShapeToolButton(
                    icon = Icons.Default.Timeline,
                    label = "Line",
                    isSelected = selectedShapeType == ShapeType.LINE,
                    onClick = { onShapeTypeSelected(ShapeType.LINE) }
                )
            }
        }

        // Create button
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = onShapeCreated,
            enabled = selectedShapeType != null,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Create Shape")
        }
    }
}

/**
 * Individual shape tool button
 */
@Composable
private fun ShapeToolButton(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(70.dp)
            .height(80.dp)
            .background(
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = 2.dp,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.Transparent
                },
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isSelected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1
        )
    }
}

/**
 * Shape Properties Panel for configuring shape attributes
 */
@Composable
fun ShapePropertiesPanel(
    fillColor: ArtFlowColor?,
    strokeColor: ArtFlowColor,
    strokeWidth: Float,
    cornerRadius: Float,
    polygonSides: Int,
    lineCap: LineCap,
    isArrow: Boolean,
    onFillColorChanged: (ArtFlowColor?) -> Unit,
    onStrokeColorChanged: (ArtFlowColor) -> Unit,
    onStrokeWidthChanged: (Float) -> Unit,
    onCornerRadiusChanged: (Float) -> Unit,
    onPolygonSidesChanged: (Int) -> Unit,
    onLineCapChanged: (LineCap) -> Unit,
    onArrowToggled: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var expandedFill by remember { mutableStateOf(false) }
    var expandedStroke by remember { mutableStateOf(false) }
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = "Shape Properties",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Fill Color Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Fill Color",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Color preview box
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(
                                    color = fillColor?.toComposeColor() 
                                        ?: Color.Transparent,
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(4.dp)
                                )
                        )
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        TextButton(onClick = { expandedFill = true }) {
                            Text(if (fillColor == null) "None" else "Change")
                        }
                        
                        IconButton(onClick = { onFillColorChanged(null) }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove fill",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                
                if (fillColor != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // RGB sliders for fine-tuning
                    var red by remember { mutableFloatStateOf(fillColor.red / 255f) }
                    var green by remember { mutableFloatStateOf(fillColor.green / 255f) }
                    var blue by remember { mutableFloatStateOf(fillColor.blue / 255f) }
                    
                    SliderWithValue(
                        value = red,
                        onValueChange = { 
                            red = it
                            onFillColorChanged(
                                ArtFlowColor(
                                    red = (red * 255).toInt(),
                                    green = (green * 255).toInt(),
                                    blue = (blue * 255).toInt()
                                )
                            )
                        },
                        label = "R"
                    )
                    
                    SliderWithValue(
                        value = green,
                        onValueChange = {
                            green = it
                            onFillColorChanged(
                                ArtFlowColor(
                                    red = (red * 255).toInt(),
                                    green = (green * 255).toInt(),
                                    blue = (blue * 255).toInt()
                                )
                            )
                        },
                        label = "G"
                    )
                    
                    SliderWithValue(
                        value = blue,
                        onValueChange = {
                            blue = it
                            onFillColorChanged(
                                ArtFlowColor(
                                    red = (red * 255).toInt(),
                                    green = (green * 255).toInt(),
                                    blue = (blue * 255).toInt()
                                )
                            )
                        },
                        label = "B"
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Stroke Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Stroke",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                // Stroke color
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Color", style = MaterialTheme.typography.bodyMedium)
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(
                                    color = strokeColor.toComposeColor(),
                                    shape = RoundedCornerShape(4.dp)
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = { expandedStroke = true }) {
                            Text("Change")
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Stroke width slider
                SliderWithValue(
                    value = strokeWidth / 50f,
                    onValueChange = { onStrokeWidthChanged(it * 50f) },
                    label = "Width: ${strokeWidth.toInt()}px",
                    valueRange = 0.02f..1f
                )
            }
        }

        // Corner Radius (for rectangles)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Corner Radius",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                SliderWithValue(
                    value = cornerRadius / 100f,
                    onValueChange = { onCornerRadiusChanged(it * 100f) },
                    label = "Radius: ${cornerRadius.toInt()}dp",
                    valueRange = 0f..1f
                )
            }
        }

        // Polygon sides
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Polygon Sides",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { if (polygonSides > 3) onPolygonSidesChanged(polygonSides - 1) },
                        enabled = polygonSides > 3
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Decrease sides")
                    }
                    
                    Text(
                        text = "$polygonSides sides",
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    IconButton(
                        onClick = { if (polygonSides < 12) onPolygonSidesChanged(polygonSides + 1) },
                        enabled = polygonSides < 12
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Increase sides")
                    }
                }
                
                // Preview icons
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    (3..8).forEach { sides ->
                        Icon(
                            imageVector = Icons.Default.Pentagon,
                            contentDescription = "$sides sides",
                            modifier = Modifier
                                .size(24.dp)
                                .rotate(if (sides == 3) 180f else 0f),
                            tint = if (sides == polygonSides) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                }
            }
        }

        // Line options
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Line Options",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                // Line cap selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    LineCap.entries.forEach { cap ->
                        FilterChip(
                            selected = lineCap == cap,
                            onClick = { onLineCapChanged(cap) },
                            label = { Text(cap.name.lowercase().capitalize()) },
                            leadingIcon = if (lineCap == cap) {
                                {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            } else {
                                null
                            }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Arrow toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Arrow Head", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = isArrow,
                        onCheckedChange = onArrowToggled
                    )
                }
            }
        }
    }
}

/**
 * Helper composable for slider with value label
 */
@Composable
private fun SliderWithValue(
    value: Float,
    onValueChange: (Float) -> Unit,
    label: String,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}

/**
 * Convert ArtFlow Color to Compose Color
 */
private fun ArtFlowColor.toComposeColor(): Color {
    return Color(red, green, blue)
}

/**
 * Capitalize first letter of string
 */
private fun String.capitalize(): String {
    return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
