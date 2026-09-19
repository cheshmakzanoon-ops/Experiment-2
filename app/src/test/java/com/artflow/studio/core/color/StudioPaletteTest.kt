package com.artflow.studio.core.color

import org.junit.Test

class StudioPaletteTest {
    @Test
    fun knownValuesUseSrgbLuminance() = StudioPaletteChecks.knownSrgbValues()

    @Test
    fun allAccentThemePairsReachTheirTargets() = StudioPaletteChecks.everyAccentAndSurfaceMeetsItsTextTarget()

    @Test
    fun neutralSurfacesAreOpaqueAndOrdered() = StudioPaletteChecks.surfacesStayOpaqueNeutralAndOrdered()

    @Test
    fun validSeedsAndEndpointsArePreserved() = StudioPaletteChecks.validSeedsAreRetainedAndMixingIsExact()

    @Test
    fun invalidOrImpossibleContrastFailsExplicitly() = StudioPaletteChecks.invalidAndImpossibleRequestsAreRejected()

    @Test
    fun generatedColoursConvergeDeterministically() = StudioPaletteChecks.generatedSeedsConvergeWithoutLosingTheContract()
}
