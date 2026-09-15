package com.artflow.studio.domain.usecase.canvas

import com.artflow.studio.domain.repository.canvas.CanvasRepository
import javax.inject.Inject

/**
 * Use case for ending a stroke and committing it to the canvas
 */
class EndStroke @Inject constructor(
    private val canvasRepository: CanvasRepository
) {
    /**
     * End the current stroke and render it to the layer
     * @param strokeId Stroke ID to end
     */
    operator fun invoke(strokeId: Long) {
        canvasRepository.endStroke(strokeId)
    }
}

