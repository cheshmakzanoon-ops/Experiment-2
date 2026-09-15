package com.artflow.studio.domain.usecase.canvas

import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import javax.inject.Inject

/**
 * Use case for beginning a new stroke on the canvas
 */
class BeginStroke @Inject constructor(
    private val canvasRepository: CanvasRepository
) {
    /**
     * Begin a new stroke
     * @param x Starting X coordinate
     * @param y Starting Y coordinate
     * @param pressure Initial pressure value (0.0 - 1.0)
     * @param brushParams Current brush parameters
     * @param layerId Target layer ID
     * @return Stroke ID for tracking
     */
    operator fun invoke(
        x: Float,
        y: Float,
        pressure: Float,
        brushParams: BrushParams,
        layerId: Long
    ): Long {
        return canvasRepository.beginStroke(x, y, pressure, brushParams, layerId)
    }
}
