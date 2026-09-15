package com.artflow.studio.domain.usecase.layer

import com.artflow.studio.core.layer.LayerMaskManager
import javax.inject.Inject

/**
 * Use case for inverting a layer mask
 * When inverted, black reveals and white hides (opposite of normal behavior)
 */
class InvertLayerMask @Inject constructor(
    private val layerMaskManager: LayerMaskManager
) {
    /**
     * Toggle or set layer mask inversion state
     * @param layerId ID of the layer with the mask
     * @param isInverted New inversion state (null to toggle)
     * @return True if updated successfully
     */
    suspend operator fun invoke(layerId: Long, isInverted: Boolean? = null): Boolean {
        val currentState = layerMaskManager.getMaskForLayer(layerId)?.isInverted ?: return false
        val newState = isInverted ?: !currentState
        return layerMaskManager.invertMask(layerId, newState)
    }
}
