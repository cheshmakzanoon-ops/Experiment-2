package com.artflow.studio.core.canvas

import kotlin.math.pow

/** Converts pointer axes without inventing force for a lifted or invalid stylus sample. */
object PointerPressure {
    fun normalize(
        stylus: Boolean,
        pressure: Float,
        contactSize: Float,
    ): Float {
        if (stylus) return if (pressure.isFinite()) pressure.coerceIn(0f, 1f) else 0f
        // Finger contact area remains the existing approximation; pen pressure is not approximated.
        val size = if (contactSize.isFinite()) contactSize.coerceIn(0f, 1f) else 0f
        return 0.35f + (size * 3f).coerceAtMost(1f) * 0.65f
    }

    /** Applies the artist's pressure curve: an exponent below 1 is softer, above 1 firmer. */
    fun curve(
        pressure: Float,
        exponent: Float,
    ): Float {
        val p = pressure.coerceIn(0f, 1f)
        if (!exponent.isFinite() || exponent == 1f) return p
        return p.pow(exponent.coerceIn(0.3f, 3f))
    }
}
