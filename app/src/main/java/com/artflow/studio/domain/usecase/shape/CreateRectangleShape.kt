package com.artflow.studio.domain.usecase.shape

import com.artflow.studio.core.shape.ShapeManager
import com.artflow.studio.domain.model.Color
import com.artflow.studio.domain.model.shape.LineCap
import com.artflow.studio.domain.model.shape.RectangleShape
import javax.inject.Inject

/**
 * Use case for creating a rectangle shape
 * Implements Phase 24: Shape Tools
 */
class CreateRectangleShape @Inject constructor(
    private val shapeManager: ShapeManager
) {
    /**
     * Execute the use case to create a rectangle
     * @param x X position of top-left corner
     * @param y Y position of top-left corner
     * @param width Width of rectangle
     * @param height Height of rectangle
     * @param cornerRadius Optional corner radius (0 for sharp corners)
     * @param fillColor Fill color (null for no fill)
     * @param strokeColor Stroke color
     * @param strokeWidth Stroke width
     * @return The created RectangleShape
     */
    operator fun invoke(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        cornerRadius: Float = 0f,
        fillColor: Color? = null,
        strokeColor: Color = Color.BLACK,
        strokeWidth: Float = 2f
    ): RectangleShape {
        return shapeManager.createRectangle(
            x = x,
            y = y,
            width = width,
            height = height,
            cornerRadius = cornerRadius,
            fillColor = fillColor,
            strokeColor = strokeColor,
            strokeWidth = strokeWidth
        )
    }
}
