package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.brush.BrushLibraryCodec
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.ColorDynamics
import com.artflow.studio.domain.model.brush.SavedBrush
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stylus tilt: the pen's lean (tiltX, radians from upright) and the direction it leans in (tiltY, its azimuth). */
class BrushTiltTest {
    private val black = 0xFF000000.toInt()
    private val flat = (Math.PI / 2).toFloat()

    /** A horizontal stroke on a 40 x 20 canvas, leaning by [tilt] throughout. */
    private fun horizontal(
        params: BrushParams,
        tilt: Float,
    ): PixelBuffer =
        PixelBuffer(40, 20).also { canvas ->
            val points =
                listOf(
                    StrokePoint(4f, 10f, 1f, tiltX = tilt, timestamp = 0L),
                    StrokePoint(36f, 10f, 1f, tiltX = tilt, timestamp = 16L),
                )
            StrokeRasterizer().draw(canvas, Stroke(id = 5, points = points, brushParams = params, layerId = 1, color = black))
        }

    private fun alpha(
        buffer: PixelBuffer,
        x: Int,
        y: Int,
    ) = buffer.pixels[y * buffer.width + x] ushr 24

    @Test fun tiltCanWidenTheMarkWithoutLighteningIt() {
        val sizeOnly = BrushParams(size = 6f, spacing = 0.05f, tiltInfluence = 1f, tiltOpacityInfluence = 0f)
        val upright = horizontal(sizeOnly, 0f)
        val leaning = horizontal(sizeOnly, flat)
        // 4 px from the centre line lies outside the upright mark (3 px radius) but inside the leaning one.
        assertEquals(0, alpha(upright, 20, 14))
        assertTrue(alpha(leaning, 20, 14) > 0)
        assertEquals(alpha(upright, 20, 10), alpha(leaning, 20, 10))
    }

    @Test fun tiltCanLightenTheMarkWithoutWideningIt() {
        val opacityOnly = BrushParams(size = 6f, spacing = 0.05f, tiltInfluence = 0f, tiltOpacityInfluence = 1f)
        val upright = horizontal(opacityOnly, 0f)
        val leaning = horizontal(opacityOnly, flat)
        assertEquals(0, alpha(leaning, 20, 14))
        assertTrue(alpha(leaning, 20, 10) < alpha(upright, 20, 10) * 0.7f)
    }

    @Test fun opacityFollowsSizeWhenItIsNotSetApart() {
        // Unset, the opacity amount is the size amount, as for every brush saved before the field existed.
        val unset = BrushParams(size = 6f, spacing = 0.05f, tiltInfluence = 1f)
        val explicit = unset.copy(tiltOpacityInfluence = 1f)
        assertTrue(horizontal(unset, flat).pixels.contentEquals(horizontal(explicit, flat).pixels))
        assertTrue(alpha(horizontal(unset, flat), 20, 10) < alpha(horizontal(unset, 0f), 20, 10) * 0.7f)
    }

    /** One dab at the centre of a 41 x 41 canvas, with the pen leaning fully in direction [azimuth]. */
    private fun dab(
        params: BrushParams,
        azimuth: Float = 0f,
        color: Int = black,
    ): PixelBuffer =
        PixelBuffer(41, 41).also { canvas ->
            val point = StrokePoint(20.5f, 20.5f, 1f, tiltX = flat, tiltY = azimuth, timestamp = 0L)
            StrokeRasterizer().draw(canvas, Stroke(id = 9, points = listOf(point), brushParams = params, layerId = 1, color = color))
        }

    @Test fun tiltToRotationTurnsAFlatTipWithThePen() {
        val chisel = BrushParams(size = 24f, roundness = 0.2f, spacing = 0.1f, tiltToRotation = true)
        // Azimuth 0 lays the flat tip along x; a quarter turn of the pen stands it along y.
        assertTrue(alpha(dab(chisel, 0f), 28, 20) > 0)
        assertEquals(0, alpha(dab(chisel, 0f), 20, 28))
        assertTrue(alpha(dab(chisel, flat), 20, 28) > 0)
        assertEquals(0, alpha(dab(chisel, flat), 28, 20))
    }

    @Test fun withoutTiltToRotationTheAzimuthIsIgnored() {
        val chisel = BrushParams(size = 24f, roundness = 0.2f, spacing = 0.1f)
        assertTrue(alpha(dab(chisel, flat), 28, 20) > 0)
        assertEquals(0, alpha(dab(chisel, flat), 20, 28))
    }

    @Test fun tipRotationInterpolatesBetweenPenSamples() {
        val chisel = BrushParams(size = 12f, roundness = 0.2f, spacing = 2.5f, tiltToRotation = true)
        val points =
            listOf(
                StrokePoint(10.5f, 20.5f, tiltY = 0f, timestamp = 0L),
                StrokePoint(70.5f, 20.5f, tiltY = flat, timestamp = 60L),
            )

        fun paint(samples: List<StrokePoint>): PixelBuffer =
            PixelBuffer(82, 41).also {
                StrokeRasterizer().draw(it, Stroke(id = 9, points = samples, brushParams = chisel, layerId = 1, color = black))
            }

        val midpoint = StrokePoint(40.5f, 20.5f, tiltY = flat / 2f, timestamp = 30L)
        assertArrayEquals(paint(listOf(points.first(), midpoint, points.last())).pixels, paint(points).pixels)
    }

    @Test fun tipRotationCrossesTheAngleBoundaryWithTheShortTurn() {
        val chisel = BrushParams(size = 20f, roundness = 0.1f, spacing = 0.75f, tiltToRotation = true)
        val angle = Math.toRadians(170.0).toFloat()
        val points =
            listOf(
                StrokePoint(10.5f, 20.5f, tiltY = angle, timestamp = 0L),
                StrokePoint(70.5f, 20.5f, tiltY = -angle, timestamp = 60L),
            )
        val actual =
            PixelBuffer(82, 41).also {
                StrokeRasterizer().draw(it, Stroke(id = 9, points = points, brushParams = chisel, layerId = 1, color = black))
            }
        // The middle dab faces 180 degrees. Adopting the end sample would move this pixel
        // from the strong centre of the narrow tip into its soft outer edge.
        assertTrue(alpha(actual, 44, 20) > 180)
        assertEquals(0, alpha(actual, 40, 29))
        assertEquals("a quarter-turn sample must not take the long route through 85 degrees", 0, alpha(actual, 25, 26))
    }

    @Test fun zeroSpacingStillAppliesTiltColourDynamics() {
        val colourTilt = BrushParams(size = 24f, colorDynamics = ColorDynamics(tiltBrightness = 1f))
        val color = 0xFFE04020.toInt()
        assertArrayEquals(
            dab(colourTilt.copy(spacing = 0.1f), color = color).pixels,
            dab(colourTilt.copy(spacing = 0f), color = color).pixels,
        )
    }

    @Test fun anImportedTipTurnsWithItsRotation() {
        // A band across the middle rows of an 8 x 8 grain: lying along x until the tip is turned.
        val band = CustomGrains.Tile(8, ByteArray(64) { (if (it / 8 in 3..4) -1 else 0).toByte() })
        val id = CustomGrains.idFor(band)
        CustomGrains.register(id, band)
        val stamp = BrushParams(size = 24f, shapeId = id, spacing = 0.1f)
        assertTrue(alpha(dab(stamp), 28, 20) > 0)
        assertEquals(0, alpha(dab(stamp), 20, 28))
        assertTrue(alpha(dab(stamp.copy(rotation = 90f)), 20, 28) > 0)
        assertEquals(0, alpha(dab(stamp.copy(rotation = 90f)), 28, 20))
    }

    @Test fun aTiltOpacityOutsideZeroToOneCannotBeSaved() {
        val invalid = BrushParams(tiltOpacityInfluence = 2f)
        assertTrue(runCatching { BrushLibraryCodec.encode(listOf(SavedBrush("a", "Ink", invalid))) }.isFailure)
    }
}
