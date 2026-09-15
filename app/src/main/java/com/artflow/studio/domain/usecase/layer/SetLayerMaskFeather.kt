package com.artflow.studio.domain.usecase.layer

import com.artflow.studio.core.layer.LayerMaskManager
import javax.inject.Inject

/**
 * Use case for setting layer mask feather radius (soft edges)
 */
class SetLayerMaskFeather @Inject constructor(
    private val layerMaskManager: LayerMaskManager
) {
    /**
     * Set the feather radius of a layer mask for soft edges
     * @param layerId ID of the layer with the mask
     * @param featherRadius Feather radius in pixels (0 = hard edge, max 500)
     * @return True if updated successfully
     */
    suspend operator fun invoke(layerId: Long, featherRadius: Float): Boolean {
        return layerMaskManager.setMaskFeather(layerId, featherRadius)
    }
}
