package com.artflow.studio.core.color

import com.artflow.studio.core.export.JpegIcc
import com.artflow.studio.core.export.PngCodec
import com.artflow.studio.core.pixels.PixelBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.zip.InflaterInputStream

class ColorProfileTest {
    private fun channels(color: Int) = listOf((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)

    @Test fun displayP3AndSrgbConvertBothWays() {
        // Pure sRGB red is a less saturated red inside Display P3.
        assertEquals(listOf(234, 51, 35), channels(ColorProfiles.convert(0xFFFF0000.toInt(), ColorProfile.SRGB, ColorProfile.DISPLAY_P3)))
        val greys = listOf(0xFF000000.toInt(), 0xFF808080.toInt(), -1)
        greys.forEach { assertEquals(it, ColorProfiles.convert(it, ColorProfile.DISPLAY_P3, ColorProfile.SRGB)) }
        val color = 0x80C86432.toInt()
        val wide = ColorProfiles.convert(color, ColorProfile.SRGB, ColorProfile.DISPLAY_P3)
        val back = ColorProfiles.convert(wide, ColorProfile.DISPLAY_P3, ColorProfile.SRGB)
        assertEquals(color ushr 24, back ushr 24)
        channels(color).zip(channels(back)).forEach { (a, b) -> assertTrue("$a vs $b", kotlin.math.abs(a - b) <= 1) }
        assertEquals(ColorProfile.SRGB, ColorProfile.from("unknown"))
    }

    @Test fun theIccProfileIsAWellFormedDisplayProfile() {
        val icc = IccProfile.displayP3
        val data = ByteBuffer.wrap(icc)
        assertEquals(icc.size, data.getInt(0))
        assertEquals("mntrRGB XYZ ", String(icc, 12, 12, Charsets.US_ASCII))
        assertEquals("acsp", String(icc, 36, 4, Charsets.US_ASCII))
        val count = data.getInt(128)
        val tags = (0 until count).associate { String(icc, 132 + it * 12, 4, Charsets.US_ASCII) to data.getInt(136 + it * 12) }
        assertEquals(setOf("desc", "cprt", "wtpt", "rXYZ", "gXYZ", "bXYZ", "rTRC", "gTRC", "bTRC"), tags.keys)
        // The colorants add up to the D50 white, and every tag lies inside the profile.
        val whiteX = listOf("rXYZ", "gXYZ", "bXYZ").sumOf { data.getInt(tags.getValue(it) + 8).toDouble() / 65536.0 }
        assertEquals(0.9642, whiteX, 0.001)
        assertTrue(tags.values.all { it % 4 == 0 && it < icc.size })
        assertEquals(tags["rTRC"], tags["bTRC"])
    }

    @Test fun pngAndJpegCarryTheProfile() {
        val png = PngCodec.encode(PixelBuffer(2, 2), profile = ColorProfile.DISPLAY_P3)
        val start = png.indices.first { String(png, it, 4, Charsets.US_ASCII) == "iCCP" } + 4
        assertEquals("Display P3", String(png, start, 10, Charsets.ISO_8859_1))
        val length = ByteBuffer.wrap(png).getInt(start - 8)
        val compressed = png.copyOfRange(start + 12, start + length)
        assertArrayEquals(IccProfile.displayP3, InflaterInputStream(compressed.inputStream()).readBytes())
        val plain = PngCodec.encode(PixelBuffer(2, 2))
        assertTrue((0..plain.size - 4).none { String(plain, it, 4, Charsets.ISO_8859_1) == "iCCP" })

        val jpeg = byteArrayOf(-1, -40, -1, -39)
        val tagged = JpegIcc.withProfile(jpeg, IccProfile.displayP3)
        assertEquals(-30, tagged[3].toInt()) // APP2 straight after SOI
        assertEquals("ICC_PROFILE", String(tagged, 6, 11, Charsets.US_ASCII))
        assertArrayEquals(IccProfile.displayP3, tagged.copyOfRange(20, tagged.size - 2))
    }
}
