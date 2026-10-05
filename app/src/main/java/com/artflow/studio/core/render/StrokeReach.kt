package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.StrokePoint
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * How far from its path a stroke can paint: the largest dab radius its dynamics allow (a full
 * square's diagonal for image tips), plus scatter and the spread of repeated dabs. The live
 * preview and the rasterizer both use it to limit work to the area a stroke can touch.
 */
object StrokeReach {
    const val PADDING = 4f
    private const val SIZE_PER_TILT = 2f
    private const val SIZE_PER_SPEED = 0.5f
    private const val IMAGE_TIP_CORNER = 1.4143f
    private const val SCATTER_DIAGONAL = 0.7072f
    private const val COUNT_SPREAD = 0.25f
    private const val MIN_RADIUS = 0.35f
    private const val MAX_SCATTER = 4f

    fun of(params: BrushParams): Float {
        // Pressure, taper and speed only shrink dabs; jitter, tilt and a reversed speed setting grow them.
        val growth =
            (1f + params.sizeJitter.coerceAtLeast(0f)) *
                (1f + SIZE_PER_TILT * params.tiltInfluence.coerceIn(0f, 1f)) *
                (1f + SIZE_PER_SPEED * (-params.velocityToSize).coerceAtLeast(0f))
        val radius = max(MIN_RADIUS, max(params.size, 1f) * growth / 2f) * if (params.shapeId != null) IMAGE_TIP_CORNER else 1f
        val scatter = params.scatter.coerceIn(0f, MAX_SCATTER) * params.size * SCATTER_DIAGONAL
        val spread = if (params.count > 1) params.size * COUNT_SPREAD else 0f
        return max(radius + scatter + spread, params.dual?.let { of(it.params) } ?: 0f)
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
