package com.artflow.studio.core.pixels

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RasterOverlayTest {
    private val blue = 0xFF0000FF.toInt()
    private val red = 0xFFFF0000.toInt()

    @Test
    fun transparentOverlayPreservesAllBackdropPixels() {
        val target = PixelBuffer.filled(3, 2, blue)
        val overlay = PixelBuffer(3, 2).apply { pixels[1] = red }
        assertTrue(RasterOverlay.draw(target, overlay))
        assertArrayEquals(intArrayOf(blue, red, blue, blue, blue, blue), target.pixels)
    }

    @Test
    fun featheringBlendsColorWithoutPunchingHolesInOpaqueArtwork() {
        val target = PixelBuffer.filled(2, 1, blue)
        val overlay = PixelBuffer.filled(2, 1, red)
        val selection = SelectionMask(2, 1, byteArrayOf(128.toByte(), 0))
        assertTrue(RasterOverlay.draw(target, overlay, selection))
        assertEquals(0xFF80007F.toInt(), target.pixels[0])
        assertEquals(blue, target.pixels[1])
    }

    @Test
    fun sourceAlphaAndSelectionCoverageMultiply() {
        val target = PixelBuffer.filled(1, 1, blue)
        assertTrue(RasterOverlay.draw(target, PixelBuffer.filled(1, 1, 0x80FF0000.toInt()), SelectionMask(1, 1, byteArrayOf(128.toByte()))))
        assertEquals(0xFF4000BF.toInt(), target.pixels[0])
    }

    @Test
    fun alphaLockPreservesTransparentAndPartiallyCoveredBackdrop() {
        val target = PixelBuffer(2, 1, intArrayOf(0, 0x400000FF))
        assertTrue(RasterOverlay.draw(target, PixelBuffer.filled(2, 1, red), alphaLocked = true))
        assertArrayEquals(intArrayOf(0, 0x40FF0000), target.pixels)
    }

    @Test
    fun emptySelectionOrTransparentOverlayDoesNotReportAnEdit() {
        val target = PixelBuffer.filled(2, 1, blue)
        assertFalse(RasterOverlay.draw(target, PixelBuffer.filled(2, 1, red), SelectionMask(2, 1)))
        assertFalse(RasterOverlay.draw(target, PixelBuffer(2, 1)))
        assertFalse(RasterOverlay.draw(target, PixelBuffer.filled(2, 1, blue)))
        assertArrayEquals(intArrayOf(blue, blue), target.pixels)
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedOverlayIsRejectedBeforeMutation() {
        RasterOverlay.draw(PixelBuffer(2, 1), PixelBuffer(1, 2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedSelectionIsRejectedBeforeMutation() {
        RasterOverlay.draw(PixelBuffer(2, 1), PixelBuffer(2, 1), SelectionMask(1, 2))
    }
}
