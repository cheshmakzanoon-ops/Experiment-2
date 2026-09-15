package com.artflow.studio.domain.usecase.layer

import com.artflow.studio.core.layer.LayerMaskManager
import javax.inject.Inject

/**
 * Use case for filling a layer mask with a solid grayscale value
 */
class FillLayerMask @Inject constructor(
    private val layerMaskManager: LayerMaskManager
) {
    /**
     * Fill entire layer mask with a grayscale value
     * @param layerId ID of the layer with the mask
     * @param grayValue Grayscale value (0=black/hide all, 255=white/reveal all)
     * @return True if filled successfully
     */
    suspend operator fun invoke(layerId: Long, grayValue: Int): Boolean {
        return layerMaskManager.fillMask(layerId, grayValue)
    }
}
