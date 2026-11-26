package com.artflow.studio.core.pixels

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HalftoneStylesTest {
    private fun style(s: LiveAdjustments.HalftoneStyle) = mapOf(LiveAdjustments.HALFTONE_STYLE to s.ordinal.toFloat())

    @Test
    fun newspaperPrintsBlackDotsOnWhitePaper() {
        val grey = PixelBuffer.filled(40, 40, 0xFF808080.toInt())
        val out =
            LiveAdjustments.apply(
                LiveAdjustments.Kind.HALFTONE,
                grey,
                LiveAdjustments.Settings(0.5f, style(LiveAdjustments.HalftoneStyle.NEWSPAPER)),
            )
        val colours = out.pixels.map { it and 0xFFFFFF }.toSet()
        assertEquals(setOf(0x000000, 0xFFFFFF), colours)
        assertTrue(out.pixels.all { it ushr 24 == 0xFF })
    }

    @Test
    fun screenPrintBuildsRedFromMagentaAndYellow() {
        val red = PixelBuffer.filled(40, 40, 0xFFFF0000.toInt())
        val out =
            LiveAdjustments.apply(
                LiveAdjustments.Kind.HALFTONE,
                red,
                LiveAdjustments.Settings(0.5f, style(LiveAdjustments.HalftoneStyle.SCREEN_PRINT)),
            )
        // Red needs no cyan, so the red channel stays full everywhere.
        assertTrue(out.pixels.all { (it shr 16) and 0xFF == 0xFF })
        // Where magenta and yellow overlap, the print is red.
        assertTrue(out.pixels.any { it and 0xFFFFFF == 0xFF0000 })
    }

    @Test
    fun transparentPixelsStayTransparent() {
        val empty = PixelBuffer(20, 20)
        val out =
            LiveAdjustments.apply(
                LiveAdjustments.Kind.HALFTONE,
                empty,
                LiveAdjustments.Settings(0.5f, style(LiveAdjustments.HalftoneStyle.NEWSPAPER)),
            )
        assertTrue(out.pixels.all { it == 0 })
    }

    @Test
    fun fullColourIgnoresHiddenRgbAndKeepsTheSourceForRepeatedPreviews() {
        val results =
            listOf(0x00000000, 0x0000FF00, 0x00FFFFFF).map { hidden ->
                val source = PixelBuffer.filled(12, 12, hidden)
                for (y in 0 until 12) for (x in 0 until 6) source.pixels[y * 12 + x] = 0x80FF0000.toInt()
                val before = source.pixels.copyOf()
                val settings = LiveAdjustments.Settings(0.5f)
                val out = LiveAdjustments.apply(LiveAdjustments.Kind.HALFTONE, source, settings)
                assertEquals("Dots retain the painted red and average coverage", 0x40FF0000, out.getSafe(6, 6))
                assertArrayEquals(before, source.pixels)
                assertArrayEquals(out.pixels, LiveAdjustments.apply(LiveAdjustments.Kind.HALFTONE, source, settings).pixels)
                out.pixels
            }
        results.drop(1).forEach { assertArrayEquals(results.first(), it) }
    }

    @Test
    fun fullColourWeightsSemiTransparentPigmentsByCoverage() {
        val source = PixelBuffer(12, 12)
        for (y in 0 until 12) {
            for (x in 0 until 12) source.pixels[y * 12 + x] = if (x < 6) 0xFFFF0000.toInt() else 0x550000FF
        }
        val out = LiveAdjustments.apply(LiveAdjustments.Kind.HALFTONE, source, LiveAdjustments.Settings(0.5f))
        assertEquals(0xAABF003F.toInt(), out.getSafe(6, 6))
    }
}
