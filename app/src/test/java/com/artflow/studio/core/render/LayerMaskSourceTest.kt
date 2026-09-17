package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.LayerMaskFactory
import com.artflow.studio.core.pixels.LayerMaskSource
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.domain.model.brush.StrokeDestination
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerMaskSourceTest {
    private fun values(buffer: PixelBuffer) = buffer.pixels.map { it and 255 }.toIntArray()

    @Test
    fun sourcesAreOpaqueGrayscaleAndDeterministic() {
        for (source in LayerMaskSource.entries) {
            val pixels = PixelBuffer.filled(3, 3, 0x4000FF00)
            val selection = SelectionMask(3, 3).apply { coverage.fill(128.toByte()) }
            val first = LayerMaskFactory.create(source, 3, 3, pixels, selection)
            val second = LayerMaskFactory.create(source, 3, 3, pixels, selection)
            assertArrayEquals(first.pixels, second.pixels)
            assertTrue(
                first.pixels.all {
                    (it ushr 24) == 255 &&
                        ((it ushr 16) and 255) == (it and 255) &&
                        ((it ushr 8) and 255) == (it and 255)
                },
            )
        }
    }

    @Test
    fun selectionAndAlphaSourcesPreserveFractionalCoverage() {
        val selection = SelectionMask(4, 1, byteArrayOf(0, 64, 128.toByte(), 255.toByte()))
        assertArrayEquals(
            intArrayOf(0, 64, 128, 255),
            values(LayerMaskFactory.create(LayerMaskSource.SELECTION, 4, 1, selection = selection)),
        )
        val pixels = PixelBuffer(4, 1, intArrayOf(0x00FFFFFF, 0x4000FF00, 0x80FF0000.toInt(), 0xFF000000.toInt()))
        val original = pixels.pixels.copyOf()
        assertArrayEquals(intArrayOf(0, 64, 128, 255), values(LayerMaskFactory.create(LayerMaskSource.LAYER_ALPHA, 4, 1, pixels)))
        assertArrayEquals(original, pixels.pixels)
    }

    @Test
    fun linearAndRadialEndpointsAreDefinedEvenOnSinglePixelCanvases() {
        assertArrayEquals(intArrayOf(0, 128, 255), values(LayerMaskFactory.create(LayerMaskSource.HORIZONTAL, 3, 1)))
        assertArrayEquals(intArrayOf(0, 128, 255), values(LayerMaskFactory.create(LayerMaskSource.VERTICAL, 1, 3)))
        val radial = values(LayerMaskFactory.create(LayerMaskSource.RADIAL, 3, 3))
        assertEquals(255, radial[4])
        assertEquals(0, radial[0])
        for (source in listOf(LayerMaskSource.HORIZONTAL, LayerMaskSource.VERTICAL, LayerMaskSource.RADIAL)) {
            assertEquals(255, values(LayerMaskFactory.create(source, 1, 1)).single())
        }
    }

    @Test
    fun missingOrMismatchedSourceDataIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { LayerMaskFactory.create(LayerMaskSource.SELECTION, 3, 3) }
        assertThrows(IllegalArgumentException::class.java) { LayerMaskFactory.create(LayerMaskSource.LAYER_ALPHA, 3, 3) }
        assertThrows(IllegalArgumentException::class.java) {
            LayerMaskFactory.create(LayerMaskSource.SELECTION, 3, 3, selection = SelectionMask(2, 2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            LayerMaskFactory.create(LayerMaskSource.LAYER_ALPHA, 3, 3, pixels = PixelBuffer(2, 2))
        }
    }

    @Test
    fun maskPaintNeverUsesInkColorAndRespectsInversion() {
        assertEquals(0x12345678, StrokeDestination.LAYER.color(0x12345678, inverted = true))
        assertEquals(-1, StrokeDestination.MASK_REVEAL.color(0))
        assertEquals(0xFF000000.toInt(), StrokeDestination.MASK_HIDE.color(0))
        assertEquals(0xFF000000.toInt(), StrokeDestination.MASK_REVEAL.color(0, inverted = true))
        assertEquals(-1, StrokeDestination.MASK_HIDE.color(0, inverted = true))
    }
}
