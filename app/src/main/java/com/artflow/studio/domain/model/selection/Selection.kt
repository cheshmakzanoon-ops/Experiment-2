package com.artflow.studio.domain.model.selection

import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import kotlin.math.sqrt

/**
 * Selection model for Phase 13: Selection Tools
 * Represents a selected area on the canvas that can be manipulated
 */
data class Selection(
    val id: Long,
    val type: SelectionType,
    val path: Path,
    val bounds: RectF,
    val createdAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true
) {
    /**
     * Check if a point is inside the selection.
     *
     * [Path] has no point-in-path test, so we rasterise the path into a [Region]
     * within the selection bounds. Points outside the bounds are rejected first,
     * which keeps the common case cheap.
     */
    fun containsPoint(x: Float, y: Float): Boolean {
        if (!bounds.contains(x, y)) return false
        val clip = Region(
            bounds.left.toInt(),
            bounds.top.toInt(),
            bounds.right.toInt() + 1,
            bounds.bottom.toInt() + 1
        )
        val region = Region()
        if (!region.setPath(path, clip)) return false
        return region.contains(x.toInt(), y.toInt())
    }

    /**
     * Get the area of the selection in pixels
     */
    fun getArea(): Float {
        return when (type) {
            SelectionType.RECTANGLE -> bounds.width() * bounds.height()
            SelectionType.ELLIPSE -> Math.PI.toFloat() * (bounds.width() / 2) * (bounds.height() / 2)
            else -> approximatePathArea(path)
        }
    }

    /**
     * Approximate area of a custom path using bounding box method
     */
    private fun approximatePathArea(path: Path): Float {
        val pathBounds = RectF()
        path.computeBounds(pathBounds, true)
        // This is a rough approximation - actual implementation would use pixel counting
        return pathBounds.width() * pathBounds.height() * 0.7f
    }
}

/**
 * Types of selection tools available
 */
enum class SelectionType {
    FREEHAND,      // Freehand lasso selection
    LASSO,         // Polygonal lasso selection
    RECTANGLE,     // Rectangular marquee
    ELLIPSE,       // Elliptical marquee
    MAGIC_WAND     // Color-based magic wand
}

/**
 * Magic wand configuration for color-based selection
 */
data class MagicWandConfig(
    val tolerance: Int = 32,           // Color tolerance (0-255)
    val contiguous: Boolean = true,    // Only select connected areas
    val sampleAllLayers: Boolean = false,  // Sample from all visible layers
    val antiAlias: Boolean = true      // Smooth edges
) {
    init {
        require(tolerance in 0..255) { "Tolerance must be between 0 and 255" }
    }
}

/**
 * Selection border animation state for "marching ants" effect
 */
data class MarchingAntsState(
    val offset: Float = 0f,
    val isAnimating: Boolean = true,
    val speed: Float = 1.0f  // Animation speed multiplier
) {
    /**
     * Update the animation offset for the next frame
     */
    fun update(deltaTimeMs: Long): MarchingAntsState {
        if (!isAnimating) return this
        val newOffset = (offset + (deltaTimeMs / 1000f) * speed * 8f) % 16f
        return copy(offset = newOffset)
    }
}

/**
 * Result of a flood fill operation for magic wand
 */
data class FloodFillResult(
    val selectedPoints: List<Pair<Int, Int>>,  // (x, y) coordinates
    val bounds: RectF,
    val fillColor: Int,
    val pointCount: Int
) {
    companion object {
        /**
         * Implement flood fill algorithm for magic wand tool
         * Uses a stack-based approach for efficiency
         */
        fun floodFill(
            pixels: Array<IntArray>,
            startX: Int,
            startY: Int,
            targetColor: Int,
            tolerance: Int,
            contiguous: Boolean = true
        ): FloodFillResult {
            // `pixels` is indexed as [y][x]: the outer array holds rows, each row holds pixels.
            val height = pixels.size
            val width = pixels.firstOrNull()?.size ?: return FloodFillResult(emptyList(), RectF(), 0, 0)
            
            if (startX !in 0 until width || startY !in 0 until height) {
                return FloodFillResult(emptyList(), RectF(), 0, 0)
            }

            val selectedPoints = mutableListOf<Pair<Int, Int>>()
            val visited = mutableSetOf<Pair<Int, Int>>()
            val stack = ArrayDeque<Pair<Int, Int>>()
            
            stack.addLast(startX to startY)
            var minX = startX
            var minY = startY
            var maxX = startX
            var maxY = startY

            while (stack.isNotEmpty()) {
                val (x, y) = stack.removeLast()
                
                if (x !in 0 until width || y !in 0 until height) continue
                if (visited.contains(x to y)) continue

                val currentColor = pixels[y][x]
                if (!colorsMatch(currentColor, targetColor, tolerance)) continue

                visited.add(x to y)
                selectedPoints.add(x to y)

                // Update bounds
                minX = minOf(minX, x)
                minY = minOf(minY, y)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)

                // Add neighboring points
                if (contiguous) {
                    stack.addLast((x + 1) to y)
                    stack.addLast((x - 1) to y)
                    stack.addLast(x to (y + 1))
                    stack.addLast(x to (y - 1))
                } else {
                    // For non-contiguous, we'd need to check all pixels
                    // This is a simplified version
                }
            }

            val bounds = RectF(
                minX.toFloat(),
                minY.toFloat(),
                maxX.toFloat() + 1,
                maxY.toFloat() + 1
            )

            return FloodFillResult(
                selectedPoints = selectedPoints,
                bounds = bounds,
                fillColor = targetColor,
                pointCount = selectedPoints.size
            )
        }

        /**
         * Check if two colors match within tolerance
         */
        private fun colorsMatch(color1: Int, color2: Int, tolerance: Int): Boolean {
            val r1 = (color1 shr 16) and 0xFF
            val g1 = (color1 shr 8) and 0xFF
            val b1 = color1 and 0xFF

            val r2 = (color2 shr 16) and 0xFF
            val g2 = (color2 shr 8) and 0xFF
            val b2 = color2 and 0xFF

            val diffR = kotlin.math.abs(r1 - r2)
            val diffG = kotlin.math.abs(g1 - g2)
            val diffB = kotlin.math.abs(b1 - b2)

            // Euclidean distance in RGB space
            val distance = sqrt((diffR * diffR + diffG * diffG + diffB * diffB).toDouble())
            return distance <= tolerance * 1.732  // 1.732 ≈ √3 for normalization
        }
    }
}

/**
 * Selection operation types for undo/redo history
 */
enum class SelectionOperation {
    CREATE,
    MODIFY,
    DELETE,
    FILL,
    STROKE,
    TRANSFORM,
    INVERT,
    FEATHER,
    CONTRACT,
    EXPAND
}
