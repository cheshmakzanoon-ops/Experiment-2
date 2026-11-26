package com.artflow.studio.core.pixels

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionRegressionTest {
    @Test
    fun ellipseExcludesExactBoundarySamplesOnEveryEdge() {
        val selection = SelectionMask.ellipse(3, 3, 0.125f, 0.125f, 2.125f, 2.125f)
        assertArrayEquals(
            byteArrayOf(128.toByte(), 175.toByte(), 0, 175.toByte(), 239.toByte(), 0, 0, 0, 0),
            selection.coverage,
        )
        for (y in 0 until selection.height) {
            for (x in 0 until selection.width) {
                assertEquals("Circle coverage must be symmetric at $x,$y", selection.coverageAt(x, y), selection.coverageAt(y, x))
            }
        }
    }

    @Test
    fun polygonKeepsItsHalfOpenBoundarySamples() {
        val points = listOf(0.125f to 0.125f, 2.125f to 0.125f, 2.125f to 2.125f, 0.125f to 2.125f)
        val selection = SelectionMask.polygon(3, 3, points)
        assertArrayEquals(byteArrayOf(-1, -1, 0, -1, -1, 0, 0, 0, 0), selection.coverage)
        assertArrayEquals(selection.coverage, SelectionMask.polygon(3, 3, points.reversed()).coverage)
    }

    @Test
    fun expandingOpaqueSelectionUsesUnsignedCoverage() {
        val selection = SelectionMask.rectangle(9, 9, 3f, 3f, 6f, 6f)
        assertEquals(9, selection.selectedPixelCount())
        val expanded = selection.expanded(1)
        assertEquals(25, expanded.selectedPixelCount())
        assertTrue(expanded.coverageAt(2, 2) > 0)
        assertEquals(1, selection.expanded(-1).selectedPixelCount())
    }

    @Test(expected = IllegalArgumentException::class)
    fun overflowingSelectionSizeFailsBeforeAllocation() {
        SelectionMask(65536, 65536)
    }
}
