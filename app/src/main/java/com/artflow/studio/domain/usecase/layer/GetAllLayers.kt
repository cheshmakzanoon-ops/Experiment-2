package com.artflow.studio.domain.usecase.layer

import com.artflow.studio.domain.model.layer.Layer
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import javax.inject.Inject

/**
 * Use case for getting all layers in the canvas
 */
class GetAllLayers @Inject constructor(
    private val canvasRepository: CanvasRepository
) {
    /**
     * Get all layers in the canvas sorted by index
     * @return List of all layers
     */
    operator fun invoke(): List<Layer> {
        return canvasRepository.getAllLayers()
    }
}
