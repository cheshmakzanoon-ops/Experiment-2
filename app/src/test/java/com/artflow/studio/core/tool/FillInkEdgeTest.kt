package com.artflow.studio.core.tool

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun antialiasedReferenceEdgesHaveTheSameCoverageAtEveryCanvasBorder() {
        val nearWhite = 0xFFFCFCFC.toInt()
        val settings = FillTool.Settings(tolerance = 0, antiAlias = true)
        for (vertical in listOf(false, true)) {
            val reference = PixelBuffer(3, 3)
            for (y in 0 until 3) {
                for (x in 0 until 3) {
                    reference.setUnchecked(x, y, listOf(white, nearWhite, black)[if (vertical) x else y])
                }
            }
            val original = reference.pixels.copyOf()
            val target = PixelBuffer(3, 3)
            FillTool.floodFill(target, 0, 0, red, settings, reference)
            val middle = target.getUnchecked(1, 1)
            assertTrue("The fringe must blend partially", middle ushr 24 in 1..254)
            for (position in 0 until 3) {
                val x = if (vertical) 1 else position
                val y = if (vertical) position else 1
                assertEquals("Equal fringe at ($x, $y)", middle, target.getUnchecked(x, y))
                assertEquals(0, target.getUnchecked(if (vertical) 2 else position, if (vertical) position else 2))
            }
            assertArrayEquals(original, reference.pixels)
        }
    }

    @Test
    fun onePixelWideCanvasesKeepTheSameSelectedAntialiasedFringe() {
        val nearWhite = 0xFFFCFCFC.toInt()
        for (vertical in listOf(false, true)) {
            val width = if (vertical) 1 else 4
            val height = if (vertical) 4 else 1
            val reference = PixelBuffer(width, height, intArrayOf(white, white, nearWhite, black))
            val target = PixelBuffer(width, height)
            val selection = SelectionMask(width, height, byteArrayOf(-1, -1, 128.toByte(), 0))
            val result =
                FillTool.floodFill(
                    target,
                    0,
                    0,
                    red,
                    FillTool.Settings(tolerance = 0, antiAlias = true, mask = selection),
                    reference,
                )
            // RGB distance is sqrt(3 * 3 * 3), giving 89/255 fringe coverage; the
            // half-selected edge applies that coverage once, giving alpha 45.
            assertEquals(3, result.filledPixels)
            assertArrayEquals(intArrayOf(red, red, 0x2DFF0000, 0), target.pixels)
        }
    }

    @Test
    fun selectionOutlineAntialiasingMatchesTheSameShapeCroppedAtCanvasEdges() {
        val larger = PixelBuffer(7, 7)
        val largeSelection = SelectionMask(7, 7).apply { coverage[3 * 7 + 3] = -1 }
        FillTool.strokeSelection(larger, red, 2, FillTool.Settings(mask = largeSelection))

        val smaller = PixelBuffer(5, 5)
        val smallSelection = SelectionMask(5, 5).apply { coverage[2 * 5 + 2] = -1 }
        FillTool.strokeSelection(smaller, red, 2, FillTool.Settings(mask = smallSelection))

        val expected = IntArray(25) { index -> larger.getUnchecked(index % 5 + 1, index / 5 + 1) }
        assertTrue(expected[2] ushr 24 in 1..254)
        assertArrayEquals(expected, smaller.pixels)
    }
}
