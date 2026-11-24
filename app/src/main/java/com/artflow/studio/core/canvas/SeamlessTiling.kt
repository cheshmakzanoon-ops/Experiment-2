package com.artflow.studio.core.canvas

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.StrokeReach
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import kotlinx.serialization.Serializable
import kotlin.math.floor

/**
 * Live seamless tiling mode for texture, surface, and pattern illustration.
 * Strokes crossing any enabled edge wrap seamlessly to the opposite side in real time.
 */
@Serializable
enum class SeamlessTileMode(
    val displayName: String,
) {
    NONE("Off"),
    HORIZONTAL("Horizontal"),
    VERTICAL("Vertical"),
    BOTH("Seamless X + Y"),
}

/**
 * Stateless seamless tile coordinate and stroke-wrapping engine.
 * Zero allocation overhead when [SeamlessTileMode.NONE] is active or when a stroke stays inside canvas bounds.
 */
object SeamlessTiling {
    /** Wraps a single canvas coordinate ([x], [y]) into `[0, width) x [0, height)` according to [mode]. */
    fun wrapPoint(
        x: Float,
        y: Float,
        width: Int,
        height: Int,
        mode: SeamlessTileMode,
    ): Pair<Float, Float> {
        require(width > 0 && height > 0) { "Canvas dimensions must be positive" }
        val wx = if (mode == SeamlessTileMode.HORIZONTAL || mode == SeamlessTileMode.BOTH) wrapCoord(x, width) else x
        val wy = if (mode == SeamlessTileMode.VERTICAL || mode == SeamlessTileMode.BOTH) wrapCoord(y, height) else y
        return wx to wy
    }

    /**
     * Expands [stroke] into the minimal set of shifted copies needed so dabs whose radius crosses
     * a tiled canvas boundary paint seamlessly onto the opposite edge.
     */
    fun wrappedStrokes(
        stroke: Stroke,
        canvasWidth: Int,
        canvasHeight: Int,
        mode: SeamlessTileMode,
    ): List<Stroke> {
        if (mode == SeamlessTileMode.NONE || stroke.points.isEmpty()) return listOf(stroke)
        val margin = StrokeReach.of(stroke.brushParams) + StrokeReach.PADDING
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (p in stroke.points) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        val wrapX = mode == SeamlessTileMode.HORIZONTAL || mode == SeamlessTileMode.BOTH
        val wrapY = mode == SeamlessTileMode.VERTICAL || mode == SeamlessTileMode.BOTH

        val xOffsets = mutableListOf(0)
        if (wrapX) {
            if (minX - margin < 0f) xOffsets.add(canvasWidth)
            if (maxX + margin >= canvasWidth) xOffsets.add(-canvasWidth)
        }
        val yOffsets = mutableListOf(0)
        if (wrapY) {
            if (minY - margin < 0f) yOffsets.add(canvasHeight)
            if (maxY + margin >= canvasHeight) yOffsets.add(-canvasHeight)
        }
        if (xOffsets.size == 1 && yOffsets.size == 1) return listOf(stroke)

        val result = ArrayList<Stroke>(xOffsets.size * yOffsets.size)
        for (dy in yOffsets) {
            for (dx in xOffsets) {
                if (dx == 0 && dy == 0) {
                    result.add(stroke)
                } else {
                    result.add(shiftStroke(stroke, dx.toFloat(), dy.toFloat()))
                }
            }
        }
        return result
    }

    /** Builds a tiled preview buffer (default 3x3) for inspecting seamless edge continuity. */
    fun renderTiledPreview(
        source: PixelBuffer,
        mode: SeamlessTileMode,
        tilesX: Int = 3,
        tilesY: Int = 3,
    ): PixelBuffer {
        val countX = if (mode == SeamlessTileMode.HORIZONTAL || mode == SeamlessTileMode.BOTH) tilesX.coerceIn(1, 5) else 1
        val countY = if (mode == SeamlessTileMode.VERTICAL || mode == SeamlessTileMode.BOTH) tilesY.coerceIn(1, 5) else 1
        val out = PixelBuffer(source.width * countX, source.height * countY)
        for (ty in 0 until countY) {
            for (tx in 0 until countX) {
                out.drawInto(source, tx * source.width, ty * source.height)
            }
        }
        return out
    }

    private fun shiftStroke(
        stroke: Stroke,
        dx: Float,
        dy: Float,
    ): Stroke =
        stroke.copy(
            points =
                stroke.points.map { point ->
                    StrokePoint(
                        x = point.x + dx,
                        y = point.y + dy,
                        pressure = point.pressure,
                        tiltX = point.tiltX,
                        tiltY = point.tiltY,
                        azimuth = point.azimuth,
                        timestamp = point.timestamp,
                        color = point.color,
                    )
                },
        )

    private fun wrapCoord(
        value: Float,
        span: Int,
    ): Float {
        val fSpan = span.toFloat()
        val wrapped = value - floor(value / fSpan) * fSpan
        return if (wrapped >= fSpan) 0f else wrapped
    }
}
