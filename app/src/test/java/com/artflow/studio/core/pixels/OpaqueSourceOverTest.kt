package com.artflow.studio.core.pixels

import com.artflow.studio.domain.model.layer.BlendMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

/** Exact compatibility with the published 8d8d0e6 Float compositor, including hidden RGB. */
class OpaqueSourceOverTest {
    @Test
    fun everyOpaqueAlphaAndChannelCombinationMatchesThePublishedFloatEvaluation() {
        for (alpha in 0..255) {
            val amount = alpha / 255f
            assertEquals(1f.toRawBits(), (amount + 1f * (1f - amount)).toRawBits())
            for (backdropChannel in 0..255) {
                val backdrop = OPAQUE or grey(backdropChannel)
                for (sourceChannel in 0..255) {
                    val source = (alpha shl 24) or grey(sourceChannel)
                    assertPixel(legacySourceOver(backdrop, source), BlendModes.sourceOver(backdrop, source), backdrop, source)
                }
            }
        }
    }

    @Test
    fun allAlphaPairsAndMixedChannelsRetainGeneralAndSourceAtopResults() {
        for (backdropAlpha in 0..255) {
            for (sourceAlpha in 0..255) {
                for (index in COLOURS.indices) {
                    val backdrop = (backdropAlpha shl 24) or COLOURS[index]
                    val source = (sourceAlpha shl 24) or COLOURS[(index + sourceAlpha + 1) % COLOURS.size]
                    assertPixel(legacySourceOver(backdrop, source), BlendModes.sourceOver(backdrop, source), backdrop, source)
                    val atop = legacySourceAtop(backdrop, source)
                    assertPixel(atop, BlendModes.sourceAtop(backdrop, source), backdrop, source)
                }
            }
        }
    }

    @Test
    fun normalAndPassThroughCompositeKeepTheSourceAndPublishedDestinationPixels() {
        val width = 257
        val height = 3
        val input = IntArray(width * height) { index -> ((index % 256) shl 24) or colour(index) }
        val backdrop =
            IntArray(input.size) { index ->
                val alpha = if (index % 3 == 0) 255 else (index * 31) and 255
                (alpha shl 24) or colour(index + 97)
            }
        for (mode in listOf(BlendMode.NORMAL, BlendMode.PASS_THROUGH)) {
            for (opacity in listOf(-0.5f, 0f, 0.37f, 1f, 2f)) {
                val source = PixelBuffer(width, height, input.copyOf())
                val destination = PixelBuffer(width, height, backdrop.copyOf())
                val expected =
                    IntArray(input.size) { index ->
                        legacySourceOver(backdrop[index], legacyScaleAlpha(input[index], opacity.coerceIn(0f, 1f)))
                    }
                BlendModes.composite(destination, source, mode, opacity)
                assertArrayEquals("$mode opacity $opacity must preserve the published pixels", expected, destination.pixels)
                assertArrayEquals("Compositing must not modify its supplied source", input, source.pixels)
            }
        }
    }

    @Test
    fun clippedCompositeRetainsEveryBackdropAlphaAndSourceOwnership() {
        val input = IntArray(256) { index -> (((index * 73) and 255) shl 24) or colour(index) }
        val backdrop = IntArray(256) { index -> (index shl 24) or colour(index + 97) }
        for (mode in listOf(BlendMode.NORMAL, BlendMode.PASS_THROUGH)) {
            for (opacity in listOf(0f, 0.3333f, 1f)) {
                val source = PixelBuffer(16, 16, input.copyOf())
                val destination = PixelBuffer(16, 16, backdrop.copyOf())
                val expected =
                    IntArray(input.size) { index ->
                        if (index == 0) {
                            backdrop[index]
                        } else {
                            val mixed = legacySourceOver(backdrop[index] or OPAQUE, legacyScaleAlpha(input[index], opacity))
                            (mixed and RGB) or (index shl 24)
                        }
                    }
                BlendModes.compositeClipped(destination, source, mode, opacity)
                assertArrayEquals("$mode clipped opacity $opacity must preserve every byte", expected, destination.pixels)
                assertArrayEquals("Clipped compositing must not modify its supplied source", input, source.pixels)
            }
        }
    }

    private fun assertPixel(
        expected: Int,
        actual: Int,
        backdrop: Int,
        source: Int,
    ) {
        if (expected != actual) {
            throw AssertionError("Source-over changed: backdrop=$backdrop source=$source expected=$expected actual=$actual")
        }
    }

    private fun grey(channel: Int): Int = (channel shl 16) or (channel shl 8) or channel

    private fun colour(index: Int): Int =
        (((index * 29 + 17) and 255) shl 16) or
            (((index * 47 + 93) and 255) shl 8) or ((index * 73 + 151) and 255)

    private fun legacyScaleAlpha(
        argb: Int,
        factor: Float,
    ): Int = (argb and RGB) or ((((argb ushr 24) * factor).roundToInt().coerceIn(0, 255)) shl 24)

    private fun legacySourceAtop(
        backdrop: Int,
        source: Int,
    ): Int {
        val alpha = backdrop ushr 24
        if (alpha == 0) return backdrop
        return (legacySourceOver(backdrop or OPAQUE, source) and RGB) or (alpha shl 24)
    }

    /** Frozen general formula from 8d8d0e6, deliberately without an opaque-backdrop shortcut. */
    private fun legacySourceOver(
        backdrop: Int,
        source: Int,
    ): Int {
        val asource = (source ushr 24) / 255f
        if (asource >= 1f) return source
        if (asource <= 0f) return backdrop
        val abackdrop = (backdrop ushr 24) / 255f
        val outAlpha = asource + abackdrop * (1f - asource)
        if (outAlpha <= 1e-6f) return 0
        val inverse = 1f / outAlpha
        val red = (((source ushr 16) and 255) * asource + ((backdrop ushr 16) and 255) * abackdrop * (1f - asource)) * inverse
        val green = (((source ushr 8) and 255) * asource + ((backdrop ushr 8) and 255) * abackdrop * (1f - asource)) * inverse
        val blue = ((source and 255) * asource + (backdrop and 255) * abackdrop * (1f - asource)) * inverse
        return (outAlpha * 255f).roundToInt().coerceIn(0, 255).shl(24) or
            red.roundToInt().coerceIn(0, 255).shl(16) or
            green.roundToInt().coerceIn(0, 255).shl(8) or
            blue.roundToInt().coerceIn(0, 255)
    }

    private companion object {
        val OPAQUE = 0xFF000000.toInt()
        const val RGB = 0x00FFFFFF
        val COLOURS = intArrayOf(0x000000, 0xFFFFFF, 0x010080, 0x7F80FE, 0xD92361, 0x3C5AA5)
    }
}
