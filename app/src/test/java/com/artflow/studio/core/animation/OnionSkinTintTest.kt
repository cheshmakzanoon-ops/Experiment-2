package com.artflow.studio.core.animation

import com.artflow.studio.core.pixels.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

class OnionSkinTintTest {
    @Test
    fun everyPixelTakesTheTintColourAndKeepsItsAlpha() {
        val frame = PixelBuffer(3, 1, intArrayOf(0x80123456.toInt(), 0xFF00FF00.toInt(), 0x00ABCDEF))
        val tinted = OnionSkinTint.tint(frame, 0xFFFF0000.toInt())
        assertEquals(3, tinted.width)
        assertEquals(1, tinted.height)
        assertEquals(0x80FF0000.toInt(), tinted.pixels[0])
        assertEquals(0xFFFF0000.toInt(), tinted.pixels[1])
        // A transparent pixel stays transparent; only its colour channels change.
        assertEquals(0x00FF0000, tinted.pixels[2])
    }

    @Test
    fun theTintColourAlphaIsIgnored() {
        val frame = PixelBuffer(1, 1, intArrayOf(0xFF102030.toInt()))
        assertEquals(0xFF00FF00.toInt(), OnionSkinTint.tint(frame, 0x0000FF00).pixels[0])
    }

    @Test
    fun tintLeavesTheSourceBufferUnchanged() {
        val frame = PixelBuffer(1, 1, intArrayOf(0xFF102030.toInt()))
        OnionSkinTint.tint(frame, 0xFF00FF00.toInt())
        assertEquals(0xFF102030.toInt(), frame.pixels[0])
    }

    @Test
    fun defaultBeforeAndAfterColoursAreRedAndGreen() {
        assertEquals(0xFFFF0000.toInt(), OnionSkinTint.COLOUR_CHOICES[0])
        assertEquals(0xFF00FF00.toInt(), OnionSkinTint.COLOUR_CHOICES[1])
    }
}
