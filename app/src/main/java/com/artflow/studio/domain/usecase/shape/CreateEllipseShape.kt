package com.artflow.studio.domain.usecase.shape

import com.artflow.studio.core.shape.ShapeManager
import com.artflow.studio.domain.model.Color
import com.artflow.studio.domain.model.shape.EllipseShape
import javax.inject.Inject

/**
 * Use case for creating an ellipse/circle shape
 * Implements Phase 24: Shape Tools
 */
class CreateEllipseShape @Inject constructor(
    private val shapeManager: ShapeManager
) {
    /**
     * Execute the use case to create an ellipse
     * @param centerX X position of center
     * @param centerY Y position of center
     * @param radiusX Horizontal radius
     * @param radiusY Vertical radius (same as radiusX for circle)
     * @param fillColor Fill color (null for no fill)
     * @param strokeColor Stroke color
     * @param strokeWidth Stroke width
     * @return The created EllipseShape
     */
    operator fun invoke(
        centerX: Float,
        centerY: Float,
        radiusX: Float,
        radiusY: Float = radiusX,
        fillColor: Color? = null,
        strokeColor: Color = Color.BLACK,
        strokeWidth: Float = 2f
    ): EllipseShape {
        return shapeManager.createEllipse(
            centerX = centerX,
            centerY = centerY,
            radiusX = radiusX,
            radiusY = radiusY,
            fillColor = fillColor,
            strokeColor = strokeColor,
            strokeWidth = strokeWidth
        )
    }
}
