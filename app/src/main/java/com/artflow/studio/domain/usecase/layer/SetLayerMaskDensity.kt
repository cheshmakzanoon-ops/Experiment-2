package com.artflow.studio.domain.usecase.layer

import com.artflow.studio.core.layer.LayerMaskManager
import javax.inject.Inject

/**
 * Use case for setting layer mask density (overall opacity)
 */
class SetLayerMaskDensity @Inject constructor(
    private val layerMaskManager: LayerMaskManager
) {
    /**
     * Set the density/opacity of a layer mask
     * @param layerId ID of the layer with the mask
     * @param density Density value from 0.0 (no effect) to 1.0 (full effect)
     * @return True if updated successfully
     */
    suspend operator fun invoke(layerId: Long, density: Float): Boolean {
        return layerMaskManager.setMaskDensity(layerId, density)
    }
}
