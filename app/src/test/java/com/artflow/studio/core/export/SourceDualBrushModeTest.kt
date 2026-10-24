package com.artflow.studio.core.export

import org.junit.Assert.assertEquals
import org.junit.Test

/** Native archive precedence, independent of the still-approximate local combine equations. */
class SourceDualBrushModeTest {
    private fun read(fields: Map<String, Any?>): ProcreateBrush.Settings = ProcreateBrush.read(KeyedArchiveBuilder().encode(fields))

    @Test
    fun extendedModeOverridesTheLegacyMode() {
        assertEquals(34, read(mapOf("dualBlendMode" to 3, "dualBlendModeExtended" to 34)).dualBlendMode)
    }

    @Test
    fun explicitExtendedZeroOverridesANonzeroLegacyMode() {
        assertEquals(0, read(mapOf("dualBlendMode" to 7, "dualBlendModeExtended" to 0)).dualBlendMode)
    }

    @Test
    fun missingExtendedModeFallsBackToTheLegacyMode() {
        assertEquals(7, read(mapOf("dualBlendMode" to 7)).dualBlendMode)
    }

    @Test
    fun missingBothModeFieldsUsesTheNativeZeroDefault() {
        assertEquals(0, read(emptyMap()).dualBlendMode)
    }
}
