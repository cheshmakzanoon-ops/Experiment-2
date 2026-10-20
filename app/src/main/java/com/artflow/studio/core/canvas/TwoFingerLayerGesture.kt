package com.artflow.studio.core.canvas

/**
 * Procreate's two-finger gestures on a layer row, recognised from one finished two-finger touch: a
 * quick still tap (then slide) sets opacity, a sideways swipe toggles Alpha Lock and a still touch
 * held long enough selects the layer's contents. Distances are in the caller's units, so the row
 * passes its own dp-to-pixel conversion.
 */
object TwoFingerLayerGesture {
    enum class Kind { TAP_OPACITY, SWIPE_ALPHA_LOCK, HOLD_SELECT_CONTENTS }

    /** A still touch shorter than this is a tap. */
    const val TAP_MAX_MS = 400L

    /** A still touch at least this long is a hold; the gap between the two is neither. */
    const val HOLD_MIN_MS = 500L

    /**
     * The gesture made by a finished touch, or null. Only exactly two fingers count, and a kind that
     * is not in [enabled] is never returned, so a switched-off gesture leaves the touch unclaimed.
     */
    fun classify(
        fingers: Int,
        travel: Float,
        stillSlop: Float,
        sideways: Float,
        swipeThreshold: Float,
        durationMs: Long,
        enabled: Set<Kind>,
    ): Kind? {
        if (fingers != 2) return null
        val still = travel < stillSlop
        val kind =
            when {
                sideways > swipeThreshold -> Kind.SWIPE_ALPHA_LOCK
                still && durationMs < TAP_MAX_MS -> Kind.TAP_OPACITY
                still && durationMs >= HOLD_MIN_MS -> Kind.HOLD_SELECT_CONTENTS
                else -> null
            }
        return kind?.takeIf { it in enabled }
    }
}
