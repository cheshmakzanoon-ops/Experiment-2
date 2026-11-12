package com.artflow.studio.core.color

import com.artflow.studio.core.pixels.Channels
import com.artflow.studio.core.pixels.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class IccColorEngineTest {
    @Test
    fun rgbToCmykSeparationCoversTacLimit() {
        // Deep saturated black/brown in RGB (0, 0, 0) could theoretically demand 100% C, M, Y, K (400%)
        // The engine must limit total ink to the profile TAC (e.g. 330% for Fogra39, 300% for SWOP)
        for (profile in listOf(
            IccProfileType.FOGRA39,
            IccProfileType.SWOP_2006,
            IccProfileType.JAPAN_COLOR_2001,
            IccProfileType.GRACOL_2006,
        )) {
            val cmyk = IccColorEngine.rgbToCmyk(0f, 0f, 0f, profile)
            val totalInk = cmyk[0] + cmyk[1] + cmyk[2] + cmyk[3]
            assertTrue(
                "Total ink $totalInk exceeded TAC ${profile.totalAreaCoverage} for profile ${profile.name}",
                totalInk <= profile.totalAreaCoverage + 0.001f,
            )
            assertTrue("Black ink was not generated for deep dark tone", cmyk[3] > 0.5f)
        }
    }

    @Test
    fun cmykToRgbRoundTripPreservesNeutralGrays() {
        // Neutral mid-tone gray (0.5, 0.5, 0.5)
        val cmyk = IccColorEngine.rgbToCmyk(0.5f, 0.5f, 0.5f, IccProfileType.FOGRA39)
        val proof =
            IccColorEngine.softProof(
                floatArrayOf(0.5f, 0.5f, 0.5f),
                sourceProfile = IccProfileType.SRGB,
                targetProfile = IccProfileType.FOGRA39,
            )

        // Balanced gray shouldn't have extreme color casts
        assertTrue("Red and green should be balanced", abs(proof[0] - proof[1]) < 0.05f)
        assertTrue("Green and blue should be balanced", abs(proof[1] - proof[2]) < 0.05f)
        assertFalse(
            "Neutral gray must not be flagged as out-of-gamut",
            IccColorEngine.isOutOfGamut(0.5f, 0.5f, 0.5f, IccProfileType.FOGRA39),
        )
    }

    @Test
    fun swopGcrGeneratesHeavyBlack() {
        // Under dark shadow (0.1, 0.1, 0.1), SWOP replaces gray component with K
        val cmyk = IccColorEngine.rgbToCmyk(0.1f, 0.1f, 0.1f, IccProfileType.SWOP_2006)
        assertTrue("SWOP K ink should be generated heavily for dark tones", cmyk[3] > 0.6f)
        assertTrue("K ink should not exceed SWOP black limit of 0.90", cmyk[3] <= IccProfileType.SWOP_2006.blackLimit + 0.001f)
    }

    @Test
    fun outOfGamutDetectionAndZebraPattern() {
        // Neon pure green (0, 1, 0) or cyan (0, 1, 1) are well outside standard coated CMYK gamuts
        val isNeonGreenOutOfGamut = IccColorEngine.isOutOfGamut(0f, 1f, 0f, IccProfileType.SWOP_2006)
        assertTrue("Neon pure RGB green must be flagged out-of-gamut for SWOP", isNeonGreenOutOfGamut)

        val buffer = PixelBuffer(16, 16)
        for (i in buffer.pixels.indices) {
            buffer.pixels[i] = Channels.argb(255, 0, 255, 0) // all neon green
        }

        val zebraColor = 0xFFFF0055.toInt()
        val zebraBuffer =
            IccColorEngine.generateGamutZebra(
                buffer,
                targetProfile = IccProfileType.SWOP_2006,
                zebraColor = zebraColor,
                stripeWidth = 4,
            )

        // Must contain zebra stripes
        var zebraCount = 0
        for (p in zebraBuffer.pixels) {
            if (p == zebraColor) zebraCount++
        }
        assertTrue("Zebra pattern must mark out-of-gamut pixels", zebraCount > 0 && zebraCount < buffer.pixels.size)
    }

    @Test
    fun cmykBufferConversionFidelity() {
        val src = PixelBuffer(4, 4)
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                src.pixels[y * 4 + x] = Channels.argb(255, x * 60, y * 60, 128)
            }
        }

        val cmykBuffer = CmykBuffer.fromRgbBuffer(src, IccProfileType.FOGRA39)
        assertEquals(4, cmykBuffer.width)
        assertEquals(4, cmykBuffer.height)
        assertEquals(4 * 4 * 4, cmykBuffer.data.size)

        val reconstructed = cmykBuffer.toRgbBuffer(IccProfileType.FOGRA39)
        assertEquals(src.width, reconstructed.width)
        assertEquals(src.height, reconstructed.height)

        // Spot check pixel at (0, 0)
        val p = reconstructed.pixels[0]
        assertEquals(255f, Channels.alpha(p), 0.01f)
    }
}
