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

    @Test
    fun removedMasterPointsDoNotComeBackFromTheDefaults() {
        val source = PixelBuffer.filled(1, 1, 0xFF808080.toInt())
        // Only the two ends, pulled down at the top: the default mid points must not return.
        val twoPoints = AdjustmentProcessor.withCurvePoints(emptyMap(), "", listOf(0f to 0f, 255f to 128f))
        assertEquals(2, AdjustmentProcessor.curvePoints(twoPoints).size)
        val out = AdjustmentProcessor.apply(source, AdjustmentType.CURVES, twoPoints).getSafe(0, 0)
        assertEquals(64f, (out and 0xFF).toFloat(), 1.5f)
    }

    @Test
    fun withCurvePointsReplacesOnlyThatCurveAndCapsTheCount() {
        val many = (0..12).map { it * 20f to it * 20f }
        val parameters = AdjustmentProcessor.withCurvePoints(identity() + identity("red_"), "red_", many)
        assertEquals(AdjustmentProcessor.MAX_CURVE_POINTS, AdjustmentProcessor.curvePoints(parameters, "red_").size)
        assertEquals(5, AdjustmentProcessor.curvePoints(parameters).size)
        val fewer = AdjustmentProcessor.withCurvePoints(parameters, "red_", listOf(0f to 0f, 255f to 255f))
        assertEquals(2, AdjustmentProcessor.curvePoints(fewer, "red_").size)
        assertTrue(fewer.keys.none { it.startsWith("red_point_2") })
    }
}
