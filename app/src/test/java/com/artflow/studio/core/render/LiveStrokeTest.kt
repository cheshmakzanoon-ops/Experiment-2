package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.model.brush.StudioBrushes
import com.artflow.studio.domain.model.layer.BlendMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.sin

class LiveStrokeTest {
    private val width = 180
    private val height = 120

    // A busy layer to paint over, so wet mix and blend modes have something to pick up.
    private val layer =
        PixelBuffer(width, height).also { buffer ->
            for (i in buffer.pixels.indices) {
                buffer.pixels[i] =
                    if ((i / width + i % width) % 7 < 3) 0xFF3A6EA5.toInt() else 0x80E0B040.toInt()
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

    /** Live frames after each batch of points equal a full redraw of the stroke so far. */
    private fun assertLiveMatchesFull(
        params: BrushParams,
        label: String,
    ) {
        val whole = stroke(params)
        if (!StrokeRasterizer.canDrawLive(whole)) return
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
    fun everyLiveCapablePresetMatchesAFullRedrawFrameByFrame() {
        var checked = 0
        for (preset in StudioBrushes.presets) {
            val params = preset.parameters.copy(size = preset.parameters.size.coerceAtMost(28f))
            if (StrokeRasterizer.canDrawLive(stroke(params))) checked++
            assertLiveMatchesFull(params, preset.id)
        }
        assertTrue("only $checked presets could be drawn live", checked > 120)
    }

    @Test
    fun wetMixScatterCountsJitterAndBlendModesMatchToo() {
        val random = Random(7)
        repeat(40) { index ->
            val params =
                BrushParams(
                    size = 4f + random.nextFloat() * 30f,
                    opacity = 0.3f + random.nextFloat() * 0.7f,
                    spacing = 0.03f + random.nextFloat() * 0.5f,
                    scatter = if (index % 3 == 0) random.nextFloat() else 0f,
                    count = 1 + index % 4,
                    sizeJitter = random.nextFloat() * 0.5f,
                    opacityJitter = random.nextFloat() * 0.5f,
                    hueJitter = if (index % 2 == 0) random.nextFloat() * 0.3f else 0f,
                    wetMix = if (index % 4 == 1) random.nextFloat() else 0f,
                    flow = 0.4f + random.nextFloat() * 0.6f,
                    roundness = if (index % 5 == 0) 0.3f else 1f,
                    rotation = random.nextFloat() * 90f,
                    tipRandomized = index % 6 == 0,
                    buildUp = index % 2 == 1,
                    falloff = if (index % 7 == 0) 0.4f else 0f,
                    wetEdges = if (index % 8 == 0) 0.6f else 0f,
                    textureId = if (index % 3 == 1) "paper" else null,
                    blendTexture = index % 3 == 1,
                    grainMoving = index % 9 == 0,
                    velocityToSize = if (index % 10 == 0) 0.5f else 0f,
                    blendMode = if (index % 11 == 0) BlendMode.MULTIPLY else BlendMode.NORMAL,
                )
            assertLiveMatchesFull(params, "random brush $index")
        }
    }

    @Test
    fun taperedSecondBrushAndEraserStrokesAreNotDrawnLive() {
        val base = stroke(BrushParams(size = 10f))
        assertTrue(StrokeRasterizer.canDrawLive(base))
        assertEquals(false, StrokeRasterizer.canDrawLive(base.copy(brushParams = BrushParams(taperEnd = 0.2f))))
        assertEquals(false, StrokeRasterizer.canDrawLive(base.copy(isEraser = true)))
        assertEquals(false, StrokeRasterizer.canDrawLive(base.copy(brushParams = BrushParams(spacing = 0f))))
    }

    @Test
    fun replayableRandomFollowsJavaRandomAndCopiesItsPosition() {
        val reference = Random(99L)
        val replay = ReplayableRandom(99L)
        repeat(50) { assertEquals(reference.nextFloat(), replay.nextFloat(), 0f) }
        // A copy continues from the same position without moving the original.
        val copy = replay.copy()
        val expected = reference.nextLong()
        assertEquals(expected, copy.nextLong())
        assertEquals(expected, replay.nextLong())
    }
}
