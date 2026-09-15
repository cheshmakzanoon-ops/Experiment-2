package com.artflow.studio.domain.usecase.layer

import com.artflow.studio.core.layer.LayerMaskManager
import com.artflow.studio.domain.model.layer.MaskCreationMethod
import javax.inject.Inject

/**
 * Use case for adding a layer mask to a layer
 * Layer masks provide non-destructive editing by controlling layer visibility
 * using grayscale values (white=reveal, black=hide)
 * 
 * Implements Phase 16: Layer Masks
 */
class AddLayerMask @Inject constructor(
    private val layerMaskManager: LayerMaskManager
) {
    /**
     * Add a layer mask to the specified layer
     * @param layerId ID of the layer to add mask to
     * @param creationMethod How the mask should be created (default: empty white mask)
     * @param canvasWidth Canvas width for mask bitmap
     * @param canvasHeight Canvas height for mask bitmap
     * @return The created LayerMask or null if failed
     */
    suspend operator fun invoke(
        layerId: Long,
        creationMethod: MaskCreationMethod = MaskCreationMethod.EMPTY,
        canvasWidth: Int,
        canvasHeight: Int
    ) = layerMaskManager.addLayerMask(layerId, creationMethod, canvasWidth, canvasHeight)
}
