package com.artflow.studio.domain.usecase.layer

import com.artflow.studio.domain.repository.canvas.CanvasRepository
import javax.inject.Inject

/**
 * Use case for reordering layers in the layer stack
 */
class ReorderLayer @Inject constructor(
    private val canvasRepository: CanvasRepository
) {
    /**
     * Move a layer to a new position in the layer stack
     * @param layerId ID of the layer to move
     * @param newIndex New index position (0 = bottom)
     * @return true if layer was reordered successfully
     */
    suspend operator fun invoke(layerId: Long, newIndex: Int): Boolean {
        return canvasRepository.reorderLayer(layerId, newIndex)
    }
}
