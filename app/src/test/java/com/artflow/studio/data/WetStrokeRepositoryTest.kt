package com.artflow.studio.data

import android.content.Context
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.render.StrokeRasterizer
import com.artflow.studio.core.symmetry.SymmetryEngine
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.ColorDynamics
import com.artflow.studio.domain.model.brush.DualBrush
import com.artflow.studio.domain.model.brush.StrokeDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import kotlin.math.abs

/** Wet controls must preserve repository preview/commit agreement and neutral mask painting. */
@OptIn(ExperimentalCoroutinesApi::class)
class WetStrokeRepositoryTest {
    private lateinit var repository: CanvasRepositoryImpl
    private val width = 64
    private val height = 48
    private val plain = BrushParams(size = 12f, spacing = 0.25f, pressureToSize = 0f, pressureToOpacity = 0f)
    private val symmetry = SymmetryEngine.Settings(type = SymmetryEngine.SymmetryType.VERTICAL)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = CanvasRepositoryImpl(ProjectStorage(mock(Context::class.java)))
    }

    @After
    fun tearDown() {
        repository.dispose()
        Dispatchers.resetMain()
    }

    private suspend fun open(): Pair<Long, PixelBuffer> {
        repository.createCanvas(width, height, 72)
        val layer = repository.getActiveLayerId()
        val base = PixelBuffer.filled(width, height, 0xFF113377.toInt())
        assertTrue(repository.setLayerPixels(layer, base, "Wet brush fixture"))
        repository.setStrokeColor(0xFFFF2200.toInt())
        repository.setSymmetry(symmetry)
        assertTrue(repository.setLayerDrawingAssist(layer, true))
        requireNotNull(repository.compositePreviewFrame())
        return layer to base
    }

    private fun replay(
        strokeId: Long,
        base: PixelBuffer,
    ): PixelBuffer {
        val stroke = requireNotNull(repository.activeStroke(strokeId))
        val result = base.copy()
        val renderer = StrokeRasterizer()
        try {
            SymmetryEngine.mirrorStroke(stroke, width, height, symmetry).forEach { renderer.draw(result, it) }
        } finally {
            renderer.release()
        }
        return result
    }

    private fun assertPixelsMatch(
        expected: PixelBuffer,
        actual: PixelBuffer,
    ) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        for (index in expected.pixels.indices) {
            for (shift in intArrayOf(24, 16, 8, 0)) {
                val difference = abs(((expected.pixels[index] ushr shift) and 255) - ((actual.pixels[index] ushr shift) and 255))
                assertTrue("Pixel $index channel $shift differs by $difference", difference <= 2)
            }
        }
    }

    @Test
    fun mirroredNewWetControlsMatchFullReplayDuringPreviewAndAfterCommit() =
        runTest {
            val variants = listOf(plain.copy(wetDilution = 0.4f), plain.copy(wetCharge = 0.4f), plain.copy(bleed = 0.7f))
            for (params in variants) {
                assertEquals(0f, params.wetMix, 0f)
                assertEquals(0f, params.dilution, 0f)
                val (layer, base) = open()
                // The marks overlap the symmetry axis, so the second mirror must sample the first.
                val stroke = repository.beginStroke(29f, 20f, 1f, params, layer, timestamp = 0)
                assertPixelsMatch(replay(stroke, base), requireNotNull(repository.compositePreviewFrame()).buffer)
                repository.continueStroke(stroke, 33f, 23f, 1f, timestamp = 20)
                assertPixelsMatch(replay(stroke, base), requireNotNull(repository.compositePreviewFrame()).buffer)
                repository.continueStroke(stroke, 35f, 28f, 1f, timestamp = 40)
                val expected = replay(stroke, base)
                assertPixelsMatch(expected, requireNotNull(repository.compositePreviewFrame()).buffer)
                repository.endStroke(stroke)
                assertPixelsMatch(expected, requireNotNull(repository.compositeFrame(0, transparentBackground = true)))
            }
        }

    @Test
    fun dryMirroredStrokesKeepIncrementalPreviewAndTheSameCommittedPixels() =
        runTest {
            val (layer, base) = open()
            val stroke = repository.beginStroke(10f, 12f, 1f, plain.copy(size = 6f), layer, timestamp = 0)
            repository.continueStroke(stroke, 16f, 16f, 1f, timestamp = 20)
            val first = requireNotNull(repository.compositePreviewFrame())
            assertNotNull(first.dirty)
            assertPixelsMatch(replay(stroke, base), first.buffer)
            repository.continueStroke(stroke, 20f, 17f, 1f, timestamp = 40)
            val next = requireNotNull(repository.compositePreviewFrame())
            assertNotNull(next.dirty)
            val expected = replay(stroke, base)
            assertPixelsMatch(expected, next.buffer)
            repository.endStroke(stroke)
            assertPixelsMatch(expected, requireNotNull(repository.compositeFrame(0, transparentBackground = true)))
        }

    @Test
    fun maskRevealAndHideIgnoreWetPickupAndColourDynamicsInBothBrushes() =
        runTest {
            val wet =
                plain.copy(
                    wetMix = 0.8f,
                    wetDilution = 1f,
                    wetCharge = 0f,
                    bleed = 1f,
                    colorDynamics = ColorDynamics(pressureBrightness = 1f),
                )
            for (params in listOf(wet, plain.copy(dual = DualBrush(wet)))) {
                for (destination in listOf(StrokeDestination.MASK_REVEAL, StrokeDestination.MASK_HIDE)) {
                    repository.createCanvas(16, 16, 72)
                    val layer = repository.getActiveLayerId()
                    assertTrue(repository.setLayerPixels(layer, PixelBuffer.filled(16, 16, -1), "Mask fixture"))
                    val initial = if (destination == StrokeDestination.MASK_REVEAL) 0 else 255
                    assertTrue(repository.addLayerMask(layer, SelectionMask(16, 16, ByteArray(256) { initial.toByte() })))
                    val stroke = repository.beginStroke(8f, 8f, 0f, params, layer, destination = destination)
                    repository.endStroke(stroke)
                    val coverage = repository.exportSnapshot(false, false, true, preserveLayerMasks = true).layerMasks.getValue(layer)
                    assertEquals(255 - initial, coverage.pixels[8 * 16 + 8] ushr 24)
                    assertEquals(initial, coverage.pixels[0] ushr 24)
                }
            }
        }
}
