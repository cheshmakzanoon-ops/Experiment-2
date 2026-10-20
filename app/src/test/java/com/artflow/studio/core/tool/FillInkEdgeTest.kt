package com.artflow.studio.core.tool

import com.artflow.studio.core.pixels.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

class FillInkEdgeTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val red = 0xFFFF0000.toInt()

    @Test
    fun aFillDoesNotTintTheInkLineItStopsAt() {
        // A white field split by a black line: the fill stops at the line, and the line keeps its colour.
        val target = PixelBuffer(9, 9)
        target.fill(white)
        for (y in 0 until 9) target.pixels[y * 9 + 4] = black
        FillTool.floodFill(target, 1, 4, red, FillTool.Settings(tolerance = 16, antiAlias = true))
        assertEquals(red, target.pixels[4 * 9 + 1])
        for (y in 0 until 9) assertEquals("ink at row $y", black, target.pixels[y * 9 + 4])
    }
}
