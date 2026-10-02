package com.artflow.studio.core.canvas

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * QuickShape: recognises a freehand stroke the artist holds at the end and returns a clean
 * replacement path — a straight line, or an ellipse/circle for closed loops.
 */
object QuickShape {
    enum class Kind { LINE, ELLIPSE }

    data class Result(
        val kind: Kind,
        val points: List<Pair<Float, Float>>,
    )

    /** Returns the recognised shape, or null when the stroke is too short or too irregular. */
    fun recognize(points: List<Pair<Float, Float>>): Result? {
        if (points.size < 3) return null
        val first = points.first()
        val last = points.last()
        val length = pathLength(points)
        if (length < MIN_LENGTH) return null
        val chord = hypot(last.first - first.first, last.second - first.second)
        if (chord / length > LINE_STRAIGHTNESS) return Result(Kind.LINE, line(first, last))
        if (chord < length * CLOSED_RATIO) return ellipse(points)
        return null
    }

    private fun line(
        a: Pair<Float, Float>,
        b: Pair<Float, Float>,
    ): List<Pair<Float, Float>> {
        val steps = max(2, (hypot(b.first - a.first, b.second - a.second) / SAMPLE_SPACING).toInt())
        return List(steps + 1) { i ->
            val t = i.toFloat() / steps
            (a.first + (b.first - a.first) * t) to (a.second + (b.second - a.second) * t)
        }
    }

    /** Principal-axis ellipse fit from the covariance of the stroke samples. */
    private fun ellipse(points: List<Pair<Float, Float>>): Result? {
        val n = points.size
        val cx = points.sumOf { it.first.toDouble() } / n
        val cy = points.sumOf { it.second.toDouble() } / n
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        points.forEach { (x, y) ->
            val dx = x - cx
            val dy = y - cy
            sxx += dx * dx
            syy += dy * dy
            sxy += dx * dy
        }
        sxx /= n
        syy /= n
        sxy /= n
        val angle = 0.5 * atan2(2 * sxy, sxx - syy)
        val cosA = cos(angle)
        val sinA = sin(angle)
        // Radii from the furthest projection onto each principal axis.
        var ru = 0.0
        var rv = 0.0
        points.forEach { (x, y) ->
            val dx = x - cx
            val dy = y - cy
            ru = max(ru, abs(dx * cosA + dy * sinA))
            rv = max(rv, abs(-dx * sinA + dy * cosA))
        }
        if (ru < MIN_RADIUS || rv < MIN_RADIUS) return null
        // Snap nearly round loops to a perfect circle.
        if (abs(ru - rv) / max(ru, rv) < CIRCLE_SNAP) {
            val r = sqrt(ru * rv)
            ru = r
            rv = r
        }
        // Reject strokes that stray far from the fitted outline (e.g. scribbles).
        var error = 0.0
        points.forEach { (x, y) ->
            val dx = x - cx
            val dy = y - cy
            val u = (dx * cosA + dy * sinA) / ru
            val v = (-dx * sinA + dy * cosA) / rv
            error += abs(sqrt(u * u + v * v) - 1.0)
        }
        if (error / n > MAX_ELLIPSE_ERROR) return null
        val circumference = PI * (3 * (ru + rv) - sqrt((3 * ru + rv) * (ru + 3 * rv)))
        val steps = max(24, (circumference / SAMPLE_SPACING).toInt())
        val start = atan2(points.first().second - cy, points.first().first - cx) - angle
        val outline =
            List(steps + 1) { i ->
                val t = start + 2 * PI * i / steps
                val u = ru * cos(t)
                val v = rv * sin(t)
                (cx + u * cosA - v * sinA).toFloat() to (cy + u * sinA + v * cosA).toFloat()
            }
        return Result(Kind.ELLIPSE, outline)
    }

    private fun pathLength(points: List<Pair<Float, Float>>): Float =
        points.zipWithNext().sumOf { (a, b) -> hypot(b.first - a.first, b.second - a.second).toDouble() }.toFloat()

    private const val MIN_LENGTH = 24f
    private const val LINE_STRAIGHTNESS = 0.9f
    private const val CLOSED_RATIO = 0.2f
    private const val MIN_RADIUS = 6.0
    private const val CIRCLE_SNAP = 0.12
    private const val MAX_ELLIPSE_ERROR = 0.12
    private const val SAMPLE_SPACING = 3f
}
