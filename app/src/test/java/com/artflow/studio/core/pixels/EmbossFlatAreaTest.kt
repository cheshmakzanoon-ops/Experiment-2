package com.artflow.studio.core.pixels

import org.junit.Assert.assertEquals
import org.junit.Test

class EmbossFlatAreaTest {
    @Test
    fun aFlatAreaEmbossesToMidGreyWhateverItsBrightness() {
        // The 128 bias is there so that a flat area comes out mid-grey, as in Photoshop's style filters. The kernel
        // has to sum to zero for that: a weight sum of one carries the area's brightness through and lifts it.
        for (level in listOf(0, 100, 255)) {
            val flat = 0xFF000000.toInt() or (level shl 16) or (level shl 8) or level
            val out = ImageFilters.emboss(PixelBuffer.filled(8, 8, flat), 1f)
            assertEquals("level $level", 0xFF808080.toInt(), out.getSafe(4, 4))
        }
    }
}
