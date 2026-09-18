package com.artflow.studio.core.pixels

import com.artflow.studio.core.tool.LiquifyTool
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/** Regression tests for transparent-edge resampling shared by resize, transforms and liquify. */
class ResamplingTest {
    private val red = 0xFFFF0000.toInt()
    private val invisibleBlue = 0x000000FF

    @Test fun hiddenRgbCannotContaminateTheVisibleMidpoint() {
        val buffer = PixelBuffer(2, 1, intArrayOf(red, invisibleBlue))
        assertEquals(0x80FF0000.toInt(), buffer.sampleBilinear(1f, 0.5f))
    }

    @Test fun transparentExteriorDoesNotDarkenTheEdge() {
        val buffer = PixelBuffer.filled(1, 1, red)
        assertEquals(0x80FF0000.toInt(), buffer.sampleBilinear(0f, 0.5f))
        assertEquals(0x40FF0000, buffer.sampleBilinear(0f, 0f))
        assertEquals(0, buffer.sampleBilinear(-0.5f, 0.5f))
        assertEquals(0, buffer.sampleBilinear(1.5f, 0.5f))
    }

    @Test fun partialAlphasWeightColourInsteadOfAveragingItBlindly() {
        val buffer = PixelBuffer(2, 1, intArrayOf(0x80FF0000.toInt(), 0xFF0000FF.toInt()))
        assertEquals(0xC05500AA.toInt(), buffer.sampleBilinear(1f, 0.5f))
    }

    @Test fun exactTexelCentresPreserveEveryBitIncludingInvisibleRgb() {
        val pixels = intArrayOf(0x00123456, 0x01ABCDEF, 0x80887766.toInt(), red)
        for (y in 0..1) {
            for (x in 0..1) {
                val buffer = PixelBuffer(2, 2, pixels.copyOf())
                assertEquals(pixels[y * 2 + x], buffer.sampleBilinear(x + 0.5f, y + 0.5f))
            }
        }
        assertEquals(pixels[0], PixelBuffer.mix4(pixels[0], pixels[1], pixels[2], pixels[3], 0f, 0f))
        assertEquals(pixels[1], PixelBuffer.mix4(pixels[0], pixels[1], pixels[2], pixels[3], 1f, 0f))
        assertEquals(pixels[2], PixelBuffer.mix4(pixels[0], pixels[1], pixels[2], pixels[3], 0f, 1f))
        assertEquals(pixels[3], PixelBuffer.mix4(pixels[0], pixels[1], pixels[2], pixels[3], 1f, 1f))
    }

    @Test fun fullyTransparentMixturesAreCanonicalTransparentBlack() {
        assertEquals(0, PixelBuffer.mix4(0x00FFFFFF, invisibleBlue, 0x00FF0000, 0x0000FF00, 0.5f, 0.5f))
    }

    @Test fun resizingKeepsSaturatedEdgesAndDoesNotChangeTheSource() {
        val buffer = PixelBuffer(2, 1, intArrayOf(red, invisibleBlue))
        assertArrayEquals(intArrayOf(red, 0xBFFF0000.toInt(), 0x40FF0000, invisibleBlue), buffer.scaled(4, 1).pixels)
        assertArrayEquals(intArrayOf(red, invisibleBlue), buffer.pixels)
    }

    @Test fun halfPixelTranslationHasTwoCleanHalfCoveredEdges() {
        val out = PixelBuffer.filled(1, 1, red).transformed(3, 1, 0.5f, 0f, 1f, 1f, 0f, 0f, 0f)
        assertArrayEquals(intArrayOf(0x80FF0000.toInt(), 0x80FF0000.toInt(), 0), out.pixels)
    }

    @Test fun identityTransformPreservesPixelsAndNegativeScaleMirrors() {
        val source = PixelBuffer(2, 1, intArrayOf(red, invisibleBlue))
        assertArrayEquals(source.pixels, source.transformed(2, 1, 0f, 0f, 1f, 1f, 0f, 0f, 0f).pixels)
        assertArrayEquals(intArrayOf(invisibleBlue, red), source.transformed(2, 1, 0f, 0f, -1f, 1f, 0f, 1f, 0.5f).pixels)
    }

    @Test fun liquifyUsesTheSameCoverageWeightedSampler() {
        val source = PixelBuffer(2, 1, intArrayOf(red, invisibleBlue))
        val displacement = LiquifyTool.DisplacementMap(2, 1)
        displacement.add(0, 0, 0.5f, 0f)
        assertArrayEquals(intArrayOf(0x80FF0000.toInt(), invisibleBlue), displacement.apply(source).pixels)
        assertArrayEquals(intArrayOf(red, invisibleBlue), source.pixels)
    }

    @Test fun fullyExteriorFiniteCoordinatesCannotOverflowIntegerNeighbours() {
        val source = PixelBuffer.filled(1, 1, red)
        for (value in listOf(Float.MAX_VALUE, -Float.MAX_VALUE, 1e20f, -1e20f)) {
            assertEquals(0, source.sampleBilinear(value, 0.5f))
            assertEquals(0, source.sampleBilinear(0.5f, value))
        }
    }

    @Test fun invalidSamplingCoordinatesAndWeightsAreRejected() {
        val source = PixelBuffer.filled(1, 1, red)
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertTrue(runCatching { source.sampleBilinear(value, 0.5f) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { source.sampleBilinear(0.5f, value) }.exceptionOrNull() is IllegalArgumentException)
        }
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.1f, 1.1f)) {
            assertTrue(runCatching { PixelBuffer.mix4(red, red, red, red, value, 0.5f) }.isFailure)
            assertTrue(runCatching { PixelBuffer.mix4(red, red, red, red, 0.5f, value) }.isFailure)
        }
    }

    @Test fun invalidTransformsFailBeforeProducingAnEmptyReplacement() {
        val source = PixelBuffer.filled(2, 2, red)
        for (scale in listOf(0f, 1e-8f, -1e-8f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertTrue(runCatching { source.transformed(2, 2, 0f, 0f, scale, 1f, 0f, 0f, 0f) }.isFailure)
            assertTrue(runCatching { source.transformed(2, 2, 0f, 0f, 1f, scale, 0f, 0f, 0f) }.isFailure)
        }
        assertTrue(runCatching { source.transformed(2, 2, Float.NaN, 0f, 1f, 1f, 0f, 0f, 0f) }.isFailure)
        assertTrue(runCatching { source.transformed(2, 2, 0f, 0f, 1f, 1f, Float.NaN, 0f, 0f) }.isFailure)
        assertTrue(source.pixels.all { it == red })
    }

    @Test fun opaqueInterpolationMatchesBilinearWeights() {
        val random = Random(793)
        repeat(1000) {
            val p = IntArray(4) { random.nextInt() or 0xFF000000.toInt() }
            val fx = random.nextFloat()
            val fy = random.nextFloat()
            val weights = floatArrayOf((1 - fx) * (1 - fy), fx * (1 - fy), (1 - fx) * fy, fx * fy)

            fun channel(shift: Int): Int =
                (p.indices.sumOf { (((p[it] ushr shift) and 255) * weights[it]).toDouble() }).roundToInt().coerceIn(0, 255)
            val expected = 0xFF000000.toInt() or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
            assertChannelsClose(expected, PixelBuffer.mix4(p[0], p[1], p[2], p[3], fx, fy))
        }
    }

    @Test fun randomSamplesAgreeWithIndependentDoublePrecisionOracle() {
        val random = Random(9182026)
        repeat(5000) {
            val pixels = IntArray(4) { random.nextInt() }
            val fx = random.nextFloat()
            val fy = random.nextFloat()
            val before = pixels.copyOf()
            assertChannelsClose(oracle(pixels, fx, fy), PixelBuffer.mix4(pixels[0], pixels[1], pixels[2], pixels[3], fx, fy))
            assertArrayEquals(before, pixels)
        }
    }

    private fun oracle(
        pixels: IntArray,
        fx: Float,
        fy: Float,
    ): Int {
        val x = fx.toDouble()
        val y = fy.toDouble()
        val weights = doubleArrayOf((1 - x) * (1 - y), x * (1 - y), (1 - x) * y, x * y)
        val alpha = pixels.indices.sumOf { (pixels[it] ushr 24) * weights[it] }
        if (alpha < 0.5) return 0

        fun channel(shift: Int): Int =
            (pixels.indices.sumOf { ((pixels[it] ushr shift) and 255) * (pixels[it] ushr 24) * weights[it] } / alpha)
                .roundToInt()
                .coerceIn(0, 255)
        return (alpha.roundToInt().coerceIn(0, 255) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun assertChannelsClose(
        expected: Int,
        actual: Int,
    ) {
        for (shift in intArrayOf(0, 8, 16, 24)) {
            assertFalse(
                "Expected ${expected.toUInt().toString(16)}, got ${actual.toUInt().toString(16)} at channel $shift",
                abs(((expected ushr shift) and 255) - ((actual ushr shift) and 255)) > 1,
            )
        }
    }
}
