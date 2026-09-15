package com.artflow.studio.domain.usecase.layer

import com.artflow.studio.domain.repository.canvas.CanvasRepository
import javax.inject.Inject

/**
 * Use case for renaming a layer
 */
class SetLayerName @Inject constructor(
    private val canvasRepository: CanvasRepository
) {
    /**
     * Set the name of a layer
     * @param layerId ID of the layer to rename
     * @param newName New layer name
     * @return true if name was changed successfully
     */
    suspend operator fun invoke(layerId: Long, newName: String): Boolean {
        return canvasRepository.setLayerName(layerId, newName)
    }
}
