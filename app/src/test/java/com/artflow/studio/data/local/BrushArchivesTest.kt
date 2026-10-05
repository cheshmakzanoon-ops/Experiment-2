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

    @Test fun aProcreateSetKeepsItsOrderNamesSettingsAndInvertedShapes() {
        val set = requireNotNull(javaClass.getResourceAsStream("/procreate/fixture.brushset")).use { it.readBytes() }
        val brushes = BrushArchives.read(set, decode)
        assertEquals(listOf("Plain", "Inked Grain"), brushes.map { it.name })
        val inked = brushes[1]
        // The stand-in shape is a white disc; this brush's shape is inverted, so its centre holds paint back.
        val shape = requireNotNull(inked.shape)
        assertEquals(0, shape.values[shape.size / 2 * shape.size + shape.size / 2].toInt() and 0xFF)
        assertEquals(255, shape.values[0].toInt() and 0xFF)
        val params = BrushArchives.parameters("custom-a", "custom-b", inked.settings)
        assertEquals(0.2f, params.spacing, 1e-6f)
        assertEquals("custom-b", params.textureId)
        assertTrue(params.blendTexture)
        assertNull(brushes[0].shape)
    }

    @Test fun photoshopTipsBecomeNamedShapes() {
        val abr = requireNotNull(javaClass.getResourceAsStream("/procreate/tips-v6.abr")).use { it.readBytes() }
        assertFalse(BrushArchives.isArchive(abr))
        val brushes = BrushArchives.readAbr(abr)
        assertEquals(listOf("Ring Tip", "Smooth Ramp"), brushes.map { it.name })
        assertTrue(brushes.all { it.shape != null && it.grain == null })
    }
}
