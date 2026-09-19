package com.artflow.studio.domain.model.brush

import kotlin.math.roundToInt

/** Exact-value entry rejects invalid values instead of silently clipping the artist's input. */
object BrushValue {
    fun parse(
        text: String,
        range: ClosedFloatingPointRange<Float>,
        integer: Boolean = false,
        displayScale: Float = 1f,
    ): Float? {
        require(displayScale.isFinite() && displayScale > 0f)
        val value = text.trim()
        if (value.length !in 1..16 || !value.matches(Regex("-?[0-9]*([.,][0-9]+)?"))) return null
        val number = value.replace(',', '.').toFloatOrNull() ?: return null
        val raw = number / displayScale
        if (!raw.isFinite() || raw !in range) return null
        if (integer && raw != raw.roundToInt().toFloat()) return null
        return raw
    }
}
