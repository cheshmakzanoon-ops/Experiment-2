package com.artflow.studio.core.symmetry

import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shipping path replicates a committed stroke through `mirrorStroke`; every symmetry option the
 * interface offers has to change what it produces, and agree with the per-sample `instances` maths.
 */
class SymmetryStrokeExpansionTest {
    private val size = 100

    private fun stroke(
        x: Float,
        y: Float,
    ) = Stroke(
        id = 10L,
        points = listOf(StrokePoint(x, y, 1f)),
        brushParams = BrushParams(),
        layerId = 1L,
        color = 0xFF000000.toInt(),
    )

    private fun expanded(
        settings: SymmetryEngine.Settings,
        x: Float = 40f,
        y: Float = 20f,
    ): List<Pair<Float, Float>> =
        SymmetryEngine.mirrorStroke(stroke(x, y), size, size, settings).map { it.points.single().x to it.points.single().y }

    private fun assertPoint(
        expected: Pair<Float, Float>,
        actual: Pair<Float, Float>,
    ) {
        assertEquals(expected.first, actual.first, 0.001f)
        assertEquals(expected.second, actual.second, 0.001f)
    }

    @Test
    fun rotationalVerticalSymmetryHalfTurnsInsteadOfMirroring() {
        val mirrored = expanded(SymmetryEngine.Settings(type = SymmetryEngine.SymmetryType.VERTICAL))
        val rotational = expanded(SymmetryEngine.Settings(type = SymmetryEngine.SymmetryType.VERTICAL, rotational = true))
        assertEquals(2, rotational.size)
        assertPoint(60f to 20f, mirrored[1])
        assertPoint(60f to 80f, rotational[1])
    }

    @Test
    fun rotationalQuadrantSymmetryTurnsInQuarters() {
        val copies = expanded(SymmetryEngine.Settings(type = SymmetryEngine.SymmetryType.QUADRANT, rotational = true))
        assertEquals(4, copies.size)
        assertPoint(40f to 20f, copies[0])
        assertPoint(80f to 40f, copies[1])
        assertPoint(60f to 80f, copies[2])
        assertPoint(20f to 60f, copies[3])
    }

    @Test
    fun rotationalCopiesMatchThePerSampleMaths() {
        val settings = SymmetryEngine.Settings(type = SymmetryEngine.SymmetryType.QUADRANT, rotational = true)
        val perSample = SymmetryEngine.instances(40f, 20f, size, size, settings).map { it.x to it.y }
        expanded(settings).zip(perSample).forEach { (stroke, sample) -> assertPoint(sample, stroke) }
    }

    @Test
    fun radialMirroredSegmentsDoubleTheCopiesAndReflectAcrossTheSpoke() {
        val plain = expanded(SymmetryEngine.Settings(type = SymmetryEngine.SymmetryType.RADIAL, radialCount = 4), 60f, 40f)
        val mirrored =
            expanded(
                SymmetryEngine.Settings(type = SymmetryEngine.SymmetryType.RADIAL, radialCount = 4, mirroredSegments = true),
                60f,
                40f,
            )
        assertEquals(4, plain.size)
        assertEquals(8, mirrored.size)
        // The first reflected copy is the original reflected across the horizontal spoke through the centre (50, 50).
        assertPoint(60f to 60f, mirrored[4])
    }

    @Test
    fun radialCopiesStayEvenlySpacedWhateverTheSpokeAngle() {
        val settings =
            SymmetryEngine.Settings(type = SymmetryEngine.SymmetryType.RADIAL, radialCount = 4, radialAngleDegrees = 30f)
        val offset = expanded(settings, 60f, 50f)
        // Four copies a quarter turn apart about the centre, with the original untouched.
        assertPoint(60f to 50f, offset[0])
        assertPoint(50f to 60f, offset[1])
        assertPoint(40f to 50f, offset[2])
        assertPoint(50f to 40f, offset[3])
    }
}
