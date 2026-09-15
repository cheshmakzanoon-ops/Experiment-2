package com.artflow.studio.domain.usecase.layer

import com.artflow.studio.domain.model.layer.Layer
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import javax.inject.Inject

/**
 * Use case for getting the active layer
 */
class GetActiveLayer @Inject constructor(
    private val canvasRepository: CanvasRepository
) {
    /**
     * Get the currently active layer
     * @return Active layer or null if no canvas exists
     */
    operator fun invoke(): Layer? {
        return canvasRepository.getActiveLayer()
    }
}
