package com.artflow.studio.domain.usecase.layer

import com.artflow.studio.domain.model.layer.Layer
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import javax.inject.Inject

/**
 * Use case for adding a new layer to the canvas
 */
class AddLayer @Inject constructor(
    private val canvasRepository: CanvasRepository
) {
    /**
     * Add a new layer to the canvas
     * @param name Layer name (optional, will auto-generate if null)
     * @param index Position in layer stack (null = above active layer)
     * @param opacity Initial opacity (0.0 - 1.0)
     * @return Created Layer object
     */
    suspend operator fun invoke(
        name: String? = null,
        index: Int? = null,
        opacity: Float = 1.0f
    ): Layer {
        return canvasRepository.addLayer(name, index, opacity)
    }
}
