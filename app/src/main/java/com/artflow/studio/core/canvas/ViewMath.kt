package com.artflow.studio.core.canvas

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Canvas and view sizes in pixels: the artwork's width and height, and the screen area it is drawn into. */
data class ViewFrame(
    val canvasWidth: Float,
    val canvasHeight: Float,
    val viewWidth: Float,
    val viewHeight: Float,
)

/**
 * The canvas view transform. A canvas point lands on screen at
 * centre + offset + R(rotation) * scale * (point - canvasCentre), where the centre is the middle of the view.
 */
data class ViewTransform(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val rotationDegrees: Float,
)

/** Pure view maths: the same transform the canvas view draws with, testable without a view. */
object ViewMath {
    /** Screen position of canvas point ([x], [y]). */
    fun canvasToScreen(
        t: ViewTransform,
        frame: ViewFrame,
        x: Float,
        y: Float,
    ): Pair<Float, Float> {
        val (cosR, sinR) = rotationCosSin(t.rotationDegrees)
        val u = t.scale * (x - frame.canvasWidth / 2f)
        val v = t.scale * (y - frame.canvasHeight / 2f)
        return (u * cosR - v * sinR + frame.viewWidth / 2f + t.offsetX) to
            (u * sinR + v * cosR + frame.viewHeight / 2f + t.offsetY)
    }

    /** Canvas point under screen position ([x], [y]); the inverse of [canvasToScreen]. */
    fun screenToCanvas(
        t: ViewTransform,
        frame: ViewFrame,
        x: Float,
        y: Float,
    ): Pair<Float, Float> {
        val (cosR, sinR) = rotationCosSin(t.rotationDegrees)
        val rx = x - frame.viewWidth / 2f - t.offsetX
        val ry = y - frame.viewHeight / 2f - t.offsetY
        val u = rx * cosR + ry * sinR
        val v = -rx * sinR + ry * cosR
        return (u / t.scale + frame.canvasWidth / 2f) to (v / t.scale + frame.canvasHeight / 2f)
    }

    /**
     * Zooms by [factor] (clamped to [minScale]..[maxScale]) so that the canvas point under the focus
     * ([focusX], [focusY]) stays under it: Ctrl+scroll, trackpad pinch and the mouse wheel zoom about the pointer.
     */
    fun zoomAbout(
        t: ViewTransform,
        frame: ViewFrame,
        factor: Float,
        minScale: Float,
        maxScale: Float,
        focusX: Float,
        focusY: Float,
    ): ViewTransform {
        if (t.scale <= 0f || !factor.isFinite() || factor <= 0f) return t
        val (canvasX, canvasY) = screenToCanvas(t, frame, focusX, focusY)
        val scale = (t.scale * factor).coerceIn(minScale, maxScale)
        val (cosR, sinR) = rotationCosSin(t.rotationDegrees)
        val u = scale * (canvasX - frame.canvasWidth / 2f)
        val v = scale * (canvasY - frame.canvasHeight / 2f)
        return t.copy(
            scale = scale,
            offsetX = focusX - frame.viewWidth / 2f - (u * cosR - v * sinR),
            offsetY = focusY - frame.viewHeight / 2f - (u * sinR + v * cosR),
        )
    }

    /** Degrees in [0, 360). */
    fun normalizeDegrees(degrees: Float): Float {
        val wrapped = degrees % FULL_TURN
        return if (wrapped < 0f) wrapped + FULL_TURN else wrapped
    }

    /** The smallest angle between two rotations, in degrees, from 0 to 180. */
    fun angularDistance(
        a: Float,
        b: Float,
    ): Float {
        val difference = abs(normalizeDegrees(a - b))
        return minOf(difference, FULL_TURN - difference)
    }

    /** Zoom as the artist reads it: 100 at actual size. */
    fun zoomPercent(scale: Float): Int = (scale * 100f).roundToInt()

    /** Rotation for the readout, signed from -179 to 180 degrees, so a small turn either way reads small. */
    fun readoutDegrees(rotationDegrees: Float): Int {
        val normalized = normalizeDegrees(rotationDegrees)
        return (if (normalized > HALF_TURN) normalized - FULL_TURN else normalized).roundToInt()
    }

    private fun rotationCosSin(rotationDegrees: Float): Pair<Float, Float> {
        val radians = Math.toRadians(rotationDegrees.toDouble())
        return cos(radians).toFloat() to sin(radians).toFloat()
    }

    private const val FULL_TURN = 360f
    private const val HALF_TURN = 180f
}
