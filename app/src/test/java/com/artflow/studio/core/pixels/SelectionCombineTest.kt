package com.artflow.studio.core.pixels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Add, Remove and Intersect against Procreate's "nothing is selected" state. The old canvas-view
 * implementation returned the new mask for every mode when the existing selection was null, so
 * Remove and Intersect silently behaved like Add.
 */
class SelectionCombineTest {
    private fun half(): SelectionMask = SelectionMask(2, 1).also { it.coverage[0] = 255.toByte() }

    private fun otherHalf(): SelectionMask = SelectionMask(2, 1).also { it.coverage[1] = 255.toByte() }

    @Test
    fun replaceWithNothingSelectedUsesTheNewMask() {
        val combined = SelectionMask.combine(null, half(), SelectionCombineMode.REPLACE)
        assertEquals(1, combined.selectedPixelCount())
        assertEquals(255, combined.coverageAt(0, 0))
    }

    @Test
    fun addWithNothingSelectedUsesTheNewMask() {
        val combined = SelectionMask.combine(null, half(), SelectionCombineMode.ADD)
        assertEquals(1, combined.selectedPixelCount())
        assertEquals(255, combined.coverageAt(0, 0))
    }

    @Test
    fun removeWithNothingSelectedSelectsNothing() {
        val combined = SelectionMask.combine(null, half(), SelectionCombineMode.SUBTRACT)
        assertEquals(0, combined.selectedPixelCount())
        assertEquals(2, combined.width)
    }

    @Test
    fun intersectWithNothingSelectedSelectsNothing() {
        val combined = SelectionMask.combine(null, half(), SelectionCombineMode.INTERSECT)
        assertEquals(0, combined.selectedPixelCount())
    }

    @Test
    fun addUnionsCoverageAndRemoveSubtractsIt() {
        val added = SelectionMask.combine(half(), otherHalf(), SelectionCombineMode.ADD)
        assertTrue((0 until 2).all { added.coverageAt(it, 0) == 255 })
        val removed = SelectionMask.combine(added, half(), SelectionCombineMode.SUBTRACT)
        assertEquals(0, removed.coverageAt(0, 0))
        assertEquals(255, removed.coverageAt(1, 0))
    }

    @Test
    fun aMismatchedMaskReplacesForAddButSelectsNothingForRemove() {
        val existing = SelectionMask(4, 4).apply { selectAll() }
        val smaller = SelectionMask(2, 1).also { it.coverage[0] = 255.toByte() }
        assertEquals(1, SelectionMask.combine(existing, smaller, SelectionCombineMode.ADD).selectedPixelCount())
        assertEquals(0, SelectionMask.combine(existing, smaller, SelectionCombineMode.SUBTRACT).selectedPixelCount())
    }

    @Test
    fun removedCoverageKeepsAntiAliasedEdges() {
        val existing = SelectionMask(1, 1).also { it.coverage[0] = 200.toByte() }
        val subtracted = SelectionMask(1, 1).also { it.coverage[0] = 55.toByte() }
        assertEquals(145, SelectionMask.combine(existing, subtracted, SelectionCombineMode.SUBTRACT).coverageAt(0, 0))
    }

    @Test
    fun cancellationIsObserved() {
        val mask = SelectionMask(64, 64)
        var calls = 0
        val combined =
            SelectionMask.combine(mask, mask, SelectionCombineMode.ADD) {
                calls++
            }
        assertEquals(0, combined.selectedPixelCount())
        assertTrue("checkActive must be consulted", calls > 0)
    }
}
