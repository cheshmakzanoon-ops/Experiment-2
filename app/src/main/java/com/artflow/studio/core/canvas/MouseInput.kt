package com.artflow.studio.core.canvas

import kotlin.math.abs
import kotlin.math.pow

/** What a mouse or trackpad press starts on the canvas. */
enum class MousePress {
    /** Left button or no button: a normal stroke, as a finger would make. */
    DRAW,

    /** Right button: sample the colour under the pointer while it is held, the eyedropper. */
    SAMPLE,

    /** Middle button: drag to pan the view. */
    PAN,
}

/** What a scroll wheel or trackpad scroll asks the canvas to do. */
sealed interface WheelAction {
    /** Nothing to move: no scroll, or a pinch that is not a change. */
    data object None : WheelAction

    /** Move the artwork by ([dx], [dy]) screen pixels; positive [dy] moves it down. */
    data class Pan(
        val dx: Float,
        val dy: Float,
    ) : WheelAction

    /** Multiply the zoom by [factor] about the pointer. */
    data class Zoom(
        val factor: Float,
    ) : WheelAction
}

/**
 * Decisions for a hardware pointer (mouse, trackpad or a pointer on a ChromeOS or DeX window). Pure, so the
 * button and scroll rules are tested without a view. The Android constants are copied here so this file
 * stays free of Android imports.
 */
object MouseInput {
    /** MotionEvent.BUTTON_PRIMARY, BUTTON_SECONDARY and BUTTON_TERTIARY. */
    const val BUTTON_PRIMARY = 1
    const val BUTTON_SECONDARY = 2
    const val BUTTON_TERTIARY = 4

    /** Pixels the artwork moves per wheel notch. */
    const val PAN_PER_NOTCH = 48f

    /** Zoom per Ctrl+wheel notch. */
    const val ZOOM_PER_NOTCH = 1.1f

    /** What a press with [buttonState] starts. The middle button pans even when another button is also held. */
    fun press(buttonState: Int): MousePress =
        when {
            (buttonState and BUTTON_TERTIARY) != 0 -> MousePress.PAN
            (buttonState and BUTTON_SECONDARY) != 0 -> MousePress.SAMPLE
            else -> MousePress.DRAW
        }

    /**
     * What a scroll does. A trackpad pinch wins when it is a real change ([pinchScale] finite, positive and not 1).
     * Otherwise Ctrl with a vertical scroll zooms about the pointer, and any other scroll pans. Positive
     * [vertical] is scroll up, which shows what is above, so the artwork moves down.
     */
    fun wheel(
        ctrl: Boolean,
        horizontal: Float,
        vertical: Float,
        pinchScale: Float,
    ): WheelAction {
        if (pinchScale.isFinite() && pinchScale > 0f && abs(pinchScale - 1f) > PINCH_EPSILON) {
            return WheelAction.Zoom(pinchScale)
        }
        if (!horizontal.isFinite() || !vertical.isFinite()) return WheelAction.None
        if (ctrl && vertical != 0f) return WheelAction.Zoom(ZOOM_PER_NOTCH.pow(vertical))
        if (horizontal == 0f && vertical == 0f) return WheelAction.None
        // (0f - horizontal) rather than -horizontal, so a zero stays +0f and compares equal.
        return WheelAction.Pan(dx = (0f - horizontal) * PAN_PER_NOTCH, dy = vertical * PAN_PER_NOTCH)
    }

    private const val PINCH_EPSILON = 0.0001f
}
