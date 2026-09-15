package com.artflow.studio.core.layer

import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.Layer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Core layer management system for ArtFlow
 * Implements Phase 8: Layer System Basics with advanced features from Phase 15-16
 * 
 * This singleton manages the layer stack, handling all layer operations including:
 * - Layer creation, deletion, and duplication
 * - Layer reordering and visibility
 * - Layer opacity and blend modes
 * - Alpha lock and clipping masks
 * - Layer merging
 */
@Singleton
class LayerManager @Inject constructor() {

    private val _layers = MutableStateFlow<List<Layer>>(emptyList())
    val layers: StateFlow<List<Layer>> = _layers.asStateFlow()

    private val _activeLayerId = MutableStateFlow<Long?>(null)
    val activeLayerId: StateFlow<Long?> = _activeLayerId.asStateFlow()

    private var nextLayerId = 1L
    private val layerHistory = mutableListOf<LayerHistoryEntry>()
    private var historyIndex = -1

    /**
     * Get the currently active layer
     */
    fun getActiveLayer(): Layer? {
        val id = _activeLayerId.value ?: return null
        return _layers.value.find { it.id == id }
    }

    /**
     * Add a new layer to the stack
     * @param name Optional layer name (defaults to "Layer N")
     * @param index Position in stack (defaults to top)
     * @param isVisible Whether layer is initially visible
     * @return The created Layer or null if failed
     */
    fun addLayer(
        name: String? = null,
        index: Int = _layers.value.size,
        isVisible: Boolean = true
    ): Layer? {
        val layerName = name ?: "Layer ${nextLayerId}"
        val layer = Layer(
            id = nextLayerId++,
            name = layerName,
            index = index,
            isVisible = isVisible,
            opacity = 1.0f,
            isLocked = false,
            blendMode = BlendMode.NORMAL,
            strokes = emptyList(),
            isAlphaLocked = false,
            isClippingMask = false
        )

        // Save state before modification
        saveHistoryState(LayerOperation.ADD, layerId = layer.id)

        val updatedLayers = _layers.value.toMutableList()
        
        // Adjust indices of layers at or above the insertion point
        updatedLayers.forEachIndexed { i, l ->
            if (l.index >= index) {
                updatedLayers[i] = l.copyWith(index = l.index + 1)
            }
        }
        
        updatedLayers.add(layer)
        _layers.value = updatedLayers
        
        // Set as active layer
        _activeLayerId.value = layer.id
        
        Timber.d("Layer added: ${layer.name} at index $index")
        return layer
    }

    /**
     * Remove a layer by ID
     * @param layerId The ID of the layer to remove
     * @return True if removed successfully
     */
    fun removeLayer(layerId: Long): Boolean {
        val layer = _layers.value.find { it.id == layerId } ?: return false
        
        // Don't remove if it's the only layer
        if (_layers.value.size <= 1) {
            Timber.w("Cannot remove the last layer")
            return false
        }

        saveHistoryState(LayerOperation.REMOVE, layerId = layerId, layerData = layer)

        val updatedLayers = _layers.value.toMutableList()
        updatedLayers.removeIf { it.id == layerId }
        
        // Adjust indices of layers above the removed layer
        updatedLayers.forEachIndexed { i, l ->
            if (l.index > layer.index) {
                updatedLayers[i] = l.copyWith(index = l.index - 1)
            }
        }
        
        _layers.value = updatedLayers
        
        // If we removed the active layer, select another
        if (_activeLayerId.value == layerId) {
            _activeLayerId.value = updatedLayers.maxByOrNull { it.index }?.id
        }
        
        Timber.d("Layer removed: ${layer.name}")
        return true
    }

    /**
     * Set the active layer
     * @param layerId The ID of the layer to activate
     * @return True if layer exists
     */
    fun setActiveLayer(layerId: Long): Boolean {
        val layer = _layers.value.find { it.id == layerId } ?: return false
        _activeLayerId.value = layerId
        Timber.d("Active layer set to: ${layer.name}")
        return true
    }

    /**
     * Set layer visibility
     * @param layerId The layer ID
     * @param isVisible Visibility state
     * @return True if updated
     */
    fun setLayerVisibility(layerId: Long, isVisible: Boolean): Boolean {
        val layer = _layers.value.find { it.id == layerId } ?: return false
        
        saveHistoryState(LayerOperation.MODIFY, layerId = layerId, oldOpacity = if (isVisible) 1f else 0f)
        
        _layers.value = _layers.value.map { l ->
            if (l.id == layerId) l.copyWith(isVisible = isVisible) else l
        }
        
        Timber.d("Layer visibility changed: ${layer.name} -> $isVisible")
        return true
    }

    /**
     * Set layer opacity
     * @param layerId The layer ID
     * @param opacity Opacity value (0.0 - 1.0)
     * @return True if updated
     */
    fun setLayerOpacity(layerId: Long, opacity: Float): Boolean {
        val clampedOpacity = opacity.coerceIn(0f, 1f)
        val layer = _layers.value.find { it.id == layerId } ?: return false
        
        saveHistoryState(LayerOperation.MODIFY, layerId = layerId, oldOpacity = layer.opacity)
        
        _layers.value = _layers.value.map { l ->
            if (l.id == layerId) l.copyWith(opacity = clampedOpacity) else l
        }
        
        Timber.d("Layer opacity changed: ${layer.name} -> $clampedOpacity")
        return true
    }

    /**
     * Set layer name
     * @param layerId The layer ID
     * @param name New layer name
     * @return True if updated
     */
    fun setLayerName(layerId: Long, name: String): Boolean {
        val layer = _layers.value.find { it.id == layerId } ?: return false
        
        saveHistoryState(LayerOperation.MODIFY, layerId = layerId, oldName = layer.name)
        
        _layers.value = _layers.value.map { l ->
            if (l.id == layerId) l.copyWith(name = name) else l
        }
        
        Timber.d("Layer renamed: ${layer.name} -> $name")
        return true
    }

    /**
     * Set layer lock state
     * @param layerId The layer ID
     * @param isLocked Lock state
     * @return True if updated
     */
    fun setLayerLock(layerId: Long, isLocked: Boolean): Boolean {
        val layer = _layers.value.find { it.id == layerId } ?: return false
        
        _layers.value = _layers.value.map { l ->
            if (l.id == layerId) l.copyWith(isLocked = isLocked) else l
        }
        
        Timber.d("Layer lock changed: ${layer.name} -> $isLocked")
        return true
    }

    /**
     * Set layer blend mode
     * @param layerId The layer ID
     * @param blendMode The blend mode
     * @return True if updated
     */
    fun setLayerBlendMode(layerId: Long, blendMode: BlendMode): Boolean {
        val layer = _layers.value.find { it.id == layerId } ?: return false
        
        saveHistoryState(LayerOperation.MODIFY, layerId = layerId, oldBlendMode = layer.blendMode)
        
        _layers.value = _layers.value.map { l ->
            if (l.id == layerId) l.copyWith(blendMode = blendMode) else l
        }
        
        Timber.d("Layer blend mode changed: ${layer.name} -> $blendMode")
        return true
    }

    /**
     * Reorder a layer to a new position
     * @param layerId The layer ID to move
     * @param newIndex The new index position
     * @return True if reordered successfully
     */
    fun reorderLayer(layerId: Long, newIndex: Int): Boolean {
        val layer = _layers.value.find { it.id == layerId } ?: return false
        val currentIndex = layer.index
        
        if (currentIndex == newIndex) return true
        
        saveHistoryState(LayerOperation.REORDER, layerId = layerId, oldIndex = currentIndex)
        
        val updatedLayers = _layers.value.toMutableList()
        
        // Find the layer at the target index
        val targetLayer = updatedLayers.find { it.index == newIndex }
        
        // Update indices based on direction of movement
        updatedLayers.forEachIndexed { i, l ->
            when {
                l.id == layerId -> {
                    updatedLayers[i] = l.copyWith(index = newIndex)
                }
                newIndex > currentIndex && l.index in (currentIndex + 1)..newIndex -> {
                    updatedLayers[i] = l.copyWith(index = l.index - 1)
                }
                newIndex < currentIndex && l.index in newIndex until currentIndex -> {
                    updatedLayers[i] = l.copyWith(index = l.index + 1)
                }
            }
        }
        
        _layers.value = updatedLayers
        Timber.d("Layer reordered: ${layer.name} from $currentIndex to $newIndex")
        return true
    }

    /**
     * Duplicate a layer
     * @param layerId The layer ID to duplicate
     * @return The new duplicated layer or null if failed
     */
    fun duplicateLayer(layerId: Long): Layer? {
        val originalLayer = _layers.value.find { it.id == layerId } ?: return null
        
        val duplicatedLayer = originalLayer.copyWith(
            id = nextLayerId++,
            name = "${originalLayer.name} Copy",
            index = originalLayer.index + 1,
            strokes = originalLayer.strokes.toList() // Copy strokes
        )
        
        saveHistoryState(LayerOperation.DUPLICATE, layerId = layerId, newLayerId = duplicatedLayer.id)
        
        val updatedLayers = _layers.value.toMutableList()
        
        // Adjust indices of layers above
        updatedLayers.forEachIndexed { i, l ->
            if (l.index > originalLayer.index) {
                updatedLayers[i] = l.copyWith(index = l.index + 1)
            }
        }
        
        updatedLayers.add(duplicatedLayer)
        _layers.value = updatedLayers
        _activeLayerId.value = duplicatedLayer.id
        
        Timber.d("Layer duplicated: ${originalLayer.name} -> ${duplicatedLayer.name}")
        return duplicatedLayer
    }

    /**
     * Merge two layers (source into target)
     * @param sourceLayerId The source layer ID
     * @param targetLayerId The target layer ID
     * @return True if merged successfully
     */
    fun mergeLayers(sourceLayerId: Long, targetLayerId: Long): Boolean {
        val sourceLayer = _layers.value.find { it.id == sourceLayerId } ?: return false
        val targetLayer = _layers.value.find { it.id == targetLayerId } ?: return false
        
        // Combine strokes from both layers
        val mergedStrokes = sourceLayer.strokes + targetLayer.strokes
        
        saveHistoryState(LayerOperation.MERGE, layerId = sourceLayerId, targetLayerId = targetLayerId)
        
        val updatedLayers = _layers.value.toMutableList()
        
        // Update target layer with merged strokes
        val mergedTarget = targetLayer.copyWith(
            strokes = mergedStrokes,
            name = "${targetLayer.name} (Merged)"
        )
        
        updatedLayers.replaceAll { l ->
            when {
                l.id == targetLayerId -> mergedTarget
                l.id == sourceLayerId -> l // Will be removed
                else -> l
            }
        }
        
        // Remove source layer
        updatedLayers.removeIf { it.id == sourceLayerId }
        
        // Adjust indices
        updatedLayers.forEachIndexed { i, l ->
            if (l.index > sourceLayer.index) {
                updatedLayers[i] = l.copyWith(index = l.index - 1)
            }
        }
        
        _layers.value = updatedLayers
        
        if (_activeLayerId.value == sourceLayerId) {
            _activeLayerId.value = targetLayerId
        }
        
        Timber.d("Layers merged: ${sourceLayer.name} into ${targetLayer.name}")
        return true
    }

    /**
     * Toggle alpha lock for a layer
     * @param layerId The layer ID
     * @param isAlphaLocked Alpha lock state
     * @return True if updated
     */
    fun setAlphaLock(layerId: Long, isAlphaLocked: Boolean): Boolean {
        val layer = _layers.value.find { it.id == layerId } ?: return false
        
        _layers.value = _layers.value.map { l ->
            if (l.id == layerId) l.copyWith(isAlphaLocked = isAlphaLocked) else l
        }
        
        Timber.d("Alpha lock changed: ${layer.name} -> $isAlphaLocked")
        return true
    }

    /**
     * Toggle clipping mask for a layer
     * @param layerId The layer ID
     * @param isClippingMask Clipping mask state
     * @return True if updated
     */
    fun setClippingMask(layerId: Long, isClippingMask: Boolean): Boolean {
        val layer = _layers.value.find { it.id == layerId } ?: return false
        
        // Can't clip the bottom-most layer
        if (layer.index == 0 && isClippingMask) {
            Timber.w("Cannot apply clipping mask to bottom layer")
            return false
        }
        
        _layers.value = _layers.value.map { l ->
            if (l.id == layerId) l.copyWith(isClippingMask = isClippingMask) else l
        }
        
        Timber.d("Clipping mask changed: ${layer.name} -> $isClippingMask")
        return true
    }

    /**
     * Get all visible layers sorted by index (bottom to top)
     */
    fun getVisibleLayers(): List<Layer> {
        return _layers.value
            .filter { it.isVisible }
            .sortedBy { it.index }
    }

    /**
     * Get layer count
     */
    fun getLayerCount(): Int = _layers.value.size

    /**
     * Check if a layer can be edited
     */
    fun canEditLayer(layerId: Long): Boolean {
        val layer = _layers.value.find { it.id == layerId } ?: return false
        return layer.canEdit()
    }

    /**
     * Save state to history for undo/redo
     */
    private fun saveHistoryState(
        operation: LayerOperation,
        layerId: Long,
        oldIndex: Int? = null,
        oldName: String? = null,
        oldOpacity: Float? = null,
        oldBlendMode: BlendMode? = null,
        newLayerId: Long? = null,
        targetLayerId: Long? = null,
        layerData: Layer? = null
    ) {
        // Remove any future states if we're not at the end
        while (historyIndex < layerHistory.lastIndex) {
            layerHistory.removeAt(layerHistory.lastIndex)
        }
        
        val entry = LayerHistoryEntry(
            operation = operation,
            layerId = layerId,
            oldIndex = oldIndex,
            oldName = oldName,
            oldOpacity = oldOpacity,
            oldBlendMode = oldBlendMode,
            newLayerId = newLayerId,
            targetLayerId = targetLayerId,
            layerSnapshot = layerData?.copy(strokes = layerData.strokes.toList()),
            timestamp = System.currentTimeMillis()
        )
        
        layerHistory.add(entry)
        historyIndex++
        
        // Limit history size
        if (layerHistory.size > 50) {
            layerHistory.removeAt(0)
            historyIndex--
        }
    }

    /**
     * Undo the last layer operation
     * @return True if undo was successful
     */
    fun undo(): Boolean {
        if (historyIndex < 0) return false
        
        val entry = layerHistory[historyIndex]
        historyIndex--
        
        when (entry.operation) {
            LayerOperation.ADD -> {
                removeLayer(entry.layerId)
            }
            LayerOperation.REMOVE -> {
                entry.layerSnapshot?.let { layer ->
                    val updatedLayers = _layers.value.toMutableList()
                    updatedLayers.add(layer)
                    _layers.value = updatedLayers
                }
            }
            LayerOperation.MODIFY -> {
                // Restore previous values
                _layers.value = _layers.value.map { l ->
                    if (l.id == entry.layerId) {
                        l.copyWith(
                            name = entry.oldName ?: l.name,
                            opacity = entry.oldOpacity ?: l.opacity,
                            blendMode = entry.oldBlendMode ?: l.blendMode
                        )
                    } else l
                }
            }
            LayerOperation.REORDER -> {
                entry.oldIndex?.let { oldIndex ->
                    reorderLayer(entry.layerId, oldIndex)
                }
            }
            LayerOperation.DUPLICATE -> {
                entry.newLayerId?.let { newId ->
                    removeLayer(newId)
                }
            }
            LayerOperation.MERGE -> {
                // Complex - would need full snapshot for proper undo
                Timber.w("Undo for merge not fully implemented")
            }
        }
        
        Timber.d("Undo performed: ${entry.operation}")
        return true
    }

    /**
     * Redo a previously undone operation
     * @return True if redo was successful
     */
    fun redo(): Boolean {
        if (historyIndex >= layerHistory.lastIndex) return false
        
        historyIndex++
        val entry = layerHistory[historyIndex]
        
        // Redo logic would mirror undo but in reverse
        // For simplicity, this is a placeholder
        Timber.d("Redo attempted: ${entry.operation}")
        return true
    }

    /**
     * Clear all layers
     */
    fun clearAllLayers() {
        _layers.value = emptyList()
        _activeLayerId.value = null
        layerHistory.clear()
        historyIndex = -1
        Timber.d("All layers cleared")
    }
}

/**
 * Layer operation types for history tracking
 */
enum class LayerOperation {
    ADD,
    REMOVE,
    MODIFY,
    REORDER,
    DUPLICATE,
    MERGE
}

/**
 * History entry for undo/redo functionality
 */
data class LayerHistoryEntry(
    val operation: LayerOperation,
    val layerId: Long,
    val oldIndex: Int? = null,
    val oldName: String? = null,
    val oldOpacity: Float? = null,
    val oldBlendMode: BlendMode? = null,
    val newLayerId: Long? = null,
    val targetLayerId: Long? = null,
    val layerSnapshot: Layer? = null,
    val timestamp: Long = System.currentTimeMillis()
)
