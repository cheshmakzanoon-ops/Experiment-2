package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.BlendModes
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.pixels.Stamping
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.model.layer.AdjustmentType
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.Layer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Pixel-level contracts shared by the editor, thumbnails and all export formats. */
class RenderingRegressionTest {
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val green = 0xFF00FF00.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val brush = BrushParams(size = 10f, opacity = 0.5f, pressureToSize = 0f, pressureToOpacity = 0f)

    private fun stroke(params: BrushParams = brush): Stroke =
        Stroke(
            id = 42L,
            points = listOf(StrokePoint(8.5f, 8.5f, timestamp = 0)),
            brushParams = params,
            layerId = 1L,
            color = red,
            timestamp = 0,
        )

    private fun alpha(pixel: Int): Int = pixel ushr 24

    @Test
    fun halfOpacityIsAppliedOnceRatherThanCubed() {
        val buffer = PixelBuffer(17, 17)
        StrokeRasterizer().draw(buffer, stroke())
        assertEquals(128, alpha(buffer.getSafe(8, 8)))
    }

    @Test
    fun overlappingSamplesDoNotDarkenOneStroke() {
        val point = stroke().points.single()
        val duplicateSamples = stroke().copy(points = List(20) { point.copy(timestamp = it.toLong()) })
        val buffer = PixelBuffer(17, 17)
        StrokeRasterizer().draw(buffer, duplicateSamples)
        assertEquals(128, alpha(buffer.getSafe(8, 8)))
    }

    @Test
    fun separateStrokesStillBuildCoverage() {
        val buffer = PixelBuffer(17, 17)
        val renderer = StrokeRasterizer()
        repeat(2) { renderer.draw(buffer, stroke()) }
        assertEquals(192, alpha(buffer.getSafe(8, 8)))
    }

    @Test
    fun pressureDynamicsRespectBaseOpacityAndHaveContinuousInfluence() {
        val params = brush.copy(pressureToOpacity = 1f, pressureToSize = 1f)
        assertEquals(0.5f, params.calculateEffectiveOpacity(1f), 0.0001f)
        assertEquals(0.125f, params.calculateEffectiveOpacity(0.25f), 0.0001f)
        assertEquals(10f, params.calculateEffectiveSize(1f), 0.0001f)
        assertEquals(2.5f, params.calculateEffectiveSize(0.25f), 0.0001f)
        assertEquals(0f, params.copy(opacity = 0f).calculateEffectiveOpacity(1f), 0f)
        assertEquals(10f, params.copy(pressureToSize = 0.00001f).calculateEffectiveSize(1f), 0.0001f)
    }

    @Test
    fun zeroOpacityAndZeroFlowDoNotPaintOrErase() {
        for (params in listOf(brush.copy(opacity = 0f), brush.copy(flow = 0f))) {
            for (eraser in listOf(false, true)) {
                val buffer = PixelBuffer.filled(17, 17, blue)
                StrokeRasterizer().draw(buffer, stroke(params).copy(isEraser = eraser))
                assertTrue(buffer.pixels.all { it == blue })
            }
        }
    }

    @Test
    fun halfOpacityEraserRemovesHalfCoverageOnce() {
        val buffer = PixelBuffer.filled(17, 17, blue)
        val erase = stroke().copy(isEraser = true, points = List(10) { stroke().points.single() })
        StrokeRasterizer().draw(buffer, erase)
        assertEquals(128, alpha(buffer.getSafe(8, 8)))
    }

    @Test
    fun alphaLockPreservesEveryDestinationAlpha() {
        for (value in listOf(0, 1, 64, 128, 254, 255)) {
            val color = (value shl 24) or 0x000000FF
            val buffer = PixelBuffer.filled(17, 17, color)
            StrokeRasterizer().draw(buffer, stroke(brush.copy(opacity = 1f)), alphaLock = true)
            assertTrue(buffer.pixels.all { alpha(it) == value })
            if (value > 0) assertEquals((value shl 24) or 0x00FF0000, buffer.getSafe(8, 8))
        }
    }

    @Test
    fun everyStampModeHonorsAlphaLock() {
        for (mode in Stamping.Mode.entries) {
            val buffer = PixelBuffer.filled(3, 3, 0x400000FF)
            Stamping.dab(buffer, 1.5f, 1.5f, 2f, red, mode = mode, alphaLock = true)
            assertTrue("$mode changed transparency", buffer.pixels.all { alpha(it) == 64 })
        }
    }

    @Test
    fun seededJitterReplaysIdenticallyAcrossThreadsAndRendererInstances() {
        val jitter = stroke(brush.copy(sizeJitter = 0.5f, opacityJitter = 0.5f, hueJitter = 0.7f, scatter = 0.8f, count = 8))
        val renderer = StrokeRasterizer()
        val expected = renderer.rasterize(listOf(jitter), 32, 32).pixels
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks = List(12) { executor.submit<IntArray> { renderer.rasterize(listOf(jitter), 32, 32).pixels } }
            tasks.forEach { assertArrayEquals(expected, it.get(10, TimeUnit.SECONDS)) }
            assertArrayEquals(expected, StrokeRasterizer().rasterize(listOf(jitter), 32, 32).pixels)
            val different = renderer.rasterize(listOf(jitter.copy(id = 43L)), 32, 32).pixels
            assertFalse(expected.contentEquals(different))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun brushCanOverlapSelectionWithoutItsCenterBeingSelected() {
        val mask = SelectionMask(17, 17)
        mask.coverage[8 * 17 + 10] = 255.toByte()
        val buffer = PixelBuffer(17, 17)
        StrokeRasterizer().draw(buffer, stroke(brush.copy(opacity = 1f)), mask = mask)
        assertEquals(255, alpha(buffer.getSafe(10, 8)))
        assertEquals(1, buffer.pixels.count { alpha(it) > 0 })
    }

    private fun input(
        id: Long,
        index: Int,
        color: Int,
        clipping: Boolean = false,
    ): Compositor.LayerInput =
        Compositor.LayerInput(Layer(id, "Layer $id", index, isClippingMask = clipping), PixelBuffer.filled(2, 2, color))

    @Test
    fun hiddenOrEmptyBaseCannotExposeClippedChildren() {
        val back = input(1, 0, blue)
        val base = input(2, 1, red)
        val child = input(3, 2, green, clipping = true)
        val bases = listOf(base.copy(layer = base.layer.copy(isVisible = false)), base.copy(raster = null))
        for (hidden in bases) {
            val result = Compositor().composite(listOf(back, hidden, child), 2, 2)
            assertTrue(result.pixels.all { it == blue })
        }
    }

    @Test
    fun orphanClippingLayerIsTransparent() {
        val result = Compositor().composite(listOf(input(1, 0, green, clipping = true)), 2, 2)
        assertTrue(result.pixels.all { it == 0 })
    }

    @Test
    fun clippingGroupPreservesBaseCoverageAndAppliesBaseOpacityOnce() {
        val base = input(1, 0, 0x40FF0000)
        val child = input(2, 1, green, clipping = true)
        val result = Compositor().composite(listOf(base, child), 2, 2)
        assertTrue(result.pixels.all { it == 0x4000FF00 })
        val faded = base.copy(layer = base.layer.copy(opacity = 0.5f))
        val resultFaded = Compositor().composite(listOf(faded, child), 2, 2)
        assertTrue(resultFaded.pixels.all { it == 0x2000FF00 })
    }

    @Test
    fun clippingBlendModesNeverExpandBaseCoverage() {
        for (mode in BlendMode.entries) {
            val target = PixelBuffer(2, 1, intArrayOf(0x40123456, 0))
            BlendModes.compositeClipped(target, PixelBuffer.filled(2, 1, red), mode, 0.5f)
            assertEquals(64, alpha(target.pixels[0]))
            assertEquals(0, target.pixels[1])
        }
    }

    @Test
    fun maskedAdjustmentHonorsBlackWhiteInversionDensityAndDisable() {
        val base = input(1, 0, red)
        val adjustment =
            Compositor.LayerInput(
                Layer(2L, "Invert", 1, adjustmentType = AdjustmentType.INVERT, maskFile = "mask.png"),
                mask = PixelBuffer(2, 2, intArrayOf(0xFF000000.toInt(), white, 0xFF000000.toInt(), white)),
            )
        val compositor = Compositor()
        val result = compositor.composite(listOf(base, adjustment), 2, 2)
        assertArrayEquals(intArrayOf(red, 0xFF00FFFF.toInt(), red, 0xFF00FFFF.toInt()), result.pixels)
        val inverted = adjustment.copy(layer = adjustment.layer.copy(maskInverted = true))
        assertEquals(0xFF00FFFF.toInt(), compositor.composite(listOf(base, inverted), 2, 2).pixels[0])
        for (layer in listOf(adjustment.layer.copy(maskDensity = 0f), adjustment.layer.copy(maskEnabled = false))) {
            val all = compositor.composite(listOf(base, adjustment.copy(layer = layer)), 2, 2)
            assertTrue(all.pixels.all { it == 0xFF00FFFF.toInt() })
        }
    }

    @Test
    fun selectionUsesCoordinatesWhenDimensionsDiffer() {
        val selection = SelectionMask(1, 2).apply { selectAll() }
        val result = Compositor().composite(listOf(input(1, 0, red)), 2, 2, options = Compositor.Options(selection = selection))
        assertEquals(red, result.pixels[0])
        assertEquals(0, alpha(result.pixels[1]))
        assertEquals(red, result.pixels[2])
        assertEquals(0, alpha(result.pixels[3]))
    }

    @Test
    fun selectionRestrictsOutputWithoutWeakeningTheAdjustmentTwice() {
        val mask = SelectionMask(2, 2, ByteArray(4) { 128.toByte() })
        val adjustment = Compositor.LayerInput(Layer(2L, "Invert", 1, adjustmentType = AdjustmentType.INVERT))
        val result =
            Compositor().composite(
                listOf(input(1, 0, red), adjustment),
                2,
                2,
                options = Compositor.Options(selection = mask),
            )
        assertTrue(result.pixels.all { it == 0x8000FFFF.toInt() })
    }

    @Test
    fun compositorNeverMutatesInputsOrReusesAResultOwnedByItsCaller() {
        val first = input(1, 0, red)
        val compositor = Compositor()
        val owned = compositor.composite(listOf(first), 2, 2)
        repeat(100) { compositor.composite(listOf(input(2, 0, blue), input(3, 1, green, clipping = true)), 2, 2) }
        assertTrue(owned.pixels.all { it == red })
        assertTrue(requireNotNull(first.raster).pixels.all { it == red })
    }
}
