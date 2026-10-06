package com.artflow.studio.core.color

import com.artflow.studio.core.pixels.AdjustmentProcessor
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.layer.AdjustmentType
import org.junit.Assert.assertEquals
import org.junit.Test

class GradientMapsTest {
    private val duo = GradientMaps.Ramp("Test", listOf(GradientMaps.Stop(0f, 0x000080), GradientMaps.Stop(1f, 0xFFFF00)))

    @Test
    fun rampsSurviveTheParameterMap() {
        GradientMaps.PRESETS.forEach { ramp ->
            assertEquals(ramp.stops, GradientMaps.fromParameters(GradientMaps.toParameters(ramp)))
        }
        assertEquals(null, GradientMaps.fromParameters(mapOf("gradient_start_hue" to 0f)))
    }

    @Test
    fun pixelsTakeTheRampColourAtTheirLuminance() {
        val source = PixelBuffer(3, 1, intArrayOf(0xFF000000.toInt(), 0x80FFFFFF.toInt(), 0xFF808080.toInt()))
        val out = AdjustmentProcessor.apply(source, AdjustmentType.GRADIENT_MAP, GradientMaps.toParameters(duo))
        assertEquals(0xFF000080.toInt(), out.pixels[0])
        // White becomes the highlight colour and keeps its own alpha.
        assertEquals(0x80FFFF00.toInt(), out.pixels[1])
        assertEquals(GradientMaps.colorAt(duo.stops, 128 / 255f), out.pixels[2] and 0xFFFFFF)
    }
}
