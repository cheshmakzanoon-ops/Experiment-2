package com.artflow.studio.core.color

import com.artflow.studio.domain.model.Color
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Keeps hue/saturation meaningful even when the current RGB colour is grey or black. */
data class ColorWheelState(
    val hue: Float,
    val saturation: Float,
    val value: Float,
    val alpha: Int,
) {
    val argb: Int get() = Color.fromHSV(hue, saturation, value, alpha).toAndroidColor()

    fun withArgb(color: Int): ColorWheelState {
        val hsv = Color.rgbToHsv(color)
        return copy(
            hue = if (hsv[1] == 0f) hue else hsv[0],
            saturation = if (hsv[2] == 0f) saturation else hsv[1],
            value = hsv[2],
            alpha = color ushr 24,
        )
    }

    fun withValue(brightness: Float): ColorWheelState = if (brightness.isFinite()) copy(value = brightness.coerceIn(0f, 1f)) else this

    /** [region] belongs to pointer-down and never changes midway through a drag. */
    fun pick(
        x: Float,
        y: Float,
        geometry: ColorWheelGeometry,
        region: ColorWheelRegion,
    ): ColorWheelState {
        if (!x.isFinite() || !y.isFinite() || !geometry.valid) return this
        val dx = x - geometry.centerX
        val dy = y - geometry.centerY
        return when (region) {
            ColorWheelRegion.SQUARE ->
                copy(
                    saturation = ((x - (geometry.centerX - geometry.inner)) / (2f * geometry.inner)).coerceIn(0f, 1f),
                    value = (1f - (y - (geometry.centerY - geometry.inner)) / (2f * geometry.inner)).coerceIn(0f, 1f),
                )
            ColorWheelRegion.HUE -> {
                if (dx == 0f && dy == 0f) return this
                val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                copy(hue = (angle + 360f) % 360f)
            }
        }
    }

    companion object {
        fun fromArgb(color: Int): ColorWheelState {
            val hsv = Color.rgbToHsv(color)
            return ColorWheelState(hsv[0], hsv[1], hsv[2], color ushr 24)
        }
    }
}

enum class ColorWheelRegion {
    HUE,
    SQUARE,
}

/** Pixel geometry shared by painting and hit testing. The square fits inside the ring. */
data class ColorWheelGeometry(
    val width: Float,
    val height: Float,
) {
    val centerX: Float get() = width / 2f
    val centerY: Float get() = height / 2f
    val radius: Float get() = (minOf(width, height) / 2f - 12f) / 1.11f
    val inner: Float get() = radius * 0.60f
    val ringStroke: Float get() = radius * 0.22f
    val valid: Boolean get() = width.isFinite() && height.isFinite() && radius > 0f

    fun hitTest(
        x: Float,
        y: Float,
    ): ColorWheelRegion? {
        if (!valid || !x.isFinite() || !y.isFinite()) return null
        val dx = x - centerX
        val dy = y - centerY
        if (x in (centerX - inner)..(centerX + inner) && y in (centerY - inner)..(centerY + inner)) return ColorWheelRegion.SQUARE
        val distance = hypot(dx, dy)
        return if (abs(distance - radius) <= ringStroke / 2f) ColorWheelRegion.HUE else null
    }
}
