package com.artflow.studio.core.color

import com.artflow.studio.core.pixels.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class PaletteImportTest {
    private fun aseColor(
        model: String,
        vararg values: Float,
    ): ByteArray {
        val name = "Swatch"
        val body = ByteBuffer.allocate(2 + (name.length + 1) * 2 + 4 + values.size * 4 + 2)
        body.putShort((name.length + 1).toShort())
        name.forEach { body.putChar(it) }
        body.putChar('\u0000')
        body.put(model.encodeToByteArray())
        values.forEach { body.putFloat(it) }
        body.putShort(0)
        val block = ByteBuffer.allocate(6 + body.capacity())
        block.putShort(1)
        block.putInt(body.capacity())
        block.put(body.array())
        return block.array()
    }

    @Test fun adobeSwatchExchangeColoursAreRead() {
        val blocks = listOf(aseColor("RGB ", 1f, 0f, 0f), aseColor("Gray", 0.5f), aseColor("CMYK", 0f, 1f, 1f, 0f))
        val file = ByteBuffer.allocate(12 + blocks.sumOf { it.size })
        file.putInt(0x41534546)
        file.putInt(0x00010000)
        file.putInt(blocks.size)
        blocks.forEach { file.put(it) }
        val palette = PaletteCodec.importAse(file.array(), "Swatches")!!
        assertEquals(listOf(0xFFFF0000.toInt(), 0xFF808080.toInt(), 0xFFFF0000.toInt()), palette.colors)
        assertNull(PaletteCodec.importAse("GIMP Palette".encodeToByteArray()))
    }

    @Test fun aPhotosMainColoursComeOut() {
        val image = PixelBuffer(10, 10)
        for (i in image.pixels.indices) image.pixels[i] = if (i < 50) 0xFFFF0000.toInt() else 0xFF0000FF.toInt()
        val colors = PaletteExtractor.colors(image, count = 4)
        assertTrue(0xFFFF0000.toInt() in colors)
        assertTrue(0xFF0000FF.toInt() in colors)
        assertEquals(colors.size, colors.distinct().size)
    }
}
