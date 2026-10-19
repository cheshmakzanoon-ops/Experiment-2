package com.artflow.studio.domain.model.brush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PressureMappingTest {
    private fun curve(vararg pairs: Pair<Float, Float>) = PressureMapping(pairs.map { PressureMapping.Point(it.first, it.second) })

    @Test fun irregularNodesAndDescendingOutputsArePreserved() {
        val response = curve(0f to 0.8f, 0.2f to 0.4f, 0.7f to 0.9f, 1f to 0.1f)
        assertEquals(0.8f, response.map(-1f), 0.00001f)
        assertEquals(0.6f, response.map(0.1f), 0.00001f)
        assertEquals(0.9f, response.map(0.7f), 0.00001f)
        assertEquals(0.5f, response.map(0.85f), 0.00001f)
        assertEquals(0.1f, response.map(2f), 0.00001f)
    }

    @Test fun malformedControlPointsFailClosed() {
        for (points in listOf(
            emptyList(),
            listOf(0f to 0f),
            listOf(0f to 0f, 0f to 1f),
            listOf(1f to 0f, 0f to 1f),
            listOf(0f to Float.NaN, 1f to 1f),
            listOf(Float.NEGATIVE_INFINITY to 0f, 1f to 1f),
            listOf(0f to 0f, 1f to 1.1f),
            (0..PressureMapping.MAX_POINTS).map { it / PressureMapping.MAX_POINTS.toFloat() to 0f },
        )) {
            assertThrows(IllegalArgumentException::class.java) { curve(*points.toTypedArray()) }
        }
        assertThrows(IllegalArgumentException::class.java) { curve(0f to 0f, 1f to 1f).map(Float.NaN) }
    }

    @Test fun overallMappingPrecedesIndependentSizeAndOpacityCurves() {
        val params =
            BrushParams(
                size = 100f,
                opacity = 0.8f,
                pressureToSize = 1f,
                pressureToOpacity = 1f,
                importedPressure =
                    ImportedPressure(
                        overall = curve(0f to 0f, 0.5f to 0.8f, 1f to 1f),
                        size = curve(0f to 0f, 0.8f to 0.2f, 1f to 1f),
                        opacity = curve(0f to 0f, 0.8f to 0.75f, 1f to 1f),
                    ),
            )
        assertEquals(0.8f, params.pressureResponse(0.5f), 0.00001f)
        assertEquals(20f, params.calculateEffectiveSize(0.5f), 0.00001f)
        assertEquals(0.6f, params.calculateEffectiveOpacity(0.5f), 0.00001f)
    }

    @Test fun negativePressureReversesTheResponseWithoutLosingBaseOpacity() {
        val params = BrushParams(size = 100f, opacity = 0.6f, pressureToSize = -0.75f, pressureToOpacity = -1f)
        assertEquals(100f, params.calculateEffectiveSize(0f), 0.00001f)
        assertEquals(25f, params.calculateEffectiveSize(1f), 0.00001f)
        assertEquals(0.6f, params.calculateEffectiveOpacity(0f), 0.00001f)
        assertEquals(0f, params.calculateEffectiveOpacity(1f), 0.00001f)
        assertEquals(0f, params.copy(opacity = 0f).calculateEffectiveOpacity(0f), 0f)
    }

    @Test fun negativeSpeedReducesSlowMarksAndPositiveSpeedStillReducesFastMarks() {
        val params = BrushParams(size = 20f, pressureToSize = 0f, pressureToOpacity = 0f, velocityToSize = -1f, velocityToOpacity = -1f)
        assertEquals(10f, params.calculateEffectiveSize(1f, 0f), 0f)
        assertEquals(20f, params.calculateEffectiveSize(1f, 10f), 0f)
        assertEquals(0.7f, params.calculateEffectiveOpacity(1f, 0f), 0.00001f)
        assertEquals(1f, params.calculateEffectiveOpacity(1f, 10f), 0f)
        val positive = params.copy(velocityToSize = 1f, velocityToOpacity = 1f)
        assertEquals(20f, positive.calculateEffectiveSize(1f, 0f), 0f)
        assertEquals(10f, positive.calculateEffectiveSize(1f, 10f), 0f)
        assertEquals(0.7f, positive.calculateEffectiveOpacity(1f, 10f), 0.00001f)
        assertTrue(params.calculateEffectiveOpacity(1f, 5f) in 0f..1f)
    }
}
