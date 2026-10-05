package com.artflow.studio.core.pixels

import com.artflow.studio.core.export.PsdCodec
import com.artflow.studio.domain.model.layer.BlendMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** The modes beyond the W3C set, checked channel by channel against their textbook formulas. */
class ExtendedBlendModesTest {
    private fun channel(
        base: Float,
        source: Float,
        mode: BlendMode,
    ): Float = BlendModes.blendChannels(base, base, base, source, source, source, mode).first

    private fun near(
        expected: Float,
        actual: Float,
    ) = assertEquals(expected, actual, 1e-5f)

    @Test
    fun linearModesAddAndSubtractWithClamping() {
        near(0.3f, channel(0.6f, 0.7f, BlendMode.LINEAR_BURN))
        near(0f, channel(0.2f, 0.3f, BlendMode.LINEAR_BURN))
        near(0.9f, channel(0.6f, 0.3f, BlendMode.ADD))
        near(1f, channel(0.8f, 0.7f, BlendMode.ADD))
        near(0.4f, channel(0.6f, 0.2f, BlendMode.SUBTRACT))
        near(0f, channel(0.2f, 0.6f, BlendMode.SUBTRACT))
        near(0.5f, channel(0.3f, 0.6f, BlendMode.DIVIDE))
        near(1f, channel(0.6f, 0.3f, BlendMode.DIVIDE))
        near(1f, channel(0.4f, 0f, BlendMode.DIVIDE))
        near(0f, channel(0f, 0f, BlendMode.DIVIDE))
    }

    @Test
    fun lightModesDependOnWhichHalfTheSourceIsIn() {
        // Vivid light burns by twice the source below middle grey and dodges above it.
        near(1f - (1f - 0.5f) / 0.6f, channel(0.5f, 0.3f, BlendMode.VIVID_LIGHT))
        near(0.5f / (1f - 0.4f), channel(0.5f, 0.7f, BlendMode.VIVID_LIGHT))
        near(0.5f + 2f * 0.6f - 1f, channel(0.5f, 0.6f, BlendMode.LINEAR_LIGHT))
        near(0f, channel(0.2f, 0.1f, BlendMode.LINEAR_LIGHT))
        near(0.4f, channel(0.7f, 0.2f, BlendMode.PIN_LIGHT))
        near(0.7f, channel(0.7f, 0.6f, BlendMode.PIN_LIGHT))
        near(0.8f, channel(0.3f, 0.9f, BlendMode.PIN_LIGHT))
        near(1f, channel(0.6f, 0.5f, BlendMode.HARD_MIX))
        near(0f, channel(0.4f, 0.5f, BlendMode.HARD_MIX))
    }

    @Test
    fun colourComparisonsKeepAWholeColour() {
        val dark = BlendModes.blendChannels(0.9f, 0.9f, 0.9f, 0.1f, 0.2f, 0.9f, BlendMode.DARKER_COLOR)
        assertEquals(Triple(0.1f, 0.2f, 0.9f), dark)
        val light = BlendModes.blendChannels(0.9f, 0.9f, 0.9f, 0.1f, 0.2f, 0.9f, BlendMode.LIGHTER_COLOR)
        assertEquals(Triple(0.9f, 0.9f, 0.9f), light)
    }

    @Test
    fun everyModeRoundTripsThroughPhotoshopKeys() {
        BlendMode.entries.filter { it != BlendMode.PASS_THROUGH }.forEach { mode ->
            assertEquals(mode, PsdCodec.blendModeFromKey(PsdCodec.blendModeKey(mode)))
        }
    }
}
