package com.artflow.studio.presentation.ui.components.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class SidebarSliderTest {
    @Test
    fun sizeSliderSpansTheBrushLimits() {
        val range = 10f..90f
        assertEquals(10f, sliderToSize(0f, range), 1e-4f)
        assertEquals(90f, sliderToSize(1f, range), 1e-4f)
        assertEquals(0.5f, sizeToSlider(sliderToSize(0.5f, range), range), 1e-4f)
    }

    @Test
    fun opacitySliderSpansTheBrushLimits() {
        val range = 0.2f..0.6f
        assertEquals(0.4f, fromSlider(0.5f, range), 1e-4f)
        assertEquals(0.5f, toSlider(0.4f, range), 1e-4f)
        assertEquals(1f, toSlider(0.9f, range), 0f)
    }
}
