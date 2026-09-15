package com.artflow.studio.domain.usecase.layer

import com.artflow.studio.domain.repository.canvas.CanvasRepository
import javax.inject.Inject

/**
 * Use case for setting clipping mask on a layer
 * Clipping mask restricts painting to the content of the layer below
 */
class SetLayerClippingMask @Inject constructor(
    private val canvasRepository: CanvasRepository
) {
    /**
     * Toggle or set clipping mask state on a layer
     * @param layerId ID of the layer to modify
     * @param isClipping New clipping state (null to toggle)
     * @return true if clipping mask state was changed successfully
     */
    suspend operator fun invoke(layerId: Long, isClipping: Boolean? = null): Boolean {
        return canvasRepository.setLayerClippingMask(layerId, isClipping)
    }
}

