package com.artflow.studio.core.pixels

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Integrates a thin ellipse over the pixel footprint instead of testing only its centre. */
internal class SubpixelEllipse(
    private val x: Float,
    private val y: Float,
    private val radius: Float,
    private val minor: Float,
    private val cosA: Float,
    private val sinA: Float,
    private val hardness: Float,
) {
    private val shortRadius = radius * minor
    private val xVariance = radius * radius * cosA * cosA + shortRadius * shortRadius * sinA * sinA
    private val xExtent = sqrt(xVariance)
    private val slope = (radius * radius - shortRadius * shortRadius) * sinA * cosA / xVariance
    private val halfHeight = radius * shortRadius / xExtent

    fun coverage(
        px: Int,
        py: Int,
    ): Float {
        val left = max(px - x, -xExtent)
        val right = min(px + 1f - x, xExtent)
        if (right <= left) return 0f
        val step = (right - left) / HORIZONTAL_SAMPLES
        var sum = 0f
        repeat(HORIZONTAL_SAMPLES) { sample ->
            val dx = left + (sample + 0.5f) * step
            val middle = y + slope * dx
            val half = halfHeight * sqrt((1f - dx * dx / xVariance).coerceAtLeast(0f))
            val top = max(py.toFloat(), middle - half)
            val bottom = min(py + 1f, middle + half)
            if (bottom > top) sum += (bottom - top) * softness(dx, top, bottom)
        }
        return (sum * step).coerceIn(0f, 1f)
    }

    private fun softness(
        dx: Float,
        top: Float,
        bottom: Float,
    ): Float {
        if (hardness >= 1f) return 1f
        var sum = 0f
        repeat(VERTICAL_SAMPLES) { sample ->
            val dy = top + (sample + 0.5f) * (bottom - top) / VERTICAL_SAMPLES - y
            val along = (dx * cosA + dy * sinA) / radius
            val across = (-dx * sinA + dy * cosA) / shortRadius
            val distance = sqrt(along * along + across * across)
            sum += ((1f - distance) / (1f - hardness)).coerceIn(0f, 1f)
        }
        return sum / VERTICAL_SAMPLES
    }

    private companion object {
        const val HORIZONTAL_SAMPLES = 8
        const val VERTICAL_SAMPLES = 4
    }
}
