package com.artflow.studio.core.pixels

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Integrates ellipse edges over the pixel footprint, with fast paths for resolved interiors. */
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
    private val inverseRadius = 1f / radius
    private val inverseShortRadius = 1f / shortRadius
    private val xVariance = radius * radius * cosA * cosA + shortRadius * shortRadius * sinA * sinA
    val xExtent = sqrt(xVariance)
    val yExtent = sqrt(radius * radius * sinA * sinA + shortRadius * shortRadius * cosA * cosA)
    private val slope = (radius * radius - shortRadius * shortRadius) * sinA * cosA / xVariance
    private val halfHeight = radius * shortRadius / xExtent

    // Maximum distance from the pixel centre to a corner in the ellipse's normalized units.
    // The triangle inequality then identifies pixels entirely inside or outside a contour.
    private val pixelReach =
        0.5f *
            sqrt(
                inverseRadius * inverseRadius + inverseShortRadius * inverseShortRadius +
                    2f * abs((inverseRadius * inverseRadius - inverseShortRadius * inverseShortRadius) * cosA * sinA),
            )
    private val outsideSquared = (1f + pixelReach) * (1f + pixelReach)
    private val solidSquared = if (hardness >= pixelReach) (hardness - pixelReach) * (hardness - pixelReach) else -1f

    fun coverage(
        px: Int,
        py: Int,
    ): Float {
        val dx = px + 0.5f - x
        val dy = py + 0.5f - y
        val along = (dx * cosA + dy * sinA) * inverseRadius
        val across = (-dx * sinA + dy * cosA) * inverseShortRadius
        val distanceSquared = along * along + across * across
        if (distanceSquared >= outsideSquared) return 0f
        if (distanceSquared <= solidSquared) return 1f
        if (hardness < 1f) {
            val distance = sqrt(distanceSquared)
            // A resolved falloff can be sampled at its centre. Integrate pixels crossing either
            // contour, and all subpixel tips, so every size uses the same geometric profile.
            if (distance >= hardness + pixelReach && distance <= 1f - pixelReach) {
                return (1f - distance) / (1f - hardness)
            }
        }
        return integratedCoverage(px, py)
    }

    private fun integratedCoverage(
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
            val along = (dx * cosA + dy * sinA) * inverseRadius
            val across = (-dx * sinA + dy * cosA) * inverseShortRadius
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
