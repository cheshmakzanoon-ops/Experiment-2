package com.artflow.studio.domain.usecase.layer

import com.artflow.studio.domain.repository.canvas.CanvasRepository
import javax.inject.Inject

/**
 * Use case for removing a layer from the canvas
 */
class RemoveLayer @Inject constructor(
    private val canvasRepository: CanvasRepository
) {
    /**
     * Remove a layer from the canvas
     * @param layerId ID of the layer to remove
     * @return true if layer was removed, false if layer not found or is background layer
     */
    suspend operator fun invoke(layerId: Long): Boolean {
        return canvasRepository.removeLayer(layerId)
    }
}
