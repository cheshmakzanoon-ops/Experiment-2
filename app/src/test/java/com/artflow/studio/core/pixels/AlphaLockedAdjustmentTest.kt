package com.artflow.studio.core.pixels

import com.artflow.studio.core.pixels.LiveAdjustments.Kind
import com.artflow.studio.core.pixels.LiveAdjustments.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlphaLockedAdjustmentTest {
    private val red = 0xFFFF0000.toInt()

    /** Four opaque red pixels followed by four transparent ones. */
    private fun source(): PixelBuffer =
        PixelBuffer(8, 1).also { buffer ->
            for (x in 0 until 4) buffer.pixels[x] = red
        }

    @Test
    fun aBlurSpreadsIntoTransparencyUnlessAlphaIsLocked() {
        val free = LiveAdjustments.apply(Kind.GAUSSIAN_BLUR, source(), Settings(amount = 0.8f))
        assertTrue(free.pixels[4] ushr 24 > 0)

        val locked = LiveAdjustments.apply(Kind.GAUSSIAN_BLUR, source(), Settings(amount = 0.8f), alphaLocked = true)
        for (x in 4 until 8) assertEquals("transparent pixel $x", 0, locked.pixels[x] ushr 24)
        for (x in 0 until 4) assertEquals("opaque pixel $x", 0xFF, locked.pixels[x] ushr 24)
    }
}
