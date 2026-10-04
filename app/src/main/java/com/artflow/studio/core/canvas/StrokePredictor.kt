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
        if (samples < MIN_SAMPLES) return emptyList()
        val speed = hypot(vx, vy)
        if (speed < MIN_SPEED) return emptyList()
        return (1..steps).map { step ->
            val t = horizonMs * step / steps
            (lastX + vx * t + 0.5f * ax * t * t) to (lastY + vy * t + 0.5f * ay * t * t)
        }
    }

    private fun smooth(
        previous: Float,
        next: Float,
    ): Float = previous + (next - previous) * SMOOTHING

    private companion object {
        const val DEFAULT_HORIZON_MS = 16f
        const val DEFAULT_STEPS = 4
        const val MIN_SAMPLES = 3
        const val MIN_SPEED = 0.02f
        const val SMOOTHING = 0.6f
    }
}
