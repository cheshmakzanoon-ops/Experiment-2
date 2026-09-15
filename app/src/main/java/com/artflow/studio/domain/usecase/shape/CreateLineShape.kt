package com.artflow.studio.domain.usecase.shape

import com.artflow.studio.core.shape.ShapeManager
import com.artflow.studio.domain.model.Color
import com.artflow.studio.domain.model.shape.LineCap
import com.artflow.studio.domain.model.shape.LineShape
import javax.inject.Inject

/**
 * Use case for creating a line shape
 * Implements Phase 24: Shape Tools
 */
class CreateLineShape @Inject constructor(
    private val shapeManager: ShapeManager
) {
    /**
     * Execute the use case to create a line
     * @param startX Start X position
     * @param startY Start Y position
     * @param endX End X position
     * @param endY End Y position
     * @param strokeColor Stroke color
     * @param strokeWidth Stroke width
     * @param lineCap Line cap style
     * @param isArrow Whether to draw arrow head at end
     * @return The created LineShape
     */
    operator fun invoke(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        strokeColor: Color = Color.BLACK,
        strokeWidth: Float = 2f,
        lineCap: LineCap = LineCap.ROUND,
        isArrow: Boolean = false
    ): LineShape {
        return shapeManager.createLine(
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            strokeColor = strokeColor,
            strokeWidth = strokeWidth,
            lineCap = lineCap,
            isArrow = isArrow
        )
    }
}
