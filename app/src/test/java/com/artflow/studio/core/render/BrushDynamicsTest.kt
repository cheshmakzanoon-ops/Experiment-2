package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.PressureResponse
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushDynamicsTest {
    private val solid = BrushParams(size = 80f, pressureToSize = 0f, pressureToOpacity = 0f)

    private fun stroke(
        params: BrushParams,
        eraser: Boolean = false,
    ): Stroke =
        Stroke(
            id = 73,
            points = listOf(StrokePoint(16f, 16f, timestamp = 0)),
            brushParams = params,
            layerId = 1,
            color = 0xFFFF5500.toInt(),
            timestamp = 0,
            isEraser = eraser,
        )

    private fun render(params: BrushParams): PixelBuffer = StrokeRasterizer().rasterize(listOf(stroke(params)), 32, 32)

    @Test
    fun oldCustomPressureDocumentsRetainTheLinearDefault() {
        val legacy = Json.decodeFromString<BrushParams>("""{"pressureCurve":"CUSTOM"}""")
        for (index in 0..100) {
            val pressure = index / 100f
            assertEquals(pressure, legacy.pressureResponse(pressure), 0.00001f)
        }
    }

    @Test
    fun customControlsAreContinuousMonotoneBoundedAndUsedByRealDynamics() {
        val response = PressureResponse(0.05f, 0.2f, 0.9f)
        val params =
            solid.copy(
                pressureCurve = BrushParams.PressureCurve.CUSTOM,
                customPressure = response,
                pressureToSize = 1f,
                pressureToOpacity = 1f,
            )
        assertEquals(0f, params.pressureResponse(-1f), 0f)
        assertEquals(1f, params.pressureResponse(2f), 0f)
        assertEquals(0.05f, params.pressureResponse(0.25f), 0f)
        assertEquals(0.2f, params.pressureResponse(0.5f), 0f)
        assertEquals(0.9f, params.pressureResponse(0.75f), 0f)
        var previous = 0f
        for (index in 0..10000) {
            val value = params.pressureResponse(index / 10000f)
            assertTrue(value in 0f..1f && value >= previous)
            assertTrue(value - previous < 0.001f)
            previous = value
        }
        assertEquals(16f, params.calculateEffectiveSize(0.5f), 0.0001f)
        assertEquals(0.2f, params.calculateEffectiveOpacity(0.5f), 0.0001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decreasingPressureControlsAreRejected() {
        PressureResponse(0.8f, 0.2f, 0.9f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonFinitePressureControlsAreRejected() {
        PressureResponse(Float.NaN, 0.5f, 0.8f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonFinitePressureInputIsRejected() {
        solid.pressureResponse(Float.POSITIVE_INFINITY)
    }

    @Test
    fun grainsActuallyChangePixelsAndDoNotDependOnClockOrRendererInstance() {
        val plain = render(solid)
        val results =
            BrushTexture.Kind.entries.map { texture ->
                val params = solid.copy(textureId = texture.id, blendTexture = true)
                val first = render(params)
                assertFalse(texture.label, plain.pixels.contentEquals(first.pixels))
                assertArrayEquals(first.pixels, render(params).pixels)
                assertTrue(
                    first.pixels
                        .map { it ushr 24 }
                        .distinct()
                        .size > 1,
                )
                first.pixels
            }
        assertFalse(results[0].contentEquals(results[1]))
        assertFalse(results[1].contentEquals(results[2]))
    }

    @Test
    fun grainScaleAndRotationAreNotNoOpControls() {
        val params = solid.copy(textureId = "canvas", blendTexture = true)
        assertFalse(render(params).pixels.contentEquals(render(params.copy(textureScale = 2f)).pixels))
        assertFalse(render(params).pixels.contentEquals(render(params.copy(textureRotation = 37f)).pixels))
    }

    @Test
    fun disabledAndUnrecognizedTexturesRetainLegacyPixels() {
        val plain = render(solid).pixels
        assertArrayEquals(plain, render(solid.copy(textureId = "paper", blendTexture = false)).pixels)
        assertArrayEquals(plain, render(solid.copy(textureId = "old-unknown-id", blendTexture = true)).pixels)
    }

    @Test
    fun brushSettingsSerializeAndReplayWithIdenticalPixels() {
        val params =
            solid.copy(
                textureId = "charcoal",
                blendTexture = true,
                textureRotation = 23f,
                pressureCurve = BrushParams.PressureCurve.CUSTOM,
                customPressure = PressureResponse(0.1f, 0.4f, 0.8f),
            )
        val restored = Json.decodeFromString<BrushParams>(Json.encodeToString(params))
        assertEquals(params, restored)
        assertArrayEquals(render(params).pixels, render(restored).pixels)
    }

    @Test
    fun duplicateDabsDoNotFillThePaperGrain() {
        val one = stroke(solid.copy(textureId = "paper", blendTexture = true))
        val repeated = one.copy(points = List(30) { one.points.single().copy(timestamp = it.toLong()) })
        val renderer = StrokeRasterizer()
        assertArrayEquals(
            renderer.rasterize(listOf(one), 32, 32).pixels,
            renderer.rasterize(listOf(repeated), 32, 32).pixels,
        )
    }

    @Test
    fun eraserIgnoresInkColorAlphaAndRetainsTexturedCoverage() {
        val params = solid.copy(textureId = "paper", blendTexture = true)
        val painted = render(params)
        val erased = PixelBuffer.filled(32, 32, 0xFFFFFFFF.toInt())
        StrokeRasterizer().draw(erased, stroke(params, eraser = true).copy(color = 0))
        for (index in erased.pixels.indices) {
            // Deposited alpha and removed alpha use complementary coverage, within byte rounding.
            assertTrue(kotlin.math.abs((painted.pixels[index] ushr 24) + (erased.pixels[index] ushr 24) - 255) <= 1)
        }
    }

    @Test
    fun grainCannotPaintOutsideSelectionOrChangeAlphaLockedCoverage() {
        val target = PixelBuffer.filled(32, 32, 0x400000FF)
        val mask = SelectionMask(32, 32)
        mask.coverage[16 * 32 + 16] = 255.toByte()
        StrokeRasterizer().draw(target, stroke(solid.copy(textureId = "paper", blendTexture = true)), alphaLock = true, mask = mask)
        assertTrue(target.pixels.all { it ushr 24 == 64 })
        assertEquals(1, target.pixels.count { it != 0x400000FF })
    }

    @Test
    fun previewIsDeterministicAndUsesTheActualBrushEngine() {
        val plain = BrushPreview.render(solid)
        assertArrayEquals(plain.pixels, BrushPreview.render(solid).pixels)
        assertFalse(plain.pixels.contentEquals(BrushPreview.render(solid.copy(textureId = "paper", blendTexture = true)).pixels))
        assertTrue(BrushPreview.render(solid.copy(opacity = 0f)).pixels.all { it == 0 })
    }
}
