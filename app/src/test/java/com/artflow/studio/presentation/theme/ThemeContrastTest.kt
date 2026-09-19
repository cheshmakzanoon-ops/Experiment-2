package com.artflow.studio.presentation.theme

import com.artflow.studio.presentation.ui.theme.ThemeContrast
import org.junit.Assert.*
import org.junit.Test

class ThemeContrastTest {
    @Test fun referenceLuminanceRatiosAreAccurateAndSymmetric() {
        assertEquals(21.0, ThemeContrast.ratio(-1, 0xFF000000.toInt()), 0.0000001)
        assertEquals(5.252, ThemeContrast.ratio(0xFFFF0000.toInt(), 0xFF000000.toInt()), 0.0000001)
        assertEquals(15.304, ThemeContrast.ratio(0xFF00FF00.toInt(), 0xFF000000.toInt()), 0.0000001)
        assertEquals(2.444, ThemeContrast.ratio(0xFF0000FF.toInt(), 0xFF000000.toInt()), 0.0000001)
        val a = 0xFFB4553D.toInt()
        val b = 0xFF3D3D3D.toInt()
        assertEquals(ThemeContrast.ratio(a, b), ThemeContrast.ratio(b, a), 0.0)
        assertEquals(1.0, ThemeContrast.ratio(a, a), 0.0)
    }

    @Test fun passingSeedsAreUnchangedAndNearMissesDoNotPassByRounding() {
        val ink = 0xFF2B3A55.toInt()
        assertEquals(ink, ThemeContrast.fit(ink, -1, 4.5))
        val nearMiss = 0xFF777777.toInt()
        assertTrue(ThemeContrast.ratio(nearMiss, -1) < 4.5)
        val corrected = ThemeContrast.fit(nearMiss, -1, 4.5)
        assertNotEquals(nearMiss, corrected)
        assertTrue(ThemeContrast.ratio(corrected, -1) >= 4.5)
    }

    @Test fun allPaletteSeedsCanReachNormalAndEnhancedTargetsOnBothStudioSurfaces() {
        val seeds = listOf(0xFF2B3A55, 0xFF4C5FD5, 0xFFB4553D, 0xFF2F6F4E, 0xFF7A3E68, 0xFFB07D2B, 0xFF5C7CFA, 0xFFF44336)
        for (seed in seeds) {
            checkTargets(seed.toInt(), 0xFF3D3D3D.toInt(), 4.5)
            checkTargets(seed.toInt(), 0xFF303036.toInt(), 7.0)
            checkTargets(seed.toInt(), 0xFFE7E7EC.toInt(), 4.5)
            checkTargets(seed.toInt(), 0xFFE7E7EC.toInt(), 7.0)
        }
    }

    private fun checkTargets(
        seed: Int,
        background: Int,
        minimum: Double,
    ) {
        val fitted = ThemeContrast.fit(seed, background, minimum)
        assertTrue(ThemeContrast.ratio(fitted, background) >= minimum)
        assertTrue(ThemeContrast.ratio(ThemeContrast.contentOn(fitted), fitted) >= minimum)
        assertEquals(255, fitted ushr 24)
    }

    @Test fun mixturesAreOpaqueAndHaveExactEndpoints() {
        val ink = 0xFF2B3A55.toInt()
        assertEquals(ink, ThemeContrast.mix(ink, -1, 0.0))
        assertEquals(-1, ThemeContrast.mix(ink, -1, 1.0))
        assertEquals(0xFF808080.toInt(), ThemeContrast.mix(0xFF000000.toInt(), -1, 0.5))
        assertEquals(-1, ThemeContrast.contentOn(0xFF000000.toInt()))
        assertEquals(0xFF000000.toInt(), ThemeContrast.contentOn(-1))
    }

    @Test fun invalidOrTranslucentInputsAreRejectedInsteadOfProducingFalseConfidence() {
        assertThrows(IllegalArgumentException::class.java) { ThemeContrast.ratio(0x00777777, -1) }
        assertThrows(IllegalArgumentException::class.java) { ThemeContrast.fit(-1, 0x88777777.toInt(), 4.5) }
        assertThrows(IllegalArgumentException::class.java) { ThemeContrast.fit(-1, 0xFF777777.toInt(), 21.0) }
        assertThrows(IllegalArgumentException::class.java) { ThemeContrast.fit(-1, 0xFF000000.toInt(), Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { ThemeContrast.mix(-1, 0xFF000000.toInt(), 1.1) }
    }
}
