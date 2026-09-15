package com.artflow.studio.domain.usecase.canvas

import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import javax.inject.Inject

/**
 * Use case for continuing an existing stroke with a new point
 */
class ContinueStroke @Inject constructor(
    private val canvasRepository: CanvasRepository
) {
    /**
     * Continue a stroke with a new point
     * @param strokeId Stroke to continue
     * @param x X coordinate
     * @param y Y coordinate
     * @param pressure Pressure value (0.0 - 1.0)
     * @param tiltX Tilt angle on X axis (-1.0 - 1.0)
     * @param tiltY Tilt angle on Y axis (-1.0 - 1.0)
     */
    operator fun invoke(
        strokeId: Long,
        x: Float,
        y: Float,
        pressure: Float = 1.0f,
        tiltX: Float = 0f,
        tiltY: Float = 0f
    ) {
        canvasRepository.continueStroke(strokeId, x, y, pressure, tiltX, tiltY)
    }
}
