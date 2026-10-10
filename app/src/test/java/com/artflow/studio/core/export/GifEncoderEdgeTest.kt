package com.artflow.studio.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

class GifEncoderEdgeTest {
    @Test
    fun aTransparentGifEdgeKeepsItsColourInsteadOfTakingTheMatte() {
        // Opaque blue, a blue pixel that is mostly opaque, and a fully transparent pixel.
        val blue = 0xFF0000FF.toInt()
        val mostlyOpaque = 0xC80000FF.toInt()
        val clear = 0x00000000
        val bytes = GifEncoder.encode(listOf(intArrayOf(blue, mostlyOpaque, clear)), 3, 1, listOf(100), keepTransparency = true)
        val image = ImageIO.read(ByteArrayInputStream(bytes))

        // Blending the partly opaque pixel onto the white matte would make it visibly lighter than pure blue.
        val edge = image.getRGB(1, 0)
        assertEquals("edge is opaque", 0xFF, (edge ushr 24) and 0xFF)
        assertTrue("edge red ${(edge shr 16) and 0xFF}", ((edge shr 16) and 0xFF) < 16)
        assertTrue("edge green ${(edge shr 8) and 0xFF}", ((edge shr 8) and 0xFF) < 16)
        assertTrue("edge blue ${edge and 0xFF}", (edge and 0xFF) > 240)
        assertEquals("clear pixel is transparent", 0, (image.getRGB(2, 0) ushr 24) and 0xFF)
    }
}
