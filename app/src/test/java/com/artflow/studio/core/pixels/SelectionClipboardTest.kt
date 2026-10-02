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

    @Test fun noSelectionCopiesWholeLayer() {
        val layer = PixelBuffer.filled(2, 2, red)
        assertEquals(4, SelectionClipboard.extract(layer, null).opaquePixelCount())
        SelectionClipboard.erase(layer, null)
        assertEquals(0, layer.opaquePixelCount())
    }
}
