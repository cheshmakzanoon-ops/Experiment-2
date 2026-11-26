package com.artflow.studio.core.pixels

import org.junit.Assert.assertEquals
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

    @Test
    fun subpixelFlatTipsPreserveTheirAreaAcrossPositionsAndAngles() {
        for (radius in listOf(1f, 3f, 8f)) {
            for (angle in listOf(0f, 30f, 45f, 70f, 90f)) {
                for (offset in listOf(0f, 0.25f, 0.5f, 0.75f)) {
                    val pixels = PixelBuffer(41, 41)
                    Stamping.dab(
                        pixels,
                        20f + offset,
                        20f + offset,
                        radius,
                        black,
                        hardness = 1f,
                        tip = Stamping.TipShape(0.05f, angle),
                    )
                    val area = pixels.pixels.sumOf { (it ushr 24) / 255.0 }
                    val expected = Math.PI * radius * radius * 0.05
                    assertTrue("The thin tip vanished at radius=$radius, angle=$angle, offset=$offset", area > 0.0)
                    assertEquals(
                        "Thin-tip paint area at radius=$radius, angle=$angle, offset=$offset",
                        expected,
                        area,
                        expected * 0.08 + 0.02,
                    )
                }
            }
        }
    }

    @Test
    fun subpixelFlatTipsRespectSoftnessSelectionAndAlphaLock() {
        val tip = Stamping.TipShape(0.05f, 0f)
        val hard = PixelBuffer(16, 16)
        val soft = PixelBuffer(16, 16)
        Stamping.dab(hard, 8f, 8f, 3f, black, hardness = 1f, tip = tip)
        Stamping.dab(soft, 8f, 8f, 3f, black, hardness = 0f, tip = tip)
        val softAlpha = soft.pixels.sumOf { it ushr 24 }
        assertTrue(softAlpha > 0)
        assertTrue(softAlpha < hard.pixels.sumOf { it ushr 24 })
        val masked = PixelBuffer(16, 16)
        Stamping.dab(masked, 8f, 8f, 3f, black, tip = tip, mask = SelectionMask(16, 16))
        assertTrue(masked.pixels.all { it == 0 })
        val locked = PixelBuffer(16, 16)
        Stamping.dab(locked, 8f, 8f, 3f, black, tip = tip, alphaLock = true)
        assertTrue(locked.pixels.all { it == 0 })
    }

    @Test
    fun thinTipCoverageDoesNotJumpAtTheLargeTipTransition() {
        fun density(radius: Float): Int {
            val pixels = PixelBuffer(41, 41)
            Stamping.dab(pixels, 20f, 20f, radius, black, hardness = 1f, tip = Stamping.TipShape(0.05f, 0f))
            return pixels.pixels.sumOf { it ushr 24 }
        }
        val below = density(14.99f)
        val above = density(15.01f)
        assertTrue(below > 0 && above > 0)
        assertEquals(above.toDouble(), below.toDouble(), above * 0.05)
    }

    @Test
    fun growingThinTipsPreserveAreaAcrossTheWholeSamplingInterval() {
        for (angle in listOf(0f, 30f, 45f, 70f, 90f)) {
            for (offset in listOf(0f, 0.25f, 0.5f, 0.75f)) {
                var previousArea = 0.0
                for (step in 0..48) {
                    val radius = 8f + step * 0.25f
                    val area = paintedArea(radius, Stamping.TipShape(0.05f, angle), offset = offset)
                    val context = "radius=$radius, angle=$angle, offset=$offset"
                    val expected = Math.PI * radius * radius * 0.05
                    assertTrue("Growing a thin tip lost paint area at $context: $previousArea to $area", area >= previousArea)
                    assertEquals("Thin-tip area at $context", expected, area, expected * 0.02 + 0.02)
                    previousArea = area
                }
            }
        }
    }

    @Test
    fun largerEllipsesKeepTheSameHardnessAndAreaProfile() {
        for (radius in listOf(12f, 24f, 48f)) {
            for (roundness in listOf(0.05f, 0.25f, 0.75f)) {
                for (angle in listOf(0f, 37f, 90f)) {
                    assertHardnessProfile(radius, roundness, angle)
                }
            }
        }
    }

    private fun assertHardnessProfile(
        radius: Float,
        roundness: Float,
        angle: Float,
    ) {
        for (hardness in listOf(0f, 0.5f, 1f)) {
            val area = paintedArea(radius, Stamping.TipShape(roundness, angle), hardness, offset = 0.25f)
            // Integrating the radial hardness ramp gives this fraction of the ellipse area.
            val expected = Math.PI * radius * radius * roundness * (1 + hardness + hardness * hardness) / 3
            assertEquals(
                "Ellipse area at radius=$radius, roundness=$roundness, angle=$angle, hardness=$hardness",
                expected,
                area,
                expected * 0.03 + 0.04,
            )
        }
    }

    private fun paintedArea(
        radius: Float,
        tip: Stamping.TipShape,
        hardness: Float = 1f,
        offset: Float = 0f,
    ): Double {
        val pixels = PixelBuffer(101, 101)
        Stamping.dab(pixels, 50f + offset, 50f + offset, radius, black, hardness = hardness, tip = tip)
        return pixels.pixels.sumOf { (it ushr 24) / 255.0 }
    }
}
