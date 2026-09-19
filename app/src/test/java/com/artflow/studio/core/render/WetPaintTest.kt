package com.artflow.studio.core.render

import org.junit.Test

class WetPaintTest {
    @Test
    fun knownMixturesPreserveInkAlpha() = WetPaintChecks.knownPigmentMixtures()

    @Test
    fun hiddenRgbAndEmptyEdgesCannotContaminateInk() = WetPaintChecks.transparentColourDoesNotContaminateTheBrush()

    @Test
    fun invalidParametersCannotMutateArtwork() = WetPaintChecks.invalidInputIsRejectedBeforeMutation()

    @Test
    fun wetPaintIsOptInForNewStrokesNotLegacyReplay() = WetPaintChecks.newStrokesMixButLegacyReplayStaysUnchanged()

    @Test
    fun coverageContractsRemainIntact() = WetPaintChecks.opacitySelectionAlphaLockAndEraserRetainCoverage()

    @Test
    fun generatedSamplesRemainDeterministic() = WetPaintChecks.pickupIsDeterministicBoundedAndKeepsInkAlpha()

    @Test
    fun practiceUsesRecordedInkColoursAndCurrentMixSettings() = WetPaintChecks.practiceRetainsIndependentColoursAndRerendersWetMarks()
}
