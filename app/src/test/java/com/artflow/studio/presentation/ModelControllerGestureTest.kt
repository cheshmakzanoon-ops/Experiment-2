package com.artflow.studio.presentation

import android.content.Context
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.presentation.ui.viewmodel.ModelController
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/** Uses the real stroke store and compositor; segment completion is distinct from gesture commit. */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelControllerGestureTest {
    private lateinit var repository: CanvasRepositoryImpl
    private val brush =
        BrushParams(size = 8f, opacity = 0.6f, spacing = 0.12f, pressureToSize = 0f, pressureToOpacity = 0f, wetMix = 0.3f)
    private val original = IntArray(96 * 64) { index -> if (index % 96 < 48) 0xFFB04020.toInt() else 0xFF3060A0.toInt() }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = CanvasRepositoryImpl(ProjectStorage(mock(Context::class.java)))
    }

    @After fun tearDown() {
        repository.dispose()
        Dispatchers.resetMain()
    }

    @Test
    fun cancelDiscardsEndedAndGrowingSegmentsWithoutPixelsOrHistory() =
        runTest {
            prepare(repository)
            val layer = repository.getActiveLayerId()
            val history = repository.historyMark
            val undo = repository.undoDepth
            val painter = ModelController(repository, backgroundScope, {}).painter({ brush }, { layer })
            painter.begin(0.15f, 0.5f, 1f)
            painter.move(0.35f, 0.5f, 1f)
            painter.end()
            painter.begin(0.65f, 0.5f, 1f)
            painter.move(0.85f, 0.5f, 1f)
            val preview = requireNotNull(repository.compositePreview()).pixels
            assertTrue((0 until 48).any { x -> preview[32 * 96 + x] != original[32 * 96 + x] })
            assertTrue((48 until 96).any { x -> preview[32 * 96 + x] != original[32 * 96 + x] })
            assertArrayEquals(original, requireNotNull(repository.layerPixels(layer)).pixels)
            assertEquals(history, repository.historyMark)
            assertEquals(undo, repository.undoDepth)

            painter.cancel()
            assertNull(repository.activeStroke(1))
            assertNull(repository.activeStroke(2))
            assertArrayEquals(original, requireNotNull(repository.compositePreview()).pixels)
            assertArrayEquals(original, requireNotNull(repository.layerPixels(layer)).pixels)
            assertEquals(history, repository.historyMark)
            assertEquals(undo, repository.undoDepth)
        }

    @Test
    fun finishingKeepsSequentialSegmentPixelsAndTheirExistingUndoSteps() =
        runTest {
            prepare(repository)
            val reference = CanvasRepositoryImpl(ProjectStorage(mock(Context::class.java)))
            try {
                prepare(reference)
                val layer = repository.getActiveLayerId()
                val undo = repository.undoDepth
                var currentBrush = brush
                val painter = ModelController(repository, backgroundScope, {}).painter({ currentBrush }, { layer })
                painter.begin(0.2f, 0.5f, 1f)
                painter.move(0.5f, 0.5f, 1f)
                painter.end()
                // A gesture keeps its captured brush even if another control changes between islands.
                currentBrush = brush.copy(size = 30f, opacity = 1f)
                painter.begin(0.4f, 0.5f, 1f)
                painter.move(0.7f, 0.5f, 1f)
                painter.end()
                painter.finish()

                sequentialSegment(reference, 0.2f, 0.5f)
                sequentialSegment(reference, 0.4f, 0.7f)
                val expected = requireNotNull(reference.layerPixels(reference.getActiveLayerId())).pixels
                assertFalse(original.contentEquals(expected))
                assertArrayEquals(expected, requireNotNull(repository.layerPixels(layer)).pixels)
                assertEquals(undo + 2, repository.undoDepth)
                assertTrue(repository.undo())
                assertTrue(repository.undo())
                assertArrayEquals(original, requireNotNull(repository.layerPixels(layer)).pixels)
                assertTrue(repository.redo())
                assertTrue(repository.redo())
                assertArrayEquals(expected, requireNotNull(repository.layerPixels(layer)).pixels)
            } finally {
                reference.dispose()
            }
        }

    @Test
    fun aStaleGestureCannotCommitAcrossOrUndoAnUnrelatedEdit() =
        runTest {
            prepare(repository)
            val layer = repository.getActiveLayerId()
            val painter = ModelController(repository, backgroundScope, {}).painter({ brush }, { layer })
            painter.begin(0.2f, 0.5f, 1f)
            painter.move(0.4f, 0.5f, 1f)
            painter.end()
            assertTrue(repository.setLayerName(layer, "Keep this edit"))
            val history = repository.historyMark
            val undo = repository.undoDepth
            painter.begin(0.6f, 0.5f, 1f)
            painter.finish()
            assertEquals("Keep this edit", repository.getActiveLayer()?.name)
            assertEquals(history, repository.historyMark)
            assertEquals(undo, repository.undoDepth)
            assertArrayEquals(original, requireNotNull(repository.layerPixels(layer)).pixels)
            assertArrayEquals(original, requireNotNull(repository.compositePreview()).pixels)
            assertNull(repository.activeStroke(1))
        }

    @Test
    fun reusedStrokeIdsAfterCanvasReplacementAreNotMovedSealedCommittedOrCancelled() =
        runTest {
            prepare(repository)
            val layer = repository.getActiveLayerId()
            // Keep the ordinary freshness metadata fixed to exercise the identity guard itself.
            // All stroke storage, preview and raster operations still use the real repository.
            val sameMetadata =
                object : CanvasRepository by repository {
                    override val historyMark: Long = 42L

                    override fun projectId(): Long = 777L
                }
            val painter = ModelController(sameMetadata, backgroundScope, {}).painter({ brush }, { layer })
            painter.begin(0.2f, 0.5f, 1f)
            val old = requireNotNull(repository.activeStroke(1)).points.first()
            prepare(repository)
            assertEquals(42L, sameMetadata.historyMark)
            assertEquals(777L, sameMetadata.projectId())
            assertEquals(layer, sameMetadata.getActiveLayerId())
            val replacement = repository.beginStroke(old.x, old.y, old.pressure, brush, layer, timestamp = old.timestamp)
            assertEquals("The regression must really reuse the ID", 1L, replacement)
            val point = requireNotNull(repository.activeStroke(replacement)).points.first()
            assertEquals("Even identical point values do not imply the same gesture", old, point)
            painter.move(0.9f, 0.5f, 1f)
            painter.end()
            painter.finish()
            painter.cancel()
            val remaining = requireNotNull(repository.activeStroke(replacement))
            assertEquals(1, remaining.points.size)
            assertSame(point, remaining.points.first())
            assertArrayEquals(original, requireNotNull(repository.layerPixels(layer)).pixels)
            repository.endStroke(replacement)
            assertNotNull(repository.layerPixels(layer))
            assertFalse(original.contentEquals(requireNotNull(repository.layerPixels(layer)).pixels))
        }

    private suspend fun prepare(target: CanvasRepositoryImpl) {
        target.createCanvas(96, 64, 72)
        assertTrue(target.setLayerPixels(target.getActiveLayerId(), PixelBuffer(96, 64, original.copyOf()), "Fixture"))
        target.setStrokeColor(0xFF20D070.toInt())
    }

    private fun sequentialSegment(
        target: CanvasRepositoryImpl,
        from: Float,
        to: Float,
    ) {
        val id = target.beginStroke(from * 96f, 32f, 1f, brush, target.getActiveLayerId())
        target.continueStroke(id, to * 96f, 32f, 1f)
        target.endStroke(id)
    }
}
