package com.artflow.studio.core.canvas

import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Canvas-level operations (Phase 35).
 *
 * These are the transformations every layer and every animation frame goes through, so the tests
 * pin down both the pixel result and the reported canvas properties.
 */
class CanvasOperationsTest {
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    private fun properties(
        width: Int = 8,
        height: Int = 8,
        dpi: Int = 72,
    ) = CanvasOperations.CanvasProperties(width, height, dpi, 0)

    /** 8x8 buffer with a red pixel at (2, 3) and (5, 6). */
    private fun dotBuffer(): PixelBuffer =
        PixelBuffer(8, 8).apply {
            setUnchecked(2, 3, red)
            setUnchecked(5, 6, red)
        }

    // ---------------------------------------------------------------------------------------
    // Resize
    // ---------------------------------------------------------------------------------------

    @Test
    fun `resample scales the pixels and reports the new size`() {
        val result = CanvasOperations.resample(PixelBuffer.filled(40, 20, red), 20, 10, properties(40, 20))

        assertEquals(20, result.buffer.width)
        assertEquals(10, result.buffer.height)
        assertEquals(20, result.properties.width)
        assertEquals(10, result.properties.height)
        assertEquals(red, result.buffer.getUnchecked(10, 5))
    }

    @Test
    fun `resample clamps absurd sizes instead of allocating them`() {
        val result = CanvasOperations.resample(PixelBuffer.filled(2, 2, red), 100_000, 100_000, properties(2, 2))

        assertEquals(CanvasOperations.MAX_DIMENSION, result.buffer.width)
        assertEquals(CanvasOperations.MAX_DIMENSION, result.buffer.height)
    }

    @Test
    fun `resizeCanvas centres existing artwork without rescaling it`() {
        val buffer = PixelBuffer(4, 4).apply { setUnchecked(0, 0, red) }

        val result =
            CanvasOperations.resizeCanvas(
                buffer = buffer,
                width = 8,
                height = 8,
                anchor = CanvasOperations.Anchor.CENTER,
                properties = properties(4, 4),
            )

        assertEquals(8, result.buffer.width)
        // (8 - 4) / 2 == 2, so the old origin lands at (2, 2) and the pixel keeps its size.
        assertEquals(red, result.buffer.getUnchecked(2, 2))
        assertEquals(0, result.buffer.getUnchecked(0, 0))
    }

    @Test
    fun `resizeCanvas anchors to each corner`() {
        val buffer = PixelBuffer(4, 4).apply { setUnchecked(0, 0, red) }

        fun placedAt(anchor: CanvasOperations.Anchor): Pair<Int, Int> {
            val result =
                CanvasOperations.resizeCanvas(
                    buffer = buffer,
                    width = 10,
                    height = 10,
                    anchor = anchor,
                    properties = properties(4, 4),
                )
            val index = result.buffer.pixels.indexOfFirst { it == red }
            return index % result.buffer.width to index / result.buffer.width
        }

        assertEquals(0 to 0, placedAt(CanvasOperations.Anchor.TOP_LEFT))
        assertEquals(6 to 0, placedAt(CanvasOperations.Anchor.TOP_RIGHT))
        assertEquals(0 to 6, placedAt(CanvasOperations.Anchor.BOTTOM_LEFT))
        assertEquals(6 to 6, placedAt(CanvasOperations.Anchor.BOTTOM_RIGHT))
        assertEquals(3 to 3, placedAt(CanvasOperations.Anchor.CENTER))
    }

    @Test
    fun `resizeCanvas fills the new area with the fill colour`() {
        val result =
            CanvasOperations.resizeCanvas(
                buffer = PixelBuffer(2, 2).apply { setUnchecked(0, 0, red) },
                width = 4,
                height = 4,
                anchor = CanvasOperations.Anchor.TOP_LEFT,
                properties = properties(2, 2),
                fillColor = blue,
            )

        assertEquals(red, result.buffer.getUnchecked(0, 0))
        assertEquals(blue, result.buffer.getUnchecked(3, 3))
    }

    @Test
    fun `tile repeats the artwork across a larger canvas`() {
        val tile = PixelBuffer(2, 2).apply { setUnchecked(0, 0, red) }

        val result = CanvasOperations.tile(tile, 6, 4, properties(2, 2))

        assertEquals(6, result.buffer.width)
        assertEquals(red, result.buffer.getUnchecked(0, 0))
        assertEquals(red, result.buffer.getUnchecked(2, 0))
        assertEquals(red, result.buffer.getUnchecked(4, 2))
    }

    // ---------------------------------------------------------------------------------------
    // Crop / trim
    // ---------------------------------------------------------------------------------------

    @Test
    fun `crop keeps the requested window and updates the properties`() {
        val result = CanvasOperations.crop(dotBuffer(), IntBounds(2, 3, 5, 6), properties())

        assertEquals(4, result.buffer.width)
        assertEquals(4, result.buffer.height)
        assertEquals(4, result.properties.width)
        assertEquals(red, result.buffer.getUnchecked(0, 0))
    }

    @Test
    fun `crop clamps bounds that reach past the canvas`() {
        val result = CanvasOperations.crop(dotBuffer(), IntBounds(-50, -50, 500, 500), properties())

        assertEquals(8, result.buffer.width)
        assertEquals(8, result.buffer.height)
    }

    @Test
    fun `crop outside the canvas is a no-op rather than a crash`() {
        val buffer = dotBuffer()
        val result = CanvasOperations.crop(buffer, IntBounds(100, 100, 200, 200), properties())

        assertSame(buffer, result.buffer)
    }

    @Test
    fun `trimTransparent hugs the content and honours padding`() {
        val tight = CanvasOperations.trimTransparent(dotBuffer(), properties())
        assertEquals(4, tight.buffer.width)
        assertEquals(4, tight.buffer.height)

        val padded = CanvasOperations.trimTransparent(dotBuffer(), properties(), padding = 1)
        assertEquals(6, padded.buffer.width)
        assertEquals(6, padded.buffer.height)
    }

    @Test
    fun `trimTransparent leaves an empty canvas alone`() {
        val empty = PixelBuffer(4, 4)
        val result = CanvasOperations.trimTransparent(empty, properties(4, 4))

        assertSame(empty, result.buffer)
    }

    @Test
    fun `expandToContent pads the canvas out around the artwork`() {
        val result = CanvasOperations.expandToContent(dotBuffer(), properties(), margin = 32)

        // Content spans (2,3)-(5,6); inflated by 32 that is a 68x68 canvas with a 30px offset.
        assertEquals(68, result.buffer.width)
        assertEquals(68, result.buffer.height)
        assertEquals(red, result.buffer.getUnchecked(32, 33))
    }

    // ---------------------------------------------------------------------------------------
    // Rotate / flip
    // ---------------------------------------------------------------------------------------

    @Test
    fun `quarter turn rotations swap the canvas dimensions`() {
        val result = CanvasOperations.rotate(PixelBuffer.filled(40, 20, red), 90, properties(40, 20))

        assertEquals(20, result.buffer.width)
        assertEquals(40, result.buffer.height)
        assertEquals(20, result.properties.width)
        assertEquals(40, result.properties.height)
    }

    @Test
    fun `a half turn keeps the dimensions and the pixel count`() {
        val result = CanvasOperations.rotate(dotBuffer(), 180, properties())

        assertEquals(8, result.buffer.width)
        assertEquals(8, result.buffer.height)
        assertEquals(2, result.buffer.opaquePixelCount())
    }

    @Test
    fun `free rotation grows the canvas so nothing is clipped`() {
        val result = CanvasOperations.rotateFree(PixelBuffer.filled(20, 20, red), 45f, properties(20, 20))

        assertTrue("expected a bigger canvas, got ${result.buffer.width}", result.buffer.width > 20)
        assertEquals(result.buffer.width, result.properties.width)
    }

    @Test
    fun `flip mirrors horizontally and vertically`() {
        val buffer = PixelBuffer(3, 3).apply { setUnchecked(0, 0, red) }

        val horizontal = CanvasOperations.flip(buffer, CanvasOperations.FlipAxis.HORIZONTAL, properties(3, 3))
        assertEquals(red, horizontal.buffer.getUnchecked(2, 0))

        val vertical = CanvasOperations.flip(buffer, CanvasOperations.FlipAxis.VERTICAL, properties(3, 3))
        assertEquals(red, vertical.buffer.getUnchecked(0, 2))
    }

    // ---------------------------------------------------------------------------------------
    // DPI
    // ---------------------------------------------------------------------------------------

    @Test
    fun `changeDpi never touches the pixels and clamps the range`() {
        val props = properties(100, 50, 72)

        assertEquals(300, CanvasOperations.changeDpi(props, 300).dpi)
        assertEquals(CanvasOperations.MIN_DPI, CanvasOperations.changeDpi(props, 1).dpi)
        assertEquals(CanvasOperations.MAX_DPI, CanvasOperations.changeDpi(props, 99_999).dpi)
        assertEquals(100, CanvasOperations.changeDpi(props, 300).width)
    }

    @Test
    fun `resampleToDpi scales the pixels to preserve the printed size`() {
        val result = CanvasOperations.resampleToDpi(PixelBuffer.filled(100, 50, red), properties(100, 50, 72), 144)

        assertEquals(200, result.buffer.width)
        assertEquals(100, result.buffer.height)
        assertEquals(144, result.properties.dpi)
    }

    @Test
    fun `resampleToDpi at the current DPI returns the same buffer`() {
        val buffer = PixelBuffer.filled(100, 50, red)
        val result = CanvasOperations.resampleToDpi(buffer, properties(100, 50, 300), 300)

        assertSame(buffer, result.buffer)
    }

    // ---------------------------------------------------------------------------------------
    // Presets and guards
    // ---------------------------------------------------------------------------------------

    @Test
    fun `every preset fits inside the allocation guard rails`() {
        assertTrue(CanvasOperations.PRESETS.isNotEmpty())
        CanvasOperations.PRESETS.forEach { preset ->
            assertTrue(
                "${preset.name} is not a safe size",
                CanvasOperations.isSizeSafe(preset.width, preset.height),
            )
            assertTrue(preset.dpi in CanvasOperations.MIN_DPI..CanvasOperations.MAX_DPI)
        }
    }

    @Test
    fun `presetByName finds a preset and describes it`() {
        val preset = CanvasOperations.presetByName("Screen HD")
        assertNotNull(preset)
        assertEquals(1920, preset!!.width)
        assertEquals(1080, preset.height)
        assertEquals("Screen HD · 1920×1080 @ 72dpi", preset.label)

        assertNull(CanvasOperations.presetByName("Nonexistent"))
    }

    @Test
    fun `isSizeSafe rejects zero, oversized and over-budget canvases`() {
        assertTrue(CanvasOperations.isSizeSafe(1, 1))
        assertTrue(CanvasOperations.isSizeSafe(8192, 1))
        assertTrue(!CanvasOperations.isSizeSafe(8193, 1))
        assertTrue(!CanvasOperations.isSizeSafe(0, 10))
        // 7000 x 7000 is 49 megapixels, past the 40 megapixel budget.
        assertTrue(!CanvasOperations.isSizeSafe(7000, 7000))
    }

    @Test
    fun `sizeWarning explains each rejection`() {
        assertNull(CanvasOperations.sizeWarning(1920, 1080))
        assertEquals("Canvas must be at least 1×1", CanvasOperations.sizeWarning(0, 10))
        assertNotNull(CanvasOperations.sizeWarning(9000, 10))
        assertNotNull(CanvasOperations.sizeWarning(7000, 7000))
    }

    @Test
    fun `aspect helpers keep the aspect ratio`() {
        assertEquals(500 to 250, CanvasOperations.scaleToWidth(1000, 500, 500))
        assertEquals(500 to 250, CanvasOperations.scaleToHeight(1000, 500, 250))
        assertEquals(100 to 50, CanvasOperations.fitInside(1000, 500, 100, 100))
        // Already small enough: fitInside must not upscale.
        assertEquals(50 to 50, CanvasOperations.fitInside(50, 50, 100, 100))
        assertEquals(CanvasOperations.centeredSquare(100, 50), IntBounds(25, 0, 74, 49))
    }

    @Test
    fun `canvas properties report print size and memory`() {
        val props = CanvasOperations.CanvasProperties(1000, 500, 72, 0)

        assertEquals(13.888f, props.widthInches, 0.01f)
        assertEquals(0.5f, props.megapixels, 0.001f)
        assertEquals(1.907f, props.megabytesPerLayer, 0.01f)
    }

    @Test
    fun `straighten rotates and trims in one step`() {
        val result = CanvasOperations.straighten(PixelBuffer.filled(20, 20, red), 5f, properties(20, 20))

        // Trimming a fully painted canvas removes nothing, so the properties stay consistent.
        assertEquals(result.buffer.width, result.properties.width)
        assertEquals(result.buffer.height, result.properties.height)
    }
}
