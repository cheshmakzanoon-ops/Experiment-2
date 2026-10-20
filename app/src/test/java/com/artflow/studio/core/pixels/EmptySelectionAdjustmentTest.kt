package com.artflow.studio.core.pixels

import com.artflow.studio.core.pixels.LiveAdjustments.Kind
import com.artflow.studio.core.pixels.LiveAdjustments.Settings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EmptySelectionAdjustmentTest {
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    /** Four red pixels then four blue ones, so a blur visibly changes the layer. */
    private fun source(): PixelBuffer =
        PixelBuffer(8, 1).also { buffer ->
            for (x in 0 until 8) buffer.pixels[x] = if (x < 4) red else blue
        }

    @Test
    fun anEmptySelectionLeavesEveryPixelAsItWas() {
        val empty = SelectionMask(8, 1)
        val out = LiveAdjustments.apply(Kind.GAUSSIAN_BLUR, source(), Settings(amount = 0.8f), empty)
        assertArrayEquals(source().pixels, out.pixels)
    }

    @Test
    fun withNoSelectionTheWholeLayerTakesTheEffect() {
        val whole = LiveAdjustments.apply(Kind.GAUSSIAN_BLUR, source(), Settings(amount = 0.8f), null)
        assertNotEquals(source().pixels.toList(), whole.pixels.toList())
    }
}
