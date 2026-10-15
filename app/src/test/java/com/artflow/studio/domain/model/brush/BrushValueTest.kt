package com.artflow.studio.domain.model.brush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrushValueTest {
    @Test fun reversedSpeedIsEnteredAsASignedPercentage() {
        assertEquals(-0.5f, BrushValue.parse("-50", -1f..1f, displayScale = 100f)!!, 1e-6f)
        assertEquals(0.25f, BrushValue.parse("25", -1f..1f, displayScale = 100f)!!, 1e-6f)
        assertNull(BrushValue.parse("-150", -1f..1f, displayScale = 100f))
    }

    @Test fun everyCountTheCodecStoresCanBeEntered() {
        assertEquals(32f, BrushValue.parse("32", 1f..32f, integer = true)!!, 0f)
        assertEquals(16f, BrushValue.parse("16", 1f..32f, integer = true)!!, 0f)
        assertNull(BrushValue.parse("33", 1f..32f, integer = true))
    }
}
