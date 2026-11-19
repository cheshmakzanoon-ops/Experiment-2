package com.artflow.studio.data.repository.canvas

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasRasterBudgetTest {
    @Test
    fun elevenTwoKPlanesExceedTheReopenBudgetOfA512MiBHeap() {
        val budget = CanvasRasterBudget(512L * 1024 * 1024)
        assertTrue(budget.allows(2048, 2048, 10))
        assertFalse(budget.allows(2048, 2048, 11))
    }

    @Test
    fun exactBoundaryIncludesMasksAndRejectsOverflowWithoutMultiplyingPlaneBytes() {
        val budget = CanvasRasterBudget(64L * 64 * 4 * 3 * 4)
        assertTrue(budget.allows(64, 64, 4))
        assertFalse(budget.allows(64, 64, 5))
        assertFalse(budget.allows(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(budget.allows(64, 64, -1))
        assertTrue(budget.allows(64, 64, 0))
    }
}
