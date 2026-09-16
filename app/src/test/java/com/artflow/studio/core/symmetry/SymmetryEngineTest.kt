package com.artflow.studio.core.symmetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Symmetry replication maths (Phase 33).
 *
 * Symmetry works by replicating brush motion, so the correctness that matters is "every mirrored
 * instance is the exact reflection of the sample" — which is what these tests pin down.
 */
class SymmetryEngineTest {
    private val width = 200
    private val height = 100

    private fun vertical(secondary: Boolean = false) =
        SymmetryEngine.Settings(
            type = SymmetryEngine.SymmetryType.VERTICAL,
            secondaryAxis = secondary,
        )

    @Test
    fun `no symmetry produces a single unchanged instance`() {
        val settings = SymmetryEngine.Settings()

        assertFalse(settings.isActive())
        assertEquals(1, settings.instanceCount())

        val instances = SymmetryEngine.instances(40f, 20f, width, height, settings, brushRotation = 15f)
        assertEquals(1, instances.size)
        assertEquals(40f, instances.first().x, 0.001f)
        assertEquals(20f, instances.first().y, 0.001f)
        assertEquals(15f, instances.first().rotationDegrees, 0.001f)
        assertTrue(SymmetryEngine.guideLines(width, height, settings).isEmpty())
    }

    @Test
    fun `vertical symmetry mirrors across the centre axis`() {
        val instances = SymmetryEngine.instances(40f, 20f, width, height, vertical())

        assertEquals(2, instances.size)
        assertEquals(40f, instances[0].x, 0.001f)
        // axisX = 0.5 * 200 = 100, so the reflection of 40 is 160.
        assertEquals(160f, instances[1].x, 0.001f)
        assertEquals(20f, instances[1].y, 0.001f)
    }

    @Test
    fun `horizontal symmetry mirrors across the centre axis`() {
        val instances =
            SymmetryEngine.instances(
                40f,
                20f,
                width,
                height,
                SymmetryEngine.Settings(type = SymmetryEngine.SymmetryType.HORIZONTAL),
            )

        assertEquals(2, instances.size)
        assertEquals(40f, instances[1].x, 0.001f)
        // axisY = 0.5 * 100 = 50, so the reflection of 20 is 80.
        assertEquals(80f, instances[1].y, 0.001f)
    }

    @Test
    fun `mirrored instances mirror the brush rotation too`() {
        val instances = SymmetryEngine.instances(40f, 20f, width, height, vertical(), brushRotation = 30f)

        assertEquals(30f, instances[0].rotationDegrees, 0.001f)
        assertEquals(-30f, instances[1].rotationDegrees, 0.001f)
    }

    @Test
    fun `quadrant symmetry produces all four reflections`() {
        val instances =
            SymmetryEngine.instances(
                40f,
                20f,
                width,
                height,
                SymmetryEngine.Settings(type = SymmetryEngine.SymmetryType.QUADRANT),
            )

        assertEquals(4, instances.size)
        val points = instances.map { it.x to it.y }
        assertTrue(points.contains(40f to 20f))
        assertTrue(points.contains(160f to 20f))
        assertTrue(points.contains(40f to 80f))
        assertTrue(points.contains(160f to 80f))
    }

    @Test
    fun `a secondary axis doubles the vertical symmetry`() {
        val settings = vertical(secondary = true)
        assertEquals(4, settings.instanceCount())
        assertEquals(4, SymmetryEngine.instances(40f, 20f, width, height, settings).size)
    }

    @Test
    fun `radial symmetry rotates around the canvas centre without changing the radius`() {
        val settings =
            SymmetryEngine.Settings(
                type = SymmetryEngine.SymmetryType.RADIAL,
                radialCount = 8,
            )

        val instances = SymmetryEngine.instances(120f, 50f, width, height, settings)
        assertEquals(8, instances.size)

        val centreX = width / 2f
        val centreY = height / 2f
        val expectedRadius = kotlin.math.hypot(120f - centreX, 50f - centreY)
        instances.forEach { instance ->
            val radius = kotlin.math.hypot(instance.x - centreX, instance.y - centreY)
            assertEquals(expectedRadius, radius, 0.01f)
        }
    }

    @Test
    fun `radial count is clamped to the supported range`() {
        assertEquals(
            2,
            SymmetryEngine
                .Settings(
                    type = SymmetryEngine.SymmetryType.RADIAL,
                    radialCount = 1,
                ).instanceCount(),
        )
        assertEquals(
            32,
            SymmetryEngine
                .Settings(
                    type = SymmetryEngine.SymmetryType.RADIAL,
                    radialCount = 500,
                ).instanceCount(),
        )
        assertEquals(2..32, SymmetryEngine.RADIAL_RANGE)
    }

    @Test
    fun `guide lines match the active symmetry`() {
        assertEquals(1, SymmetryEngine.guideLines(width, height, vertical()).size)
        assertEquals(2, SymmetryEngine.guideLines(width, height, vertical(secondary = true)).size)
        assertEquals(
            2,
            SymmetryEngine
                .guideLines(
                    width,
                    height,
                    SymmetryEngine.Settings(type = SymmetryEngine.SymmetryType.QUADRANT),
                ).size,
        )
        assertEquals(
            6,
            SymmetryEngine
                .guideLines(
                    width,
                    height,
                    SymmetryEngine.Settings(type = SymmetryEngine.SymmetryType.RADIAL, radialCount = 6),
                ).size,
        )
    }

    @Test
    fun `vertical guide sits on the configured axis`() {
        val line = SymmetryEngine.guideLines(width, height, vertical()).first()

        assertEquals(100f, line.startX, 0.001f)
        assertEquals(100f, line.endX, 0.001f)
        assertEquals(height.toFloat(), line.endY, 0.001f)
        assertTrue(line.isPrimary)
    }

    @Test
    fun `offsets move the guide axis`() {
        val settings =
            SymmetryEngine.Settings(
                type = SymmetryEngine.SymmetryType.VERTICAL,
                offsetX = 0.1f,
            )

        assertEquals(120f, SymmetryEngine.guideLines(width, height, settings).first().startX, 0.001f)
        // The reflection follows the shifted axis: 2 * 120 - 40 = 200.
        assertEquals(200f, SymmetryEngine.instances(40f, 20f, width, height, settings)[1].x, 0.001f)
    }

    @Test
    fun `snapToAxis pulls nearby points onto the guide and leaves the rest`() {
        val settings = vertical()

        assertEquals(100f, SymmetryEngine.snapToAxis(104f, 20f, width, height, settings, tolerance = 5f).first, 0.001f)
        assertEquals(110f, SymmetryEngine.snapToAxis(110f, 20f, width, height, settings, tolerance = 5f).first, 0.001f)
        // Vertical symmetry does not constrain y.
        assertEquals(20f, SymmetryEngine.snapToAxis(100f, 20f, width, height, settings, tolerance = 5f).second, 0.001f)
    }

    @Test
    fun `snapToAxis is inert without symmetry`() {
        val result = SymmetryEngine.snapToAxis(3f, 4f, width, height, SymmetryEngine.Settings(), tolerance = 100f)
        assertEquals(3f to 4f, result)
    }

    @Test
    fun `sanitize clamps every setting into range`() {
        val sanitized =
            SymmetryEngine.sanitize(
                SymmetryEngine.Settings(
                    type = SymmetryEngine.SymmetryType.RADIAL,
                    centreX = 5f,
                    centreY = -5f,
                    offsetX = -9f,
                    offsetY = 9f,
                    radialCount = 900,
                    radialAngleDegrees = 400f,
                ),
            )

        assertEquals(2f, sanitized.centreX, 0.001f)
        assertEquals(-1f, sanitized.centreY, 0.001f)
        assertEquals(-1f, sanitized.offsetX, 0.001f)
        assertEquals(1f, sanitized.offsetY, 0.001f)
        assertEquals(32, sanitized.radialCount)
        assertEquals(40f, sanitized.radialAngleDegrees, 0.001f)
    }

    @Test
    fun `every preset is already sanitized`() {
        assertTrue(SymmetryEngine.PRESETS.isNotEmpty())
        SymmetryEngine.PRESETS.forEach { preset ->
            assertEquals(preset.name, preset.settings, SymmetryEngine.sanitize(preset.settings))
        }
        assertEquals(
            6,
            SymmetryEngine.PRESETS
                .first { it.name == "Mandala 6" }
                .settings.radialCount,
        )
    }

    @Test
    fun `instances far outside the canvas are reported as skippable`() {
        assertTrue(SymmetryEngine.isOutsideCanvas(SymmetryEngine.Instance(-200f, 0f, 0f), width, height))
        assertTrue(SymmetryEngine.isOutsideCanvas(SymmetryEngine.Instance(0f, 500f, 0f), width, height))
        assertFalse(SymmetryEngine.isOutsideCanvas(SymmetryEngine.Instance(10f, 10f, 0f), width, height))
    }

    @Test
    fun `stampsPerSample counts one stamp per instance`() {
        assertEquals(1, SymmetryEngine.stampsPerSample(SymmetryEngine.Settings()))
        assertEquals(
            32,
            SymmetryEngine.stampsPerSample(
                SymmetryEngine.Settings(type = SymmetryEngine.SymmetryType.RADIAL, radialCount = 32),
            ),
        )
    }
}
