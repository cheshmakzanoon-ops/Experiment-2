package com.artflow.studio.core.pixels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionRegressionTest {
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
