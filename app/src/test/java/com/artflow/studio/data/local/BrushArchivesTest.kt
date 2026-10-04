package com.artflow.studio.data.local

import com.artflow.studio.core.pixels.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BrushArchivesTest {
    private fun zip(vararg names: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            names.forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    // Stands in for PNG decoding: a white disc on black, so the tile has real contrast.
    private val decode: (ByteArray) -> PixelBuffer? = {
        PixelBuffer(8, 8).also { image ->
            for (i in image.pixels.indices) image.pixels[i] = if (i % 8 in 2..5 && i / 8 in 2..5) -1 else 0xFF000000.toInt()
        }
    }

    @Test fun everyBrushInASetBringsItsShapeAndGrain() {
        val set = zip("brushset.plist", "A/Brush.archive", "A/Shape.png", "A/Grain.png", "B/Brush.archive", "B/shape.png")
        assertTrue(BrushArchives.isArchive(set))
        val brushes = BrushArchives.read(set, decode)
        assertEquals(2, brushes.size)
        assertNotNull(brushes[0].shape)
        assertNotNull(brushes[0].grain)
        assertNull(brushes[1].grain)
        val params = BrushArchives.parameters("custom-a", null)
        assertEquals("custom-a", params.shapeId)
        assertFalse(params.blendTexture)
    }

    @Test fun filesWithoutBrushImagesAreRefused() {
        assertFalse(BrushArchives.isArchive("{}".toByteArray()))
        assertThrows(IllegalArgumentException::class.java) { BrushArchives.read(zip("Brush.archive"), decode) }
    }
}
