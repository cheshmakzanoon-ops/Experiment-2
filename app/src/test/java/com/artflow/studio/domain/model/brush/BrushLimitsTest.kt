package com.artflow.studio.domain.model.brush

import org.junit.Assert.assertEquals
import org.junit.Test

class BrushLimitsTest {
    @Test
    fun limitsDefaultToTheWholeRange() {
        val params = BrushParams()
        assertEquals(MIN_BRUSH_SIZE..MAX_BRUSH_SIZE, params.sizeLimits)
        assertEquals(MIN_BRUSH_OPACITY..1f, params.opacityLimits)
    }

    @Test
    fun limitsStayInRangeAndInOrder() {
        assertEquals(10f..80f, BrushParams(minSize = 10f, maxSize = 80f).sizeLimits)
        assertEquals(MIN_BRUSH_SIZE..MAX_BRUSH_SIZE, BrushParams(minSize = 90f, maxSize = 20f).sizeLimits)
        assertEquals(MIN_BRUSH_SIZE..MAX_BRUSH_SIZE, BrushParams(minSize = Float.NaN, maxSize = 9000f).sizeLimits)
        assertEquals(0.2f..0.6f, BrushParams(minOpacity = 0.2f, maxOpacity = 0.6f).opacityLimits)
    }

    @Test
    fun pressureToFlowThinsLightPresses() {
        val brush = BrushParams(flow = 0.8f, pressureToFlow = 1f)
        assertEquals(0.8f, brush.flowAt(1f), 1e-5f)
        assertEquals(0f, brush.flowAt(0f), 1e-5f)
        assertEquals(0.8f, BrushParams(flow = 0.8f).flowAt(0f), 1e-5f)
    }
}
