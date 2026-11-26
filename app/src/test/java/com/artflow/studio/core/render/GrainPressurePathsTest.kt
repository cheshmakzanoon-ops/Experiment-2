package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.DualBrush
import com.artflow.studio.domain.model.brush.GrainBlend
import com.artflow.studio.domain.model.brush.PressureMapping
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Grain controls must use the same pressure and seeded sequence through every rendering path. */
class GrainPressurePathsTest {
    private val pressure = PressureMapping(listOf(PressureMapping.Point(0f, 0.1f), PressureMapping.Point(1f, 0.9f)))
    private val curves =
        listOf(
            "dynamicsPressureGrainDepthCurve",
            "dynamicsPressureGrainDepthJitterCurve",
            "dynamicsPressureGrainScaleCurve",
            "dynamicsPressureGrainZoomCurve",
            "dynamicsPressureGrainMovementCurve",
        ).associateWith { pressure }
    private val curveCases = curves.map { (key, value) -> key to mapOf(key to value) } + ("combined" to curves)
    private val grain =
        BrushParams(
            size = 18f,
            opacity = 0.7f,
            spacing = 0.37f,
            pressureToSize = 0f,
            pressureToOpacity = 0f,
            blendTexture = true,
            textureId = "canvas",
            textureScale = 1.3f,
            textureRotation = 27f,
            grainDepth = 0.6f,
            grainMoving = true,
        )
    private val jittered = grain.copy(sizeJitter = 0.3f, opacityJitter = 0.25f, scatter = 0.2f, count = 3, countJitter = 0.5f)
    private val crop = IntBounds(16, 8, 79, 55)
    private val backdrop =
        PixelBuffer(WIDTH, HEIGHT).also { buffer ->
            for (y in 0 until HEIGHT) {
                for (x in 0 until WIDTH) {
                    buffer.pixels[y * WIDTH + x] = if ((x + y) % 7 < 3) 0xFF225588.toInt() else 0x80CC9933.toInt()
                }
            }
        }

    @Test
    fun liveGrainPressureMatchesReplayAcrossFullAndCroppedFrames() {
        for ((label, mappings) in curveCases) {
            assertLiveMatchesReplay(jittered.copy(importedAuxiliaryCurves = mappings), label)
        }
    }

    @Test
    fun wetPickupAndGrainJitterShareTheReplayRandomPositionWithoutAdvancingFutureDabs() {
        assertLiveMatchesReplay(
            jittered.copy(
                importedAuxiliaryCurves = curves,
                wetMix = 0.6f,
                wetBlur = 0.75f,
                wetBlurJitter = 0.9f,
                wetPull = 0.5f,
                pull = 0.4f,
                hueJitter = 0.15f,
            ),
            "wet grain",
        )
    }

    @Test
    fun eraserRemovesTheSamePressureControlledGrainCoverageThatPaintingAdds() {
        for ((label, mappings) in curveCases) {
            val stroke = stroke(jittered.copy(importedAuxiliaryCurves = mappings))
            val painted = PixelBuffer(WIDTH, HEIGHT).also { StrokeRasterizer().draw(it, stroke) }
            val erased = PixelBuffer.filled(WIDTH, HEIGHT, 0xFF7799BB.toInt())
            StrokeRasterizer().draw(erased, stroke.copy(isEraser = true))
            assertTrue("$label paints visible coverage", painted.pixels.any { (it ushr 24) > 0 })
            for (index in painted.pixels.indices) {
                val added = painted.pixels[index] ushr 24
                val remaining = erased.pixels[index] ushr 24
                // Paint and erase quantize opposite sides of the same coverage independently.
                assertTrue("$label at $index: paint=$added, remaining=$remaining", abs(added + remaining - 255) <= 1)
                assertEquals("$label preserves remaining RGB", 0x007799BB, erased.pixels[index] and 0x00FFFFFF)
            }
        }
    }

    @Test
    fun secondaryGrainMatchesASoloBrushWithTheSecondarySeed() {
        // An opaque primary covers the entire canvas; Multiply exposes the secondary's coverage.
        val primary = BrushParams(size = 512f, pressureToSize = 0f, pressureToOpacity = 0f, spacing = 0.2f)
        for ((label, mappings) in curveCases) {
            for (blend in GrainBlend.entries) {
                val secondary = jittered.copy(opacity = 1f, importedAuxiliaryCurves = mappings, grainBlend = blend)
                val compound = stroke(primary.copy(dual = DualBrush(secondary)))
                val solo = compound.copy(id = compound.id + 7919L, brushParams = secondary)
                val expected = PixelBuffer(WIDTH, HEIGHT).also { StrokeRasterizer().draw(it, solo) }
                val actual = PixelBuffer(WIDTH, HEIGHT).also { StrokeRasterizer().draw(it, compound) }
                for (index in expected.pixels.indices) {
                    assertEquals("$label / $blend at $index", expected.pixels[index] ushr 24, actual.pixels[index] ushr 24)
                }
            }
        }
    }

    @Test
    fun pressureGrainRetainsCanvasOriginForCroppedPaintEraseAndDualReplay() {
        val primary = BrushParams(size = 40f, pressureToSize = 0f, pressureToOpacity = 0f, spacing = 0.2f)
        for (moving in listOf(false, true)) {
            val textured = grain.copy(importedAuxiliaryCurves = curves, grainMoving = moving)
            for (params in listOf(textured, primary.copy(dual = DualBrush(textured)))) {
                for (erase in listOf(false, true)) {
                    val whole =
                        stroke(params).copy(
                            points = listOf(StrokePoint(40.5f, 30.5f, pressure = 0.35f)),
                            isEraser = erase,
                        )
                    val shifted = whole.copy(points = whole.points.map { it.copy(x = it.x - crop.left, y = it.y - crop.top) })
                    val expected = backdrop.copy().also { StrokeRasterizer().draw(it, whole) }
                    val actual = backdrop.crop(crop).also { StrokeRasterizer(crop.left, crop.top).draw(it, shifted) }
                    assertArrayEquals(
                        "moving=$moving / dual=${params.dual != null} / erase=$erase",
                        expected.crop(crop).pixels,
                        actual.pixels,
                    )
                }
            }
        }
    }

    private fun assertLiveMatchesReplay(
        params: BrushParams,
        label: String,
    ) {
        val whole = stroke(params)
        assertTrue(StrokeRasterizer.canDrawLive(whole))
        val replay = StrokeRasterizer()
        val live = StrokeRasterizer()
        val cropped = StrokeRasterizer(crop.left, crop.top)
        val state = live.startLive(whole, WIDTH, HEIGHT)
        val cropState = cropped.startLive(whole, WIDTH, HEIGHT)
        // Repeated frames must leave both the persistent RNG and temporary closing dab unchanged.
        for (count in listOf(1, 1, 2, 3, 3, 4, 5, 5)) {
            val partial = whole.copy(points = whole.points.take(count))
            val expected = backdrop.copy().also { replay.draw(it, partial) }
            val actual = backdrop.copy().also { live.drawLive(it, state, partial, backdrop) }
            val part = backdrop.crop(crop).also { cropped.drawLive(it, cropState, partial, backdrop) }
            assertArrayEquals("$label after $count points", expected.pixels, actual.pixels)
            assertArrayEquals("$label cropped after $count points", expected.crop(crop).pixels, part.pixels)
        }
    }

    private fun stroke(params: BrushParams): Stroke =
        Stroke(
            id = 4242L,
            points =
                listOf(
                    StrokePoint(12.5f, 20.5f, pressure = 0.2f, timestamp = 0L),
                    StrokePoint(31f, 25.5f, pressure = 0.7f, timestamp = 17L),
                    StrokePoint(45.5f, 39f, pressure = 0.35f, timestamp = 35L),
                    StrokePoint(64f, 28.5f, pressure = 0.9f, timestamp = 50L),
                    StrokePoint(83.5f, 45f, pressure = 0.45f, timestamp = 73L),
                ),
            brushParams = params,
            layerId = 1L,
            color = 0xFFBB3366.toInt(),
            timestamp = 0L,
        )

    private companion object {
        const val WIDTH = 96
        const val HEIGHT = 64
    }
}
