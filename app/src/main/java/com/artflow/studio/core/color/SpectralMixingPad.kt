package com.artflow.studio.core.color

import com.artflow.studio.core.pixels.PixelBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Interactive Kubelka-Munk physical pigment mixing pad.
 *
 * Artists load pigment wells or smear colors directly across the mixing canvas using subtractive
 * spectral physics (e.g. Ultramarine Blue + Hansa Yellow yields authentic natural green rather than
 * muddy RGB grey), then pick any blended hue straight onto their active brush.
 */
class SpectralMixingPad(
    val width: Int = DEFAULT_WIDTH,
    val height: Int = DEFAULT_HEIGHT,
    initialWells: List<Int> = DEFAULT_WELLS,
) {
    init {
        require(width > 0 && height > 0) { "Mixing pad dimensions must be positive" }
    }

    val buffer: PixelBuffer = PixelBuffer(width, height).also { it.fill(PAD_BACKGROUND) }

    private val _wells = initialWells.take(MAX_WELLS).toMutableList()
    val wells: List<Int> get() = _wells.toList()

    /** Active pigment color currently carried on the palette knife / mixing finger. */
    var carryColor: Int = _wells.firstOrNull() ?: 0xFF1A4B9C.toInt()
        private set

    fun selectWell(index: Int): Int {
        val color = _wells.getOrElse(index) { carryColor }
        carryColor = color
        return color
    }

    fun setWellColor(
        index: Int,
        argb: Int,
    ) {
        if (index in _wells.indices) {
            _wells[index] = argb or (0xFF shl 24)
        }
    }

    /** Deposits a pure dab of [color] at ([cx], [cy]) with spectral mixing over existing pigment. */
    fun deposit(
        cx: Float,
        cy: Float,
        color: Int,
        radius: Float = 12f,
        mixStrength: Float = 0.65f,
    ) {
        carryColor = color or (0xFF shl 24)
        stampSpectral(cx, cy, carryColor, radius.coerceIn(2f, 40f), mixStrength.coerceIn(0.05f, 1f))
    }

    /**
     * Smears pigment from ([fromX], [fromY]) to ([toX], [toY]), picking up underlying pad pigment
     * and blending it with [carryColor] via Kubelka-Munk spectral mixing.
     */
    fun smear(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        sourceColor: Int = carryColor,
        radius: Float = 11f,
        pickupRate: Float = 0.35f,
    ): Int {
        var active = sourceColor or (0xFF shl 24)
        val dx = toX - fromX
        val dy = toY - fromY
        val dist = hypot(dx, dy)
        val step = max(1.5f, radius * 0.25f)
        val steps = max(1, ceil(dist / step).toInt())
        val r = radius.coerceIn(2f, 40f)
        val pick = pickupRate.coerceIn(0.05f, 0.85f)

        for (i in 0..steps) {
            val t = i.toFloat() / steps.toFloat()
            val px = fromX + dx * t
            val py = fromY + dy * t
            val under = sampleAt(px, py)
            if (under != PAD_BACKGROUND) {
                active = SpectralMixer.mixArgb(active, under, pick)
            }
            stampSpectral(px, py, active, r, 0.6f)
        }
        carryColor = active
        return active
    }

    /** Samples the ARGB color at pad coordinates ([x], [y]). */
    fun sampleAt(
        x: Float,
        y: Float,
    ): Int {
        val ix = x.toInt().coerceIn(0, width - 1)
        val iy = y.toInt().coerceIn(0, height - 1)
        return buffer.pixels[iy * width + ix]
    }

    /** Wipes the mixing surface clean back to warm ceramic palette white. */
    fun clear() {
        buffer.fill(PAD_BACKGROUND)
    }

    /** Restores the default artist pigment wells and clears the surface. */
    fun resetToDefault() {
        _wells.clear()
        _wells.addAll(DEFAULT_WELLS)
        carryColor = _wells.first()
        clear()
    }

    private fun stampSpectral(
        cx: Float,
        cy: Float,
        color: Int,
        radius: Float,
        strength: Float,
    ) {
        val minX = max(0, floor(cx - radius).toInt())
        val maxX = min(width - 1, ceil(cx + radius).toInt())
        val minY = max(0, floor(cy - radius).toInt())
        val maxY = min(height - 1, ceil(cy + radius).toInt())
        for (y in minY..maxY) {
            val row = y * width
            for (x in minX..maxX) {
                val d = hypot(x + 0.5f - cx, y + 0.5f - cy)
                if (d > radius) continue
                val falloff = (1f - d / radius).let { it * it }
                val idx = row + x
                val dst = buffer.pixels[idx]
                val w = (falloff * strength).coerceIn(0f, 1f)
                buffer.pixels[idx] =
                    if (dst == PAD_BACKGROUND) {
                        SpectralMixer.mixArgb(PAD_BACKGROUND, color, (w * 1.4f).coerceIn(0f, 1f))
                    } else {
                        SpectralMixer.mixArgb(dst, color, w)
                    }
            }
        }
    }

    companion object {
        const val DEFAULT_WIDTH = 160
        const val DEFAULT_HEIGHT = 110
        const val MAX_WELLS = 8
        const val PAD_BACKGROUND: Int = 0xFFF5F3EE.toInt()

        /** Classic 8-pigment studio palette: Ultramarine, Hansa Yellow, Cadmium Red, Viridian, Ochre, Burnt Sienna, White, Ivory Black. */
        val DEFAULT_WELLS: List<Int> =
            listOf(
                0xFF1A4B9C.toInt(),
                0xFFF5D300.toInt(),
                0xFFD92B2B.toInt(),
                0xFF1F7A54.toInt(),
                0xFFC98E2B.toInt(),
                0xFF8C3B20.toInt(),
                0xFFF7F7F5.toInt(),
                0xFF1E1D1B.toInt(),
            )
    }
}
