package com.artflow.studio.core.pixels

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class RoundTipEdgeTest {
    private val width = 40
    private val centre = 20f

    @Test
    fun aHardRoundTipHasAnAntiAliasedRimAndKeepsItsSize() {
        val target = PixelBuffer(width, width)
        Stamping.dab(target, centre, centre, radius = 10f, color = 0xFF000000.toInt(), hardness = 1f)
        val painted = target.pixels.indices.filter { target.pixels[it] ushr 24 != 0 }
        // Some rim pixels are partly covered, not only fully on or off.
        assertTrue(painted.any { (target.pixels[it] ushr 24) in 1..254 })
        // Nothing paints beyond the radius plus the half-pixel rim, so the size is unchanged.
        val farthest =
            painted.maxOf { i ->
                val dx = i % width + 0.5f - centre
                val dy = i / width + 0.5f - centre
                sqrt(dx * dx + dy * dy)
            }
        assertTrue("farthest coverage at $farthest", farthest <= 10.5f + 1e-4f)
    }
}
