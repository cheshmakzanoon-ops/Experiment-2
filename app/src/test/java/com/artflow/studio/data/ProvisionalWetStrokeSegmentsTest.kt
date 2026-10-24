package com.artflow.studio.data

import android.content.Context
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.brush.BrushParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/** A later wet segment must pick up earlier provisional paint exactly as sequential commits do. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProvisionalWetStrokeSegmentsTest {
    private lateinit var repository: CanvasRepositoryImpl
    private lateinit var reference: CanvasRepositoryImpl
    private val wet =
        BrushParams(
            size = 20f,
            spacing = 0.25f,
            pressureToSize = 0f,
            pressureToOpacity = 0f,
            smoothing = 0f,
            wetMix = 1f,
        )

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
    fun laterWetPreviewBeforeAndAfterSealingMatchesSequentialCommittedPixels() =
        runTest {
            for (brush in listOf(wet, wet.copy(pull = 0.75f, wetPull = 0.5f, wetBlur = 0.5f))) {
                val original = prepare(repository)
                val layer = repository.getActiveLayerId()
                val depth = repository.undoDepth
                val mark = repository.historyMark
                requireNotNull(repository.compositePreviewFrame())
                val first = start(repository, brush, firstSamples)
                requireNotNull(repository.compositePreview())
                repository.sealStrokeSegment(first)
                val firstPreview = requireNotNull(repository.compositePreviewFrame()).buffer.pixels.copyOf()
                if (brush == wet) assertEquals(FIRST_PICKUP, firstPreview[PROBE])

                val second = start(repository, brush, secondSamples.take(1))
                var expected = IntArray(0)
                for (index in 1 until secondSamples.size) {
                    append(repository, second, secondSamples[index])
                    expected = sequentialPixels(brush, secondSamples.take(index + 1))
                    val full = requireNotNull(repository.compositePreview()).pixels.copyOf()
                    val frame = requireNotNull(repository.compositePreviewFrame())
                    assertNull("Dependent pickup needs the full provisional layer and its earlier path", frame.dirty)
                    assertArrayEquals("Active full preview must sample the earlier provisional segment", expected, full)
                    assertArrayEquals("The frame path must agree after each appended wet sample", expected, frame.buffer.pixels)
                    if (brush == wet) assertEquals(SECOND_PICKUP, expected[PROBE])
                    assertArrayEquals(
                        "Preview cannot commit provisional paint",
                        original,
                        requireNotNull(repository.layerPixels(layer)).pixels,
                    )
                    assertEquals(depth, repository.undoDepth)
                    assertEquals(mark, repository.historyMark)
                }

                // The 94.5 -> 158.5 update above would normally crop away the first 30.5 sample.
                // The carried/pulled/blurred variant therefore exercises pickup history outside
                // that tail crop, as well as the simple overlap's independently known color.
                repository.sealStrokeSegment(second)
                assertArrayEquals(
                    "Sealing must not change visible wet pixels",
                    expected,
                    requireNotNull(repository.compositePreview()).pixels,
                )
                assertArrayEquals(expected, requireNotNull(repository.compositePreviewFrame()).buffer.pixels)
                assertArrayEquals(original, requireNotNull(repository.layerPixels(layer)).pixels)
                assertEquals(depth, repository.undoDepth)

                repository.endStroke(first)
                repository.endStroke(second)
                assertArrayEquals(
                    "Sequential final commits must match the provisional preview",
                    expected,
                    requireNotNull(repository.layerPixels(layer)).pixels,
                )
                assertArrayEquals(expected, requireNotNull(repository.compositePreviewFrame()).buffer.pixels)
                assertEquals(depth + 2, repository.undoDepth)
                assertNull(repository.activeStroke(first))
                assertNull(repository.activeStroke(second))
            }
        }

    @Test
    fun cancellingSealedAndGrowingWetSegmentsRestoresOriginalPixelsAndHistory() =
        runTest {
            val original = prepare(repository)
            val layer = repository.getActiveLayerId()
            val depth = repository.undoDepth
            val mark = repository.historyMark
            requireNotNull(repository.compositePreviewFrame())
            val first = start(repository, wet, firstSamples)
            requireNotNull(repository.compositePreview())
            repository.sealStrokeSegment(first)
            requireNotNull(repository.compositePreviewFrame())
            val second = start(repository, wet, secondSamples)
            val expected = sequentialPixels(wet, secondSamples)
            val preview = requireNotNull(repository.compositePreviewFrame()).buffer.pixels.copyOf()
            assertEquals(SECOND_PICKUP, preview[PROBE])
            assertArrayEquals(expected, preview)
            assertFalse("The fixture must show actual provisional paint", original.contentEquals(preview))
            assertArrayEquals(original, requireNotNull(repository.layerPixels(layer)).pixels)

            repository.cancelStroke(second)
            repository.cancelStroke(first)
            assertNull(repository.activeStroke(first))
            assertNull(repository.activeStroke(second))
            assertArrayEquals(original, requireNotNull(repository.layerPixels(layer)).pixels)
            assertArrayEquals(original, requireNotNull(repository.compositePreview()).pixels)
            assertArrayEquals(original, requireNotNull(repository.compositePreviewFrame()).buffer.pixels)
            assertEquals(depth, repository.undoDepth)
            assertEquals(mark, repository.historyMark)
        }

    private fun newRepository(): CanvasRepositoryImpl = CanvasRepositoryImpl(ProjectStorage(mock(Context::class.java)))

    private suspend fun prepare(target: CanvasRepositoryImpl): IntArray {
        target.createCanvas(WIDTH, HEIGHT, 72)
        val original = IntArray(WIDTH * HEIGHT) { RED }
        assertTrue(target.setLayerPixels(target.getActiveLayerId(), PixelBuffer(WIDTH, HEIGHT, original.copyOf()), "Wet fixture"))
        target.setStrokeColor(BLUE)
        return original
    }

    /** Ordinary committed strokes, never sealed and never rendered through the preview cache. */
    private suspend fun sequentialPixels(
        brush: BrushParams,
        samples: List<Sample>,
    ): IntArray {
        prepare(reference)
        reference.endStroke(start(reference, brush, firstSamples))
        reference.endStroke(start(reference, brush, samples))
        return requireNotNull(reference.layerPixels(reference.getActiveLayerId())).pixels.copyOf()
    }

    private fun start(
        target: CanvasRepositoryImpl,
        brush: BrushParams,
        samples: List<Sample>,
    ): Long {
        val point = samples.first()
        val id = target.beginStroke(point.x, point.y, 1f, brush, target.getActiveLayerId(), timestamp = point.time)
        assertTrue(id != 0L)
        samples.drop(1).forEach { append(target, id, it) }
        return id
    }

    private fun append(
        target: CanvasRepositoryImpl,
        id: Long,
        point: Sample,
    ) {
        target.continueStroke(id, point.x, point.y, 1f, timestamp = point.time)
    }

    private data class Sample(
        val x: Float,
        val y: Float,
        val time: Long,
    )

    private companion object {
        const val WIDTH = 256
        const val HEIGHT = 128
        const val PROBE = 60 * WIDTH + 40
        val RED = 0xFFFF0000.toInt()
        val BLUE = 0xFF0000FF.toInt()

        // Local pickup mixes 60% of opaque underlying paint into fresh blue:
        // first (153, 0, 102), then (round(153*0.6), 0, round(255+(102-255)*0.6)).
        val FIRST_PICKUP = 0xFF990066.toInt()
        val SECOND_PICKUP = 0xFF5C00A3.toInt()

        // Five-pixel dab steps and binary-exact sample fractions avoid unrelated crop-coordinate rounding.
        val firstSamples = listOf(Sample(30.5f, 60.5f, 1000L), Sample(190.5f, 60.5f, 1016L))
        val secondSamples = listOf(Sample(30.5f, 60.5f, 2000L), Sample(94.5f, 60.5f, 2016L), Sample(158.5f, 60.5f, 2032L))
    }
}
