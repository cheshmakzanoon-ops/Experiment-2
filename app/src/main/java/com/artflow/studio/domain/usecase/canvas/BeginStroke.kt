package com.artflow.studio.domain.usecase.canvas

import com.artflow.studio.core.brush.BrushEngine
import com.artflow.studio.domain.model.brush.BrushParams
import javax.inject.Inject

/**
 * Use case for beginning a new stroke on the canvas
 * Implements Phase 7: Brush Engine Foundation
 */
class BeginStroke @Inject constructor(
    private val brushEngine: BrushEngine
) {
    /**
     * Begin a new stroke
     * @param x Starting X coordinate
     * @param y Starting Y coordinate
     * @param pressure Initial pressure value (0.0 - 1.0)
     * @param tiltX Tilt angle X axis
     * @param tiltY Tilt angle Y axis
     * @return Stroke ID for tracking, or null if failed
     */
    operator fun invoke(
        x: Float,
        y: Float,
        pressure: Float = 1.0f,
        tiltX: Float = 0f,
        tiltY: Float = 0f
    ): Long? {
        return brushEngine.beginStroke(x, y, pressure, tiltX, tiltY)
    }
}
