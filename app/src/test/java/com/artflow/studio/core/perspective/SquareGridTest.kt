package com.artflow.studio.core.perspective

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SquareGridTest {
    private val grid = PerspectiveGuide.Settings(type = PerspectiveGuide.GuideType.GRID, gridSpacing = 100, snapRadius = 24f)

    @Test fun snapsToNearestLine() {
        assertEquals(100f to 37f, PerspectiveGuide.snap(108f, 37f, grid, 1000, 1000))
        assertEquals(42f to 200f, PerspectiveGuide.snap(42f, 190f, grid, 1000, 1000))
        assertEquals(150f to 150f, PerspectiveGuide.snap(150f, 150f, grid, 1000, 1000))
    }

    @Test fun drawsBothAxes() {
        val lines = PerspectiveGuide.guideLines(grid, 1000, 600)
        assertTrue(lines.any { it.startX == it.endX })
        assertTrue(lines.any { it.startY == it.endY })
    }
}
