package com.artflow.studio.core.export

import com.artflow.studio.core.pixels.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TiffCodecTest {
    @Test fun writesARgbaStripReadersCanFind() {
        val image = PixelBuffer(3, 2).also { it.pixels[4] = 0x80112233.toInt() }
        val bytes = ByteBuffer.wrap(TiffCodec.write(image, 300)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals('I'.code.toByte(), bytes.get(0))
        assertEquals(42, bytes.getShort(2).toInt())
        val ifd = bytes.getInt(4)
        val entries =
            (0 until bytes.getShort(ifd).toInt()).associate { i ->
                val at = ifd + 2 + i * 12
                bytes.getShort(at).toInt() to at + 8
            }
        assertEquals(3, bytes.getInt(entries.getValue(256)))
        assertEquals(2, bytes.getInt(entries.getValue(257)))
        assertEquals(4, bytes.getShort(entries.getValue(277)).toInt())
        assertEquals(300, bytes.getInt(bytes.getInt(entries.getValue(282))))
        val strip = bytes.getInt(entries.getValue(273))
        assertEquals(24, bytes.getInt(entries.getValue(279)))
        val pixel = strip + 4 * 4
        assertEquals(0x11, bytes.get(pixel).toInt() and 0xFF)
        assertEquals(0x22, bytes.get(pixel + 1).toInt() and 0xFF)
        assertEquals(0x33, bytes.get(pixel + 2).toInt() and 0xFF)
        assertEquals(0x80, bytes.get(pixel + 3).toInt() and 0xFF)
        assertEquals(strip + 24, bytes.capacity())
    }
}
