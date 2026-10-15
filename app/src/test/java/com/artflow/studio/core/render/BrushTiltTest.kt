package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.brush.BrushLibraryCodec
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.SavedBrush
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
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

    @Test fun aTiltOpacityOutsideZeroToOneCannotBeSaved() {
        val invalid = BrushParams(tiltOpacityInfluence = 2f)
        assertTrue(runCatching { BrushLibraryCodec.encode(listOf(SavedBrush("a", "Ink", invalid))) }.isFailure)
    }
}
