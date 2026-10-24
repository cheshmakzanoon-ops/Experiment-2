@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.artflow.studio.presentation.ui.components.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.artflow.studio.R
import com.artflow.studio.core.animation.AnimationPlayback
import com.artflow.studio.core.animation.AnimationTimeline
import com.artflow.studio.core.animation.PlayMode
import com.artflow.studio.core.animation.withPlayMode
import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.core.canvas.TwoFingerLayerGesture
import com.artflow.studio.core.perspective.GuideLineStyle
import com.artflow.studio.core.perspective.PerspectiveGuide
import com.artflow.studio.core.pixels.LayerMaskSource
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionCombineMode
import com.artflow.studio.core.render.LayerGroups
import com.artflow.studio.core.symmetry.SymmetryEngine
import com.artflow.studio.core.text.TextLayout
import com.artflow.studio.data.local.FontLibrary
import com.artflow.studio.domain.model.animation.AnimationSettings
import com.artflow.studio.domain.model.layer.AdjustmentType
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.FilterType
import com.artflow.studio.domain.model.layer.Layer
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
// Layers
// ---------------------------------------------------------------------------------------------

/** Procreate's layer options, shown when the already-selected layer is tapped. They act on that layer. */
data class LayerOptionActions(
    val onSelectContents: () -> Unit,
    val onCopy: () -> Unit,
    val onFill: () -> Unit,
    val onClear: () -> Unit,
    val onInvert: () -> Unit,
    val onReference: (Long, Boolean) -> Unit,
    val onMask: () -> Unit,
    val onCombineDown: (Long) -> Unit,
    val onEditText: (Layer) -> Unit = {},
    val onFillReference: (Long, Boolean) -> Unit = { _, _ -> },
    val onDrawingAssist: (Long, Boolean) -> Unit = { _, _ -> },
    val onPrivate: (Long, Boolean) -> Unit = { _, _ -> },
    val onEffects: (Layer) -> Unit = {},
)

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
    /** Two-finger tap: slide across the canvas to set this layer's opacity. */
    val onOpacityMode: (Long) -> Unit = {},
    /** Two-finger touch and hold: select the layer's contents. */
    val onSelectContents: (Long) -> Unit = {},
    /** Touch and hold the visibility box: show only this layer, or bring the others back. */
    val onSolo: (Long) -> Unit = {},
    /** Enabled layer gestures, supplied from the user's gesture preferences. */
    val twoFingerGestures: Set<TwoFingerLayerGesture.Kind> = TwoFingerLayerGesture.Kind.entries.toSet(),
)

data class LayerStackActions(
    val onAddLayer: () -> Unit,
    val onReorder: (Long, Int) -> Unit,
    val onFlatten: () -> Unit,
    val onMergeVisible: () -> Unit,
    val onAddAdjustment: (AdjustmentType) -> Unit,
    val onAddFilter: (FilterType) -> Unit,
    val onAdjustmentParameter: (Long, String, Float) -> Unit,
    val onFilterAmount: (Long, Float) -> Unit,
    val onInsertPhoto: (() -> Unit)? = null,
    val onImportPsd: (() -> Unit)? = null,
    val onGroupWithBelow: ((Long) -> Unit)? = null,
    val onUngroup: ((Long) -> Unit)? = null,
    val onGroupLayers: (List<Long>) -> Unit = {},
    val onDeleteLayers: (List<Long>) -> Unit = {},
    val onMergeLayers: (List<Long>) -> Unit = {},
    val background: BackgroundControls? = null,
)

/** Procreate's Background colour row at the foot of the Layers panel. */
data class BackgroundControls(
    val color: Int,
    /** Tapping the swatch fills the background with the current colour. */
    val onUseCurrentColour: () -> Unit,
    val onVisible: (Boolean) -> Unit,
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
    thumbnails: Map<Long, PixelBuffer> = emptyMap(),
    optionActions: LayerOptionActions? = null,
    // Layers swiped right join the active layer in a multi-selection, as in Procreate.
    picked: Set<Long> = emptySet(),
    onPicked: (Set<Long>) -> Unit = {},
) {
    val onName = rowActions.onName
    val onBlendMode = rowActions.onBlendMode
    var dragging by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val onAdjustmentParameter = stackActions.onAdjustmentParameter
    val onFilterAmount = stackActions.onFilterAmount
    var blendTarget by remember { mutableStateOf<Layer?>(null) }
    var renameTarget by remember { mutableStateOf<Layer?>(null) }
    var collapsedGroups by remember { mutableStateOf(emptySet<Long>()) }
    val pickedIds = picked.filter { id -> id != activeLayerId && layers.any { it.id == id } }.toSet()
    val active = layers.firstOrNull { it.id == activeLayerId }

    Column(modifier = modifier.fillMaxWidth()) {
        LayersHeader(layers, active, stackActions)
        pickedIds.takeIf { it.isNotEmpty() }?.let { ids ->
            val chosen = (ids + activeLayerId).toList()
            MultiLayerBar(
                count = chosen.size,
                onGroup = { stackActions.onGroupLayers(chosen) },
                onMerge = { stackActions.onMergeLayers(chosen) },
                onDelete = { stackActions.onDeleteLayers(chosen) },
                onClear = { onPicked(emptySet()) },
            )
        }

        LazyColumn(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
        ) {
            val groupsById = layers.filter { it.isGroup }.associateBy { it.id }
            val visibleLayers =
                layers.sortedByDescending { it.index }.filter { layer ->
                    LayerGroups.ancestors(layer, groupsById).none { it.id in collapsedGroups }
                }
            items(visibleLayers, key = { it.id }) { layer ->
                GroupedRow(layer, layer.id in collapsedGroups) {
                    collapsedGroups = if (layer.id in collapsedGroups) collapsedGroups - layer.id else collapsedGroups + layer.id
                }
                Box(
                    Modifier
                        .padding(start = 20.dp * LayerGroups.ancestors(layer, groupsById).size)
                        .reorderOnLongPress(
                            layer = layer,
                            lifted = dragging == layer.id,
                            offset = dragOffset,
                            lastIndex = layers.lastIndex,
                            onReorder = stackActions.onReorder,
                        ) { id, offset ->
                            dragging = id
                            dragOffset = offset
                        },
                ) {
                    LayerRow(
                        layer = layer,
                        isActive = layer.id == activeLayerId,
                        thumbnail = thumbnails[layer.id],
                        actions = rowActions,
                        options = optionActions,
                        onBlendMode = { blendTarget = layer },
                        onRename = { renameTarget = layer },
                        picked = layer.id in pickedIds,
                        onPick = { onPicked(if (layer.id in pickedIds) pickedIds - layer.id else pickedIds + layer.id) },
                    )
                }
            }
        }

        stackActions.background?.let { BackgroundRow(it) }

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
                        label = stringResource(R.string.adjustmentoverlay_amount),
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
            currentBlendContract = layer.blendContract,
            isGroup = layer.isGroup,
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
            title = { Text(stringResource(R.string.editorsheets_rename_layer)) },
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
                }) { Text(stringResource(R.string.layer_rename)) }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** The Layers title row: move the active layer, add a layer and the stack's actions menu. */
@Composable
private fun LayersHeader(
    layers: List<Layer>,
    active: Layer?,
    stackActions: LayerStackActions,
) {
    var addMenuVisible by remember { mutableStateOf(false) }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.layers), style = MaterialTheme.typography.titleMedium)
        Row {
            IconButton(
                enabled = active != null && active.index < layers.lastIndex,
                onClick = { active?.let { stackActions.onReorder(it.id, it.index + 1) } },
            ) {
                Icon(Icons.Default.ArrowUpward, contentDescription = stringResource(R.string.editorsheets_move_active_layer_up))
            }
            IconButton(
                enabled = active != null && active.index > 0,
                onClick = { active?.let { stackActions.onReorder(it.id, it.index - 1) } },
            ) {
                Icon(Icons.Default.ArrowDownward, contentDescription = stringResource(R.string.editorsheets_move_active_layer_down))
            }
            IconButton(onClick = stackActions.onAddLayer) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.editorsheets_add_layer))
            }
            Box {
                IconButton(onClick = { addMenuVisible = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.editorsheets_layer_actions))
                }
                DropdownMenu(expanded = addMenuVisible, onDismissRequest = { addMenuVisible = false }) {
                    stackActions.onImportPsd?.let { importPsd ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.editorsheets_import_psd_layers)) },
                            onClick = {
                                importPsd()
                                addMenuVisible = false
                            },
                        )
                    }
                    stackActions.onInsertPhoto?.let { insert ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.editorsheets_insert_photo)) },
                            onClick = {
                                insert()
                                addMenuVisible = false
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.editorsheets_merge_visible)) },
                        onClick = {
                            stackActions.onMergeVisible()
                            addMenuVisible = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.editorsheets_flatten_image)) },
                        onClick = {
                            stackActions.onFlatten()
                            addMenuVisible = false
                        },
                    )
                    GroupMenuItems(active, stackActions) { addMenuVisible = false }
                    EffectLayerMenuItems(stackActions.onAddAdjustment, stackActions.onAddFilter) { addMenuVisible = false }
                }
            }
        }
    }
}

/** Shown while layers are multi-selected: group or delete them together. */
@Composable
private fun MultiLayerBar(
    count: Int,
    onGroup: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.editorsheets_layers_selected, count),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onGroup) { Text(stringResource(R.string.editorsheets_group)) }
        TextButton(onClick = onMerge) { Text(stringResource(R.string.editorsheets_merge)) }
        TextButton(onClick = onDelete) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = onClear) { Text(stringResource(R.string.layeropacityoverlay_done)) }
    }
}

@Composable
private fun LayerMaskControls(
    layer: Layer,
    hasSelection: Boolean,
    actions: LayerMaskActions,
) {
    var maskMenuVisible by remember(layer.id) { mutableStateOf(false) }
    Text(stringResource(R.string.layer_mask), style = MaterialTheme.typography.titleSmall)
    if (!layer.hasMask()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                Button(
                    onClick = { maskMenuVisible = true },
                    enabled = !layer.isLocked && !layer.isReference,
                ) { Text(stringResource(R.string.editorsheets_add_mask)) }
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
                stringResource(R.string.editorsheets_a_mask_hides_parts_of_this_layer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val canPaint = layer.canEdit() && layer.maskEnabled && !layer.isReference
            OutlinedButton(
                onClick = { actions.onPaintMask(true) },
                enabled = canPaint,
            ) { Text(stringResource(R.string.editorsheets_paint_reveal)) }
            OutlinedButton(
                onClick = { actions.onPaintMask(false) },
                enabled = canPaint,
            ) { Text(stringResource(R.string.editorsheets_paint_hide)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { actions.onMaskEnabled(!layer.maskEnabled) },
                label = { Text(if (layer.maskEnabled) "Enabled" else "Disabled") },
            )
            AssistChip(onClick = actions.onInvertMask, label = { Text(stringResource(R.string.chip_invert)) })
            AssistChip(onClick = actions.onRemoveMask, label = { Text(stringResource(R.string.editorsheets_remove)) })
        }
        LabeledSlider(
            label = stringResource(R.string.editorsheets_density),
            value = layer.maskDensity,
            range = 0f..1f,
            onChange = actions.onMaskDensity,
        )
        LabeledSlider(
            label = stringResource(R.string.editorsheets_feather),
            value = layer.maskFeather,
            range = 0f..64f,
            onChange = actions.onMaskFeather,
        )
    }
}

/**
 * Touch and hold a layer, then drag it up or down the stack, as in Procreate. [onDrag] reports the
 * lifted layer (null when dropped) and its offset in pixels.
 */
private fun Modifier.reorderOnLongPress(
    layer: Layer,
    lifted: Boolean,
    offset: Float,
    lastIndex: Int,
    onReorder: (Long, Int) -> Unit,
    onDrag: (Long?, Float) -> Unit,
): Modifier =
    zIndex(if (lifted) 1f else 0f)
        .graphicsLayer {
            translationY = if (lifted) offset else 0f
            shadowElevation = if (lifted) 8f else 0f
        }.pointerInput(layer.id, layer.index) {
            var travel = 0f
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    travel = 0f
                    onDrag(layer.id, 0f)
                },
                onDragEnd = {
                    // The list runs top layer first, so dragging down moves the layer lower in the stack.
                    val steps = (travel / size.height.coerceAtLeast(1)).roundToInt()
                    if (steps != 0) onReorder(layer.id, (layer.index - steps).coerceIn(0, lastIndex))
                    onDrag(null, 0f)
                },
                onDragCancel = { onDrag(null, 0f) },
            ) { change, amount ->
                change.consume()
                travel += amount.y
                onDrag(layer.id, travel)
            }
        }

@Composable
private fun BackgroundRow(background: BackgroundControls) {
    val useColourDescription = stringResource(R.string.editorsheets_use_current_colour_for_background)
    val showBackgroundDescription = stringResource(R.string.editorsheets_show_background)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorChip(
            background.color or 0xFF000000.toInt(),
            onClick = background.onUseCurrentColour,
            modifier = Modifier.semantics { contentDescription = useColourDescription },
        )
        Text(
            stringResource(R.string.editorsheets_background_colour),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        Checkbox(
            checked = (background.color ushr 24) != 0,
            onCheckedChange = background.onVisible,
            modifier = Modifier.semantics { contentDescription = showBackgroundDescription },
        )
    }
}

/** Header for a group (collapse toggle) — member rows are indented by [LayerRow]. */
@Composable
private fun GroupedRow(
    layer: Layer,
    collapsed: Boolean,
    onToggle: () -> Unit,
) {
    if (!layer.isGroup) return
    TextButton(onClick = onToggle, modifier = Modifier.padding(start = 8.dp)) {
        Icon(if (collapsed) Icons.Default.ChevronRight else Icons.Default.ExpandMore, contentDescription = null)
        Text(if (collapsed) "Show ${layer.name}" else "Collapse ${layer.name}", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun GroupMenuItems(
    active: Layer?,
    actions: LayerStackActions,
    dismiss: () -> Unit,
) {
    val group = actions.onGroupWithBelow
    if (active != null && !active.isGroup && group != null) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.editorsheets_group_with_layer_below)) },
            onClick = {
                group(active.id)
                dismiss()
            },
        )
    }
    val ungroup = actions.onUngroup
    val groupId = if (active?.isGroup == true) active.id else active?.parentGroupId
    if (groupId != null && ungroup != null) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.editorsheets_ungroup)) },
            onClick = {
                ungroup(groupId)
                dismiss()
            },
        )
    }
}

@Composable
private fun EffectLayerMenuItems(
    onAddAdjustment: (AdjustmentType) -> Unit,
    onAddFilter: (FilterType) -> Unit,
    dismiss: () -> Unit,
) {
    Divider()
    DropdownMenuItem(
        text = { Text(stringResource(R.string.editorsheets_add_adjustment_layer)) },
        onClick = { dismiss() },
        trailingIcon = { Icon(Icons.Default.Tune, contentDescription = null) },
    )
    AdjustmentType.entries.forEach { type ->
        DropdownMenuItem(
            text = { Text("   ${type.displayName}") },
            onClick = {
                onAddAdjustment(type)
                dismiss()
            },
        )
    }
    Divider()
    DropdownMenuItem(
        text = { Text(stringResource(R.string.editorsheets_add_filter_layer)) },
        onClick = { dismiss() },
        trailingIcon = { Icon(Icons.Default.FilterVintage, contentDescription = null) },
    )
    FilterType.entries.forEach { type ->
        DropdownMenuItem(
            text = { Text("   ${type.displayName}") },
            onClick = {
                onAddFilter(type)
                dismiss()
            },
        )
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
        Text(stringResource(R.string.editorchrome_selection), style = MaterialTheme.typography.titleMedium)
        Text(
            if (selectionCount > 0) "$selectionCount pixels selected" else "Nothing selected",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(stringResource(R.string.editorsheets_combine_mode), style = MaterialTheme.typography.labelMedium)
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
            label = stringResource(R.string.editorsheets_magic_wand_tolerance),
            value = tolerance.toFloat(),
            range = 0f..255f,
            onChange = { onToleranceChange(it.toInt()) },
        )
        LabeledSlider(
            label = stringResource(R.string.editorsheets_feather_radius),
            value = featherRadius.toFloat(),
            range = 0f..64f,
            onChange = { onFeatherChange(it.toInt()) },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = onSelectAll, label = { Text(stringResource(R.string.editorchrome_select_all)) })
            AssistChip(onClick = onInvertSelection, label = { Text(stringResource(R.string.chip_invert)) })
            AssistChip(onClick = onClearSelection, enabled = hasSelection, label = { Text(stringResource(R.string.chip_clear)) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = onApplyFeather,
                enabled = hasSelection,
                label = { Text(stringResource(R.string.editorsheets_apply_feather)) },
            )
            AssistChip(onClick = onSelectionFromLayer, label = { Text(stringResource(R.string.editorsheets_from_layer_alpha)) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = onTrimToSelection,
                enabled = hasSelection,
                label = { Text(stringResource(R.string.editorsheets_crop_to_selection)) },
            )
            AssistChip(onClick = { onColorRange(0xFF000000.toInt()) }, label = { Text(stringResource(R.string.editorsheets_select_black)) })
            AssistChip(onClick = { onColorRange(0xFFFFFFFF.toInt()) }, label = { Text(stringResource(R.string.editorsheets_select_white)) })
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
        val printSize = CanvasOperations.CanvasProperties(width, height, dpi, backgroundColor)
        Text(
            "$width × $height px · $dpi dpi · %.1f × %.1f in".format(printSize.widthInches, printSize.heightInches),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Box {
            OutlinedButton(onClick = { presetMenu = true }) { Text(stringResource(R.string.export_presets)) }
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
                label = { Text(stringResource(R.string.editorsheets_width)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = targetHeight,
                onValueChange = { targetHeight = it.filter { c -> c.isDigit() }.take(5) },
                label = { Text(stringResource(R.string.editorsheets_height)) },
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

        Text(stringResource(R.string.editorsheets_anchor), style = MaterialTheme.typography.labelMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
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
        }) { Text(stringResource(R.string.editorsheets_apply_resize)) }

        Divider()
        Text(stringResource(R.string.editorsheets_transform), style = MaterialTheme.typography.titleSmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AssistChip(onClick = { onRotate(90) }, label = { Text(stringResource(R.string.editorchrome_rotate_90)) })
            AssistChip(onClick = { onRotate(-90) }, label = { Text(stringResource(R.string.editorchrome_rotate_90_2)) })
            AssistChip(onClick = { onRotate(180) }, label = { Text(stringResource(R.string.editorsheets_rotate_180)) })
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AssistChip(onClick = { onFlip(false) }, label = { Text(stringResource(R.string.editorsheets_flip_horizontal)) })
            AssistChip(onClick = { onFlip(true) }, label = { Text(stringResource(R.string.editorsheets_flip_vertical)) })
            AssistChip(onClick = onTrim, label = { Text(stringResource(R.string.editorsheets_trim_transparency)) })
        }

        Divider()
        // The drag only moves this slider; the resolution is set once on release, so one drag is one undo step.
        var liveDpi by remember(dpi) { mutableStateOf(dpi.toFloat()) }
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Resolution (dpi)", style = MaterialTheme.typography.labelMedium)
                Text(
                    "${liveDpi.roundToInt()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = liveDpi.coerceIn(36f, 600f),
                onValueChange = { liveDpi = it },
                onValueChangeFinished = { onDpi(liveDpi.roundToInt()) },
                valueRange = 36f..600f,
            )
        }

        Text(stringResource(R.string.editorsheets_background), style = MaterialTheme.typography.titleSmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
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

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedButton(onClick = { onClear(0) }) { Text(stringResource(R.string.editorsheets_clear_to_transparency)) }
            OutlinedButton(onClick = { onClear(backgroundColor) }) { Text(stringResource(R.string.editorsheets_fill_with_background)) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Animation
// ---------------------------------------------------------------------------------------------

/** Frame copy and paste for the Animation panel: whether a frame is on the clipboard, and the two actions. */
data class FrameClipboard(
    val canPaste: Boolean,
    val onCopy: (Int) -> Unit,
    val onPaste: (Int) -> Unit,
)

/** The app-wide onion skin switch as the Animation panel shows it. */
data class OnionSwitch(
    val enabled: Boolean,
    val onToggle: (Boolean) -> Unit,
)

@Composable
fun AnimationSheet(
    timeline: AnimationTimeline.State,
    onion: OnionSwitch,
    onSelectFrame: (Int) -> Unit,
    onAddFrame: (Boolean) -> Unit,
    onDeleteFrame: (Int) -> Unit,
    onMoveFrame: (Int, Int) -> Unit,
    onFrameDuration: (Int, Int) -> Unit,
    onSettings: (AnimationSettings) -> Unit,
    onTogglePlayback: () -> Unit,
    clipboard: FrameClipboard,
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
            Text(stringResource(R.string.editorsheets_animation), style = MaterialTheme.typography.titleMedium)
            Row {
                IconButton(onClick = { onAddFrame(false) }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.editorsheets_new_frame))
                }
                IconButton(onClick = { onAddFrame(true) }) {
                    Icon(Icons.Default.FilterNone, contentDescription = stringResource(R.string.editorsheets_duplicate_frame))
                }
                IconButton(onClick = { clipboard.onCopy(timeline.activeIndex) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.editorsheets_copy_frame))
                }
                IconButton(onClick = { clipboard.onPaste(timeline.activeIndex + 1) }, enabled = clipboard.canPaste) {
                    Icon(Icons.Default.ContentPaste, contentDescription = stringResource(R.string.editorsheets_paste_frame))
                }
                IconButton(
                    onClick = onTogglePlayback,
                    enabled = timeline.isPlaying || AnimationPlayback.forwardIndices(timeline).size > 1,
                ) {
                    Icon(
                        imageVector = if (timeline.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (timeline.isPlaying) "Pause" else "Play",
                    )
                }
            }
        }

        FrameStrip(
            timeline = timeline,
            onSelectFrame = onSelectFrame,
            onMoveFrame = onMoveFrame,
            onDeleteFrame = onDeleteFrame,
        )

        Text(
            stringResource(R.string.editorsheets_total_duration_and_frames, timeline.durationMs, timeline.frameCount),
            style = MaterialTheme.typography.bodySmall,
        )

        LabeledSlider(
            label = stringResource(R.string.editorsheets_frame_duration_ms),
            value =
                timeline.frames
                    .getOrNull(timeline.activeIndex)
                    ?.durationMs
                    ?.toFloat() ?: 100f,
            range = 16f..2000f,
            onChange = { onFrameDuration(timeline.activeIndex, it.toInt()) },
        )
        LabeledSlider(
            label = stringResource(R.string.editorsheets_playback_fps),
            value = settings.fps.toFloat(),
            range = 1f..60f,
            onChange = { onSettings(settings.copy(fps = it.toInt())) },
        )
        Text(stringResource(R.string.editorsheets_play_mode), style = MaterialTheme.typography.labelMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val playMode = PlayMode.of(settings)
            PlayMode.entries.forEach { mode ->
                FilterChip(
                    selected = playMode == mode,
                    onClick = { onSettings(settings.withPlayMode(mode)) },
                    label = { Text(mode.displayName, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        AnimationFrameSettings(timeline, onSettings)
        AnimationRangeSettings(timeline, onSettings)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = onion.enabled, onCheckedChange = onion.onToggle)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.editorsheets_onion_skin), style = MaterialTheme.typography.bodySmall)
        }
        LabeledSlider(
            label = stringResource(R.string.editorsheets_onion_skin_frames),
            value = settings.onionSkinFrames.toFloat(),
            range = 0f..AnimationSettings.MAX_ONION_SKIN_FRAMES.toFloat(),
            onChange = { onSettings(settings.copy(onionSkinFrames = it.toInt())) },
        )
        LabeledSlider(
            label = stringResource(R.string.editorsheets_onion_skin_opacity),
            value = settings.onionSkinOpacity,
            range = 0f..1f,
            onChange = { onSettings(settings.copy(onionSkinOpacity = it)) },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = settings.onionSkinTinted, onCheckedChange = { onSettings(settings.copy(onionSkinTinted = it)) })
            Spacer(Modifier.width(8.dp))
            Text("Color secondary frames", style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = settings.blendPrimaryFrame, onCheckedChange = { onSettings(settings.copy(blendPrimaryFrame = it)) })
            Spacer(Modifier.width(8.dp))
            Text("Blend primary frame", style = MaterialTheme.typography.bodySmall)
        }
        AnimationOnionColors(settings, onSettings)
    }
}

// ---------------------------------------------------------------------------------------------
// Guides
// ---------------------------------------------------------------------------------------------

/** Whether guides pull strokes: globally, and on the current layer through Drawing Assist. */
data class GuideAssist(
    val snapToGuides: Boolean,
    val onSnap: (Boolean) -> Unit,
    val assisted: Boolean = false,
    val onAssisted: (Boolean) -> Unit = {},
    /** Opens Edit Drawing Guide, where the vanishing points are dragged on the canvas. */
    val onEditPoints: () -> Unit = {},
)

@Composable
fun GuidesSheet(
    symmetry: SymmetryEngine.Settings,
    perspective: PerspectiveGuide.Settings,
    showSymmetryGuides: Boolean,
    showPerspectiveGuides: Boolean,
    onSymmetry: (SymmetryEngine.Settings) -> Unit,
    onPerspective: (PerspectiveGuide.Settings) -> Unit,
    onShowSymmetry: (Boolean) -> Unit,
    onShowPerspective: (Boolean) -> Unit,
    assist: GuideAssist,
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
        Text(stringResource(R.string.editorsheets_symmetry), style = MaterialTheme.typography.titleMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SymmetryEngine.SymmetryType.entries.forEach { type ->
                FilterChip(
                    selected = symmetry.type == type,
                    onClick = { onSymmetry(symmetry.copy(type = type)) },
                    label = { Text(type.displayName, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        LabeledSlider(
            label = stringResource(R.string.editorsheets_radial_copies),
            value = symmetry.radialCount.toFloat(),
            range = SymmetryEngine.RADIAL_RANGE.first.toFloat()..SymmetryEngine.RADIAL_RANGE.last.toFloat(),
            onChange = { onSymmetry(symmetry.copy(radialCount = it.toInt())) },
        )
        LabeledSlider(
            label = stringResource(R.string.editorsheets_axis_x),
            value = symmetry.centreX,
            range = 0f..1f,
            onChange = { onSymmetry(symmetry.copy(centreX = it)) },
        )
        LabeledSlider(
            label = stringResource(R.string.editorsheets_axis_y),
            value = symmetry.centreY,
            range = 0f..1f,
            onChange = { onSymmetry(symmetry.copy(centreY = it)) },
        )
        if (symmetry.isActive()) OutlinedButton(onClick = assist.onEditPoints) { Text("Move axis on the canvas") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = symmetry.secondaryAxis,
                onCheckedChange = { onSymmetry(symmetry.copy(secondaryAxis = it)) },
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.editorsheets_second_axis_kaleidoscope), style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = symmetry.rotational,
                onCheckedChange = { onSymmetry(symmetry.copy(rotational = it)) },
            )
            Spacer(Modifier.width(8.dp))
            Text("Rotational symmetry", style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = symmetry.mirroredSegments,
                onCheckedChange = { onSymmetry(symmetry.copy(mirroredSegments = it)) },
            )
            Spacer(Modifier.width(8.dp))
            Text("Mirror radial segments", style = MaterialTheme.typography.bodySmall)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(SymmetryEngine.PRESETS) { preset ->
                AssistChip(
                    onClick = { onSymmetry(SymmetryEngine.sanitize(preset.settings)) },
                    label = { Text(preset.name, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        GuideLineStyleControls(
            style = symmetry.lineStyle,
            onStyle = { onSymmetry(symmetry.copy(lineStyle = it)) },
        )

        Divider()
        Text(stringResource(R.string.editorsheets_perspective), style = MaterialTheme.typography.titleMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PerspectiveGuide.GuideType.entries.forEach { type ->
                FilterChip(
                    selected = perspective.type == type,
                    onClick = { onPerspective(perspective.copy(type = type)) },
                    label = { Text(type.displayName, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        LabeledSlider(
            label = stringResource(R.string.editorsheets_rays),
            value = perspective.density.toFloat(),
            range = PerspectiveGuide.DENSITY_RANGE.first.toFloat()..PerspectiveGuide.DENSITY_RANGE.last.toFloat(),
            onChange = { onPerspective(perspective.copy(density = it.toInt())) },
        )
        if (perspective.type == PerspectiveGuide.GuideType.GRID || perspective.type == PerspectiveGuide.GuideType.ISOMETRIC) {
            LabeledSlider(
                label = "Grid size",
                value = perspective.gridSpacing.toFloat(),
                range = 8f..512f,
                onChange = { onPerspective(perspective.copy(gridSpacing = it.roundToInt())) },
            )
        }
        if (perspective.type == PerspectiveGuide.GuideType.GRID) {
            LabeledSlider(
                label = "Grid rotation",
                value = perspective.gridRotation,
                range = 0f..180f,
                onChange = { onPerspective(perspective.copy(gridRotation = it.roundToInt().toFloat())) },
            )
        }
        if (perspective.activePointCount() > 0) {
            OutlinedButton(onClick = assist.onEditPoints) { Text("Edit vanishing points") }
        }
        GuideAppearance(perspective, onPerspective)
        LabeledSlider(
            label = stringResource(R.string.editorsheets_horizon),
            value = perspective.horizonY,
            range = 0f..1f,
            onChange = { onPerspective(PerspectiveGuide.withHorizon(perspective, it)) },
        )
        if (perspective.type == PerspectiveGuide.GuideType.GRID || perspective.type == PerspectiveGuide.GuideType.ISOMETRIC) {
            LabeledSlider(
                label = "Grid size (px)",
                value = perspective.gridSpacing.toFloat(),
                range = 8f..512f,
                onChange = { onPerspective(perspective.copy(gridSpacing = it.toInt())) },
            )
        }
        LabeledSlider(
            label = stringResource(R.string.editorsheets_snap_radius),
            value = perspective.snapRadius,
            range = PerspectiveGuide.SNAP_RADIUS_RANGE,
            onChange = { onPerspective(perspective.copy(snapRadius = it)) },
        )
        LabeledSlider(
            label = stringResource(R.string.editorsheets_snap_strength),
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
            Text(stringResource(R.string.editorsheets_snap_to_rays), style = MaterialTheme.typography.bodySmall)
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
        Text(stringResource(R.string.editorsheets_display), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = assist.assisted, onCheckedChange = assist.onAssisted)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.editorsheets_assisted_drawing_on_this_layer), style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = assist.snapToGuides, onCheckedChange = assist.onSnap)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.editorsheets_snap_strokes_to_guides), style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = showSymmetryGuides, onCheckedChange = onShowSymmetry)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.editorsheets_show_symmetry_guides), style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = showPerspectiveGuides, onCheckedChange = onShowPerspective)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.editorsheets_show_perspective_guides), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Thickness, opacity and colour of one guide's lines, as Procreate's guide editor offers them. */
@Composable
private fun GuideLineStyleControls(
    style: GuideLineStyle,
    onStyle: (GuideLineStyle) -> Unit,
) {
    LabeledSlider(
        label = stringResource(R.string.editorsheets_line_thickness),
        value = style.thickness,
        range = GuideLineStyle.THICKNESS_RANGE,
        onChange = { onStyle(style.copy(thickness = it)) },
    )
    LabeledSlider(
        label = stringResource(R.string.editorsheets_line_opacity),
        value = style.opacity,
        range = GuideLineStyle.OPACITY_RANGE,
        onChange = { onStyle(style.copy(opacity = it)) },
    )
    ColourSwatches(
        label = stringResource(R.string.editorsheets_line_colour),
        selected = style.colour or OPAQUE,
        choices = GuideLineStyle.COLOUR_CHOICES.map { it or OPAQUE },
        onSelect = { onStyle(style.copy(colour = it and RGB_ONLY)) },
    )
}

/** A label and a row of colour swatches; the selected one has a heavier outline. Colours are ARGB. */
@Composable
private fun ColourSwatches(
    label: String,
    selected: Int,
    choices: List<Int>,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        choices.forEach { choice ->
            val description = stringResource(R.string.editorsheets_colour_swatch, label, choice.toUInt().toString(16).padStart(8, '0'))
            Box(
                modifier =
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color(choice))
                        .border(if (choice == selected) 3.dp else 1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable { onSelect(choice) }
                        .semantics { contentDescription = description },
            )
        }
    }
}

private const val OPAQUE = -0x1000000
private const val RGB_ONLY = 0x00FFFFFF

/**
 * The frame strip: tap a frame to select it, press and hold then drag to reorder, and use its
 * buttons to step a frame earlier or later or to delete it.
 */
@Composable
private fun FrameStrip(
    timeline: AnimationTimeline.State,
    onSelectFrame: (Int) -> Unit,
    onMoveFrame: (Int, Int) -> Unit,
    onDeleteFrame: (Int) -> Unit,
) {
    var dragging by remember { mutableStateOf<Int?>(null) }
    var travel by remember { mutableFloatStateOf(0f) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(timeline.frames.size) { index ->
            val frame = timeline.frames[index]
            Card(
                modifier =
                    Modifier
                        .width(84.dp)
                        .zIndex(if (dragging == index) 1f else 0f)
                        .graphicsLayer { translationX = if (dragging == index) travel else 0f }
                        .clickable { onSelectFrame(index) }
                        .pointerInput(index, timeline.frames.size) {
                            val gap = 6.dp.toPx()
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    dragging = index
                                    travel = 0f
                                },
                                onDragEnd = {
                                    val steps = (travel / (size.width + gap)).roundToInt()
                                    val target = (index + steps).coerceIn(0, timeline.frames.lastIndex)
                                    if (target != index) onMoveFrame(index, target)
                                    dragging = null
                                    travel = 0f
                                },
                                onDragCancel = {
                                    dragging = null
                                    travel = 0f
                                },
                            ) { change, amount ->
                                change.consume()
                                travel += amount.x
                            }
                        },
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
                    val role = AnimationPlayback.role(timeline.frames.map { it.id }, timeline.settings, index)
                    if (role != AnimationPlayback.Role.ANIMATED) {
                        Text(role.label, style = MaterialTheme.typography.labelSmall)
                    }
                    if (frame.id in timeline.settings.hiddenFrameIds) {
                        Text(stringResource(R.string.animation_hidden), style = MaterialTheme.typography.labelSmall)
                    }
                    Text(
                        "${frame.durationMs} ms",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Row {
                        IconButton(
                            onClick = { if (index > 0) onMoveFrame(index, index - 1) },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                Icons.Default.ChevronLeft,
                                contentDescription = stringResource(R.string.editorsheets_move_earlier),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        IconButton(
                            onClick = { if (index < timeline.frames.lastIndex) onMoveFrame(index, index + 1) },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = stringResource(R.string.editorsheets_move_later),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        IconButton(onClick = { onDeleteFrame(index) }, modifier = Modifier.size(48.dp)) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.editorsheets_delete_frame),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
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
    modifier: Modifier = Modifier,
    importedFonts: List<String> = emptyList(),
    onImportFont: (() -> Unit)? = null,
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
            Text(stringResource(R.string.editorsheets_text), style = MaterialTheme.typography.titleMedium)
            Button(onClick = onPlace, enabled = text.isNotBlank()) { Text(stringResource(R.string.editorsheets_place_on_canvas)) }
        }

        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text(stringResource(R.string.editorsheets_content)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )

        var fontMenu by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { fontMenu = true }) { Text(FontLibrary.label(style.fontFamily)) }
            DropdownMenu(expanded = fontMenu, onDismissRequest = { fontMenu = false }) {
                onImportFont?.let { import ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.editorsheets_import_font)) },
                        onClick = {
                            fontMenu = false
                            import()
                        },
                    )
                }
                (importedFonts + TextLayout.FONT_FAMILIES).forEach { family ->
                    DropdownMenuItem(
                        text = { Text(FontLibrary.label(family)) },
                        onClick = {
                            onStyleChange(style.copy(fontFamily = family))
                            fontMenu = false
                        },
                    )
                }
            }
        }

        LabeledSlider(
            label = stringResource(R.string.size),
            value = style.fontSize,
            range = 8f..400f,
            onChange = { onStyleChange(style.copy(fontSize = it)) },
        )
        LabeledSlider(
            label = stringResource(R.string.editorsheets_line_height),
            value = style.lineHeight,
            range = 0.8f..3f,
            onChange = { onStyleChange(style.copy(lineHeight = it)) },
        )
        LabeledSlider(
            label = stringResource(R.string.editorsheets_letter_spacing),
            value = style.letterSpacing,
            range = -4f..40f,
            onChange = { onStyleChange(style.copy(letterSpacing = it)) },
        )
        LabeledSlider(
            label = stringResource(R.string.editorsheets_wrap_width),
            value = style.maxWidth ?: 0f,
            range = 0f..4000f,
            onChange = { onStyleChange(style.copy(maxWidth = if (it <= 0f) null else it)) },
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(
                selected = style.bold,
                onClick = { onStyleChange(style.copy(bold = !style.bold)) },
                label = { Text(stringResource(R.string.editorsheets_bold)) },
            )
            FilterChip(
                selected = style.italic,
                onClick = { onStyleChange(style.copy(italic = !style.italic)) },
                label = { Text(stringResource(R.string.editorsheets_italic)) },
            )
            FilterChip(
                selected = style.underline,
                onClick = { onStyleChange(style.copy(underline = !style.underline)) },
                label = { Text(stringResource(R.string.editorsheets_underline)) },
            )
            FilterChip(
                selected = style.strikeThrough,
                onClick = { onStyleChange(style.copy(strikeThrough = !style.strikeThrough)) },
                label = { Text(stringResource(R.string.editorsheets_strike)) },
            )
            FilterChip(
                selected = style.allCaps,
                onClick = { onStyleChange(style.copy(allCaps = !style.allCaps)) },
                label = { Text(stringResource(R.string.editorsheets_all_caps)) },
            )
            FilterChip(
                selected = style.outline,
                onClick = { onStyleChange(style.copy(outline = !style.outline)) },
                label = { Text(stringResource(R.string.editorsheets_outline)) },
            )
            FilterChip(
                selected = style.vertical,
                onClick = { onStyleChange(style.copy(vertical = !style.vertical)) },
                label = { Text(stringResource(R.string.editorsheets_vertical)) },
            )
        }

        Text(stringResource(R.string.editorsheets_alignment), style = MaterialTheme.typography.labelMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextLayout.Alignment.entries.forEach { alignment ->
                FilterChip(
                    selected = style.alignment == alignment,
                    onClick = { onStyleChange(style.copy(alignment = alignment)) },
                    label = { Text(alignment.displayName, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        Text(stringResource(R.string.editorsheets_colour), style = MaterialTheme.typography.labelMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TEXT_SWATCHES.forEach { (name, swatch) ->
                ColorChip(
                    swatch,
                    onClick = { onColorChange(swatch) },
                    modifier =
                        Modifier
                            .border(if (swatch == color) 3.dp else 1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .semantics {
                                contentDescription = name
                                selected = swatch == color
                            },
                )
            }
            Text(
                "Any other colour: pick it in the Colour panel; the text uses the current colour.",
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
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        )
    }
}

/** Procreate's guide appearance: line colour, opacity and thickness. */
@Composable
private fun GuideAppearance(
    perspective: PerspectiveGuide.Settings,
    onPerspective: (PerspectiveGuide.Settings) -> Unit,
) {
    Text("Guide colour", style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GUIDE_SWATCHES.forEach { (name, swatch) ->
            ColorChip(
                swatch,
                onClick = { onPerspective(perspective.copy(lineColor = swatch)) },
                modifier =
                    Modifier
                        .border(if (swatch == perspective.lineColor) 3.dp else 1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .semantics {
                            contentDescription = "$name guide"
                            selected = swatch == perspective.lineColor
                        },
            )
        }
    }
    LabeledSlider(
        label = "Guide opacity",
        value = perspective.lineOpacity,
        range = 0.05f..1f,
        onChange = { onPerspective(perspective.copy(lineOpacity = it)) },
    )
    LabeledSlider(
        label = "Guide thickness",
        value = perspective.lineThickness,
        range = 0.5f..PerspectiveGuide.MAX_LINE_THICKNESS,
        onChange = { onPerspective(perspective.copy(lineThickness = it)) },
    )
}

private val GUIDE_SWATCHES =
    listOf(
        "Green" to PerspectiveGuide.DEFAULT_LINE_COLOR,
        "Blue" to 0xFF2D9CDB.toInt(),
        "Red" to 0xFFE53935.toInt(),
        "Purple" to 0xFF8E24AA.toInt(),
        "Black" to 0xFF111111.toInt(),
        "White" to 0xFFFFFFFF.toInt(),
    )

/** Quick text colours; any other comes from the Colour panel. */
private val TEXT_SWATCHES =
    listOf(
        "Black" to 0xFF111111.toInt(),
        "White" to 0xFFFFFFFF.toInt(),
        "Red" to 0xFFD32F2F.toInt(),
        "Orange" to 0xFFF57C00.toInt(),
        "Yellow" to 0xFFFBC02D.toInt(),
        "Green" to 0xFF388E3C.toInt(),
        "Blue" to 0xFF1976D2.toInt(),
        "Purple" to 0xFF7B1FA2.toInt(),
    )
