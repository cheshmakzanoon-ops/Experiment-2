package com.artflow.studio.domain.usecase.layer

import com.artflow.studio.core.layer.LayerMaskManager
import javax.inject.Inject

/**
 * Use case for painting on a layer mask with grayscale values
 * White (255) reveals the layer, Black (0) hides it
 */
class PaintOnLayerMask @Inject constructor(
    private val layerMaskManager: LayerMaskManager
) {
    /**
     * Paint on a layer mask at the specified coordinates
     * @param layerId ID of the layer with the mask
     * @param x X coordinate on the canvas
     * @param y Y coordinate on the canvas
     * @param brushSize Size of the brush in pixels
     * @param grayValue Grayscale value (0=black/hide, 255=white/reveal)
     * @param pressure Pressure value for opacity modulation (0.0 - 1.0)
     * @return True if painted successfully
     */
    suspend operator fun invoke(
        layerId: Long,
        x: Float,
        y: Float,
        brushSize: Float,
        grayValue: Int,
        pressure: Float = 1.0f
    ): Boolean {
        return layerMaskManager.paintOnMask(layerId, x, y, brushSize, grayValue, pressure)
    }
}
