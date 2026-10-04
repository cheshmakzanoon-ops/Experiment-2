package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.StrokePoint
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * How far from its path a stroke can paint. The reach is a full brush size (twice the largest dab
 * radius) plus jitter, tilt and scatter, so it safely contains every dab; the live preview and the
 * rasterizer both use it to limit work to the area a stroke can touch.
 */
object StrokeReach {
    const val PADDING = 4f

    fun of(params: BrushParams): Float {
        val tiltGain = 1f + 2f * params.tiltInfluence.coerceIn(0f, 1f)
        val own = params.size * (1f + params.sizeJitter) * tiltGain + params.size * params.scatter
        return max(own, params.dual?.let { of(it.params) } ?: 0f)
    }

    /** Pixels of a [width] × [height] buffer that [points] painted with [params] can reach; may be empty. */
    fun bounds(
        points: List<StrokePoint>,
        params: BrushParams,
        width: Int,
        height: Int,
    ): IntBounds {
        if (points.isEmpty()) return IntBounds(0, 0, -1, -1)
        val reach = of(params) + PADDING
        return IntBounds(
            max(0, floor(points.minOf { it.x } - reach).toInt()),
            max(0, floor(points.minOf { it.y } - reach).toInt()),
            min(width - 1, ceil(points.maxOf { it.x } + reach).toInt()),
            min(height - 1, ceil(points.maxOf { it.y } + reach).toInt()),
        )
    }
}
