@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.artflow.studio.presentation.ui.components.selection

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.artflow.studio.core.selection.SelectionManager
import com.artflow.studio.domain.model.selection.MagicWandConfig
import com.artflow.studio.domain.model.selection.SelectionType

/**
 * Selection Tools Panel for Phase 13: Selection Tools
 * Provides UI for all selection tools and operations
 */
@Composable
fun SelectionToolsPanel(
    selectionManager: SelectionManager,
    modifier: Modifier = Modifier,
    onSelectionToolChanged: (SelectionTool) -> Unit = {}
) {
    var selectedTool by remember { mutableStateOf(SelectionTool.RECTANGLE) }
    var showMagicWandOptions by remember { mutableStateOf(false) }
    var magicWandTolerance by remember { mutableStateOf(32) }
    var featherRadius by remember { mutableStateOf(0f) }

    // Observe the live selection state so the operation buttons enable/disable correctly.
    val activeSelection by selectionManager.selection.collectAsState()
    val hasSelection = activeSelection != null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Selection Tools Row
        Text(
            text = "Selection Tools",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item {
                SelectionToolButton(
                    icon = Icons.Default.Square,
                    label = "Rectangle",
                    selected = selectedTool == SelectionTool.RECTANGLE,
                    onClick = {
                        selectedTool = SelectionTool.RECTANGLE
                        onSelectionToolChanged(SelectionTool.RECTANGLE)
                    }
                )
            }
            item {
                SelectionToolButton(
                    icon = Icons.Default.Circle,
                    label = "Ellipse",
                    selected = selectedTool == SelectionTool.ELLIPSE,
                    onClick = {
                        selectedTool = SelectionTool.ELLIPSE
                        onSelectionToolChanged(SelectionTool.ELLIPSE)
                    }
                )
            }
            item {
                SelectionToolButton(
                    icon = Icons.Default.Draw,
                    label = "Freehand",
                    selected = selectedTool == SelectionTool.FREEHAND,
                    onClick = {
                        selectedTool = SelectionTool.FREEHAND
                        onSelectionToolChanged(SelectionTool.FREEHAND)
                    }
                )
            }
            item {
                SelectionToolButton(
                    icon = Icons.Default.Polyline,
                    label = "Lasso",
                    selected = selectedTool == SelectionTool.LASSO,
                    onClick = {
                        selectedTool = SelectionTool.LASSO
                        onSelectionToolChanged(SelectionTool.LASSO)
                    }
                )
            }
            item {
                SelectionToolButton(
                    icon = Icons.Default.AutoFixHigh,
                    label = "Magic Wand",
                    selected = selectedTool == SelectionTool.MAGIC_WAND,
                    onClick = {
                        selectedTool = SelectionTool.MAGIC_WAND
                        showMagicWandOptions = true
                        onSelectionToolChanged(SelectionTool.MAGIC_WAND)
                    }
                )
            }
        }

        // Magic Wand Options
        if (showMagicWandOptions && selectedTool == SelectionTool.MAGIC_WAND) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Magic Wand Settings",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        IconButton(onClick = { showMagicWandOptions = false }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Tolerance Slider
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Tolerance",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "$magicWandTolerance",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = magicWandTolerance.toFloat(),
                            onValueChange = { magicWandTolerance = it.toInt() },
                            valueRange = 0f..255f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Text(
                        text = "Lower values select similar colors only. Higher values select broader range.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }

        Divider()

        // Selection Operations Row
        Text(
            text = "Selection Operations",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SelectionOperationButton(
                icon = Icons.Default.Clear,
                label = "Deselect",
                onClick = { selectionManager.clearSelection() },
                enabled = hasSelection
            )
            SelectionOperationButton(
                icon = Icons.Default.InvertColors,
                label = "Invert",
                onClick = { /* Invert selection */ },
                enabled = hasSelection
            )
            SelectionOperationButton(
                icon = Icons.Default.BlurOn,
                label = "Feather",
                onClick = { 
                    if (featherRadius > 0) {
                        selectionManager.featherSelection(featherRadius)
                    }
                },
                enabled = hasSelection
            )
            SelectionOperationButton(
                icon = Icons.Default.ContentCut,
                label = "Cut",
                onClick = { /* Cut selection */ },
                enabled = hasSelection
            )
            SelectionOperationButton(
                icon = Icons.Default.ContentCopy,
                label = "Copy",
                onClick = { /* Copy selection */ },
                enabled = hasSelection
            )
        }

        // Feather Slider
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Feather Radius",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "%.0f px".format(featherRadius),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = featherRadius,
                onValueChange = { featherRadius = it },
                valueRange = 0f..50f,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SelectionToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        leadingIcon = if (selected) {
            {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        } else null,
        modifier = Modifier.height(40.dp)
    )
}

@Composable
private fun SelectionOperationButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick, enabled = enabled)
            .padding(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            },
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            }
        )
    }
}

/**
 * Available selection tools
 */
enum class SelectionTool {
    RECTANGLE,
    ELLIPSE,
    FREEHAND,
    LASSO,
    MAGIC_WAND
}
