package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Speed dynamics: a positive setting narrows or fades a fast stroke, a negative one enlarges or lifts it. */
class BrushSpeedTest {
    private val black = 0xFF000000.toInt()

    /** A horizontal stroke of 32 px whose samples are [milliseconds] apart. */
    private fun line(
        params: BrushParams,
        milliseconds: Long,
    ): PixelBuffer =
        PixelBuffer(40, 20).also { canvas ->
            val points =
                listOf(
                    StrokePoint(4f, 10f, 1f, timestamp = 0L),
                    StrokePoint(36f, 10f, 1f, timestamp = milliseconds),
                )
            StrokeRasterizer().draw(canvas, Stroke(id = 7, points = points, brushParams = params, layerId = 1, color = black))
        }

    private fun alpha(
        buffer: PixelBuffer,
        x: Int,
        y: Int,
    ) = buffer.pixels[y * buffer.width + x] ushr 24

    @Test fun effectiveSizeFollowsSpeedInEitherDirection() {
        val fastest = 10f
        val narrowed = BrushParams(size = 20f, pressureToSize = 0f, velocityToSize = 1f)
        val enlarged = BrushParams(size = 20f, pressureToSize = 0f, velocityToSize = -1f)
        assertEquals(10f, narrowed.calculateEffectiveSize(1f, fastest), 1e-4f)
        assertEquals(30f, enlarged.calculateEffectiveSize(1f, fastest), 1e-4f)
        assertEquals(20f, enlarged.calculateEffectiveSize(1f, 0f), 1e-4f)
    }

    @Test fun effectiveOpacityFollowsSpeedInEitherDirection() {
        val faded = BrushParams(opacity = 0.5f, pressureToOpacity = 0f, velocityToOpacity = 1f)
        val lifted = BrushParams(opacity = 0.5f, pressureToOpacity = 0f, velocityToOpacity = -1f)
        assertEquals(0.35f, faded.calculateEffectiveOpacity(1f, 10f), 1e-4f)
        assertEquals(0.65f, lifted.calculateEffectiveOpacity(1f, 10f), 1e-4f)
    }

    @Test fun reversedSpeedStaysInsideTheStrokeReach() {
        val params = BrushParams(size = 20f, pressureToSize = 0f, velocityToSize = -1f)
        assertTrue(StrokeReach.of(params) >= params.calculateEffectiveSize(1f, 10f) / 2f)
    }

    @Test fun aFastStrokeGrowsWithReversedSpeed() {
        val reversed = BrushParams(size = 12f, spacing = 0.05f, pressureToSize = 0f, velocityToSize = -1f)
        // 7.5 px from the centre line lies outside a slow mark (6 px radius) and inside a fast one (9 px).
        assertEquals(0, alpha(line(reversed, 300L), 20, 17))
        assertTrue(alpha(line(reversed, 3L), 20, 17) > 0)
    }

    @Test fun aFastStrokeNarrowsWithPositiveSpeed() {
        val positive = BrushParams(size = 12f, spacing = 0.05f, pressureToSize = 0f, velocityToSize = 1f)
        // 4.5 px from the centre line is reached by a slow mark (6 px radius) but not a fast one (3 px).
        assertTrue(alpha(line(positive, 300L), 20, 14) > 0)
        assertEquals(0, alpha(line(positive, 3L), 20, 14))
    }
}
