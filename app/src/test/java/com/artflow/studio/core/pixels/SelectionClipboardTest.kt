package com.artflow.studio.core.pixels

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionClipboardTest {
    private val red = 0xFFFF0000.toInt()

    @Test fun cutMovesOnlySelectedPixels() {
        val layer = PixelBuffer.filled(2, 1, red)
        val mask = SelectionMask(2, 1).also { it.coverage[1] = 255.toByte() }
        val copied = SelectionClipboard.extract(layer, mask)
        SelectionClipboard.erase(layer, mask)
        assertEquals(0, copied.pixels[0] ushr 24)
        assertEquals(red, copied.pixels[1])
        assertEquals(red, layer.pixels[0])
        assertEquals(0, layer.pixels[1] ushr 24)
    }

    @Test fun anEmptySelectionSelectsNothing() {
        // A selection left empty by subtracting everything must not act as the whole layer.
        val empty = SelectionMask(2, 1)
        val layer = PixelBuffer.filled(2, 1, red)
        SelectionClipboard.erase(layer, empty)
        SelectionClipboard.fill(layer, empty, 0xFF00FF00.toInt())
        assertEquals(red, layer.pixels[0])
        assertEquals(red, layer.pixels[1])
        assertEquals(0, SelectionClipboard.extract(layer, empty).opaquePixelCount())
    }

    @Test fun noSelectionCopiesWholeLayer() {
        val layer = PixelBuffer.filled(2, 2, red)
        assertEquals(4, SelectionClipboard.extract(layer, null).opaquePixelCount())
        SelectionClipboard.erase(layer, null)
        assertEquals(0, layer.opaquePixelCount())
    }
}
