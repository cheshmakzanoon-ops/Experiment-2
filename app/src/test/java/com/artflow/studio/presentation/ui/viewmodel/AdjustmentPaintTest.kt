package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AdjustmentPaintTest {
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    @Test fun pencilModeShowsTheEffectOnlyWherePainted() {
        val source = PixelBuffer(20, 10).also { it.fill(red) }
        val effect = PixelBuffer(20, 10).also { it.fill(blue) }
        val painted = SelectionMask(20, 10)
        AdjustmentPaint.dab(painted, 5f, 5f, 3f)
        val out = AdjustmentPaint.mix(source, effect, null, painted)
        assertEquals(blue, out.pixels[4 * 20 + 4])
        assertEquals(red, out.pixels[5 * 20 + 15])
    }

    @Test fun withoutMasksTheEffectIsUsedAsIs() {
        val source = PixelBuffer(4, 4)
        val effect = PixelBuffer(4, 4)
        assertSame(effect, AdjustmentPaint.mix(source, effect, null, null))
    }
}
