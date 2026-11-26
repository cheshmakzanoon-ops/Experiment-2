package com.artflow.studio.data.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

class Bt709VideoColorTest {
    @Test
    fun standardEndpointsAndPrimaryColoursHaveKnownVideoCodes() {
        val examples =
            mapOf(
                0x000000 to intArrayOf(16, 128, 128),
                0xFFFFFF to intArrayOf(235, 128, 128),
                0xFF0000 to intArrayOf(63, 102, 240),
                0x00FF00 to intArrayOf(173, 42, 26),
                0x0000FF to intArrayOf(32, 240, 118),
                0x808080 to intArrayOf(115, 128, 128),
            )
        examples.forEach { (srgb, expected) -> assertArrayEquals("sRGB $srgb", expected, encode(srgb)) }
    }

    @Test
    fun multicolourGridMatchesIndependentUnquantizedEquations() {
        // Include both transfer-curve knees and dark/midrange values that an omitted transfer
        // conversion would change. The reference shares no LUT or integer matrix coefficients.
        forEachColour { srgb ->
            val actual = encode(srgb)
            val expected = referenceYuv(srgb)
            actual.indices.forEach { channel ->
                assertTrue("sRGB $srgb, YCbCr channel $channel", abs(actual[channel] - expected[channel]) <= 1)
            }
            assertTrue(actual[0] in 16..235)
            assertTrue(actual[1] in 16..240)
            assertTrue(actual[2] in 16..240)
        }
    }

    @Test
    fun independentInverseMatrixAndTransferRecoverSrgbColours() {
        forEachColour { srgb ->
            val yuv = encode(srgb)
            val y = (yuv[0] - 16) / 219.0
            val cb = (yuv[1] - 128) / 224.0
            val cr = (yuv[2] - 128) / 224.0
            val red = y + 1.5748 * cr
            val blue = y + 1.8556 * cb
            val green = (y - 0.2126 * red - 0.0722 * blue) / 0.7152
            listOf(red, green, blue).forEachIndexed { index, signal ->
                val bounded = signal.coerceIn(0.0, 1.0)
                val linear = if (bounded < 0.081) bounded / 4.5 else ((bounded + 0.099) / 1.099).pow(1 / 0.45)
                val decoded = if (linear <= 0.0031308) 12.92 * linear else 1.055 * linear.pow(1 / 2.4) - 0.055
                val expected = srgb shr (16 - 8 * index) and 255
                assertTrue("sRGB $srgb, RGB channel $index", abs(expected - (decoded * 255).roundToInt()) <= 6)
            }
        }
    }

    @Test
    fun everyGreyLevelStaysNeutralAndMonotonic() {
        var previous = 16
        for (channel in 0..255) {
            val yuv = encode(channel * 0x010101)
            assertTrue(yuv[0] >= previous)
            assertEquals(128, yuv[1])
            assertEquals(128, yuv[2])
            previous = yuv[0]
        }
        assertEquals(235, previous)
    }

    @Test
    fun chromaSubsamplingAveragesAfterTheTransferConversion() {
        val colors = intArrayOf(0x000000, 0xFF0000, 0x008080, 0x4080FF)
        val video = IntArray(4) { Bt709VideoColor.fromSrgb(colors[it]) }
        val averaged = Bt709VideoColor.average2x2(video, 2, 0, 0)
        val reference = colors.map(::referenceYuv)
        assertTrue(abs(Bt709VideoColor.chromaBlue(averaged) - reference.map { it[1] }.average()) <= 1)
        assertTrue(abs(Bt709VideoColor.chromaRed(averaged) - reference.map { it[2] }.average()) <= 1)
    }

    private fun encode(srgb: Int): IntArray {
        val rgb = Bt709VideoColor.fromSrgb(srgb)
        return intArrayOf(Bt709VideoColor.luma(rgb), Bt709VideoColor.chromaBlue(rgb), Bt709VideoColor.chromaRed(rgb))
    }

    /** ITU-R BT.709-6 sections 1.2 and 3.2–3.4, evaluated directly in floating point. */
    private fun referenceYuv(srgb: Int): DoubleArray {
        val channels =
            listOf(16, 8, 0).map { shift ->
                val encoded = (srgb shr shift and 255) / 255.0
                val light = if (encoded <= 0.04045) encoded / 12.92 else ((encoded + 0.055) / 1.055).pow(2.4)
                if (light < 0.018) 4.5 * light else 1.099 * light.pow(0.45) - 0.099
            }
        val y = 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]
        return doubleArrayOf(16 + 219 * y, 128 + 224 * (channels[2] - y) / 1.8556, 128 + 224 * (channels[0] - y) / 1.5748)
    }

    private fun forEachColour(check: (Int) -> Unit) {
        val channels = listOf(0, 1, 4, 8, 10, 11, 16, 24, 32, 39, 40, 64, 95, 128, 160, 213, 220, 254, 255)
        for (red in channels) {
            for (green in channels) {
                for (blue in channels) check((red shl 16) or (green shl 8) or blue)
            }
        }
    }
}
