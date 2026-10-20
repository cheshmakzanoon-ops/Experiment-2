package com.artflow.studio.domain.model.brush

import org.junit.Assert.assertEquals
import org.junit.Test

class GrainBlendTest {
    @Test
    fun grainBlendsReshapeCoverage() {
        assertEquals(0.25f, GrainBlend.MULTIPLY.combine(0.5f, 0.5f), 1e-6f)
        assertEquals(0f, GrainBlend.SUBTRACT.combine(0.4f, 0.5f), 1e-6f)
        assertEquals(0.4f, GrainBlend.SUBTRACT.combine(0.9f, 0.5f), 1e-6f)
        assertEquals(1f, GrainBlend.HARD_MIX.combine(0.6f, 0.5f), 0f)
        assertEquals(0f, GrainBlend.HARD_MIX.combine(0.4f, 0.5f), 0f)
    }
}
