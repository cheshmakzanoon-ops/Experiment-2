package com.artflow.studio.domain.usecase.layer

import com.artflow.studio.domain.repository.canvas.CanvasRepository
import javax.inject.Inject

/**
 * Use case for merging two layers together
 */
class MergeLayers @Inject constructor(
    private val canvasRepository: CanvasRepository
) {
    /**
     * Merge a source layer into a target layer
     * @param sourceLayerId ID of the layer to merge from (will be removed)
     * @param targetLayerId ID of the layer to merge into
     * @return true if layers were merged successfully
     */
    suspend operator fun invoke(sourceLayerId: Long, targetLayerId: Long): Boolean {
        return canvasRepository.mergeLayers(sourceLayerId, targetLayerId)
    }

    /**
     * Merge all visible layers into a single layer
     * @param keepOriginals Whether to keep original layers after merge
     * @return ID of the merged layer
     */
    suspend fun mergeVisible(keepOriginals: Boolean = false): Long? {
        return canvasRepository.mergeVisibleLayers(keepOriginals)
    }

    /**
     * Merge down - merge current layer with the layer below it
     * @param layerId ID of the layer to merge down
     * @return true if merge was successful
     */
    suspend fun mergeDown(layerId: Long): Boolean {
        return canvasRepository.mergeLayerDown(layerId)
    }
}
