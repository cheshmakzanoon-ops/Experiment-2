package com.artflow.studio.domain.model.brush

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.StrokeRasterizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class SecondaryColourTest {
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    @Test
    fun pressureBlendsTowardTheSecondaryColour() {
        val brush = BrushParams(secondaryPressure = 1f, pressureCurve = BrushParams.PressureCurve.LINEAR)
        assertEquals(red, brush.applyColorJitter(red, pressure = 0f, secondary = blue))
        assertEquals(blue, brush.applyColorJitter(red, pressure = 1f, secondary = blue))
        val half = brush.applyColorJitter(red, pressure = 0.5f, secondary = blue)
        assertEquals(0x80, (half shr 16) and 0xFF, 1f)
        assertEquals(0x80, half and 0xFF, 1f)
        // No secondary colour, or no secondary dynamics, leaves the colour alone.
        assertEquals(red, brush.applyColorJitter(red, pressure = 1f))
        assertEquals(red, BrushParams().applyColorJitter(red, pressure = 1f, secondary = blue))
    }

    @Test
    fun jitterVariesEachDabAndKeepsAlpha() {
        val brush = BrushParams(secondaryJitter = 1f)
        val random = Random(4)
        val colours = (0 until 20).map { brush.applyColorJitter(0x80FF0000.toInt(), random = random, secondary = blue) }
        assertTrue(colours.all { (it ushr 24) == 0x80 })
        assertTrue(colours.toSet().size > 10)
    }

    @Test
    fun strokesCarryTheirSecondaryColourIntoThePixels() {
        val points = (0..20).map { StrokePoint(5f + it * 2f, 10f, pressure = 1f, timestamp = it.toLong()) }
        val params = BrushParams(size = 6f, spacing = 0.1f, pressureToSize = 0f, pressureToOpacity = 0f, secondaryPressure = 1f)
        fun paint(secondary: Int?): Int {
            val target = PixelBuffer(60, 20)
            val stroke = Stroke(points = points, brushParams = params, layerId = 1, color = red, secondaryColor = secondary)
            StrokeRasterizer().draw(target, stroke)
            return target.pixels[10 * 60 + 25]
        }
        assertEquals(red, paint(null))
        assertEquals(blue, paint(blue))
        assertNotEquals(paint(null), paint(blue))
    }
}

private fun assertEquals(
    expected: Int,
    actual: Int,
    delta: Float,
) = assertEquals(expected.toFloat(), actual.toFloat(), delta)
