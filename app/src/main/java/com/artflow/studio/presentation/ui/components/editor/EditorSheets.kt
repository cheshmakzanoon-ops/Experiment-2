@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.artflow.studio.presentation.ui.components.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artflow.studio.core.animation.AnimationTimeline
import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.core.perspective.PerspectiveGuide
import com.artflow.studio.core.pixels.LayerMaskSource
import com.artflow.studio.core.symmetry.SymmetryEngine
import com.artflow.studio.core.text.TextLayout
import com.artflow.studio.domain.model.animation.AnimationSettings
import com.artflow.studio.domain.model.layer.AdjustmentType
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.FilterType
import com.artflow.studio.domain.model.layer.Layer
import com.artflow.studio.presentation.ui.components.canvas.SelectionCombineMode

// ---------------------------------------------------------------------------------------------
// Layers
// ---------------------------------------------------------------------------------------------

/**
 * Layer stack editor.
 *
 * Every action goes through `CanvasViewModel` into `CanvasRepository`, which is the document: undo,
 * save, export and the canvas all read the same stack, so the panel can never drift out of sync.
 */
data class LayerRowActions(
    val onSelect: (Long) -> Unit,
    val onVisibility: (Long, Boolean) -> Unit,
    val onOpacity: (Long, Float) -> Unit,
    val onName: (Long, String) -> Unit,
    val onLock: (Long, Boolean) -> Unit,
    val onAlphaLock: (Long, Boolean) -> Unit,
    val onClipping: (Long, Boolean) -> Unit,
    val onBlendMode: (Long, BlendMode) -> Unit,
    val onDuplicate: (Long) -> Unit,
    val onDelete: (Long) -> Unit,
    val onMergeDown: (Long) -> Unit,
)

data class LayerStackActions(
    val onAddLayer: () -> Unit,
    val onFlatten: () -> Unit,
    val onMergeVisible: () -> Unit,
    val onAddAdjustment: (AdjustmentType) -> Unit,
    val onAddFilter: (FilterType) -> Unit,
    val onAdjustmentParameter: (Long, String, Float) -> Unit,
    val onFilterAmount: (Long, Float) -> Unit,
)

data class LayerMaskActions(
    val onAddMask: (LayerMaskSource) -> Unit,
    val onRemoveMask: () -> Unit,
    val onInvertMask: () -> Unit,
    val onMaskEnabled: (Boolean) -> Unit,
    val onMaskDensity: (Float) -> Unit,
    val onMaskFeather: (Float) -> Unit,
    val onPaintMask: (Boolean) -> Unit,
)

@Composable
fun LayersSheet(
    layers: List<Layer>,
    activeLayerId: Long,
    rowActions: LayerRowActions,
    stackActions: LayerStackActions,
    maskActions: LayerMaskActions,
    hasSelection: Boolean,
    modifier: Modifier = Modifier,
) {
    val onSelect = rowActions.onSelect
    val onVisibility = rowActions.onVisibility
    val onOpacity = rowActions.onOpacity
    val onName = rowActions.onName
    val onLock = rowActions.onLock
    val onAlphaLock = rowActions.onAlphaLock
    val onClipping = rowActions.onClipping
    val onBlendMode = rowActions.onBlendMode
    val onDuplicate = rowActions.onDuplicate
    val onDelete = rowActions.onDelete
    val onMergeDown = rowActions.onMergeDown
    val onAddLayer = stackActions.onAddLayer
    val onFlatten = stackActions.onFlatten
    val onMergeVisible = stackActions.onMergeVisible
    val onAddAdjustment = stackActions.onAddAdjustment
    val onAddFilter = stackActions.onAddFilter
    val onAdjustmentParameter = stackActions.onAdjustmentParameter
    val onFilterAmount = stackActions.onFilterAmount
    var blendTarget by remember { mutableStateOf<Layer?>(null) }
    var renameTarget by remember { mutableStateOf<Layer?>(null) }
    var addMenuVisible by remember { mutableStateOf(false) }
    val active = layers.firstOrNull { it.id == activeLayerId }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Layers", style = MaterialTheme.typography.titleMedium)
            Row {
                IconButton(onClick = onAddLayer) {
                    Icon(Icons.Default.Add, contentDescription = "Add layer")
                }
                Box {
                    IconButton(onClick = { addMenuVisible = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Layer actions")
                    }
                    DropdownMenu(expanded = addMenuVisible, onDismissRequest = { addMenuVisible = false }) {
                        DropdownMenuItem(
                            text = { Text("Merge visible") },
                            onClick = {
                                onMergeVisible()
                                addMenuVisible = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Flatten image") },
                            onClick = {
                                onFlatten()
                                addMenuVisible = false
                            },
                        )
                        Divider()
                        DropdownMenuItem(
                            text = { Text("Add adjustment layer") },
                            onClick = { addMenuVisible = false },
                            trailingIcon = { Icon(Icons.Default.Tune, contentDescription = null) },
                        )
                        AdjustmentType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = { Text("   ${type.displayName}") },
                                onClick = {
                                    onAddAdjustment(type)
                                    addMenuVisible = false
                                },
                            )
                        }
                        Divider()
                        DropdownMenuItem(
                            text = { Text("Add filter layer") },
                            onClick = { addMenuVisible = false },
                            trailingIcon = { Icon(Icons.Default.FilterVintage, contentDescription = null) },
                        )
                        FilterType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = { Text("   ${type.displayName}") },
                                onClick = {
                                    onAddFilter(type)
                                    addMenuVisible = false
                                },
                            )
                        }
                    }
                }
            }
        }

        LazyColumn(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
            reverseLayout = true,
        ) {
            items(layers.sortedByDescending { it.index }, key = { it.id }) { layer ->
                LayerRow(
                    layer = layer,
                    isActive = layer.id == activeLayerId,
                    onSelect = { onSelect(layer.id) },
                    onVisibility = { onVisibility(layer.id, !layer.isVisible) },
                    onOpacity = { onOpacity(layer.id, it) },
                    onBlendMode = { blendTarget = layer },
                    onLock = { onLock(layer.id, !layer.isLocked) },
                    onAlphaLock = { onAlphaLock(layer.id, !layer.isAlphaLocked) },
                    onClipping = { onClipping(layer.id, !layer.isClippingMask) },
                    onDuplicate = { onDuplicate(layer.id) },
                    onDelete = { onDelete(layer.id) },
                    onMergeDown = { onMergeDown(layer.id) },
                    onRename = { renameTarget = layer },
                )
            }
        }

        active?.let { layer ->
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            Column(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LayerMaskControls(layer, hasSelection, maskActions)

                layer.adjustmentType?.let { type ->
                    Text(type.displayName, style = MaterialTheme.typography.titleSmall)
                    type.parameterRanges.forEach { (key, range) ->
                        val value = layer.adjustmentParameters[key] ?: type.defaultParameters[key] ?: 0f
                        LabeledSlider(
                            label = key.replace('_', ' '),
                            value = value,
                            range = range,
                            onChange = { onAdjustmentParameter(layer.id, key, it) },
                        )
                    }
                }

                layer.filterType?.let { type ->
                    Text(type.displayName, style = MaterialTheme.typography.titleSmall)
                    LabeledSlider(
                        label = "Amount",
                        value = layer.filterAmount,
                        range = 0f..1f,
                        onChange = { onFilterAmount(layer.id, it) },
                    )
                }
            }
        }
    }

    blendTarget?.let { layer ->
        com.artflow.studio.presentation.ui.components.layer.BlendModeSelectorDialog(
            currentBlendMode = layer.blendMode,
            onBlendModeSelected = { mode ->
                onBlendMode(layer.id, mode)
                blendTarget = null
            },
            onDismiss = { blendTarget = null },
        )
    }

    renameTarget?.let { layer ->
        var text by remember(layer.id) { mutableStateOf(layer.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename layer") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onName(layer.id, text)
                    renameTarget = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LayerMaskControls(
    layer: Layer,
    hasSelection: Boolean,
    actions: LayerMaskActions,
) {
    var maskMenuVisible by remember(layer.id) { mutableStateOf(false) }
    Text("Mask", style = MaterialTheme.typography.titleSmall)
    if (!layer.hasMask()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                Button(
                    onClick = { maskMenuVisible = true },
                    enabled = !layer.isLocked && !layer.isReference,
                ) { Text("Add mask") }
                DropdownMenu(expanded = maskMenuVisible, onDismissRequest = { maskMenuVisible = false }) {
                    LayerMaskSource.entries.forEach { source ->
                        DropdownMenuItem(
                            text = { Text(source.label) },
                            enabled = source != LayerMaskSource.SELECTION || hasSelection,
                            onClick = {
                                actions.onAddMask(source)
                                maskMenuVisible = false
                            },
                        )
                    }
                }
            }
            Text(
                "A mask hides parts of this layer without erasing them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val canPaint = layer.canEdit() && layer.maskEnabled && !layer.isReference
            OutlinedButton(onClick = { actions.onPaintMask(true) }, enabled = canPaint) { Text("Paint reveal") }
            OutlinedButton(onClick = { actions.onPaintMask(false) }, enabled = canPaint) { Text("Paint hide") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { actions.onMaskEnabled(!layer.maskEnabled) },
                label = { Text(if (layer.maskEnabled) "Enabled" else "Disabled") },
            )
            AssistChip(onClick = actions.onInvertMask, label = { Text("Invert") })
            AssistChip(onClick = actions.onRemoveMask, label = { Text("Remove") })
        }
        LabeledSlider(
            label = "Density",
            value = layer.maskDensity,
            range = 0f..1f,
            onChange = actions.onMaskDensity,
        )
        LabeledSlider(
            label = "Feather",
            value = layer.maskFeather,
            range = 0f..64f,
            onChange = actions.onMaskFeather,
        )
    }
}

@Composable
private fun LayerRow(
    layer: Layer,
    isActive: Boolean,
    onSelect: () -> Unit,
    onVisibility: () -> Unit,
    onOpacity: (Float) -> Unit,
    onBlendMode: () -> Unit,
    onLock: () -> Unit,
    onAlphaLock: () -> Unit,
    onClipping: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onMergeDown: () -> Unit,
    onRename: () -> Unit,
) {
    var menuVisible by remember { mutableStateOf(false) }
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clickable(onClick = onSelect),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isActive) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    },
            ),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onVisibility, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = if (layer.isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = "Visibility",
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = layer.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text =
                            buildString {
                                append(layer.blendMode.displayName)
                                append(" · ")
                                append("${(layer.opacity * 100).toInt()}%")
                                if (layer.isClippingMask) append(" · clipping")
                                if (layer.isAlphaLocked) append(" · alpha locked")
                                if (layer.isReference) append(" · reference")
                            },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (layer.isLocked) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "Locked",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
                Box {
                    IconButton(onClick = { menuVisible = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Layer menu", modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = menuVisible, onDismissRequest = { menuVisible = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = {
                                onRename()
                                menuVisible = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Blend mode") },
                            onClick = {
                                onBlendMode()
                                menuVisible = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Duplicate") },
                            onClick = {
                                onDuplicate()
                                menuVisible = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Merge down") },
                            onClick = {
                                onMergeDown()
                                menuVisible = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (layer.isAlphaLocked) "Unlock alpha" else "Lock alpha") },
                            onClick = {
                                onAlphaLock()
                                menuVisible = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (layer.isClippingMask) "Release clipping" else "Clip to layer below") },
                            onClick = {
                                onClipping()
                                menuVisible = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (layer.isLocked) "Unlock" else "Lock") },
                            onClick = {
                                onLock()
                                menuVisible = false
                            },
                        )
                        Divider()
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                onDelete()
                                menuVisible = false
                            },
                        )
                    }
                }
            }
            Slider(
                value = layer.opacity,
                onValueChange = onOpacity,
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Selection
// ---------------------------------------------------------------------------------------------

@Composable
fun SelectionSheet(
    mode: SelectionCombineMode,
    selectionCount: Int,
    tolerance: Int,
    featherRadius: Int,
    hasSelection: Boolean,
    onModeChange: (SelectionCombineMode) -> Unit,
    onToleranceChange: (Int) -> Unit,
    onFeatherChange: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onInvertSelection: () -> Unit,
    onApplyFeather: () -> Unit,
    onSelectionFromLayer: () -> Unit,
    onTrimToSelection: () -> Unit,
    onColorRange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Selection", style = MaterialTheme.typography.titleMedium)
        Text(
            if (selectionCount > 0) "$selectionCount pixels selected" else "Nothing selected",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Combine mode", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionCombineMode.entries.forEach { entry ->
                FilterChip(
                    selected = mode == entry,
                    onClick = { onModeChange(entry) },
                    label = { Text(entry.displayName, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        LabeledSlider(
            label = "Magic wand tolerance",
            value = tolerance.toFloat(),
            range = 0f..255f,
            onChange = { onToleranceChange(it.toInt()) },
        )
        LabeledSlider(
            label = "Feather radius",
            value = featherRadius.toFloat(),
            range = 0f..64f,
            onChange = { onFeatherChange(it.toInt()) },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = onSelectAll, label = { Text("Select all") })
            AssistChip(onClick = onInvertSelection, label = { Text("Invert") })
            AssistChip(onClick = onClearSelection, enabled = hasSelection, label = { Text("Clear") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = onApplyFeather,
                enabled = hasSelection,
                label = { Text("Apply feather") },
            )
            AssistChip(onClick = onSelectionFromLayer, label = { Text("From layer alpha") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = onTrimToSelection,
                enabled = hasSelection,
                label = { Text("Crop to selection") },
            )
            AssistChip(onClick = { onColorRange(0xFF000000.toInt()) }, label = { Text("Select black") })
            AssistChip(onClick = { onColorRange(0xFFFFFFFF.toInt()) }, label = { Text("Select white") })
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Canvas operations
// ---------------------------------------------------------------------------------------------

@Composable
fun CanvasOpsSheet(
    width: Int,
    height: Int,
    dpi: Int,
    backgroundColor: Int,
    onResize: (Int, Int, Boolean, CanvasOperations.Anchor) -> Unit,
    onRotate: (Int) -> Unit,
    onFlip: (Boolean) -> Unit,
    onTrim: () -> Unit,
    onDpi: (Int) -> Unit,
    onBackgroundColor: (Int) -> Unit,
    onClear: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var targetWidth by remember(width) { mutableStateOf(width.toString()) }
    var targetHeight by remember(height) { mutableStateOf(height.toString()) }
    var resample by remember { mutableStateOf(true) }
    var anchor by remember { mutableStateOf(CanvasOperations.Anchor.CENTER) }
    var presetMenu by remember { mutableStateOf(false) }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Canvas", style = MaterialTheme.typography.titleMedium)
        Text(
            "$width × $height px · $dpi dpi",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Box {
            OutlinedButton(onClick = { presetMenu = true }) { Text("Presets") }
            DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                CanvasOperations.PRESETS.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(preset.label) },
                        onClick = {
                            targetWidth = preset.width.toString()
                            targetHeight = preset.height.toString()
                            presetMenu = false
                        },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = targetWidth,
                onValueChange = { targetWidth = it.filter { c -> c.isDigit() }.take(5) },
                label = { Text("Width") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = targetHeight,
                onValueChange = { targetHeight = it.filter { c -> c.isDigit() }.take(5) },
                label = { Text("Height") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = resample, onCheckedChange = { resample = it })
            Spacer(Modifier.width(8.dp))
            Text(
                if (resample) "Resample pixels (scales the artwork)" else "Keep pixels (changes the frame)",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Text("Anchor", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CanvasOperations.Anchor.entries.forEach { entry ->
                FilterChip(
                    selected = anchor == entry,
                    onClick = { anchor = entry },
                    label = { Text(entry.displayName, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        Button(onClick = {
            val w = targetWidth.toIntOrNull() ?: width
            val h = targetHeight.toIntOrNull() ?: height
            onResize(w, h, resample, anchor)
        }) { Text("Apply resize") }

        Divider()
        Text("Transform", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { onRotate(90) }, label = { Text("Rotate 90°") })
            AssistChip(onClick = { onRotate(-90) }, label = { Text("Rotate -90°") })
            AssistChip(onClick = { onRotate(180) }, label = { Text("Rotate 180°") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { onFlip(false) }, label = { Text("Flip horizontal") })
            AssistChip(onClick = { onFlip(true) }, label = { Text("Flip vertical") })
            AssistChip(onClick = onTrim, label = { Text("Trim transparency") })
        }

        Divider()
        LabeledSlider(
            label = "Resolution (dpi)",
            value = dpi.toFloat(),
            range = 36f..600f,
            onChange = { onDpi(it.toInt()) },
        )

        Text("Background", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorChip(0xFFFFFFFF.toInt(), onClick = { onBackgroundColor(0xFFFFFFFF.toInt()) })
            ColorChip(0xFF111111.toInt(), onClick = { onBackgroundColor(0xFF111111.toInt()) })
            ColorChip(0xFFF5EFE6.toInt(), onClick = { onBackgroundColor(0xFFF5EFE6.toInt()) })
            ColorChip(0x00000000, onClick = { onBackgroundColor(0) })
            Text(
                "Current: ${String.format("#%08X", backgroundColor)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onClear(0) }) { Text("Clear to transparency") }
            OutlinedButton(onClick = { onClear(backgroundColor) }) { Text("Fill with background") }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Animation
// ---------------------------------------------------------------------------------------------

@Composable
fun AnimationSheet(
    timeline: AnimationTimeline.State,
    onionEnabled: Boolean,
    onSelectFrame: (Int) -> Unit,
    onAddFrame: (Boolean) -> Unit,
    onDeleteFrame: (Int) -> Unit,
    onMoveFrame: (Int, Int) -> Unit,
    onFrameDuration: (Int, Int) -> Unit,
    onSettings: (AnimationSettings) -> Unit,
    onToggleOnion: (Boolean) -> Unit,
    onTogglePlayback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = timeline.settings
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Animation", style = MaterialTheme.typography.titleMedium)
            Row {
                IconButton(onClick = { onAddFrame(false) }) {
                    Icon(Icons.Default.Add, contentDescription = "New frame")
                }
                IconButton(onClick = { onAddFrame(true) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate frame")
                }
                IconButton(onClick = onTogglePlayback) {
                    Icon(
                        imageVector = if (timeline.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (timeline.isPlaying) "Pause" else "Play",
                    )
                }
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(timeline.frames.size) { index ->
                val frame = timeline.frames[index]
                Card(
                    modifier =
                        Modifier
                            .width(84.dp)
                            .clickable { onSelectFrame(index) },
                    colors =
                        CardDefaults.cardColors(
                            containerColor =
                                if (index == timeline.activeIndex) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                        ),
                ) {
                    Column(modifier = Modifier.padding(6.dp)) {
                        Text("${index + 1}", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "${frame.durationMs} ms",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Row {
                            IconButton(
                                onClick = { if (index > 0) onMoveFrame(index, index - 1) },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(Icons.Default.ChevronLeft, contentDescription = "Move earlier", modifier = Modifier.size(16.dp))
                            }
                            IconButton(
                                onClick = { if (index < timeline.frames.lastIndex) onMoveFrame(index, index + 1) },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(Icons.Default.ChevronRight, contentDescription = "Move later", modifier = Modifier.size(16.dp))
                            }
                            IconButton(onClick = { onDeleteFrame(index) }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete frame", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }

        Text("Total ${timeline.durationMs} ms · ${timeline.frameCount} frames", style = MaterialTheme.typography.bodySmall)

        LabeledSlider(
            label = "Frame duration (ms)",
            value =
                timeline.frames
                    .getOrNull(timeline.activeIndex)
                    ?.durationMs
                    ?.toFloat() ?: 100f,
            range = 16f..2000f,
            onChange = { onFrameDuration(timeline.activeIndex, it.toInt()) },
        )
        LabeledSlider(
            label = "Playback fps",
            value = settings.fps.toFloat(),
            range = 1f..60f,
            onChange = { onSettings(settings.copy(fps = it.toInt())) },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = settings.loop, onCheckedChange = { onSettings(settings.copy(loop = it)) })
            Spacer(Modifier.width(8.dp))
            Text("Loop playback", style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = settings.pingPong, onCheckedChange = { onSettings(settings.copy(pingPong = it)) })
            Spacer(Modifier.width(8.dp))
            Text("Ping-pong playback", style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = onionEnabled, onCheckedChange = onToggleOnion)
            Spacer(Modifier.width(8.dp))
            Text("Onion skin", style = MaterialTheme.typography.bodySmall)
        }
        LabeledSlider(
            label = "Onion skin frames",
            value = settings.onionSkinFrames.toFloat(),
            range = 0f..5f,
            onChange = { onSettings(settings.copy(onionSkinFrames = it.toInt())) },
        )
        LabeledSlider(
            label = "Onion skin opacity",
            value = settings.onionSkinOpacity,
            range = 0.05f..1f,
            onChange = { onSettings(settings.copy(onionSkinOpacity = it)) },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Guides
// ---------------------------------------------------------------------------------------------

@Composable
fun GuidesSheet(
    symmetry: SymmetryEngine.Settings,
    perspective: PerspectiveGuide.Settings,
    snapToGuides: Boolean,
    showSymmetryGuides: Boolean,
    showPerspectiveGuides: Boolean,
    onSymmetry: (SymmetryEngine.Settings) -> Unit,
    onPerspective: (PerspectiveGuide.Settings) -> Unit,
    onSnap: (Boolean) -> Unit,
    onShowSymmetry: (Boolean) -> Unit,
    onShowPerspective: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Symmetry", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SymmetryEngine.SymmetryType.entries.forEach { type ->
                FilterChip(
                    selected = symmetry.type == type,
                    onClick = { onSymmetry(symmetry.copy(type = type)) },
                    label = { Text(type.displayName, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        LabeledSlider(
            label = "Radial copies",
            value = symmetry.radialCount.toFloat(),
            range = SymmetryEngine.RADIAL_RANGE.first.toFloat()..SymmetryEngine.RADIAL_RANGE.last.toFloat(),
            onChange = { onSymmetry(symmetry.copy(radialCount = it.toInt())) },
        )
        LabeledSlider(
            label = "Axis X",
            value = symmetry.centreX,
            range = 0f..1f,
            onChange = { onSymmetry(symmetry.copy(centreX = it)) },
        )
        LabeledSlider(
            label = "Axis Y",
            value = symmetry.centreY,
            range = 0f..1f,
            onChange = { onSymmetry(symmetry.copy(centreY = it)) },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = symmetry.secondaryAxis,
                onCheckedChange = { onSymmetry(symmetry.copy(secondaryAxis = it)) },
            )
            Spacer(Modifier.width(8.dp))
            Text("Second axis (kaleidoscope)", style = MaterialTheme.typography.bodySmall)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(SymmetryEngine.PRESETS) { preset ->
                AssistChip(
                    onClick = { onSymmetry(SymmetryEngine.sanitize(preset.settings)) },
                    label = { Text(preset.name, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        Divider()
        Text("Perspective", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PerspectiveGuide.GuideType.entries.forEach { type ->
                FilterChip(
                    selected = perspective.type == type,
                    onClick = { onPerspective(perspective.copy(type = type)) },
                    label = { Text(type.displayName, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        LabeledSlider(
            label = "Rays",
            value = perspective.density.toFloat(),
            range = PerspectiveGuide.DENSITY_RANGE.first.toFloat()..PerspectiveGuide.DENSITY_RANGE.last.toFloat(),
            onChange = { onPerspective(perspective.copy(density = it.toInt())) },
        )
        LabeledSlider(
            label = "Horizon",
            value = perspective.horizonY,
            range = 0f..1f,
            onChange = { onPerspective(PerspectiveGuide.withHorizon(perspective, it)) },
        )
        LabeledSlider(
            label = "Snap radius",
            value = perspective.snapRadius,
            range = PerspectiveGuide.SNAP_RADIUS_RANGE,
            onChange = { onPerspective(perspective.copy(snapRadius = it)) },
        )
        LabeledSlider(
            label = "Snap strength",
            value = perspective.snapStrength,
            range = 0f..1f,
            onChange = { onPerspective(perspective.copy(snapStrength = it)) },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = perspective.snapEnabled,
                onCheckedChange = { onPerspective(perspective.copy(snapEnabled = it)) },
            )
            Spacer(Modifier.width(8.dp))
            Text("Snap to rays", style = MaterialTheme.typography.bodySmall)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(PerspectiveGuide.PRESETS) { preset ->
                AssistChip(
                    onClick = { onPerspective(PerspectiveGuide.sanitize(preset.settings)) },
                    label = { Text(preset.name, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        Divider()
        Text("Display", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = snapToGuides, onCheckedChange = onSnap)
            Spacer(Modifier.width(8.dp))
            Text("Snap strokes to guides", style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = showSymmetryGuides, onCheckedChange = onShowSymmetry)
            Spacer(Modifier.width(8.dp))
            Text("Show symmetry guides", style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = showPerspectiveGuides, onCheckedChange = onShowPerspective)
            Spacer(Modifier.width(8.dp))
            Text("Show perspective guides", style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Text
// ---------------------------------------------------------------------------------------------

@Composable
fun TextSheet(
    text: String,
    style: TextLayout.TextStyle,
    color: Int,
    onTextChange: (String) -> Unit,
    onStyleChange: (TextLayout.TextStyle) -> Unit,
    onColorChange: (Int) -> Unit,
    onPlace: () -> Unit,
    hasPosition: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Text", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onPlace, enabled = text.isNotBlank()) {
                Text(if (hasPosition) "Place on canvas" else "Choose position")
            }
        }

        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text("Content") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )

        var fontMenu by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { fontMenu = true }) { Text(style.fontFamily) }
            DropdownMenu(expanded = fontMenu, onDismissRequest = { fontMenu = false }) {
                TextLayout.FONT_FAMILIES.forEach { family ->
                    DropdownMenuItem(
                        text = { Text(family) },
                        onClick = {
                            onStyleChange(style.copy(fontFamily = family))
                            fontMenu = false
                        },
                    )
                }
            }
        }

        LabeledSlider(
            label = "Size",
            value = style.fontSize,
            range = 8f..400f,
            onChange = { onStyleChange(style.copy(fontSize = it)) },
        )
        LabeledSlider(
            label = "Line height",
            value = style.lineHeight,
            range = 0.8f..3f,
            onChange = { onStyleChange(style.copy(lineHeight = it)) },
        )
        LabeledSlider(
            label = "Letter spacing",
            value = style.letterSpacing,
            range = -4f..40f,
            onChange = { onStyleChange(style.copy(letterSpacing = it)) },
        )
        LabeledSlider(
            label = "Wrap width",
            value = style.maxWidth ?: 0f,
            range = 0f..4000f,
            onChange = { onStyleChange(style.copy(maxWidth = if (it <= 0f) null else it)) },
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(
                selected = style.bold,
                onClick = { onStyleChange(style.copy(bold = !style.bold)) },
                label = { Text("Bold") },
            )
            FilterChip(
                selected = style.italic,
                onClick = { onStyleChange(style.copy(italic = !style.italic)) },
                label = { Text("Italic") },
            )
            FilterChip(
                selected = style.underline,
                onClick = { onStyleChange(style.copy(underline = !style.underline)) },
                label = { Text("Underline") },
            )
            FilterChip(
                selected = style.strikeThrough,
                onClick = { onStyleChange(style.copy(strikeThrough = !style.strikeThrough)) },
                label = { Text("Strike") },
            )
        }

        Text("Alignment", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextLayout.Alignment.entries.forEach { alignment ->
                FilterChip(
                    selected = style.alignment == alignment,
                    onClick = { onStyleChange(style.copy(alignment = alignment)) },
                    label = { Text(alignment.displayName, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        Text("Colour", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ColorChip(0xFF111111.toInt(), onClick = { onColorChange(0xFF111111.toInt()) })
            ColorChip(0xFFFFFFFF.toInt(), onClick = { onColorChange(0xFFFFFFFF.toInt()) })
            ColorChip(color, onClick = { })
            Text(
                "Tap the colour chip in the toolbar for the full picker.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                text = if (range.endInclusive <= 1.001f) "${(value * 100).toInt()}%" else value.toInt().toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
