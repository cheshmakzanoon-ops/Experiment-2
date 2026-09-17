package com.artflow.studio.core.tool

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FillToolTest {
    private val red = 0xFFFF0000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    @Test
    fun floodFillNeverPaintsOutsideSelectionEvenWithAntialiasing() {
        for (antiAlias in listOf(false, true)) {
            val target = PixelBuffer.filled(5, 5, white)
            val selection = SelectionMask(5, 5).apply { coverage[12] = 255.toByte() }
            FillTool.floodFill(target, 2, 2, red, FillTool.Settings(mask = selection, antiAlias = antiAlias))
            assertEquals(red, target.pixels[12])
            assertEquals(24, target.pixels.count { it == white })
        }
    }

    @Test
    fun featheredFillSelectionIsAppliedOnlyOnce() {
        val target = PixelBuffer(1, 1)
        val selection = SelectionMask(1, 1, byteArrayOf(128.toByte()))
        FillTool.fillAll(target, red, FillTool.Settings(mask = selection, antiAlias = false))
        assertEquals(0x80FF0000.toInt(), target.pixels[0])
    }

    @Test
    fun alphaLockedFillPreservesCoverageInEveryBlendMode() {
        for (mode in FillTool.BlendModeChoice.entries) {
            val target = PixelBuffer(2, 1, intArrayOf(0x400000FF, 0))
            FillTool.fillAll(target, red, FillTool.Settings(alphaLock = true, mode = mode, antiAlias = false))
            assertEquals(64, target.pixels[0] ushr 24)
            assertEquals(0, target.pixels[1])
        }
    }

    @Test
    fun transparentPixelsMatchRegardlessOfTheirHiddenRgb() {
        val target = PixelBuffer(3, 1, intArrayOf(0, 0x00FF0000, 0x0000FFFF))
        val result = FillTool.floodFill(target, 0, 0, red, FillTool.Settings(tolerance = 0, antiAlias = false))
        assertEquals(3, result.filledPixels)
        assertTrue(target.pixels.all { it == red })
    }

    private fun boxWithGap(): PixelBuffer =
        PixelBuffer.filled(15, 15, white).apply {
            for (n in 3..11) {
                setUnchecked(n, 3, black)
                setUnchecked(n, 11, black)
                setUnchecked(3, n, black)
                setUnchecked(11, n, black)
            }
            setUnchecked(7, 3, white)
        }

    @Test
    fun gapClosingSealsOpenBoundaryInsteadOfJumpingThroughInk() {
        val target = boxWithGap()
        FillTool.floodFill(target, 7, 7, red, FillTool.Settings(tolerance = 0, gapClose = 1, antiAlias = false))
        assertEquals(red, target.getSafe(7, 7))
        assertEquals(white, target.getSafe(0, 0))
        assertEquals(black, target.getSafe(3, 7))
    }

    @Test
    fun zeroGapClosingStillFollowsConnectedPixelsThroughAnOpening() {
        val target = boxWithGap()
        FillTool.floodFill(target, 7, 7, red, FillTool.Settings(tolerance = 0, gapClose = 0, antiAlias = false))
        assertEquals(red, target.getSafe(0, 0))
        assertEquals(black, target.getSafe(3, 7))
    }

    @Test
    fun emptyAndSmallerSelectionsAreSafe() {
        val target = PixelBuffer.filled(2, 2, white)
        val selection = SelectionMask(1, 1)
        FillTool.fillAll(target, red, FillTool.Settings(mask = selection))
        assertTrue(target.pixels.all { it == white })
        selection.selectAll()
        FillTool.fillAll(target, red, FillTool.Settings(mask = selection))
        assertEquals(red, target.pixels[0])
        assertEquals(3, target.pixels.count { it == white })
    }
}
