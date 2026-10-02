package com.artflow.studio.core.canvas

/** Procreate's quick pinch: closing a pinch quickly snaps the canvas back to fit the screen. */
object QuickPinch {
    const val MAX_DURATION_MS = 400L
    const val MAX_SCALE_RATIO = 0.7f

    fun isQuickPinch(
        startScale: Float,
        endScale: Float,
        durationMillis: Long,
    ): Boolean = startScale > 0f && durationMillis in 0..MAX_DURATION_MS && endScale / startScale <= MAX_SCALE_RATIO
}
