package com.artflow.studio.core.canvas

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickPinchTest {
    @Test
    fun `a fast pinch closed past the threshold fits the canvas`() {
        assertTrue(QuickPinch.isQuickPinch(startScale = 2f, endScale = 1f, durationMillis = 180))
    }

    @Test
    fun `slow, small or opening pinches keep the view`() {
        assertFalse(QuickPinch.isQuickPinch(2f, 1f, 900))
        assertFalse(QuickPinch.isQuickPinch(2f, 1.8f, 150))
        assertFalse(QuickPinch.isQuickPinch(1f, 2f, 150))
        assertFalse(QuickPinch.isQuickPinch(0f, 1f, 150))
    }
}
