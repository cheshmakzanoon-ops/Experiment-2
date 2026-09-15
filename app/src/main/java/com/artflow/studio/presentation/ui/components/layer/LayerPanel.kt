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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artflow.studio.core.layer.LayerManager
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.Layer

/**
 * Layer panel UI component for managing layers
 * Displays layer list with controls for visibility, opacity, blend mode, etc.
 */
@Composable
fun LayerPanel(
    modifier: Modifier = Modifier,
    layerManager: LayerManager,
    onLayerSelected: (Long) -> Unit = {},
    onClose: () -> Unit = {}
) {
    val layers by layerManager.layers.collectAsState()
    val activeLayerId by layerManager.activeLayerId.collectAsState()
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Layers",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Row {
                IconButton(onClick = { layerManager.addLayer() }) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Layer",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        
        Divider()
        
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(4.dp),
            reverseLayout = true
        ) {
            items(
                items = layers.sortedByDescending { it.index },
                key = { it.id }
            ) { layer ->
                LayerListItem(
                    layer = layer,
                    isActive = layer.id == activeLayerId,
                    onSelected = { 
                        onLayerSelected(layer.id)
                        layerManager.setActiveLayer(layer.id) 
                    },
                    onVisibilityToggle = { layerManager.setLayerVisibility(layer.id, !layer.isVisible) },
                    onOpacityChange = { layerManager.setLayerOpacity(layer.id, it) },
                    onBlendModeChange = { layerManager.setLayerBlendMode(layer.id, it) },
                    onLockToggle = { layerManager.setLayerLock(layer.id, !layer.isLocked) },
                    onAlphaLockToggle = { layerManager.setAlphaLock(layer.id, !layer.isAlphaLocked) },
                    onDuplicate = { layerManager.duplicateLayer(layer.id) },
                    onDelete = { layerManager.removeLayer(layer.id) },
                    onMergeDown = {
                        val layerBelow = layers.find { l -> l.index == layer.index - 1 }
                        layerBelow?.let { layerManager.mergeLayers(layer.id, it.id) }
                    }
                )
            }
        }
    }
}

@Composable
fun LayerListItem(
    layer: Layer,
    isActive: Boolean,
    onSelected: () -> Unit,
    onVisibilityToggle: () -> Unit,
    onOpacityChange: (Float) -> Unit,
    onBlendModeChange: (BlendMode) -> Unit,
    onLockToggle: () -> Unit,
    onAlphaLockToggle: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onMergeDown: () -> Unit
) {
    var showOptions by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable(onClick = onSelected),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onVisibilityToggle, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = if (layer.isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = "Toggle Visibility",
                    tint = if (layer.isVisible) MaterialTheme.colorScheme.primary else Color.Gray,
                    modifier = Modifier.size(18.dp)
                )
            }
            
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .padding(horizontal = 4.dp)
                    .background(
                        color = if (layer.isVisible) Color.White.copy(alpha = layer.opacity) else Color.LightGray,
                        shape = MaterialTheme.shapes.small
                    )
            )
            
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            ) {
                Text(
                    text = layer.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${(layer.opacity * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = layer.blendMode.name.lowercase().replace("_", " "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            if (layer.isLocked) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Locked",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
            }
            
            Box {
                IconButton(onClick = { showOptions = true }, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More Options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
                
                DropdownMenu(expanded = showOptions, onDismissRequest = { showOptions = false }) {
                    DropdownMenuItem(
                        text = { Text("Duplicate") },
                        onClick = { onDuplicate(); showOptions = false },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Merge Down") },
                        onClick = { onMergeDown(); showOptions = false },
                        leadingIcon = { Icon(Icons.Default.Merge, contentDescription = null) },
                        enabled = layer.index > 0
                    )
                    DropdownMenuItem(
                        text = { Text("Alpha Lock") },
                        onClick = { onAlphaLockToggle(); showOptions = false },
                        leadingIcon = {
                            Icon(
                                if (layer.isAlphaLocked) Icons.Default.CheckCircle else Icons.Default.Circle,
                                contentDescription = null
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (layer.isLocked) "Unlock" else "Lock") },
                        onClick = { onLockToggle(); showOptions = false },
                        leadingIcon = {
                            Icon(
                                if (layer.isLocked) Icons.Default.LockOpen else Icons.Default.Lock,
                                contentDescription = null
                            )
                        }
                    )
                    Divider()
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        onClick = { onDelete(); showOptions = false },
                        leadingIcon = {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        }
                    )
                }
            }
        }
        
        Slider(
            value = layer.opacity,
            onValueChange = onOpacityChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            valueRange = 0f..1f,
            steps = 99
        )
    }
}
