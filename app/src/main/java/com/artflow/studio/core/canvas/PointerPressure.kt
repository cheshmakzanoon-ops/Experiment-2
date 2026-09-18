package com.artflow.studio.core.canvas

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
}
