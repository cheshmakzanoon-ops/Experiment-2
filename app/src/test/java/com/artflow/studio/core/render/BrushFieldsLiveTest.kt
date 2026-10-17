package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.model.layer.BlendMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.sin

/**
 * Live strokes must match a full redraw frame by frame for the newer brush fields too: the wet mix
 * controls, pressure bleed and the rendering modes. Mirrors LiveStrokeTest without its internal-only references.
 */
class BrushFieldsLiveTest {
    private val width = 180
    private val height = 120

    private val layer =
        PixelBuffer(width, height).also { buffer ->
            for (i in buffer.pixels.indices) {
                buffer.pixels[i] = if ((i / width + i % width) % 7 < 3) 0xFF3A6EA5.toInt() else 0x80E0B040.toInt()
            }
        }

    private fun stroke(params: BrushParams): Stroke =
        Stroke(
            id = 4242L,
            points =
                (0 until 22).map { i ->
                    StrokePoint(x = 20f + i * 6.5f, y = 60f + sin(i / 3f) * 30f, pressure = 0.2f + (i % 6) * 0.13f, timestamp = i * 9L)
                } + StrokePoint(x = 163f, y = 75f, pressure = 0.5f, timestamp = 400L),
            brushParams = params,
            layerId = 0L,
            color = 0xFFCC3355.toInt(),
            timestamp = 0L,
        )

    private fun assertLiveMatchesFull(
        params: BrushParams,
        label: String,
    ) {
        val whole = stroke(params)
        assertTrue("$label should be drawable live", StrokeRasterizer.canDrawLive(whole))
        val full = StrokeRasterizer()
        val live = StrokeRasterizer()
        val region = IntBounds(37, 21, 151, 103)
        val cropped = StrokeRasterizer(region.left, region.top)
        val state = live.startLive(whole, width, height)
        val cropState = cropped.startLive(whole, width, height)
        for (count in listOf(1, 2, 3, 7, 12, 13, 20, whole.points.size)) {
            val partial = whole.copy(points = whole.points.take(count))
            val expected = layer.copy().also { full.draw(it, partial) }
            val actual = layer.copy().also { live.drawLive(it, state, partial, layer) }
            assertArrayEquals("$label after $count points", expected.pixels, actual.pixels)
            val part = layer.crop(region).also { cropped.drawLive(it, cropState, partial, layer) }
            assertArrayEquals("$label cropped after $count points", expected.crop(region).pixels, part.pixels)
        }
    }

    @Test
    fun wetMixControlsMatchAFullRedrawFrameByFrame() {
        val random = Random(21)
        repeat(40) { index ->
            val params =
                BrushParams(
                    size = 6f + random.nextFloat() * 24f,
                    opacity = 0.4f + random.nextFloat() * 0.6f,
                    spacing = 0.05f + random.nextFloat() * 0.4f,
                    flow = 0.5f + random.nextFloat() * 0.5f,
                    wetMix = if (index % 2 == 0) random.nextFloat() else 0f,
                    wetDilution = if (index % 3 == 0) random.nextFloat() else 0f,
                    wetCharge = if (index % 4 == 1) random.nextFloat() else 1f,
                    wetAttack = if (index % 5 == 2) random.nextFloat() else 1f,
                    wetPull = if (index % 6 == 0) random.nextFloat() else 0f,
                    wetBlur = if (index % 2 == 1) random.nextFloat() else 0f,
                    wetBlurJitter = if (index % 7 == 0) random.nextFloat() else 0f,
                    pressureToSize = if (index % 3 == 1) 0.6f else 0.5f,
                    bleed = if (index % 3 == 2) random.nextFloat() else 0f,
                    count = 1 + index % 3,
                    sizeJitter = if (index % 4 == 0) 0.3f else 0f,
                    buildUp = index % 5 == 0,
                    blendMode = if (index % 8 == 0) BlendMode.MULTIPLY else BlendMode.NORMAL,
                )
            assertLiveMatchesFull(params, "wet brush $index")
        }
    }
}
