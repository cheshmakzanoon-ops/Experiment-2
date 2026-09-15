package com.artflow.studio.domain.usecase.layer

import com.artflow.studio.domain.repository.canvas.CanvasRepository
import javax.inject.Inject

/**
 * Use case for setting layer opacity
 */
class SetLayerOpacity @Inject constructor(
    private val canvasRepository: CanvasRepository
) {
    /**
     * Set the opacity of a layer
     * @param layerId ID of the layer to modify
     * @param opacity New opacity value (0.0 - 1.0)
     * @return true if opacity was changed successfully
     */
    suspend operator fun invoke(layerId: Long, opacity: Float): Boolean {
        return canvasRepository.setLayerOpacity(layerId, opacity)
    }
}
