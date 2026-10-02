package com.artflow.studio.core.pixels

import org.junit.Assert.assertTrue
import org.junit.Test

class StampingTipTest {
    private val black = 0xFF000000.toInt()

    private fun stamp(tip: Stamping.TipShape): PixelBuffer =
        PixelBuffer(41, 41).also { Stamping.dab(it, 20.5f, 20.5f, 16f, black, hardness = 1f, tip = tip) }

    @Test fun flatTipCoversLessThanRoundTip() {
        val round = stamp(Stamping.TipShape.ROUND).opaquePixelCount()
        val flat = stamp(Stamping.TipShape(0.25f, 0f)).opaquePixelCount()
        assertTrue("flat $flat should be well under round $round", flat < round / 2)
    }

    @Test fun angleOrientsTheLongAxis() {
        val horizontal = stamp(Stamping.TipShape(0.2f, 0f))
        val vertical = stamp(Stamping.TipShape(0.2f, 90f))
        // Far along x is painted only by the horizontal tip, far along y only by the vertical one.
        assertTrue(horizontal.pixels[20 * 41 + 34] ushr 24 != 0)
        assertTrue(horizontal.pixels[34 * 41 + 20] ushr 24 == 0)
        assertTrue(vertical.pixels[34 * 41 + 20] ushr 24 != 0)
        assertTrue(vertical.pixels[20 * 41 + 34] ushr 24 == 0)
    }
}
