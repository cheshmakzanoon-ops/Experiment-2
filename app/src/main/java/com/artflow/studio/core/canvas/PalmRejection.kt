package com.artflow.studio.core.canvas

import kotlin.math.abs

/**
 * Palm rejection for a stylus artist.
 *
 * While a stylus hovers over the screen or touches it, a finger that lands with a large contact (a
 * resting palm) or within [contactWindowMs] of the pen's contact is ignored: it cannot start a
 * stroke or a gesture. Without a stylus in range every finger works as before, so a finger-only artist
 * is never affected. A finger that lands during the pen's own stroke is already ignored by
 * [PointerGestureRouter], because the pen owns the touch stream.
 */
class PalmRejection(
    private val contactWindowMs: Long = CONTACT_WINDOW_MS,
    private val palmContactSize: Float = PALM_CONTACT_SIZE,
) {
    private var hovering = false
    private var penDown = false
    private var lastContactMs = NO_TIME

    /** The stylus has entered ([hovering] true) or left the hover range. */
    fun stylusHover(hovering: Boolean) {
        this.hovering = hovering
    }

    /** The stylus has touched down or lifted. */
    fun stylusContact(
        down: Boolean,
        timeMs: Long,
    ) {
        penDown = down
        lastContactMs = timeMs
    }

    /** Whether a finger that lands now, with [contactSize] normalised to 0..1, must not start anything. */
    fun rejectsFinger(
        contactSize: Float,
        timeMs: Long,
    ): Boolean {
        if (!hovering && !penDown) return false
        val large = contactSize.isFinite() && contactSize >= palmContactSize
        val nearContact = lastContactMs != NO_TIME && abs(timeMs - lastContactMs) < contactWindowMs
        return large || nearContact
    }

    /** Clears the stylus state, for example when the canvas is paused or detached. */
    fun reset() {
        hovering = false
        penDown = false
        lastContactMs = NO_TIME
    }

    companion object {
        /** A finger within this long of the pen's contact is taken to be the palm that came with it. */
        const val CONTACT_WINDOW_MS = 400L

        /** Finger contact area, normalised 0..1. Fingers typically report well below this; a resting palm above it. */
        const val PALM_CONTACT_SIZE = 0.45f

        private const val NO_TIME = Long.MIN_VALUE
    }
}
