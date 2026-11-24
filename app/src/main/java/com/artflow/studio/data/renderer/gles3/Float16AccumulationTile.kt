package com.artflow.studio.data.renderer.gles3

import com.artflow.studio.core.pixels.Channels
import com.artflow.studio.core.pixels.PixelBuffer
import kotlin.math.roundToInt

/**
 * Float16/Float32 working-precision RGBA accumulation tile with ordered triangular dithering
 * down to 8-bit [PixelBuffer]. Prevents quantization banding during low-opacity airbrush
 * buildup and smooth gradient rendering, matching GLES 3.0+ RGBA16F FBO semantics on JVM/CPU.
 */
class Float16AccumulationTile(
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0 && height > 0) { "Tile dimensions must be positive" }
    }

    /** Interleaved premultiplied RGBA float channels (4 floats per pixel). */
    val rgba = FloatArray(width * height * 4)

    fun clear() {
        rgba.fill(0f)
    }

    /** Loads an 8-bit straight-alpha [PixelBuffer] into this float working tile. */
    fun loadFrom(source: PixelBuffer) {
        require(source.width == width && source.height == height) { "Buffer dimensions must match tile" }
        for (i in source.pixels.indices) {
            val p = source.pixels[i]
            val a = ((p ushr 24) and 0xFF) / 255f
            val base = i * 4
            rgba[base] = (((p shr 16) and 0xFF) / 255f) * a
            rgba[base + 1] = (((p shr 8) and 0xFF) / 255f) * a
            rgba[base + 2] = ((p and 0xFF) / 255f) * a
            rgba[base + 3] = a
        }
    }

    /** Accumulates a straight-alpha premultiplied source-over pass at full floating-point precision. */
    fun accumulatePixel(
        x: Int,
        y: Int,
        r: Float,
        g: Float,
        b: Float,
        alpha: Float,
    ) {
        if (x !in 0 until width || y !in 0 until height) return
        val sa = alpha.coerceIn(0f, 1f)
        if (sa <= 0f) return
        val base = (y * width + x) * 4
        val inv = 1f - sa
        rgba[base] = r.coerceIn(0f, 1f) * sa + rgba[base] * inv
        rgba[base + 1] = g.coerceIn(0f, 1f) * sa + rgba[base + 1] * inv
        rgba[base + 2] = b.coerceIn(0f, 1f) * sa + rgba[base + 2] * inv
        rgba[base + 3] = (sa + rgba[base + 3] * inv).coerceIn(0f, 1f)
    }

    /** Quantizes the float working tile into an 8-bit [PixelBuffer] with subtle ordered dithering. */
    fun resolveTo8Bit(
        target: PixelBuffer,
        dither: Boolean = true,
    ) {
        require(target.width == width && target.height == height) { "Target dimensions must match tile" }
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val idx = row + x
                val base = idx * 4
                val a = rgba[base + 3].coerceIn(0f, 1f)
                if (a <= 1e-5f) {
                    target.pixels[idx] = 0
                    continue
                }
                val noise = if (dither) (bayer4(x, y) - 0.5f) / 255f else 0f
                val r = ((rgba[base] / a + noise) * 255f).roundToInt().coerceIn(0, 255)
                val g = ((rgba[base + 1] / a + noise) * 255f).roundToInt().coerceIn(0, 255)
                val b = ((rgba[base + 2] / a + noise) * 255f).roundToInt().coerceIn(0, 255)
                val outA = ((a + noise) * 255f).roundToInt().coerceIn(0, 255)
                target.pixels[idx] = Channels.argb(outA, r, g, b)
            }
        }
    }

    companion object {
        private val BAYER_4X4 =
            floatArrayOf(
                0f, 8f, 2f, 10f,
                12f, 4f, 14f, 6f,
                3f, 11f, 1f, 9f,
                15f, 7f, 13f, 5f,
            )

        private fun bayer4(
            x: Int,
            y: Int,
        ): Float = (BAYER_4X4[((y and 3) shl 2) or (x and 3)] + 0.5f) / 16f

        /** IEEE-754 binary16 (half-float) conversion matching GLES 3.0 RGBA16F storage. */
        fun floatToHalf(value: Float): Short {
            val bits = java.lang.Float.floatToRawIntBits(value)
            val sign = (bits ushr 16) and 0x8000
            val exp = ((bits ushr 23) and 0xFF) - 112
            val mant = bits and 0x007FFFFF
            if (exp <= 0) {
                if (exp < -10) return sign.toShort()
                val m = (mant or 0x00800000) ushr (1 - exp)
                return (sign or ((m + 0x1000) ushr 13)).toShort()
            }
            if (exp >= 31) return (sign or 0x7C00).toShort()
            return (sign or (exp shl 10) or ((mant + 0x1000) ushr 13)).toShort()
        }

        fun halfToFloat(half: Short): Float {
            val h = half.toInt() and 0xFFFF
            val sign = (h and 0x8000) shl 16
            val exp = (h ushr 10) and 0x1F
            val mant = h and 0x03FF
            val bits =
                when (exp) {
                    0 -> {
                        if (mant == 0) {
                            sign
                        } else {
                            var e = -1
                            var m = mant
                            while ((m and 0x0400) == 0) {
                                m = m shl 1
                                e--
                            }
                            m = m and 0x03FF
                            sign or ((e + 127 - 14) shl 23) or (m shl 13)
                        }
                    }
                    31 -> sign or 0x7F800000 or (mant shl 13)
                    else -> sign or ((exp + 112) shl 23) or (mant shl 13)
                }
            return java.lang.Float.intBitsToFloat(bits)
        }
    }
}
