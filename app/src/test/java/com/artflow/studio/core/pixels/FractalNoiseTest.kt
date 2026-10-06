package com.artflow.studio.core.pixels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FractalNoiseTest {
    @Test
    fun everyTypeStaysInRangeAndVariesSmoothly() {
        for (type in FractalNoise.Type.entries.drop(1)) {
            val values = (0 until 200).map { FractalNoise.sample(type, it * 0.05f, 3.3f) }
            assertTrue(values.all { it in 0f..1f })
            // Neighbouring samples a twentieth of a feature apart differ only a little.
            assertTrue(values.zipWithNext().all { (a, b) -> kotlin.math.abs(a - b) < 0.25f })
            assertTrue(values.max() - values.min() > 0.1f)
        }
    }

    @Test
    fun theNoiseAdjustmentUsesTheChosenTypeAndKeepsAlpha() {
        val source = PixelBuffer.filled(32, 32, 0x80808080.toInt())
        val grain = LiveAdjustments.apply(LiveAdjustments.Kind.NOISE, source, LiveAdjustments.Settings(0.5f))
        val clouds =
            LiveAdjustments.apply(
                LiveAdjustments.Kind.NOISE,
                source,
                LiveAdjustments.Settings(
                    0.5f,
                    mapOf(
                        LiveAdjustments.NOISE_TYPE to
                            FractalNoise.Type.CLOUDS.ordinal
                                .toFloat(),
                    ),
                ),
            )
        assertNotEquals(grain.pixels.toList(), clouds.pixels.toList())
        assertTrue(clouds.pixels.all { it ushr 24 == 0x80 })
        // Clouds change gradually: neighbours are close, unlike per-pixel grain.
        assertTrue(kotlin.math.abs((clouds.pixels[0] and 0xFF) - (clouds.pixels[1] and 0xFF)) < 12)
        assertEquals(
            source.pixels.toList(),
            LiveAdjustments.apply(LiveAdjustments.Kind.NOISE, source, LiveAdjustments.Settings(0f)).pixels.toList(),
        )
    }
}
