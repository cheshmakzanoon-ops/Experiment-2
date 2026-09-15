package com.artflow.studio.domain.usecase.layer

import com.artflow.studio.core.layer.LayerMaskManager
import javax.inject.Inject

/**
 * Use case for toggling layer mask enabled/disabled state
 */
class ToggleLayerMask @Inject constructor(
    private val layerMaskManager: LayerMaskManager
) {
    /**
     * Toggle or set layer mask enabled state
     * @param layerId ID of the layer with the mask
     * @param isEnabled New enabled state (null to toggle)
     * @return True if updated successfully
     */
    suspend operator fun invoke(layerId: Long, isEnabled: Boolean? = null): Boolean {
        val currentState = layerMaskManager.getMaskForLayer(layerId)?.isEnabled ?: return false
        val newState = isEnabled ?: !currentState
        return layerMaskManager.toggleMaskEnabled(layerId, newState)
    }
}
