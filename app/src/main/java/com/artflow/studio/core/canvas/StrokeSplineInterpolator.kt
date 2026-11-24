package com.artflow.studio.core.canvas

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Online Catmull-Rom cubic spline interpolator for live touch and stylus strokes.
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

    private fun emitSpan(emit: (x: Float, y: Float, pressure: Float, tiltX: Float, tiltY: Float, timestamp: Long) -> Unit) {
        val chord = hypot(x2 - x1, y2 - y1)
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
                val cx = catmullRom(x0, x1, x2, x3, u)
                val cy = catmullRom(y0, y1, y2, y3, u)
                val cpr = (pr1 + (pr2 - pr1) * u).coerceIn(0f, 1f)
                val ctx = tx1 + (tx2 - tx1) * u
                val cty = ty1 + (ty2 - ty1) * u
                val ct = t1 + ((t2 - t1) * u).toLong()
                emit(cx, cy, cpr, ctx, cty, ct)
            }
        }
    }

    private fun catmullRom(
        v0: Float,
        v1: Float,
        v2: Float,
        v3: Float,
        u: Float,
    ): Float {
        val u2 = u * u
        val u3 = u2 * u
        return 0.5f * (
            (2f * v1) +
                (-v0 + v2) * u +
                (2f * v0 - 5f * v1 + 4f * v2 - v3) * u2 +
                (-v0 + 3f * v1 - 3f * v2 + v3) * u3
        )
    }

    companion object {
        const val DEFAULT_MAX_STEP_PX = 2.5f
        private const val MAX_SUBDIVISIONS = 16
    }
}
