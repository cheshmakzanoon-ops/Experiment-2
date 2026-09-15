package com.artflow.studio.domain.usecase.layer

import com.artflow.studio.core.layer.LayerMaskManager
import javax.inject.Inject

/**
 * Use case for removing a layer mask from a layer
 */
class RemoveLayerMask @Inject constructor(
    private val layerMaskManager: LayerMaskManager
) {
    /**
     * Remove a layer mask from the specified layer
     * @param layerId ID of the layer to remove mask from
     * @param discardChanges If true, discard mask; if false, apply mask to layer alpha before removal
     * @return True if removed successfully
     */
    suspend operator fun invoke(layerId: Long, discardChanges: Boolean = true): Boolean {
        return layerMaskManager.removeLayerMask(layerId, discardChanges)
    }
}
