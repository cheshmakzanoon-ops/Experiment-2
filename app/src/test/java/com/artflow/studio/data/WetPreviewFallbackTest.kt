package com.artflow.studio.data

import android.content.Context
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.symmetry.SymmetryEngine
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.DualBrush
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.model.layer.Layer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/** Replayed wet dabs must retain pickup from the path before the latest preview damage. */
@OptIn(ExperimentalCoroutinesApi::class)
class WetPreviewFallbackTest {
    private lateinit var repository: CanvasRepositoryImpl
    private lateinit var reference: CanvasRepositoryImpl
    private val plain =
        BrushParams(
            size = 20f,
            spacing = 0.25f,
            pressureToSize = 0f,
            pressureToOpacity = 0f,
            smoothing = 0f,
        )
    private val wet = plain.copy(wetMix = 0.1f, pull = 1f)
    private val compound = wet.copy(dual = DualBrush(plain))
    private val dryEdit = plain.copy(size = 8f, opacity = 0.25f)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = newRepository()
        reference = newRepository()
    }

    @After
    fun tearDown() {
        repository.dispose()
        reference.dispose()
        Dispatchers.resetMain()
    }

    @Test
    fun wetCompoundTailMatchesCommitAndCancelPreservesTheOriginalDocument() =
        runTest {
            openBoth()
            val saved = savedState()
            val expected = commitReference(compound)
            // Frozen full-canvas replay: this point is FF3800C7 in the old cropped tail.
            assertEquals(0xFFDF0020.toInt(), expected[60 * WIDTH + 190])

            val cancelled = growingPreview(compound, expected)
            assertSavedUnchanged(saved)
            repository.cancelStroke(cancelled)
            assertNull(repository.activeStroke(cancelled))
            assertArrayEquals(saved.pixels, requireNotNull(repository.compositePreviewFrame()).buffer.pixels)
            assertSavedUnchanged(saved)

            val committed = growingPreview(compound, expected)
            assertSavedUnchanged(saved)
            repository.endStroke(committed)
            assertArrayEquals(expected, savedPixels(repository))
            assertArrayEquals(expected, requireNotNull(repository.compositePreviewFrame()).buffer.pixels)
            assertEquals(saved.undoDepth + 1, repository.undoDepth)
            assertTrue(repository.undo())
            assertArrayEquals(saved.pixels, savedPixels(repository))
            assertTrue(repository.redo())
            assertArrayEquals(expected, savedPixels(repository))
        }

    @Test
    fun wetMirrorsRetainEarlierPickupWhenOnlyTheTailMoves() =
        runTest {
            openBoth()
            val symmetry = SymmetryEngine.Settings(type = SymmetryEngine.SymmetryType.VERTICAL)
            for (target in listOf(repository, reference)) {
                target.setSymmetry(symmetry)
                assertTrue(target.setLayerDrawingAssist(target.getActiveLayerId(), true))
            }
            val saved = savedState()
            val expected = commitReference(wet)
            val stroke = growingPreview(wet, expected)
            assertSavedUnchanged(saved)
            repository.endStroke(stroke)
            assertArrayEquals(expected, savedPixels(repository))
        }

    @Test
    fun aDryEditCannotCropAwayThePickupOfAnEarlierSealedWetSegment() =
        runTest {
            openBoth()
            val saved = savedState()
            val wetPixels = commitReference(wet)
            val expected = commitReference(dryEdit, drySamples)
            requireNotNull(repository.compositePreviewFrame())
            val first = start(repository, wet)
            assertPreview(wetPixels)
            repository.sealStrokeSegment(first)
            assertPreview(wetPixels)

            val second = start(repository, dryEdit, drySamples)
            assertPreview(expected)
            assertSavedUnchanged(saved)
            // A small dry edit at x300 does not discard the sealed wet segment's x30 pickup.
            assertEquals(0xFFF70008.toInt(), expected[52 * WIDTH + 292])
            repository.endStroke(first)
            repository.endStroke(second)
            assertArrayEquals(expected, savedPixels(repository))
            assertTrue(repository.undo())
            assertTrue(repository.undo())
            assertArrayEquals(saved.pixels, savedPixels(repository))
        }

    @Test
    fun historicalWetVectorsOnTheEditedLayerSurvivePreviewAndCancellation() = runTest { historicalWetPreview(onLowerLayer = false) }

    @Test
    fun historicalWetVectorsBelowTheEditedLayerSurvivePreviewAndCancellation() = runTest { historicalWetPreview(onLowerLayer = true) }

    @Test
    fun wetStrokeOverDryVectorHistoryUsesTheCompletePickupPath() =
        runTest {
            openBoth()
            for (target in listOf(repository, reference)) {
                val layer = target.getActiveLayerId()
                assertTrue(target.replaceLayerStrokes(layer, listOf(historical(layer, plain.copy(opacity = 0.1f)))))
            }
            val saved = savedState()
            val expected = commitReference(wet)
            val stroke = growingPreview(wet, expected)
            assertSavedUnchanged(saved)
            repository.endStroke(stroke)
            assertArrayEquals(expected, savedPixels(repository))
            assertTrue(
                repository
                    .getAllLayers()
                    .single()
                    .strokes
                    .isEmpty(),
            )
            assertTrue(repository.undo())
            assertArrayEquals(saved.pixels, savedPixels(repository))
            assertEquals(saved.layers, repository.getAllLayers())
        }

    @Test
    fun soloLiveWetAndDryCompoundControlsKeepExactRegionalPreviews() =
        runTest {
            for (brush in listOf(wet, plain.copy(pull = 1f, dual = DualBrush(plain)))) {
                openBoth()
                val saved = savedState()
                val expected = commitReference(brush)
                val stroke = growingPreview(brush, expected, regional = true)
                assertSavedUnchanged(saved)
                repository.endStroke(stroke)
                assertArrayEquals(expected, savedPixels(repository))
            }
        }

    private suspend fun historicalWetPreview(onLowerLayer: Boolean) {
        openBoth()
        for (target in listOf(repository, reference)) {
            val layer = target.getActiveLayerId()
            assertTrue(target.replaceLayerStrokes(layer, listOf(historical(layer, wet))))
            if (onLowerLayer) target.addLayer("Dry edit")
        }
        val saved = savedState()
        assertEquals(1, saved.layers.sumOf { it.strokes.size })
        val expected = commitReference(dryEdit, drySamples)
        requireNotNull(repository.compositePreviewFrame())
        val stroke = start(repository, dryEdit, drySamples)
        assertPreview(expected)
        assertSavedUnchanged(saved)

        repository.cancelStroke(stroke)
        assertNull(repository.activeStroke(stroke))
        // This also caught the old defect after cancellation: replaying the historical lower
        // layer inside the erased preview area still forgot its earlier wet samples.
        assertPreview(saved.pixels)
        assertSavedUnchanged(saved)
        repository.endStroke(start(repository, dryEdit, drySamples))
        assertArrayEquals(expected, savedPixels(repository))
    }

    /** The reference commits normally and is never passed through either preview API. */
    private suspend fun commitReference(
        brush: BrushParams,
        samples: List<Sample> = longSamples,
    ): IntArray {
        reference.endStroke(start(reference, brush, samples))
        return savedPixels(reference)
    }

    private suspend fun growingPreview(
        brush: BrushParams,
        expected: IntArray,
        regional: Boolean = false,
    ): Long {
        requireNotNull(repository.compositePreviewFrame())
        val stroke = start(repository, brush, longSamples.take(1))
        for (index in 1 until longSamples.size) {
            append(repository, stroke, longSamples[index])
            val frame = requireNotNull(repository.compositePreviewFrame())
            if (regional) assertNotNull("Eligible controls should retain regional redraws", frame.dirty)
            val full = requireNotNull(repository.compositePreview()).pixels
            assertArrayEquals("The frame must retain all earlier pickup before sample $index", full, frame.buffer.pixels)
            if (index == longSamples.lastIndex) assertArrayEquals("An independent commit must agree", expected, frame.buffer.pixels)
        }
        return stroke
    }

    private suspend fun assertPreview(expected: IntArray) {
        assertArrayEquals(expected, requireNotNull(repository.compositePreviewFrame()).buffer.pixels)
        assertArrayEquals(expected, requireNotNull(repository.compositePreview()).pixels)
    }

    private suspend fun savedPixels(target: CanvasRepositoryImpl): IntArray =
        requireNotNull(target.compositeFrame(0, transparentBackground = true)).pixels.copyOf()

    private suspend fun savedState(): SavedState =
        SavedState(savedPixels(repository), repository.getAllLayers(), repository.undoDepth, repository.historyMark)

    private suspend fun assertSavedUnchanged(saved: SavedState) {
        assertArrayEquals("Preview must not change committed pixels", saved.pixels, savedPixels(repository))
        assertEquals("Preview must retain the committed vector/raster layer state", saved.layers, repository.getAllLayers())
        assertEquals(saved.undoDepth, repository.undoDepth)
        assertEquals(saved.historyMark, repository.historyMark)
    }

    private suspend fun openBoth() {
        for (target in listOf(repository, reference)) {
            target.createCanvas(WIDTH, HEIGHT, 72)
            assertTrue(target.setLayerPixels(target.getActiveLayerId(), PixelBuffer.filled(WIDTH, HEIGHT, RED), "Wet fixture"))
            target.setStrokeColor(BLUE)
        }
    }

    private fun start(
        target: CanvasRepositoryImpl,
        brush: BrushParams,
        samples: List<Sample> = longSamples,
    ): Long {
        val first = samples.first()
        val id = target.beginStroke(first.x, first.y, 1f, brush, target.getActiveLayerId(), timestamp = first.time)
        assertTrue(id != 0L)
        samples.drop(1).forEach { append(target, id, it) }
        return id
    }

    private fun append(
        target: CanvasRepositoryImpl,
        id: Long,
        sample: Sample,
    ) {
        target.continueStroke(id, sample.x, sample.y, 1f, timestamp = sample.time)
    }

    private fun historical(
        layer: Long,
        params: BrushParams,
    ): Stroke =
        Stroke(
            id = 9001L,
            points = longSamples.map { StrokePoint(it.x, it.y, timestamp = it.time, color = BLUE) },
            brushParams = params,
            layerId = layer,
            color = BLUE,
            timestamp = 1000L,
        )

    private fun newRepository(): CanvasRepositoryImpl = CanvasRepositoryImpl(ProjectStorage(mock(Context::class.java)))

    private data class SavedState(
        val pixels: IntArray,
        val layers: List<Layer>,
        val undoDepth: Int,
        val historyMark: Long,
    )

    private data class Sample(
        val x: Float,
        val y: Float,
        val time: Long,
    )

    private companion object {
        const val WIDTH = 512
        const val HEIGHT = 128
        val RED = 0xFFFF0000.toInt()
        val BLUE = 0xFF0000FF.toInt()

        // Exact half-pixel coordinates and five-pixel dab steps avoid unrelated crop rounding.
        val longSamples = listOf(Sample(30.5f, 60.5f, 1000L), Sample(190.5f, 60.5f, 1016L), Sample(350.5f, 60.5f, 1032L))
        val drySamples = listOf(Sample(300.5f, 60.5f, 2000L), Sample(330.5f, 60.5f, 2016L))
    }
}
