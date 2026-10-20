package com.artflow.studio.core.perspective

import org.junit.Assert.assertEquals
import org.junit.Test

class VanishingPointDragTest {
    @Test
    fun aHorizonPointCarriesTheHorizonAndLandsWhereItWasDragged() {
        val two = PerspectiveGuide.Settings(type = PerspectiveGuide.GuideType.TWO_POINT)
        val moved = PerspectiveGuide.dragPoint(two, 1, 750f, 300f, 1000, 1000)
        assertEquals(750f to 300f, PerspectiveGuide.pointPosition(moved, 1, 1000, 1000))
        // The other horizon point follows the horizon.
        assertEquals(300f, PerspectiveGuide.pointPosition(moved, 0, 1000, 1000).second, 1e-3f)
    }

    @Test
    fun theThirdPointMovesWithoutTheHorizon() {
        val three = PerspectiveGuide.Settings(type = PerspectiveGuide.GuideType.THREE_POINT)
        val moved = PerspectiveGuide.dragPoint(three, 2, 500f, 900f, 1000, 1000)
        assertEquals(500f to 900f, PerspectiveGuide.pointPosition(moved, 2, 1000, 1000))
        assertEquals(three.horizonY, moved.horizonY, 0f)
    }
}
