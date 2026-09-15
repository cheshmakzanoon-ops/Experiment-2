package com.artflow.studio.domain.usecase.layer

import com.artflow.studio.domain.repository.canvas.CanvasRepository
import javax.inject.Inject

/**
 * Use case for setting the active layer
 */
class SetActiveLayer @Inject constructor(
    private val canvasRepository: CanvasRepository
) {
    /**
     * Set the active layer
     * @param layerId ID of the layer to make active
     * @return true if layer was set as active
     */
    suspend operator fun invoke(layerId: Long): Boolean {
        return canvasRepository.setActiveLayer(layerId)
    }
}
