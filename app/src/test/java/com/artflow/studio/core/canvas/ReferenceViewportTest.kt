package com.artflow.studio.core.canvas

import org.junit.Test

class ReferenceViewportTest {
    @Test
    fun samplingRejectsLetterboxingAndHalfOpenEdges() = ReferenceViewportChecks.letterboxingAndEdges()

    @Test
    fun panZoomAndFitRemainBounded() = ReferenceViewportChecks.boundedPanAndFit()

    @Test
    fun zoomPreservesTheGestureCentroid() = ReferenceViewportChecks.zoomCentroidIsStable()

    @Test
    fun nonFiniteInputIsRejectedOrIgnored() = ReferenceViewportChecks.invalidInputCannotCorruptTheMapping()

    @Test
    fun resizingCannotLoseTheImage() = ReferenceViewportChecks.resizedViewportKeepsTheImageReachable()

    @Test
    fun samplingMatchesTheRenderingTransform() = ReferenceViewportChecks.renderedPixelCentresRoundTrip()
}
