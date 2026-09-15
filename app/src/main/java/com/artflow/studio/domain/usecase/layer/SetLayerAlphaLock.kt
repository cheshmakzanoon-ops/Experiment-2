package com.artflow.studio.domain.usecase.layer

import com.artflow.studio.domain.repository.canvas.CanvasRepository
import javax.inject.Inject

/**
 * Use case for setting alpha lock on a layer
 * Alpha lock restricts painting to only existing opaque pixels
 */
class SetLayerAlphaLock @Inject constructor(
    private val canvasRepository: CanvasRepository
) {
    /**
     * Toggle or set alpha lock state on a layer
     * @param layerId ID of the layer to modify
     * @param isLocked New lock state (null to toggle)
     * @return true if alpha lock state was changed successfully
     */
    suspend operator fun invoke(layerId: Long, isLocked: Boolean? = null): Boolean {
        return canvasRepository.setLayerAlphaLock(layerId, isLocked)
    }
}

