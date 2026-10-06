package com.artflow.studio.core.pixels

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
}
