package com.artflow.studio.core.canvas

import kotlin.math.hypot

/**
 * Predicts where the pen will be a few milliseconds ahead, so the stroke can be drawn ahead of the
 * last reported sample and feel attached to the pen. Velocity and acceleration are smoothed from
 * the recent samples; predictions are only ever drawn as a temporary overlay, never painted.
 */
class StrokePredictor(
    private val horizonMs: Float = DEFAULT_HORIZON_MS,
) {
    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L
    private var vx = 0f
    private var vy = 0f
    private var ax = 0f
    private var ay = 0f
    private var samples = 0

    fun reset() {
        samples = 0
        vx = 0f
        vy = 0f
        ax = 0f
        ay = 0f
    }

    fun add(
        x: Float,
        y: Float,
        timeMs: Long,
    ) {
        if (samples > 0) {
            val dt = (timeMs - lastT).toFloat()
            if (dt <= 0f) return
            val nvx = (x - lastX) / dt
            val nvy = (y - lastY) / dt
            if (samples > 1) {
                ax = smooth(ax, (nvx - vx) / dt)
                ay = smooth(ay, (nvy - vy) / dt)
            }
            vx = smooth(vx, nvx)
            vy = smooth(vy, nvy)
        }
        lastX = x
        lastY = y
        lastT = timeMs
        samples++
    }

    /** Up to [steps] points from the last sample towards the predicted position; empty until moving. */
    fun predict(steps: Int = DEFAULT_STEPS): List<Pair<Float, Float>> {
        if (samples < MIN_SAMPLES || steps <= 0) return emptyList()
        val speed = hypot(vx, vy)
        if (speed < MIN_SPEED) return emptyList()
        val out = FloatArray(steps * 2)
        val count = predictInto(out, steps)
        return List(count / 2) { index -> out[index * 2] to out[index * 2 + 1] }
    }

    /**
     * Zero-allocation prediction writing `(x, y)` pairs into [out] (must hold at least `steps * 2`
     * floats). Returns the number of floats written (`0` when idle or stationary).
     */
    fun predictInto(
        out: FloatArray,
        steps: Int = DEFAULT_STEPS,
    ): Int {
        if (samples < MIN_SAMPLES || steps <= 0 || out.size < steps * 2) return 0
        val speed = hypot(vx, vy)
        if (speed < MIN_SPEED) return 0
        val dampedAx = ax * ACCEL_DAMPING
        val dampedAy = ay * ACCEL_DAMPING
        var written = 0
        for (step in 1..steps) {
            val t = horizonMs * step / steps
            var dx = vx * t + 0.5f * dampedAx * t * t
            var dy = vy * t + 0.5f * dampedAy * t * t
            val dist = hypot(dx, dy)
            if (dist > MAX_PREDICTION_PX) {
                val scale = MAX_PREDICTION_PX / dist
                dx *= scale
                dy *= scale
            }
            out[written++] = lastX + dx
            out[written++] = lastY + dy
        }
        return written
    }

    private fun smooth(
        previous: Float,
        next: Float,
    ): Float = previous + (next - previous) * SMOOTHING

    companion object {
        const val DEFAULT_HORIZON_MS = 16f
        const val DEFAULT_STEPS = 4
        const val MAX_PREDICTION_PX = 48f
        private const val MIN_SAMPLES = 3
        private const val MIN_SPEED = 0.02f
        private const val SMOOTHING = 0.6f
        private const val ACCEL_DAMPING = 0.5f
    }
}
