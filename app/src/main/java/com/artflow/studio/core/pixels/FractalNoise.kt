package com.artflow.studio.core.pixels

import kotlin.math.abs
import kotlin.math.floor

/**
 * Procreate's Noise types: Clouds (soft fractal noise), Billows (puffy, with creases where it
 * crosses the middle) and Ridges (sharp crests), made from several octaves of smooth value noise.
 */
object FractalNoise {
    enum class Type(
        val displayName: String,
    ) {
        GRAIN("Grain"),
        CLOUDS("Clouds"),
        BILLOWS("Billows"),
        RIDGES("Ridges"),
    }

    /** Adds noise of [type] to [source]; [amount] 0..1 is its strength, [scale] its feature size in pixels. */
    fun apply(
        source: PixelBuffer,
        type: Type,
        amount: Float,
        scale: Float,
        seed: Int = SEED,
    ): PixelBuffer {
        if (type == Type.GRAIN) return ImageFilters.addNoise(source, amount)
        val strength = amount.coerceIn(0f, 1f) * MAX_SWING
        val size = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        val out = source.copy()
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val i = y * source.width + x
                val pixel = source.pixels[i]
                if ((pixel ushr 24) == 0) continue
                val shift = (sample(type, x / size, y / size, seed) - 0.5f) * strength
                out.pixels[i] =
                    Channels.argb(
                        Channels.alpha(pixel).toInt(),
                        clamp(Channels.red(pixel) + shift),
                        clamp(Channels.green(pixel) + shift),
                        clamp(Channels.blue(pixel) + shift),
                    )
            }
        }
        return out
    }

    /** The noise value (0..1) of [type] at ([x], [y]) in feature-size units. */
    fun sample(
        type: Type,
        x: Float,
        y: Float,
        seed: Int = SEED,
    ): Float {
        var total = 0f
        var weight = 0f
        var amplitude = 1f
        var frequency = 1f
        for (octave in 0 until OCTAVES) {
            val n = valueNoise(x * frequency, y * frequency, seed + octave * OCTAVE_SEED_STEP)
            val v =
                when (type) {
                    Type.BILLOWS -> abs(2f * n - 1f)
                    Type.RIDGES -> (1f - abs(2f * n - 1f)).let { it * it }
                    else -> n
                }
            total += v * amplitude
            weight += amplitude
            amplitude *= PERSISTENCE
            frequency *= 2f
        }
        return total / weight
    }

    /** Smoothly interpolated random values on an integer lattice, in 0..1. */
    private fun valueNoise(
        x: Float,
        y: Float,
        seed: Int,
    ): Float {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = smooth(x - x0)
        val fy = smooth(y - y0)
        val top = lerp(lattice(x0, y0, seed), lattice(x0 + 1, y0, seed), fx)
        val bottom = lerp(lattice(x0, y0 + 1, seed), lattice(x0 + 1, y0 + 1, seed), fx)
        return lerp(top, bottom, fy)
    }

    private fun lattice(
        x: Int,
        y: Int,
        seed: Int,
    ): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777215f
    }

    private fun smooth(t: Float) = t * t * (3f - 2f * t)

    private fun lerp(
        a: Float,
        b: Float,
        t: Float,
    ) = a + (b - a) * t

    private fun clamp(value: Float) = (value + 0.5f).toInt().coerceIn(0, 255)

    private const val SEED = 7
    private const val OCTAVES = 5
    private const val OCTAVE_SEED_STEP = 101
    private const val PERSISTENCE = 0.5f
    private const val MAX_SWING = 255f
    const val MIN_SCALE = 2f
    const val MAX_SCALE = 400f
}
