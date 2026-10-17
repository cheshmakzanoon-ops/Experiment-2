package com.artflow.studio.domain.model.brush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saved brush libraries keep the wet mix controls, and libraries written before they existed still load. */
class WetMixPersistenceTest {
    private val wet =
        BrushParams(
            size = 14f,
            wetMix = 0.6f,
            wetDilution = 0.3f,
            wetCharge = 0.4f,
            wetAttack = 0.2f,
            wetPull = 0.7f,
            wetBlur = 0.5f,
            wetBlurJitter = 0.25f,
        )

    @Test
    fun wetMixControlsRoundTripThroughTheLibrary() {
        val encoded = BrushLibraryCodec.encode(listOf(SavedBrush("wet-1", "Wet", wet)))
        assertEquals(wet, BrushLibraryCodec.decode(encoded).single().parameters)
    }

    @Test
    fun libraryWrittenBeforeTheControlsDecodesWithTheirDefaults() {
        val encoded = BrushLibraryCodec.encode(listOf(SavedBrush("old-1", "Old", BrushParams(size = 9f, wetMix = 0.2f))))
        val withoutControls =
            encoded.replace(Regex(""",\s*"wet(Dilution|Charge|Attack|Pull|Blur|BlurJitter)"\s*:\s*[-0-9.Ee+]+"""), "")
        assertFalse("The fixture must not carry the new keys", withoutControls.contains("wetDilution"))
        val decoded = BrushLibraryCodec.decode(withoutControls).single().parameters
        assertEquals(BrushParams(size = 9f, wetMix = 0.2f), decoded)
        assertEquals(1f, decoded.wetCharge, 0f)
        assertEquals(1f, decoded.wetAttack, 0f)
    }

    @Test
    fun outOfRangeWetControlsAreRejectedLikeTheOtherDynamics() {
        val invalid = listOf(SavedBrush("bad-1", "Bad", BrushParams(wetPull = 1.5f)))
        val failure = runCatching { BrushLibraryCodec.encode(invalid) }.exceptionOrNull()
        assertTrue("A pull above one is not a stored value", failure is IllegalArgumentException)
    }
}
