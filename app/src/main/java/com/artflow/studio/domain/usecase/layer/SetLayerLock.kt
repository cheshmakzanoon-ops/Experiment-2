package com.artflow.studio.domain.usecase.layer

import com.artflow.studio.domain.repository.canvas.CanvasRepository
import javax.inject.Inject

/**
 * Use case for locking/unlocking a layer
 */
class SetLayerLock @Inject constructor(
    private val canvasRepository: CanvasRepository
) {
    /**
     * Set the lock state of a layer
     * @param layerId ID of the layer to lock/unlock
     * @param isLocked New lock state
     * @return true if lock state was changed successfully
     */
    suspend operator fun invoke(layerId: Long, isLocked: Boolean): Boolean {
        return canvasRepository.setLayerLock(layerId, isLocked)
    }
}
