package com.artflow.studio.domain.usecase.layer

import com.artflow.studio.domain.repository.canvas.CanvasRepository
import javax.inject.Inject

/**
 * Use case for duplicating an existing layer
 */
class DuplicateLayer @Inject constructor(
    private val canvasRepository: CanvasRepository
) {
    /**
     * Create a copy of an existing layer
     * @param layerId ID of the layer to duplicate
     * @return ID of the newly created duplicated layer, or null if failed
     */
    suspend operator fun invoke(layerId: Long): Long? {
        return canvasRepository.duplicateLayer(layerId)
    }
}
