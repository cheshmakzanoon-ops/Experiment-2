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

class BurntEdgesTest {
    private val red = 0xFFFF0000.toInt()
    private val points = (0..20).map { StrokePoint(5f + it * 2f, 10.4f, pressure = 1f, timestamp = it.toLong()) }

    private fun paint(
        flow: Float,
        burnt: Float,
    ): PixelBuffer =
        PixelBuffer(60, 20).also { target ->
            val params =
                BrushParams(size = 12f, spacing = 0.1f, flow = flow, pressureToSize = 0f, pressureToOpacity = 0f, burntEdges = burnt)
            StrokeRasterizer().draw(target, Stroke(points = points, brushParams = params, layerId = 1, color = red))
        }

    @Test
    fun burntEdgesDarkenPartlyCoveredPaint() {
        // Low flow gives the tip a soft edge, so pixels there are only partly covered.
        val plain = paint(0.2f, 0f)
        val burnt = paint(0.2f, 1f)
        val partial = plain.pixels.indices.filter { (plain.pixels[it] ushr 24) in 10..245 }
        assertTrue("the stroke has a soft edge", partial.isNotEmpty())
        assertTrue(partial.all { ((burnt.pixels[it] shr 16) and 0xFF) < ((plain.pixels[it] shr 16) and 0xFF) })
    }

    @Test
    fun fullyCoveredPaintKeepsItsColour() {
        // Burning darkens only the partly covered anti-aliased rim of a hard tip; fully covered pixels keep their colour.
        val plain = paint(1f, 0f)
        val burnt = paint(1f, 1f)
        val covered = plain.pixels.indices.filter { plain.pixels[it] ushr 24 == 0xFF }
        assertTrue(covered.isNotEmpty())
        covered.forEach { assertEquals(plain.pixels[it].toLong(), burnt.pixels[it].toLong()) }
    }
}

class GrainDepthTest {
    @Test
    fun depthScalesHowStronglyTheGrainShows() {
        fun coverages(depth: Float) =
            requireNotNull(
                com.artflow.studio.core.render.BrushTexture
                    .from(BrushParams(textureId = "speckle", blendTexture = true, grainDepth = depth)),
            ).let { texture -> (0 until 400).map { texture.coverage(it % 20, it / 20) } }
        val full = coverages(1f)
        val half = coverages(0.5f)
        val none = coverages(0f)
        assertTrue(full.any { it < 0.5f })
        assertTrue(none.all { it == 1f })
        full.indices.forEach { assertEquals(1f - 0.5f * (1f - full[it]), half[it], 1e-6f) }
    }
}
