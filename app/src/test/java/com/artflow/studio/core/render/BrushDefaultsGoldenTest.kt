package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.DualBrush
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.model.brush.StudioBrushes
import com.artflow.studio.domain.model.layer.BlendMode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random
import kotlin.math.sin

/**
 * Pins the pixels brushes paint with the settings that existed before wet-mix, rendering-mode and
 * path-jitter fields were added. A brush that leaves the newer fields at their defaults must keep
 * painting exactly these pixels. The constants pin the base codebase at adf0bc7208974f1579095ea5a890b7c1375f02f9,
 * independently compiled from Git before accepting the native brush mapping changes. Its renderer already
 * disagreed with the earlier constants; see docs/BRUSH_BASELINE_VALIDATION_2026-10-21.md for the comparison.
 */
class BrushDefaultsGoldenTest {
    private val width = 180
    private val height = 120

    // The same busy layer the live-stroke checks paint over, so wet mix and blend modes have something to read.
    private val layer =
        PixelBuffer(width, height).also { buffer ->
            for (i in buffer.pixels.indices) {
                buffer.pixels[i] = if ((i / width + i % width) % 7 < 3) 0xFF3A6EA5.toInt() else 0x80E0B040.toInt()
            }
        }

    private fun stroke(
        params: BrushParams,
        color: Int = 0xFFCC3355.toInt(),
    ): Stroke =
        Stroke(
            id = 4242L,
            points =
                (0 until 22).map { i ->
                    StrokePoint(x = 20f + i * 6.5f, y = 60f + sin(i / 3f) * 30f, pressure = 0.2f + (i % 6) * 0.13f, timestamp = i * 9L)
                } + StrokePoint(x = 163f, y = 75f, pressure = 0.5f, timestamp = 400L),
            brushParams = params,
            layerId = 0L,
            color = color,
            timestamp = 0L,
        )

    private fun fingerprint(pixels: IntArray): Long {
        var hash = 1125899906842597L
        for (pixel in pixels) hash = 31 * hash + pixel
        return hash
    }

    private fun paintedHash(params: BrushParams): Long =
        fingerprint(layer.copy().also { StrokeRasterizer().draw(it, stroke(params)) }.pixels)

    @Test
    fun presetsPaintTheCapturedPixels() {
        var combined = 17L
        for (preset in StudioBrushes.presets) {
            val params = preset.parameters.copy(size = preset.parameters.size.coerceAtMost(28f))
            combined = 31 * combined + paintedHash(params)
        }
        assertEquals(-2657600560538100551L, combined)
    }

    @Test
    fun randomBrushesPaintTheCapturedPixels() {
        val random = Random(11)
        var combined = 29L
        repeat(60) { index ->
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
                    burntEdges = if (index % 9 == 0) 0.5f else 0f,
                    textureId = if (index % 3 == 1) "paper" else null,
                    blendTexture = index % 3 == 1,
                    grainMoving = index % 9 == 0,
                    velocityToSize = if (index % 10 == 0) 0.5f else 0f,
                    taperStart = if (index % 11 == 0) 0.3f else 0f,
                    taperEnd = if (index % 11 == 0) 0.4f else 0f,
                    taperOpacity = if (index % 11 == 0) 0.6f else 0f,
                    tiltInfluence = if (index % 12 == 0) 0.7f else 0f,
                    secondaryPressure = if (index % 5 == 2) 0.6f else 0f,
                    blendMode = if (index % 11 == 0) BlendMode.MULTIPLY else BlendMode.NORMAL,
                    dual =
                        if (index % 13 == 0) {
                            DualBrush(BrushParams(size = 5f, spacing = 0.05f), DualBrush.Mode.SUBTRACT)
                        } else {
                            null
                        },
                )
            combined = 31 * combined + paintedHash(params)
        }
        assertEquals(6912832811445097011L, combined)
    }

    @Test
    fun eraserStrokesPaintTheCapturedPixels() {
        val eraser = stroke(BrushParams(size = 14f, spacing = 0.1f, flow = 0.5f, wetMix = 0f)).copy(isEraser = true)
        assertEquals(2947699143526432517L, fingerprint(layer.copy().also { StrokeRasterizer().draw(it, eraser) }.pixels))
    }
}
