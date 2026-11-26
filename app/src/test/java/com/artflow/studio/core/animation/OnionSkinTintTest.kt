package com.artflow.studio.core.animation

import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.pixels.PixelBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class OnionSkinTintTest {
    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()

    @Test fun silhouetteTakesTheTintColourAndKeepsEachPixelsAlpha() {
        val source = PixelBuffer(3, 1)
        source.pixels[0] = 0x80123456.toInt()
        source.pixels[1] = 0x00FFFFFF
        source.pixels[2] = 0xFF0000FF.toInt()
        val tinted = OnionSkinTint.silhouette(source, red)
        assertArrayEquals(intArrayOf(0x80FF0000.toInt(), 0x00FF0000, 0xFFFF0000.toInt()), tinted.pixels)
    }

    @Test fun tintAlphaIsIgnoredSoOpacityComesFromTheSource() {
        val source = PixelBuffer(1, 1)
        source.pixels[0] = 0xFF000000.toInt()
        assertEquals(green, OnionSkinTint.silhouette(source, (0x10 shl 24) or 0x00FF00).pixels[0])
    }

    @Test fun sourceIsNotModifiedAndSizeIsPreserved() {
        val source = PixelBuffer(4, 2)
        source.pixels.fill(0x40ABCDEF)
        val tinted = OnionSkinTint.silhouette(source, red)
        assertEquals(4, tinted.width)
        assertEquals(2, tinted.height)
        assertEquals(0x40ABCDEF, source.pixels[0])
        assertEquals(0x40FF0000, tinted.pixels[7])
    }

    @Test fun srgbPickerTintIsExpressedInTheWideDocumentProfileBeforeDisplay() {
        val source = PixelBuffer(3, 1, intArrayOf(0x80123456.toInt(), 0x00FFFFFF, 0xFF0000FF.toInt()))
        val original = source.pixels.copyOf()
        val tinted = OnionSkinTint.silhouette(source, red, ColorProfile.DISPLAY_P3)

        // sRGB red has Display P3 components (234, 51, 35). Copying (255, 0, 0)
        // instead would ask the display shader for P3 red, shifting the chosen swatch.
        assertArrayEquals(intArrayOf(0x80EA3323.toInt(), 0x00EA3323, 0xFFEA3323.toInt()), tinted.pixels)
        assertArrayEquals(original, source.pixels)
    }
}
