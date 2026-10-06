package com.artflow.studio.core.canvas

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * StreamLine-style stroke stabilisation: each sample pulls the pen position towards the raw
 * input by a fraction that shrinks as [amount] grows, removing hand tremor. When the stroke ends,
 * [finish] emits catch-up points so the line still ends exactly where the stylus lifted.
 *
 * With a [stringLength] the brush is also pulled along on a string of that length: it stays put
 * while the pen moves within reach and only follows once the string is taut, so wobbles shorter
 * than the string never reach the line. The line then ends where the brush is, not at the pen.
 */
class StrokeStabilizer(
    amount: Float,
    stringLength: Float = 0f,
) {
    private val string = if (stringLength.isFinite()) stringLength.coerceAtLeast(0f) else 0f
    private val lag = (amount.coerceIn(0f, 1f).let { it * it } * MAX_LAG).coerceIn(0f, MAX_LAG)
    private var x = 0f
    private var y = 0f
    private var started = false

    val isActive: Boolean get() = lag > 0.001f || string > 0f

    fun start(
        rawX: Float,
        rawY: Float,
    ): Pair<Float, Float> {
        x = rawX
        y = rawY
        started = true
        return x to y
    }

    fun add(
        rawX: Float,
        rawY: Float,
    ): Pair<Float, Float> {
        if (!started) return start(rawX, rawY)
        var targetX = rawX
        var targetY = rawY
        if (string > 0f) {
            val reach = hypot(rawX - x, rawY - y)
            // Within reach the string is slack and the brush stays where it is.
            if (reach <= string) return x to y
            val pull = (reach - string) / reach
            targetX = x + (rawX - x) * pull
            targetY = y + (rawY - y) * pull
        }
        val follow = 1f - lag
        x += (targetX - x) * follow
        y += (targetY - y) * follow
        return x to y
    }

    /** Points from the current smoothed position to the final raw position. */
    fun finish(
        rawX: Float,
        rawY: Float,
    ): List<Pair<Float, Float>> {
        if (!started || string > 0f) return emptyList()
        val distance = hypot(rawX - x, rawY - y)
        if (distance < 0.5f) return emptyList()
        val steps = min(MAX_CATCH_UP_STEPS, max(1, (distance / CATCH_UP_SPACING).toInt()))
        val fromX = x
        val fromY = y
        x = rawX
        y = rawY
        return List(steps) { i ->
            val t = (i + 1f) / steps
            (fromX + (rawX - fromX) * t) to (fromY + (rawY - fromY) * t)
        }
    }

    private companion object {
        const val MAX_LAG = 0.92f
        const val CATCH_UP_SPACING = 2f
        const val MAX_CATCH_UP_STEPS = 64
    }
}
