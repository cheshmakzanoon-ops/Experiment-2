package com.artflow.studio.core.pixels

import com.artflow.studio.domain.model.layer.AdjustmentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterCorrectnessTest {
    private fun red(pixel: Int) = (pixel shr 16) and 0xFF

    @Test
    fun embossTurnsFlatAreasMidGreyAndStrengthDeepensEdges() {
        val flat = PixelBuffer.filled(8, 8, 0xFF646464.toInt())
        assertEquals(128, red(ImageFilters.emboss(flat).getSafe(4, 4)))
        // A soft horizontal ramp: more strength, more relief, until it clips.
        val ramp =
            PixelBuffer(16, 8).also { b ->
                for (i in b.pixels.indices) b.pixels[i] = 0xFF000000.toInt() or ((i % 16) * 4 * 0x010101)
            }
        val light = red(ImageFilters.emboss(ramp, 1f).getSafe(8, 4))
        val deep = red(ImageFilters.emboss(ramp, 3f).getSafe(8, 4))
        assertTrue("$light then $deep", deep > light && light > 128)
    }

    @Test
    fun selectiveColourBlackDarkens() {
        val source = PixelBuffer.filled(2, 2, 0xFFC03030.toInt())
        val out = AdjustmentProcessor.apply(source, AdjustmentType.SELECTIVE_COLOR, mapOf("reds_black" to 100f))
        assertTrue(red(out.getSafe(0, 0)) < red(source.getSafe(0, 0)))
    }

    @Test
    fun theOpacityAdjustmentFadesAsYouSlide() {
        val source = PixelBuffer.filled(2, 2, 0xFF336699.toInt())
        val half = LiveAdjustments.apply(LiveAdjustments.Kind.OPACITY, source, LiveAdjustments.Settings(amount = 0.5f))
        assertEquals(0x336699, half.getSafe(0, 0) and 0xFFFFFF)
        assertTrue((half.getSafe(0, 0) ushr 24) in 126..129)
        val untouched = LiveAdjustments.apply(LiveAdjustments.Kind.OPACITY, source, LiveAdjustments.Settings(amount = 0f))
        assertEquals(source.getSafe(0, 0), untouched.getSafe(0, 0))
    }
}
