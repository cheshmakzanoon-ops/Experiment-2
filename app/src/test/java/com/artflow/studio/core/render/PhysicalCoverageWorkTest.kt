package com.artflow.studio.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Grain and dual coverage are expensive and cannot contribute outside the physical dab. */
class PhysicalCoverageWorkTest {
    @Test
    fun wetnessSamplesOnlyTheCircleAndKeepsItsCoverageScaledMaterial() {
        val plane = PhysicalPaintPlane(32, 32)
        var sampled = 0
        PhysicalPaintEngine.depositWetness(plane, 15.5f, 15.5f, 10f, 0.9f, 0.7f) { x, y ->
            assertInsideCircle(x, y)
            sampled++
            0.5f
        }
        assertEquals(circlePixels(), sampled)
        assertEquals(0.45f, plane.wetnessAt(15, 15), 0f)
        assertEquals(0.35f, plane.pigmentAt(15, 15), 0f)
        assertEquals(0f, plane.wetnessAt(5, 5), 0f)
        assertEquals(0f, plane.pigmentAt(5, 5), 0f)
    }

    @Test
    fun impastoSamplesOnlyTheCircleAndKeepsExistingMaterialAtItsBoundary() {
        val plane = PhysicalPaintPlane(32, 32)
        // The original boundary rule includes distance == radius. Preserve its accepted update.
        plane.setCell(25, 15, wetness = 0.4f, impasto = 0.6f, pigment = 0.2f)
        var sampled = 0
        PhysicalPaintEngine.depositImpasto(plane, 15.5f, 15.5f, 10f, 0.8f, 1f, 0f) { x, y ->
            assertInsideCircle(x, y)
            sampled++
            0.5f
        }
        assertEquals(circlePixels(), sampled)
        assertEquals(0.8f * 0.55f * 0.85f * 0.5f, plane.impastoAt(15, 15), 0f)
        assertEquals(0.6f * (1f - 0.35f * 0.5f), plane.impastoAt(25, 15), 0.0000001f)
        assertEquals(0.4f, plane.wetnessAt(25, 15), 0f)
        assertEquals(0.2f, plane.pigmentAt(25, 15), 0f)
        assertEquals(0f, plane.impastoAt(5, 5), 0f)
    }

    private fun assertInsideCircle(
        x: Int,
        y: Int,
    ) {
        val dx = x - 15
        val dy = y - 15
        assertTrue("Coverage cannot affect the dab at ($x, $y)", dx * dx + dy * dy <= 100)
    }

    private fun circlePixels(): Int =
        (0 until 32).sumOf { y ->
            (0 until 32).count { x -> (x - 15) * (x - 15) + (y - 15) * (y - 15) <= 100 }
        }
}
