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

    @Test fun snapsOntoTheLinesItDraws() {
        // The grid is centred on (1024, 625), so the drawn lines sit at x = 24 + 100k and y = 25 + 100k.
        val drawn = PerspectiveGuide.guideLines(grid, 2048, 1250)
        val verticals = drawn.filter { it.startX == it.endX }.map { it.startX }.filter { it in 100f..900f }
        val horizontals = drawn.filter { it.startY == it.endY }.map { it.startY }.filter { it in 100f..900f }
        assertTrue(verticals.isNotEmpty() && horizontals.isNotEmpty())
        verticals.forEach { x -> assertEquals(x to 350f, PerspectiveGuide.snap(x + 3f, 350f, grid, 2048, 1250)) }
        horizontals.forEach { y -> assertEquals(350f to y, PerspectiveGuide.snap(350f, y + 3f, grid, 2048, 1250)) }
    }
}
