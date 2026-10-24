@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.artflow.studio.presentation.ui.components.layer

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.artflow.studio.R
import com.artflow.studio.core.pixels.BlendModes
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.LayerBlendContract

/**
 * Blend mode selector dialog for choosing layer blend modes
 * Implements Phase 12: Blend Modes
 */
@Composable
fun BlendModeSelectorDialog(
    currentBlendMode: BlendMode,
    onBlendModeSelected: (BlendMode) -> Unit,
    onDismiss: () -> Unit,
    isGroup: Boolean = false,
    currentBlendContract: LayerBlendContract = LayerBlendContract.STANDARD,
) {
    val importedDarken = currentBlendContract == LayerBlendContract.PROCREATE_DARKEN_19
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.blendmodeselector_blend_mode))
        },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (importedDarken) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.blendmodeselector_imported_darken),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            stringResource(R.string.blendmodeselector_imported_darken_choice),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                BlendModeGrid(
                    selectedBlendMode = currentBlendMode.takeUnless { importedDarken },
                    onBlendModeSelected = onBlendModeSelected,
                    isGroup = isGroup,
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.layeropacityoverlay_done))
            }
        },
    )
}

/**
 * Grid layout displaying all available blend modes with preview
 */
@Composable
private fun BlendModeGrid(
    selectedBlendMode: BlendMode?,
    onBlendModeSelected: (BlendMode) -> Unit,
    modifier: Modifier = Modifier,
    isGroup: Boolean = false,
) {
    val blendModes = BlendMode.entries.filter { isGroup || it != BlendMode.PASS_THROUGH }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        items(blendModes, key = { it.ordinal }) { blendMode ->
            BlendModeChip(
                blendMode = blendMode,
                isSelected = blendMode == selectedBlendMode,
                onClick = { onBlendModeSelected(blendMode) },
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
    onClick: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (isSelected) {
                        Modifier.border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(8.dp),
                        )
                    } else {
                        Modifier
                    },
                ).clickable(onClick = onClick)
                .semantics { selected = isSelected },
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    },
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 4.dp else 1.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Visual preview of blend mode effect
            BlendModePreview(
                blendMode = blendMode,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(4.dp)),
            )

            // Blend mode name
            Text(
                text = blendMode.displayName,
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                maxLines = 2,
            )
        }
    }
}

/** The same two colours in every chip, blended by the canvas engine rather than illustrative tints. */
@Composable
internal fun BlendModePreview(
    blendMode: BlendMode,
    modifier: Modifier = Modifier,
) {
    val overlap =
        remember(blendMode) {
            Color(BlendModes.previewBlend(0xFFC08040.toInt(), 0xFF4080C0.toInt(), blendMode))
        }
    val description = stringResource(R.string.blendmodeselector_preview, blendMode.displayName)
    Canvas(modifier = modifier.semantics { contentDescription = description }) {
        drawRect(Color(0xFFE8E8E8))
        drawRect(
            color = Color(0xFFC08040),
            topLeft = Offset(size.width * 0.08f, size.height * 0.08f),
            size = Size(size.width * 0.62f, size.height * 0.62f),
        )
        drawRect(
            color = Color(0xFF4080C0),
            topLeft = Offset(size.width * 0.38f, size.height * 0.38f),
            size = Size(size.width * 0.54f, size.height * 0.54f),
        )
        drawRect(
            color = overlap,
            topLeft = Offset(size.width * 0.38f, size.height * 0.38f),
            size = Size(size.width * 0.32f, size.height * 0.32f),
        )
    }
}

/**
 * Compact blend mode dropdown selector for layer panel
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlendModeDropdown(
    selectedBlendMode: BlendMode,
    onBlendModeSelected: (BlendMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selectedBlendMode.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.blendmodeselector_blend_mode)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
            textStyle = MaterialTheme.typography.bodyMedium,
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                ),
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
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
                            onClick = null,
                        )
                    },
                )
            }
        }
    }
}

// NOTE: display names for blend modes come from BlendMode.displayName in the domain model;
// this file previously redeclared it as a private extension that was shadowed by the member.
