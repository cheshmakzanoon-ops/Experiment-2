package com.artflow.studio.domain.usecase.shape

import com.artflow.studio.core.shape.ShapeManager
import com.artflow.studio.domain.model.Color
import com.artflow.studio.domain.model.shape.PolygonShape
import javax.inject.Inject

/**
 * Use case for creating a polygon shape
 * Implements Phase 24: Shape Tools
 */
class CreatePolygonShape @Inject constructor(
    private val shapeManager: ShapeManager
) {
    /**
     * Execute the use case to create a polygon
     * @param centerX X position of center
     * @param centerY Y position of center
     * @param radius Radius of the polygon
     * @param sides Number of sides (3 = triangle, 5 = pentagon, etc.)
     * @param rotationDegrees Initial rotation in degrees
     * @param fillColor Fill color (null for no fill)
     * @param strokeColor Stroke color
     * @param strokeWidth Stroke width
     * @return The created PolygonShape
     */
    operator fun invoke(
        centerX: Float,
        centerY: Float,
        radius: Float,
        sides: Int = 3,
        rotationDegrees: Float = 0f,
        fillColor: Color? = null,
        strokeColor: Color = Color.BLACK,
        strokeWidth: Float = 2f
    ): PolygonShape {
        return shapeManager.createPolygon(
            centerX = centerX,
            centerY = centerY,
            radius = radius,
            sides = sides,
            rotationDegrees = rotationDegrees,
            fillColor = fillColor,
            strokeColor = strokeColor,
            strokeWidth = strokeWidth
        )
    }
}
