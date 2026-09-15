package com.artflow.studio.domain.usecase.layer

import com.artflow.studio.domain.repository.canvas.CanvasRepository
import javax.inject.Inject

/**
 * Use case for toggling layer visibility
 */
class SetLayerVisibility @Inject constructor(
    private val canvasRepository: CanvasRepository
) {
    /**
     * Toggle or set the visibility of a layer
     * @param layerId ID of the layer to modify
     * @param isVisible New visibility state (null to toggle)
     * @return true if visibility was changed successfully
     */
    suspend operator fun invoke(layerId: Long, isVisible: Boolean? = null): Boolean {
        return canvasRepository.setLayerVisibility(layerId, isVisible)
    }
}
