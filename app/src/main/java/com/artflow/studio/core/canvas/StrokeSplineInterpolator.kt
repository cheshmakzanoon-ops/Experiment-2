package com.artflow.studio.core.canvas

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Online cubic Hermite (non-uniform Catmull-Rom) interpolator for live touch and stylus strokes.
 *
 * Samples arrive unevenly: a slow, dense run followed by one long jump is routine. A uniform Catmull-Rom
 * spline takes its tangents from the neighbouring samples regardless of their distance, so beside a much
 * shorter or longer span it overshoots the span's ends and can even run backwards along it. The tangents
 * here are scaled by the neighbouring chord lengths and bounded relative to the span, which keeps the
 * curve smooth through evenly spaced samples (it reduces to Catmull-Rom there) and well behaved otherwise.
 *
 * Smooths polygonal segments on fast sweeps or 60 Hz touch digitizers by subdividing the middle
 * span `(p1 -> p2)` of a 4-point sliding window whenever its chord length exceeds [maxStepPx].
 * Uses primitive fields with an inline callback to avoid per-sample object allocations.
 */
class StrokeSplineInterpolator(
    private val maxStepPx: Float = DEFAULT_MAX_STEP_PX,
) {
    private var count = 0

    // 4-point sliding window: p0, p1, p2, p3
    private var x0 = 0f
    private var y0 = 0f
    private var pr0 = 0f
    private var tx0 = 0f
    private var ty0 = 0f
    private var t0 = 0L

    private var x1 = 0f
    private var y1 = 0f
    private var pr1 = 0f
    private var tx1 = 0f
    private var ty1 = 0f
    private var t1 = 0L

    private var x2 = 0f
    private var y2 = 0f
    private var pr2 = 0f
    private var tx2 = 0f
    private var ty2 = 0f
    private var t2 = 0L

    private var x3 = 0f
    private var y3 = 0f
    private var pr3 = 0f
    private var tx3 = 0f
    private var ty3 = 0f
    private var t3 = 0L

    fun reset() {
        count = 0
    }

    /** Seeds the first sample of a stroke (already emitted by `beginStroke`). */
    fun start(
        x: Float,
        y: Float,
        pressure: Float,
        tiltX: Float,
        tiltY: Float,
        timestamp: Long,
    ) {
        x0 = x
        y0 = y
        pr0 = pressure
        tx0 = tiltX
        ty0 = tiltY
        t0 = timestamp
        x1 = x
        y1 = y
        pr1 = pressure
        tx1 = tiltX
        ty1 = tiltY
        t1 = timestamp
        count = 1
    }

    /**
     * Feeds the next sample and invokes [emit] for every interpolated point along the newly
     * completed smooth span.
     */
    fun add(
        x: Float,
        y: Float,
        pressure: Float,
        tiltX: Float,
        tiltY: Float,
        timestamp: Long,
        emit: (x: Float, y: Float, pressure: Float, tiltX: Float, tiltY: Float, timestamp: Long) -> Unit,
    ) {
        if (count == 0) {
            start(x, y, pressure, tiltX, tiltY, timestamp)
            emit(x, y, pressure, tiltX, tiltY, timestamp)
            return
        }
        if (count == 1) {
            x2 = x
            y2 = y
            pr2 = pressure
            tx2 = tiltX
            ty2 = tiltY
            t2 = timestamp
            count = 2
            return
        }
        if (count == 2) {
            x3 = x
            y3 = y
            pr3 = pressure
            tx3 = tiltX
            ty3 = tiltY
            t3 = timestamp
            count = 3
            emitSpan(emit)
            return
        }
        shiftWindow(x, y, pressure, tiltX, tiltY, timestamp)
        emitSpan(emit)
    }

    /** Flushes the final pending segment so the stroke ends at the exact lift coordinate. */
    fun finish(emit: (x: Float, y: Float, pressure: Float, tiltX: Float, tiltY: Float, timestamp: Long) -> Unit) {
        when (count) {
            2 -> {
                // Only two points total: emit p2 directly.
                emit(x2, y2, pr2, tx2, ty2, t2)
            }

            3 -> {
                // Shift once more with p3 duplicated as tangent endpoint to flush (p2 -> p3).
                shiftWindow(x3, y3, pr3, tx3, ty3, t3)
                emitSpan(emit)
            }
        }
        count = 0
    }

    private fun shiftWindow(
        x: Float,
        y: Float,
        pressure: Float,
        tiltX: Float,
        tiltY: Float,
        timestamp: Long,
    ) {
        x0 = x1
        y0 = y1
        pr0 = pr1
        tx0 = tx1
        ty0 = ty1
        t0 = t1

        x1 = x2
        y1 = y2
        pr1 = pr2
        tx1 = tx2
        ty1 = ty2
        t1 = t2

        x2 = x3
        y2 = y3
        pr2 = pr3
        tx2 = tx3
        ty2 = ty3
        t2 = t3

        x3 = x
        y3 = y
        pr3 = pressure
        tx3 = tiltX
        ty3 = tiltY
        t3 = timestamp
    }

    private var tangentX = 0f
    private var tangentY = 0f

    /**
     * The span's end tangent from the chord to the far neighbour, scaled by [spanLength] over the two
     * chords it joins, then bounded: no backward travel along the span and no excursion beyond 3x its length.
     */
    private fun boundedTangent(
        towardX: Float,
        towardY: Float,
        neighbourChord: Float,
        spanLength: Float,
        spanX: Float,
        spanY: Float,
    ) {
        val total = neighbourChord + spanLength
        if (total <= 0f || spanLength <= 0f) {
            tangentX = 0f
            tangentY = 0f
            return
        }
        val scale = spanLength / total
        var mx = towardX * scale
        var my = towardY * scale
        val ux = spanX / spanLength
        val uy = spanY / spanLength
        val along = (mx * ux + my * uy).coerceIn(0f, MAX_TANGENT_SPANS * spanLength)
        var px = mx - (mx * ux + my * uy) * ux
        var py = my - (mx * ux + my * uy) * uy
        val perpendicular = sqrt(px * px + py * py)
        val limit = MAX_PERPENDICULAR_SPANS * spanLength
        if (perpendicular > limit) {
            px *= limit / perpendicular
            py *= limit / perpendicular
        }
        mx = ux * along + px
        my = uy * along + py
        tangentX = mx
        tangentY = my
    }

    private fun emitSpan(emit: (x: Float, y: Float, pressure: Float, tiltX: Float, tiltY: Float, timestamp: Long) -> Unit) {
        val chord = hypot(x2 - x1, y2 - y1)
        val before = hypot(x1 - x0, y1 - y0)
        val after = hypot(x3 - x2, y3 - y2)
        boundedTangent(x2 - x0, y2 - y0, before, chord, x2 - x1, y2 - y1)
        val m1x = tangentX
        val m1y = tangentY
        boundedTangent(x3 - x1, y3 - y1, after, chord, x2 - x1, y2 - y1)
        val m2x = tangentX
        val m2y = tangentY
        val steps =
            if (chord <= maxStepPx) {
                1
            } else {
                min(MAX_SUBDIVISIONS, max(1, ceil(chord / maxStepPx).toInt()))
            }
        for (i in 1..steps) {
            val u = i.toFloat() / steps
            if (i == steps) {
                emit(x2, y2, pr2, tx2, ty2, t2)
            } else {
                val cx = hermite(x1, x2, m1x, m2x, u)
                val cy = hermite(y1, y2, m1y, m2y, u)
                val cpr = (pr1 + (pr2 - pr1) * u).coerceIn(0f, 1f)
                val ctx = tx1 + (tx2 - tx1) * u
                val cty = ty1 + (ty2 - ty1) * u
                val ct = t1 + ((t2 - t1) * u).toLong()
                emit(cx, cy, cpr, ctx, cty, ct)
            }
        }
    }

    private fun hermite(
        start: Float,
        end: Float,
        startTangent: Float,
        endTangent: Float,
        u: Float,
    ): Float {
        val u2 = u * u
        val u3 = u2 * u
        return (2f * u3 - 3f * u2 + 1f) * start +
            (u3 - 2f * u2 + u) * startTangent +
            (-2f * u3 + 3f * u2) * end +
            (u3 - u2) * endTangent
    }

    companion object {
        const val DEFAULT_MAX_STEP_PX = 2.5f
        private const val MAX_SUBDIVISIONS = 16
        private const val MAX_TANGENT_SPANS = 3f
        private const val MAX_PERPENDICULAR_SPANS = 1.5f
    }
}
