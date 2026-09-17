package com.artflow.studio.core.pixels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Buffer geometry, sampling and the operations the canvas tools rely on. */
class PixelBufferTest {
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    @Test
    fun scalingExtendsOpaqueEdgesWithoutIntroducingTransparency() {
        val source = PixelBuffer.filled(2, 2, red)
        val enlarged = source.scaled(17, 19)
        assertTrue(enlarged.pixels.all { it == red })
        assertTrue(source.pixels.all { it == red })
    }

    @Test
    fun scalingOnePixelPreservesItsPartialAlphaAtEveryEdge() {
        val colour = 0x80123456.toInt()
        assertTrue(
            PixelBuffer
                .filled(1, 1, colour)
                .scaled(13, 15)
                .pixels
                .all { it == colour },
        )
    }

    @Test
    fun `fill and read back every pixel`() {
        val buffer = PixelBuffer(4, 3)
        buffer.fill(red)
        assertEquals(12, buffer.pixels.count { it == red })
        assertEquals(red, buffer.getSafe(3, 2))
    }

    @Test
    fun `out of range reads are transparent instead of crashing`() {
        val buffer = PixelBuffer(2, 2)
        buffer.fill(red)
        assertEquals(0, buffer.getSafe(-1, 0))
        assertEquals(0, buffer.getSafe(0, 9))
    }

    @Test
    fun `isEmpty and opaquePixelCount agree with the content`() {
        val buffer = PixelBuffer(2, 2)
        assertTrue(buffer.isEmpty())
        buffer.setUnchecked(0, 0, 0x80FF00FF.toInt())
        assertTrue(!buffer.isEmpty())
        assertEquals(1, buffer.opaquePixelCount())
    }

    @Test
    fun `bilinear sampling lands between neighbouring pixels`() {
        val buffer = PixelBuffer(2, 1)
        buffer.setUnchecked(0, 0, 0xFF000000.toInt())
        buffer.setUnchecked(1, 0, 0xFFFFFFFF.toInt())

        // Halfway between the two texels: the red channel should be mid-grey.
        val middle = buffer.sampleBilinear(1.0f, 0.5f)
        val red = (middle shr 16) and 0xFF
        assertTrue("expected a mid-tone, got $red", red in 100..155)
    }

    @Test
    fun `crop returns the requested window`() {
        val buffer = PixelBuffer(4, 4)
        for (y in 0 until 4) for (x in 0 until 4) buffer.setUnchecked(x, y, if (x < 2 && y < 2) red else blue)

        val cropped = buffer.crop(IntBounds(0, 0, 1, 1))
        assertEquals(2, cropped.width)
        assertEquals(2, cropped.height)
        assertEquals(red, cropped.getUnchecked(1, 1))
    }

    @Test
    fun `contentBounds reports the painted area and ignores transparent pixels`() {
        val buffer = PixelBuffer(8, 8)
        assertNull(buffer.contentBounds())

        buffer.setUnchecked(2, 3, red)
        buffer.setUnchecked(5, 6, red)
        val bounds = buffer.contentBounds()
        assertNotNull(bounds)
        assertEquals(IntBounds(2, 3, 5, 6), bounds)
    }

    @Test
    fun `scaled resizes while preserving the dominant colour`() {
        val buffer = PixelBuffer.filled(4, 4, red)
        val scaled = buffer.scaled(8, 8)
        assertEquals(8, scaled.width)
        assertEquals(8, scaled.height)
        assertEquals(red, scaled.getUnchecked(4, 4))
    }

    @Test
    fun `horizontal and vertical flips mirror the content`() {
        val buffer = PixelBuffer(3, 1)
        buffer.setUnchecked(0, 0, red)

        assertEquals(red, buffer.flippedHorizontally().getUnchecked(2, 0))

        val column = PixelBuffer(1, 3)
        column.setUnchecked(0, 0, red)
        assertEquals(red, column.flippedVertically().getUnchecked(0, 2))
    }

    @Test
    fun `rotating by 90 degrees swaps the axes`() {
        val buffer = PixelBuffer(4, 2)
        buffer.setUnchecked(0, 0, red)

        val rotated = buffer.rotated(90)
        assertEquals(2, rotated.width)
        assertEquals(4, rotated.height)
        assertTrue(rotated.pixels.any { it == red })
    }

    @Test
    fun `resizedCanvas keeps the artwork inside the new canvas`() {
        val buffer = PixelBuffer(4, 4)
        buffer.setUnchecked(1, 1, red)

        val expanded = buffer.resizedCanvas(6, 6)
        assertEquals(6, expanded.width)
        assertEquals(red, expanded.getUnchecked(1, 1))
    }

    @Test
    fun `drawInto composites a source buffer at an offset`() {
        val target = PixelBuffer(4, 4)
        val source = PixelBuffer(2, 2)
        source.fill(red)

        target.drawInto(source, 1, 1)
        assertEquals(red, target.getUnchecked(1, 1))
        assertEquals(red, target.getUnchecked(2, 2))
        assertEquals(0, target.getUnchecked(0, 0))
    }

    @Test
    fun `IntBounds intersection and union behave like sets`() {
        val a = IntBounds(0, 0, 4, 4)
        val b = IntBounds(2, 2, 8, 8)
        assertEquals(IntBounds(2, 2, 4, 4), a.intersect(b))
        assertEquals(IntBounds(0, 0, 8, 8), a.union(b))
        assertEquals(5, a.width)
        assertTrue(a.contains(2, 2))
    }

    @Test
    fun `channels round trip through floats`() {
        val argb = Channels.argb(128, 10, 20, 30)
        assertEquals(128f, Channels.alpha(argb), 0.001f)
        assertEquals(10f, Channels.red(argb), 0.001f)
        assertEquals(0x80, (argb shr 24) and 0xFF)
    }

    @Test
    fun `scaleAlpha only changes the alpha channel`() {
        val color = 0x80FF8844.toInt()
        val scaled = Channels.scaleAlpha(color, 0.5f)
        assertEquals(0x40, (scaled shr 24) and 0xFF)
        assertEquals(color and 0x00FFFFFF, scaled and 0x00FFFFFF)
    }
}
