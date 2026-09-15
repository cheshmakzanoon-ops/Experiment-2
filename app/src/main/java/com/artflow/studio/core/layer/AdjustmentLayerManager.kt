package com.artflow.studio.core.layer

import com.artflow.studio.domain.model.layer.AdjustmentLayer
import com.artflow.studio.domain.model.layer.AdjustmentType
import com.artflow.studio.domain.model.layer.BlendMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for adjustment layers in ArtFlow
 * Implements Phase 25: Adjustment Layers - Non-destructive color corrections
 * 
 * This singleton manages adjustment layer operations including:
 * - Creating and removing adjustment layers
 * - Modifying adjustment parameters
 * - Toggling visibility and enable state
 * - Reordering adjustment layers in the stack
 */
@Singleton
class AdjustmentLayerManager @Inject constructor() {

    private val _adjustmentLayers = MutableStateFlow<List<AdjustmentLayer>>(emptyList())
    val adjustmentLayers: StateFlow<List<AdjustmentLayer>> = _adjustmentLayers.asStateFlow()

    private var nextAdjustmentLayerId = 1L

    /**
     * Add a new adjustment layer to the stack
     * @param adjustmentType The type of adjustment to apply
     * @param name Optional layer name (defaults to adjustment type display name)
     * @param index Position in stack (defaults to top)
     * @return The created AdjustmentLayer or null if failed
     */
    fun addAdjustmentLayer(
        adjustmentType: AdjustmentType,
        name: String? = null,
        index: Int = _adjustmentLayers.value.size
    ): AdjustmentLayer? {
        val layerName = name ?: adjustmentType.displayName
        val layer = AdjustmentLayer(
            id = nextAdjustmentLayerId++,
            name = layerName,
            index = index,
            isVisible = true,
            opacity = 1.0f,
            adjustmentType = adjustmentType,
            parameters = adjustmentType.defaultParameters,
            isEnabled = true,
            blendMode = BlendMode.NORMAL
        )

        val updatedLayers = _adjustmentLayers.value.toMutableList()
        
        // Adjust indices of layers at or above the insertion point
        updatedLayers.forEachIndexed { i, l ->
            if (l.index >= index) {
                updatedLayers[i] = l.copyWith(index = l.index + 1)
            }
        }
        
        updatedLayers.add(layer)
        _adjustmentLayers.value = updatedLayers
        
        Timber.d("Adjustment layer added: ${layer.name} (${adjustmentType.displayName}) at index $index")
        return layer
    }

    /**
     * Remove an adjustment layer by ID
     * @param layerId The ID of the layer to remove
     * @return True if removed successfully
     */
    fun removeAdjustmentLayer(layerId: Long): Boolean {
        val layer = _adjustmentLayers.value.find { it.id == layerId } ?: return false

        val updatedLayers = _adjustmentLayers.value.toMutableList()
        updatedLayers.removeIf { it.id == layerId }
        
        // Adjust indices of layers above the removed layer
        updatedLayers.forEachIndexed { i, l ->
            if (l.index > layer.index) {
                updatedLayers[i] = l.copyWith(index = l.index - 1)
            }
        }
        
        _adjustmentLayers.value = updatedLayers
        
        Timber.d("Adjustment layer removed: ${layer.name}")
        return true
    }

    /**
     * Set adjustment layer visibility
     * @param layerId The layer ID
     * @param isVisible Visibility state
     * @return True if updated
     */
    fun setAdjustmentLayerVisibility(layerId: Long, isVisible: Boolean): Boolean {
        val layer = _adjustmentLayers.value.find { it.id == layerId } ?: return false
        
        _adjustmentLayers.value = _adjustmentLayers.value.map { l ->
            if (l.id == layerId) l.copyWith(isVisible = isVisible) else l
        }
        
        Timber.d("Adjustment layer visibility changed: ${layer.name} -> $isVisible")
        return true
    }

    /**
     * Set adjustment layer enable state
     * @param layerId The layer ID
     * @param isEnabled Enable state
     * @return True if updated
     */
    fun setAdjustmentLayerEnabled(layerId: Long, isEnabled: Boolean): Boolean {
        val layer = _adjustmentLayers.value.find { it.id == layerId } ?: return false
        
        _adjustmentLayers.value = _adjustmentLayers.value.map { l ->
            if (l.id == layerId) l.copyWith(isEnabled = isEnabled) else l
        }
        
        Timber.d("Adjustment layer enabled changed: ${layer.name} -> $isEnabled")
        return true
    }

    /**
     * Set adjustment layer opacity
     * @param layerId The layer ID
     * @param opacity Opacity value (0.0 - 1.0)
     * @return True if updated
     */
    fun setAdjustmentLayerOpacity(layerId: Long, opacity: Float): Boolean {
        val clampedOpacity = opacity.coerceIn(0f, 1f)
        val layer = _adjustmentLayers.value.find { it.id == layerId } ?: return false
        
        _adjustmentLayers.value = _adjustmentLayers.value.map { l ->
            if (l.id == layerId) l.copyWith(opacity = clampedOpacity) else l
        }
        
        Timber.d("Adjustment layer opacity changed: ${layer.name} -> $clampedOpacity")
        return true
    }

    /**
     * Set a specific parameter value for an adjustment layer
     * @param layerId The layer ID
     * @param parameter The parameter name
     * @param value The parameter value (will be validated against range)
     * @return True if updated
     */
    fun setAdjustmentParameter(layerId: Long, parameter: String, value: Float): Boolean {
        val layer = _adjustmentLayers.value.find { it.id == layerId } ?: return false
        
        // Validate parameter exists for this adjustment type
        if (parameter !in layer.adjustmentType.parameterRanges && 
            parameter !in layer.adjustmentType.defaultParameters) {
            Timber.w("Invalid parameter '$parameter' for adjustment type ${layer.adjustmentType}")
            return false
        }
        
        // Validate and clamp value to range
        val validatedValue = layer.adjustmentType.validateParameter(parameter, value)
        
        val updatedParameters = layer.parameters.toMutableMap()
        updatedParameters[parameter] = validatedValue
        
        _adjustmentLayers.value = _adjustmentLayers.value.map { l ->
            if (l.id == layerId) l.copyWith(parameters = updatedParameters) else l
        }
        
        Timber.d("Adjustment parameter changed: ${layer.name}.$parameter = $validatedValue")
        return true
    }

    /**
     * Set multiple parameters at once for batch updates
     * @param layerId The layer ID
     * @param newParameters Map of parameter names to values
     * @return True if updated
     */
    fun setAdjustmentParameters(layerId: Long, newParameters: Map<String, Float>): Boolean {
        val layer = _adjustmentLayers.value.find { it.id == layerId } ?: return false
        
        val updatedParameters = layer.parameters.toMutableMap()
        newParameters.forEach { (key, value) ->
            if (key in layer.adjustmentType.parameterRanges || 
                key in layer.adjustmentType.defaultParameters) {
                updatedParameters[key] = layer.adjustmentType.validateParameter(key, value)
            }
        }
        
        _adjustmentLayers.value = _adjustmentLayers.value.map { l ->
            if (l.id == layerId) l.copyWith(parameters = updatedParameters) else l
        }
        
        Timber.d("Adjustment parameters batch updated: ${layer.name} (${newParameters.size} params)")
        return true
    }

    /**
     * Reset all parameters to default values for an adjustment layer
     * @param layerId The layer ID
     * @return True if reset successfully
     */
    fun resetAdjustmentParameters(layerId: Long): Boolean {
        val layer = _adjustmentLayers.value.find { it.id == layerId } ?: return false
        
        _adjustmentLayers.value = _adjustmentLayers.value.map { l ->
            if (l.id == layerId) l.copyWith(parameters = l.adjustmentType.defaultParameters) else l
        }
        
        Timber.d("Adjustment parameters reset to defaults: ${layer.name}")
        return true
    }

    /**
     * Reorder an adjustment layer to a new position
     * @param layerId The layer ID to move
     * @param newIndex The new index position
     * @return True if reordered successfully
     */
    fun reorderAdjustmentLayer(layerId: Long, newIndex: Int): Boolean {
        val layer = _adjustmentLayers.value.find { it.id == layerId } ?: return false
        val currentIndex = layer.index
        
        if (currentIndex == newIndex) return true
        
        val updatedLayers = _adjustmentLayers.value.toMutableList()
        
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
        
        _adjustmentLayers.value = updatedLayers
        Timber.d("Adjustment layer reordered: ${layer.name} from $currentIndex to $newIndex")
        return true
    }

    /**
     * Get an adjustment layer by ID
     */
    fun getAdjustmentLayer(layerId: Long): AdjustmentLayer? {
        return _adjustmentLayers.value.find { it.id == layerId }
    }

    /**
     * Get all active (visible and enabled) adjustment layers sorted by index
     * These are the layers that should be applied during rendering
     */
    fun getActiveAdjustmentLayers(): List<AdjustmentLayer> {
        return _adjustmentLayers.value
            .filter { it.canApply() }
            .sortedBy { it.index }
    }

    /**
     * Get adjustment layers that affect a specific layer index
     * Returns all adjustment layers with index greater than the target layer
     */
    fun getAdjustmentLayersAffecting(targetLayerIndex: Int): List<AdjustmentLayer> {
        return _adjustmentLayers.value
            .filter { it.index > targetLayerIndex && it.canApply() }
            .sortedBy { it.index }
    }

    /**
     * Get adjustment layer count
     */
    fun getAdjustmentLayerCount(): Int = _adjustmentLayers.value.size

    /**
     * Duplicate an adjustment layer
     * @param layerId The layer ID to duplicate
     * @return The new duplicated layer or null if failed
     */
    fun duplicateAdjustmentLayer(layerId: Long): AdjustmentLayer? {
        val originalLayer = _adjustmentLayers.value.find { it.id == layerId } ?: return null
        
        val duplicatedLayer = originalLayer.copyWith(
            id = nextAdjustmentLayerId++,
            name = "${originalLayer.name} Copy",
            index = originalLayer.index + 1,
            parameters = originalLayer.parameters.toMap()
        )
        
        val updatedLayers = _adjustmentLayers.value.toMutableList()
        
        // Adjust indices of layers above
        updatedLayers.forEachIndexed { i, l ->
            if (l.index > originalLayer.index) {
                updatedLayers[i] = l.copyWith(index = l.index + 1)
            }
        }
        
        updatedLayers.add(duplicatedLayer)
        _adjustmentLayers.value = updatedLayers
        
        Timber.d("Adjustment layer duplicated: ${originalLayer.name} -> ${duplicatedLayer.name}")
        return duplicatedLayer
    }

    /**
     * Clear all adjustment layers
     */
    fun clearAllAdjustmentLayers() {
        _adjustmentLayers.value = emptyList()
        Timber.d("All adjustment layers cleared")
    }
}
