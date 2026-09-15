package com.artflow.studio.presentation.ui.components.layer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.artflow.studio.domain.model.layer.BlendMode

/**
 * Blend mode selector dialog for choosing layer blend modes
 * Implements Phase 12: Blend Modes
 */
@Composable
fun BlendModeSelectorDialog(
    currentBlendMode: BlendMode,
    onBlendModeSelected: (BlendMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Blend Mode")
                Badge {
                    Text("Phase 12")
                }
            }
        },
        text = {
            BlendModeGrid(
                selectedBlendMode = currentBlendMode,
                onBlendModeSelected = onBlendModeSelected,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

/**
 * Grid layout displaying all available blend modes with preview
 */
@Composable
private fun BlendModeGrid(
    selectedBlendMode: BlendMode,
    onBlendModeSelected: (BlendMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val blendModes = BlendMode.entries.filter { it != BlendMode.PASS_THROUGH }
    
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        items(blendModes, key = { it.ordinal }) { blendMode ->
            BlendModeChip(
                blendMode = blendMode,
                isSelected = blendMode == selectedBlendMode,
                onClick = { onBlendModeSelected(blendMode) }
            )
        }
    }
}

/**
 * Individual blend mode chip with visual preview
 */
@Composable
private fun BlendModeChip(
    blendMode: BlendMode,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(8.dp)
                    )
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 4.dp else 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Visual preview of blend mode effect
            BlendModePreview(
                blendMode = blendMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
            )
            
            // Blend mode name
            Text(
                text = blendMode.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2
            )
        }
    }
}

/**
 * Visual preview showing blend mode effect
 */
@Composable
private fun BlendModePreview(
    blendMode: BlendMode,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // Base layer (white background)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
        )
        
        // Sample gradient overlay to show blend effect
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(
                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFFF6B5C),
                            Color(0xFFFF6B5C).copy(alpha = 0.5f),
                            Color.Transparent
                        )
                    ),
                    shape = RoundedCornerShape(4.dp)
                )
        )
        
        // Overlay color to demonstrate blending
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(
                    color = when (blendMode) {
                        BlendMode.MULTIPLY -> Color(0xFF4A90D9).copy(alpha = 0.6f)
                        BlendMode.SCREEN -> Color(0xFFD94A90).copy(alpha = 0.6f)
                        BlendMode.OVERLAY -> Color(0xFF90D94A).copy(alpha = 0.6f)
                        BlendMode.DARKEN -> Color(0xFFD9A04A).copy(alpha = 0.6f)
                        BlendMode.LIGHTEN -> Color(0xFF4AD9D9).copy(alpha = 0.6f)
                        BlendMode.COLOR_DODGE -> Color(0xFFFFD94A).copy(alpha = 0.6f)
                        BlendMode.COLOR_BURN -> Color(0xFFD94A4A).copy(alpha = 0.6f)
                        BlendMode.HARD_LIGHT -> Color(0xFFA04AD9).copy(alpha = 0.6f)
                        BlendMode.SOFT_LIGHT -> Color(0xFF4AD9A0).copy(alpha = 0.6f)
                        BlendMode.DIFFERENCE -> Color(0xFFD94AD9).copy(alpha = 0.6f)
                        BlendMode.EXCLUSION -> Color(0xFF888888).copy(alpha = 0.6f)
                        BlendMode.HUE -> Color(0xFFFF8844).copy(alpha = 0.6f)
                        BlendMode.SATURATION -> Color(0xFF44FF88).copy(alpha = 0.6f)
                        BlendMode.COLOR -> Color(0xFF4488FF).copy(alpha = 0.6f)
                        BlendMode.LUMINOSITY -> Color(0xFFFF4488).copy(alpha = 0.6f)
                        else -> Color.Gray.copy(alpha = 0.5f)
                    },
                    shape = RoundedCornerShape(2.dp)
                )
        )
    }
}

/**
 * Compact blend mode dropdown selector for layer panel
 */
@Composable
fun BlendModeDropdown(
    selectedBlendMode: BlendMode,
    onBlendModeSelected: (BlendMode) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedBlendMode.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Blend Mode") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )
        
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            BlendMode.entries.filter { it != BlendMode.PASS_THROUGH }.forEach { blendMode ->
                DropdownMenuItem(
                    text = { Text(blendMode.displayName) },
                    onClick = {
                        onBlendModeSelected(blendMode)
                        expanded = false
                    },
                    leadingIcon = {
                        RadioButton(
                            selected = blendMode == selectedBlendMode,
                            onClick = null
                        )
                    }
                )
            }
        }
    }
}

/**
 * Human-readable display name for blend modes
 */
private val BlendMode.displayName: String
    get() = when (this) {
        BlendMode.NORMAL -> "Normal"
        BlendMode.MULTIPLY -> "Multiply"
        BlendMode.SCREEN -> "Screen"
        BlendMode.OVERLAY -> "Overlay"
        BlendMode.DARKEN -> "Darken"
        BlendMode.LIGHTEN -> "Lighten"
        BlendMode.COLOR_DODGE -> "Color Dodge"
        BlendMode.COLOR_BURN -> "Color Burn"
        BlendMode.HARD_LIGHT -> "Hard Light"
        BlendMode.SOFT_LIGHT -> "Soft Light"
        BlendMode.DIFFERENCE -> "Difference"
        BlendMode.EXCLUSION -> "Exclusion"
        BlendMode.HUE -> "Hue"
        BlendMode.SATURATION -> "Saturation"
        BlendMode.COLOR -> "Color"
        BlendMode.LUMINOSITY -> "Luminosity"
        BlendMode.PASS_THROUGH -> "Pass Through"
    }
