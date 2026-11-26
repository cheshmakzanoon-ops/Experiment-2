package com.artflow.studio.core.symmetry

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.StrokeRasterizer
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.DualBrush
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

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

    @Test
    fun radialCopiesFollowTheMovedAxisWithOffsets() {
        val settings =
            SymmetryEngine.Settings(
                type = SymmetryEngine.SymmetryType.RADIAL,
                radialCount = 4,
                centreX = 0.2f,
                centreY = 0.3f,
                offsetX = 0.05f,
                offsetY = -0.05f,
                radialAngleDegrees = 30f,
            )
        val copies = expanded(settings, 35f, 25f)
        assertPoint(35f to 25f, copies[0])
        assertPoint(25f to 35f, copies[1])
        assertPoint(15f to 25f, copies[2])
        assertPoint(25f to 15f, copies[3])
    }

    @Test
    fun oddAndEvenRadialCopiesCarryEverySamplesDirectionAroundTheMovedAxis() {
        val source =
            stroke(31f, 42f).copy(
                points =
                    listOf(
                        StrokePoint(31f, 42f, pressure = 0.4f, tiltX = 0.8f, tiltY = 0.35f, azimuth = 0.7f, timestamp = 100L),
                        StrokePoint(38f, 49f, pressure = 0.7f, tiltX = 0.6f, tiltY = -0.2f, azimuth = 0.4f, timestamp = 120L),
                    ),
                brushParams = BrushParams(rotation = 27f, tiltToRotation = true),
            )
        for (count in listOf(3, 4, 5, 32)) {
            val settings =
                SymmetryEngine.Settings(
                    type = SymmetryEngine.SymmetryType.RADIAL,
                    centreX = 0.25f,
                    centreY = 0.4f,
                    radialCount = count,
                    radialAngleDegrees = 15f,
                    mirroredSegments = true,
                )
            val copies = SymmetryEngine.mirrorStroke(source, size, size, settings)
            assertEquals(2 * count, copies.size)
            assertSame(source, copies[0])
            assertEquals(copies, SymmetryEngine.mirrorStroke(source, size, size, settings))
            copies.forEachIndexed { index, copy ->
                val reflected = index >= count
                val turn = 2 * Math.PI * (index % count) / count + if (reflected) Math.toRadians(30.0) else 0.0
                val direction = if (reflected) -1 else 1
                assertEquals(source.id + index, copy.id)
                assertEquals(reflected, copy.brushParams.tipFlipY)
                assertEquals(direction * 27f, copy.brushParams.rotation, 0.0001f)
                source.points.zip(copy.points).forEach { (original, point) ->
                    val dx = original.x - 25f
                    val dy = (original.y - 40f) * direction
                    assertPoint(
                        (25 + dx * cos(turn) - dy * sin(turn)).toFloat() to (40 + dx * sin(turn) + dy * cos(turn)).toFloat(),
                        point.x to point.y,
                    )
                    assertAngle(direction * original.tiltY + turn, point.tiltY.toDouble())
                    assertAngle(direction * original.azimuth + turn, point.azimuth.toDouble())
                    assertEquals(original.pressure, point.pressure, 0f)
                    assertEquals(original.tiltX, point.tiltX, 0f)
                    assertEquals(original.timestamp, point.timestamp)
                    assertEquals(original.color, point.color)
                    val helper =
                        SymmetryEngine.instances(original.x, original.y, size, size, settings, brushRotation = 27f)[index]
                    assertPoint(helper.x to helper.y, point.x to point.y)
                }
            }
        }
    }

    @Test
    fun reflectedCopiesTransformTipAndPenAnglesWithoutTurningTwice() {
        val secondary = BrushParams(rotation = 13f, tiltToRotation = false, tipFlipY = true)
        val brush = BrushParams(rotation = 27f, tiltToRotation = true, tipFlipX = true, dual = DualBrush(secondary))
        val point = StrokePoint(40f, 20f, pressure = 0.4f, tiltX = 0.7f, tiltY = 0.35f, timestamp = 100L)
        val source = stroke(point.x, point.y).copy(points = listOf(point), brushParams = brush)
        val copies =
            SymmetryEngine.mirrorStroke(source, size, size, SymmetryEngine.Settings(type = SymmetryEngine.SymmetryType.QUADRANT))

        assertSame(source, copies[0])
        assertSame(brush, source.brushParams)
        val expectedAngles = listOf(0.35, Math.PI - 0.35, -0.35, Math.PI + 0.35)
        copies.forEachIndexed { index, copy ->
            assertAngle(
                expectedAngles[index],
                copy.points
                    .single()
                    .tiltY
                    .toDouble(),
            )
            val reflected = index == 1 || index == 2
            val secondaryAngle =
                when (index) {
                    1 -> 167f
                    2 -> -13f
                    3 -> 193f
                    else -> 13f
                }
            // Every non-geometric parameter, including imported calibration, remains the original value.
            val expectedSecondary = secondary.copy(rotation = secondaryAngle, tipFlipY = !reflected)
            val expectedBrush =
                brush.copy(
                    rotation = if (reflected) -27f else 27f,
                    tipFlipY = reflected,
                    dual = brush.dual!!.copy(params = expectedSecondary),
                )
            assertEquals(expectedBrush, copy.brushParams)
        }
    }

    @Test
    fun renderedQuarterTurnCopiesRotateFlatTipsWithAndWithoutTiltRotation() {
        val canvasSize = 81
        val settings = SymmetryEngine.Settings(type = SymmetryEngine.SymmetryType.RADIAL, radialCount = 4)
        for (tiltToRotation in listOf(false, true)) {
            val source =
                stroke(20.5f, 40.5f).copy(
                    points = listOf(StrokePoint(20.5f, 40.5f, tiltX = 0.8f, tiltY = 0f, timestamp = 100L)),
                    brushParams = BrushParams(size = 18f, roundness = 0.2f, spacing = 0.1f, tiltToRotation = tiltToRotation),
                )
            val copies = SymmetryEngine.mirrorStroke(source, canvasSize, canvasSize, settings)
            val rasterizer = StrokeRasterizer()
            try {
                val painted = PixelBuffer(canvasSize, canvasSize)
                copies.forEach { rasterizer.draw(painted, it) }

                fun alpha(
                    x: Int,
                    y: Int,
                ) = painted.pixels[y * canvasSize + x] ushr 24

                // Source at (20.5, 40.5) stays horizontal; its quarter turn at (40.5, 20.5) stands vertically.
                assertTrue(alpha(26, 40) > 0)
                assertEquals(0, alpha(20, 46))
                assertTrue(alpha(40, 26) > 0)
                assertEquals(0, alpha(46, 20))
            } finally {
                rasterizer.release()
            }
        }
    }

    @Test
    fun transformedSamplesRenderTheSameLiveAndOnReplay() {
        val source =
            stroke(31f, 42f).copy(
                points =
                    listOf(
                        StrokePoint(31f, 42f, 0.4f, tiltX = 0.8f, tiltY = 2.97f, timestamp = 100L),
                        StrokePoint(38f, 49f, 0.7f, tiltX = 0.6f, tiltY = -2.97f, timestamp = 120L),
                        StrokePoint(47f, 57f, 0.6f, tiltX = 0.4f, tiltY = -2.6f, timestamp = 150L),
                    ),
                brushParams =
                    BrushParams(
                        size = 12f,
                        rotation = 27f,
                        tiltToRotation = true,
                        roundness = 0.2f,
                        sizeJitter = 0.15f,
                        opacityJitter = 0.2f,
                    ),
            )
        val settings =
            SymmetryEngine.Settings(
                type = SymmetryEngine.SymmetryType.RADIAL,
                centreX = 0.4f,
                centreY = 0.6f,
                radialCount = 5,
                radialAngleDegrees = 15f,
                mirroredSegments = true,
            )
        assertTrue(StrokeRasterizer.canDrawLive(source))
        val rasterizer = StrokeRasterizer()
        try {
            val states = SymmetryEngine.mirrorStroke(source, size, size, settings).map { rasterizer.startLive(it, size, size) }
            for (count in 1..source.points.size) {
                val partial = source.copy(points = source.points.take(count))
                val copies = SymmetryEngine.mirrorStroke(partial, size, size, settings)
                val replay = PixelBuffer(size, size)
                val live = PixelBuffer(size, size)
                copies.forEachIndexed { index, copy ->
                    rasterizer.draw(replay, copy)
                    rasterizer.drawLive(live, states[index], copy, canvas = null)
                }
                assertArrayEquals("symmetry live/replay after $count samples", replay.pixels, live.pixels)
            }
        } finally {
            rasterizer.release()
        }
    }

    private fun assertAngle(
        expected: Double,
        actual: Double,
    ) {
        assertEquals(cos(expected), cos(actual), 0.0001)
        assertEquals(sin(expected), sin(actual), 0.0001)
    }
}
