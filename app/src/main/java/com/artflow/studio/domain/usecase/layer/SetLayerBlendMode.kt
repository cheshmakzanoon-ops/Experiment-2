package com.artflow.studio.domain.usecase.layer

import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import javax.inject.Inject

/**
 * Use case for setting layer blend mode
 */
class SetLayerBlendMode @Inject constructor(
    private val canvasRepository: CanvasRepository
) {
    /**
     * Set the blend mode of a layer
     * @param layerId ID of the layer to modify
     * @param blendMode New blend mode
     * @return true if blend mode was changed successfully
     */
    suspend operator fun invoke(layerId: Long, blendMode: BlendMode): Boolean {
        return canvasRepository.setLayerBlendMode(layerId, blendMode)
    }
}
