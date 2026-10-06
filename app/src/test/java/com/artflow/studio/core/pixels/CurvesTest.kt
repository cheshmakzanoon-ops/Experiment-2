package com.artflow.studio.core.pixels

import com.artflow.studio.domain.model.layer.AdjustmentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurvesTest {
    private fun identity(prefix: String = "") =
        (0..4).flatMap { i -> listOf("${prefix}point_${i}_x" to i * 63.75f, "${prefix}point_${i}_y" to i * 63.75f) }.toMap()

    @Test
    fun aRedCurveChangesOnlyRed() {
        val source = PixelBuffer.filled(1, 1, 0xFF808080.toInt())
        // Raise the middle of the red curve.
        val parameters = identity() + identity("red_") + ("red_point_2_y" to 200f)
        val out = AdjustmentProcessor.apply(source, AdjustmentType.CURVES, parameters).getSafe(0, 0)
        assertTrue(((out shr 16) and 0xFF) > 0x80 + 40)
        assertEquals(0x80, (out shr 8) and 0xFF)
        assertEquals(0x80, out and 0xFF)
    }

    @Test
    fun withoutChannelCurvesTheMasterCurveActsAlone() {
        val source = PixelBuffer.filled(1, 1, 0xFF808080.toInt())
        val darker = identity() + ("point_2_y" to 60f)
        val out = AdjustmentProcessor.apply(source, AdjustmentType.CURVES, darker).getSafe(0, 0)
        val channel = out and 0xFF
        assertTrue(channel < 0x80 - 30)
        assertEquals(channel, (out shr 16) and 0xFF)
    }
}
