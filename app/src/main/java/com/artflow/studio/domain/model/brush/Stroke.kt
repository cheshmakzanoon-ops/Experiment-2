package com.artflow.studio.domain.model.brush

import android.graphics.Color
import kotlinx.serialization.Serializable

/**
 * Represents a point in a stroke with pressure and tilt information
 * Implements Phase 7: Brush Engine Foundation - Stroke Point Model
 */
@Serializable
data class StrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 1.0f,
    val tiltX: Float = 0f,
    val tiltY: Float = 0f,
    val azimuth: Float = 0f,
    val timestamp: Long = System.currentTimeMillis(),
    val color: Int = Color.BLACK,
) {
    /**
     * Calculate distance to another point
     */
    fun distanceTo(other: StrokePoint): Float {
        val dx = other.x - this.x
        val dy = other.y - this.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /**
     * Calculate velocity from another point (pixels per millisecond)
     */
    fun velocityFrom(other: StrokePoint): Float {
        val timeDiff = (this.timestamp - other.timestamp).toFloat()
        if (timeDiff <= 0) return 0f
        return distanceTo(other) / timeDiff
    }
}

/**
 * Represents a complete stroke with all its points
 * Implements Phase 7: Brush Engine Foundation - Stroke Model
 */
@Serializable
data class Stroke(
    val id: Long = System.nanoTime(),
    val points: List<StrokePoint>,
    val brushParams: BrushParams,
    val layerId: Long,
    val color: Int,
    val timestamp: Long = System.currentTimeMillis(),
    /**
     * Eraser strokes subtract coverage from the layer instead of painting.
     *
     * Keeping the flag on the stroke (rather than pre-processing it away) means the compositor
     * replays it exactly like it was drawn, so a saved document reopens identical to the screen.
     */
    val isEraser: Boolean = false,
) {
    /**
     * Get the bounding box of the stroke
     */
    fun getBounds(): RectF {
        if (points.isEmpty()) return RectF(0f, 0f, 0f, 0f)

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE

        points.forEach { point ->
            minX = kotlin.math.min(minX, point.x)
            minY = kotlin.math.min(minY, point.y)
            maxX = kotlin.math.max(maxX, point.x)
            maxY = kotlin.math.max(maxY, point.y)
        }

        return RectF(minX, minY, maxX, maxY)
    }

    /**
     * Calculate total length of the stroke path
     */
    fun calculateLength(): Float {
        if (points.size < 2) return 0f

        var totalLength = 0f
        for (i in 1 until points.size) {
            totalLength += points[i].distanceTo(points[i - 1])
        }

        return totalLength
    }
}

/**
 * Simple rectangle structure for bounds
 */
data class RectF(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun contains(
        x: Float,
        y: Float,
    ): Boolean = x >= left && x <= right && y >= top && y <= bottom

    fun intersects(other: RectF): Boolean =
        !(
            other.left > right ||
                other.right < left ||
                other.top > bottom ||
                other.bottom < top
        )
}
