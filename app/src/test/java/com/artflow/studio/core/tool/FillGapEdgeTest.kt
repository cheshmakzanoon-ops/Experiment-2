package com.artflow.studio.core.tool

import com.artflow.studio.core.pixels.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

class FillGapEdgeTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val red = 0xFFFF0000.toInt()

    /** A white canvas split by a black wall down column 7, with a one-pixel hole in the wall at row 1, next to the top edge. */
    private fun wallWithHoleBesideTheEdge(): PixelBuffer =
        PixelBuffer.filled(15, 15, white).apply {
            for (y in 0 until 15) setUnchecked(7, y, black)
            setUnchecked(7, 1, white)
        }

    @Test
    fun gapClosingSealsAHoleInAWallThatReachesTheCanvasEdge() {
        val target = wallWithHoleBesideTheEdge()
        FillTool.floodFill(target, 3, 7, red, FillTool.Settings(tolerance = 0, gapClose = 2, antiAlias = false))
        assertEquals(red, target.getSafe(3, 7))
        assertEquals(white, target.getSafe(10, 7))
    }
}
