package com.artflow.studio.core.canvas

import kotlin.math.PI
import kotlin.math.hypot

/**
 * Prefs > Pressure and Smoothing's motion filtering, applied to every stroke before the brush's own
 * smoothing: an adaptive low-pass (a "one euro" filter) that steadies slow, careful movement more
 * than fast sweeps, so jitter goes without making quick strokes lag. Expression gives a share of
 * the filtered-out movement back.
 */
class MotionFilter(
    amount: Float,
    expression: Float,
) {
    private val amount = amount.coerceIn(0f, 1f)
    private val expression = expression.coerceIn(0f, 1f)
    private var x = 0f
    private var y = 0f
    private var lastTime = 0L

    val isActive: Boolean get() = amount > 0f && expression < 1f

    fun start(
        x: Float,
        y: Float,
        timeMs: Long,
    ) {
        this.x = x
        this.y = y
        lastTime = timeMs
    }

    /** The filtered position for a pen sample at ([px], [py]) taken at [timeMs]. */
    fun add(
        px: Float,
        py: Float,
        timeMs: Long,
    ): Pair<Float, Float> {
        if (!isActive) return px to py
        val seconds = (timeMs - lastTime).coerceAtLeast(1L) / MILLIS
        lastTime = timeMs
        val speed = hypot(px - x, py - y) / seconds
        val cutoff = MIN_CUTOFF + (MAX_CUTOFF - MIN_CUTOFF) * (1f - amount) + SPEED_GAIN * (1f - amount * KEPT_GAIN) * speed
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        val alpha = seconds / (seconds + tau)
        x += alpha * (px - x)
        y += alpha * (py - y)
        return (x + expression * (px - x)) to (y + expression * (py - y))
    }

    private companion object {
        const val MILLIS = 1000f

        /** Cutoff frequencies in hertz: full filtering keeps only slow drift at rest. */
        const val MIN_CUTOFF = 1f
        const val MAX_CUTOFF = 30f

        /** How much faster movement raises the cutoff (per canvas pixel per second). */
        const val SPEED_GAIN = 0.02f
        const val KEPT_GAIN = 0.8f
    }
}

/** Prefs > Pressure and Smoothing's pressure smoothing: sudden pressure changes are evened out. */
class PressureSmoother(
    amount: Float,
) {
    private val response = 1f - amount.coerceIn(0f, 1f) * MAX_DAMPING
    private var value = Float.NaN

    fun start(pressure: Float) {
        value = pressure
    }

    fun add(pressure: Float): Float {
        value = if (value.isNaN()) pressure else value + response * (pressure - value)
        return value
    }

    private companion object {
        /** Full smoothing still follows a tenth of each change, so pressure never freezes. */
        const val MAX_DAMPING = 0.9f
    }
}
