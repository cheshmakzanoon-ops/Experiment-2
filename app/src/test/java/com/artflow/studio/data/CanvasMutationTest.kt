package com.artflow.studio.data

import android.content.Context
import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.core.pixels.AdjustmentProcessor
import com.artflow.studio.core.pixels.LayerMaskSource
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.model.layer.AdjustmentType
import com.artflow.studio.domain.model.layer.FilterType
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/** Real document operations; only Android Context and its main dispatcher are replaced. */
@OptIn(ExperimentalCoroutinesApi::class)
class CanvasMutationTest {
    private lateinit var repository: CanvasRepositoryImpl
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val green = 0xFF00FF00.toInt()

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

    private suspend fun open(): Long {
        repository.createCanvas(8, 6, 72)
        return repository.getActiveLayerId()
    }

    private suspend fun image(transparent: Boolean = true): PixelBuffer =
        requireNotNull(repository.compositeFrame(0, transparentBackground = transparent))

    private suspend fun paint(
        layer: Long,
        color: Int,
    ) {
        assertTrue(repository.setLayerPixels(layer, PixelBuffer.filled(8, 6, color), "Fixture"))
    }

    private suspend fun legacyInk(): Long {
        val layer = open()
        val points = listOf(StrokePoint(2.5f, 1.5f, timestamp = 0))
        repository.replaceLayerStrokes(
            layer,
            listOf(
                Stroke(
                    id = 71,
                    points = points,
                    brushParams = BrushParams(size = 3f, pressureToSize = 0f),
                    layerId = layer,
                    color = red,
                    timestamp = 0,
                ),
            ),
        )
        assertTrue(image().pixels.any { (it ushr 24) != 0 })
        return layer
    }

    @Test
    fun alphaLockChangesInvalidatePendingRasterEdits() =
        runTest {
            repository.createCanvas(16, 16, 72)
            val layer = repository.getActiveLayerId()
            val session = requireNotNull(repository.beginRasterEdit(layer))
            assertFalse(session.alphaLocked)
            session.buffer.fill(0xFFFF0000.toInt())
            repository.setLayerAlphaLock(layer, true)
            val depth = repository.undoDepth
            assertFalse(repository.commitRasterEdit(session, "Stale lock policy"))
            assertEquals(depth, repository.undoDepth)
            assertTrue(repository.layerPixels(layer)?.isEmpty() != false)
            val lockedSession = requireNotNull(repository.beginRasterEdit(layer))
            assertTrue(lockedSession.alphaLocked)
            repository.cancelRasterEdit(lockedSession)
        }

    @Test
    fun transientToolRevisionChangesForCreateCommitUndoRedoAndDispose() =
        runTest {
            val initial = repository.contentRevision
            val layer = open()
            val created = repository.contentRevision
            assertTrue(created > initial)
            val session = requireNotNull(repository.beginRasterEdit(layer))
            assertEquals(created, session.contentRevision)
            assertEquals(created, repository.contentRevision)
            session.buffer.fill(red)
            assertTrue(repository.commitRasterEdit(session, "Revision fixture"))
            val committed = repository.contentRevision
            assertTrue(committed > created)
            assertTrue(repository.undo())
            val undone = repository.contentRevision
            assertTrue(undone > committed)
            assertTrue(repository.redo())
            val redone = repository.contentRevision
            assertTrue(redone > undone)
            repository.dispose()
            assertTrue(repository.contentRevision > redone)
        }

    @Test
    fun mergeDownPreservesAlphaAndDoesNotApplyMaskOrOpacityTwice() =
        runTest {
            val lower = open()
            paint(lower, 0x800000FF.toInt())
            repository.setLayerOpacity(lower, 0.5f)
            repository.addLayerMask(lower, SelectionMask(8, 6).apply { coverage.fill(128.toByte()) })
            val upper = repository.addLayer("Upper").id
            paint(upper, 0x80FF0000.toInt())
            repository.setLayerOpacity(upper, 0.75f)
            val before = image()
            assertTrue(repository.mergeLayerDown(upper))
            assertArrayEquals(before.pixels, image().pixels)
            val baked = requireNotNull(repository.getActiveLayer())
            assertEquals(1f, baked.opacity, 0f)
            assertFalse(baked.hasMask())
            assertTrue(baked.strokes.isEmpty())
        }

    @Test
    fun mergingDoesNotBakeTheCanvasBackgroundIntoTransparentArtwork() =
        runTest {
            val lower = open()
            paint(lower, 0x400000FF)
            val upper = repository.addLayer("Upper").id
            paint(upper, 0x40FF0000)
            repository.setCanvasBackgroundColor(green)
            val before = image()
            assertTrue(repository.mergeLayerDown(upper))
            assertArrayEquals(before.pixels, image().pixels)
            repository.setCanvasBackgroundColor(0)
            assertArrayEquals(before.pixels, image(false).pixels)
        }

    @Test
    fun mergeLayersUsesStackOrderRatherThanArgumentOrder() =
        runTest {
            val lower = open()
            paint(lower, blue)
            val upper = repository.addLayer("Upper").id
            paint(upper, red)
            val before = image()
            assertTrue(repository.mergeLayers(upper, lower))
            assertArrayEquals(before.pixels, image().pixels)
        }

    @Test
    fun mergeDownDoesNotDeleteHiddenOrLockedSources() =
        runTest {
            val lower = open()
            paint(lower, blue)
            val upper = repository.addLayer("Upper").id
            paint(upper, red)
            repository.setLayerVisibility(lower, false)
            val depth = repository.undoDepth
            assertFalse(repository.mergeLayerDown(upper))
            assertEquals(depth, repository.undoDepth)
            assertEquals(2, repository.getAllLayers().size)
            repository.setLayerVisibility(lower, true)
            repository.setLayerLock(lower, true)
            assertFalse(repository.mergeLayerDown(upper))
            assertEquals(2, repository.getAllLayers().size)
        }

    @Test
    fun mergedCopyRetainsSourcesWithoutDoublingTheirOpacity() =
        runTest {
            val lower = open()
            paint(lower, 0x400000FF)
            val upper = repository.addLayer("Upper").id
            paint(upper, 0x40FF0000)
            val before = image()
            assertTrue(repository.mergeVisibleLayers(keepOriginals = true) != null)
            assertArrayEquals(before.pixels, image().pixels)
            assertEquals(3, repository.getAllLayers().size)
            assertEquals(1, repository.getAllLayers().count { it.isVisible })
        }

    @Test
    fun mergeUndoAndRedoRestorePixelsAndEditableMasks() =
        runTest {
            val lower = open()
            paint(lower, blue)
            repository.createLayerMask(lower, LayerMaskSource.HORIZONTAL)
            val upper = repository.addLayer("Upper").id
            paint(upper, 0x40FF0000)
            val before = image()
            val depth = repository.undoDepth
            assertTrue(repository.mergeLayerDown(upper))
            assertEquals(depth + 1, repository.undoDepth)
            assertTrue(repository.undo())
            assertEquals(2, repository.getAllLayers().size)
            assertTrue(repository.getAllLayers().first().hasMask())
            assertArrayEquals(before.pixels, image().pixels)
            assertTrue(repository.redo())
            assertArrayEquals(before.pixels, image().pixels)
        }

    @Test
    fun rotationPreservesLegacyVectorInkAndItsUndoState() =
        runTest {
            legacyInk()
            val before = image()
            assertTrue(repository.rotateCanvas(90))
            assertArrayEquals(before.rotated(90).pixels, image().pixels)
            assertEquals(6, repository.getCanvasSize().width)
            assertEquals(8, repository.getCanvasSize().height)
            assertTrue(repository.undo())
            assertArrayEquals(before.pixels, image().pixels)
        }

    @Test
    fun flipPreservesLegacyVectorInk() =
        runTest {
            legacyInk()
            val before = image()
            val props = CanvasOperations.CanvasProperties(8, 6, 72, 0)
            val expected = CanvasOperations.flip(before, CanvasOperations.FlipAxis.HORIZONTAL, props).buffer
            assertTrue(repository.flipCanvas(false))
            assertArrayEquals(expected.pixels, image().pixels)
        }

    @Test
    fun resampleScalesLegacyVectorInkWithTheRestOfTheCanvas() =
        runTest {
            legacyInk()
            val before = image()
            assertTrue(repository.resizeCanvas(16, 12, true, CanvasOperations.Anchor.CENTER))
            assertArrayEquals(before.scaled(16, 12).pixels, image().pixels)
        }

    @Test
    fun unsupportedRotationCannotPushAnUndoStep() =
        runTest {
            open()
            val depth = repository.undoDepth
            assertFalse(repository.rotateCanvas(45))
            assertEquals(depth, repository.undoDepth)
        }

    @Test
    fun rasterSessionStartsWithAllHistoricalInk() =
        runTest {
            val layer = legacyInk()
            val expected = image()
            val session = requireNotNull(repository.beginRasterEdit(layer))
            assertArrayEquals(expected.pixels, session.buffer.pixels)
            repository.cancelRasterEdit(session)
        }

    @Test
    fun committedRasterEditsDoNotReplayOlderInkOverNewPixels() =
        runTest {
            val layer = legacyInk()
            assertTrue(repository.applyRasterEdit(layer, "Replace") { it.fill(blue) })
            assertTrue(image().pixels.all { it == blue })
            assertTrue(repository.getActiveLayer()!!.strokes.isEmpty())
        }

    @Test
    fun replacingStrokePointsDoesNotKeepCallerOwnedMutableLists() =
        runTest {
            val layer = open()
            val points = mutableListOf(StrokePoint(2.5f, 1.5f, timestamp = 0))
            repository.replaceLayerStrokes(
                layer,
                listOf(
                    Stroke(
                        id = 42,
                        points = points,
                        brushParams = BrushParams(size = 3f),
                        layerId = layer,
                        color = red,
                    ),
                ),
            )
            val before = image()
            points.clear()
            assertArrayEquals(before.pixels, image().pixels)
        }

    @Test
    fun emptySelectionNeverTurnsIntoUnrestrictedPainting() =
        runTest {
            val layer = open()
            paint(layer, blue)
            repository.setSelection(SelectionMask(8, 6))
            assertTrue(repository.selection() != null)
            val stroke = repository.beginStroke(3f, 3f, 1f, BrushParams(size = 32f), layer)
            repository.endStroke(stroke)
            assertTrue(image().pixels.all { it == blue })
        }

    @Test
    fun selectionInputAndOutputCannotMutateRepositoryCoverage() =
        runTest {
            open()
            val selected = SelectionMask(8, 6).apply { selectAll() }
            repository.setSelection(selected)
            selected.clear()
            assertTrue(requireNotNull(repository.selection()).isFull())
            requireNotNull(repository.selection()).clear()
            assertTrue(requireNotNull(repository.selection()).isFull())
        }

    @Test
    fun wrongSizedSelectionCannotReplaceValidCoverage() =
        runTest {
            open()
            repository.setSelection(SelectionMask(8, 6).apply { selectAll() })
            assertThrows(IllegalArgumentException::class.java) { repository.setSelection(SelectionMask(1, 1)) }
            assertTrue(requireNotNull(repository.selection()).isFull())
        }

    @Test
    fun adjustmentBakesLegacyInkWithoutReplayingUnadjustedVectors() =
        runTest {
            legacyInk()
            val before = image()
            val expected = AdjustmentProcessor.apply(before, AdjustmentType.INVERT, emptyMap())
            assertTrue(repository.applyAdjustmentToCanvas(AdjustmentType.INVERT, emptyMap(), false))
            assertArrayEquals(expected.pixels, image().pixels)
            assertTrue(requireNotNull(repository.getActiveLayer()).strokes.isEmpty())
            assertTrue(repository.undo())
            assertArrayEquals(before.pixels, image().pixels)
        }

    @Test
    fun adjustmentOfBlankLayerDoesNotCreateAnOpaqueCanvasBackground() =
        runTest {
            open()
            repository.setCanvasBackgroundColor(green)
            repository.applyAdjustmentToCanvas(AdjustmentType.INVERT, emptyMap(), false)
            assertTrue(image().pixels.all { (it ushr 24) == 0 })
        }

    @Test
    fun adjustmentRefusesLockedLayerWithoutHistoryOrPixelChanges() =
        runTest {
            val layer = open()
            paint(layer, red)
            repository.setLayerLock(layer, true)
            val depth = repository.undoDepth
            assertFalse(repository.applyAdjustmentToCanvas(AdjustmentType.INVERT, emptyMap(), false))
            assertEquals(depth, repository.undoDepth)
            assertTrue(image().pixels.all { it == red })
        }

    @Test
    fun emptySelectionAdjustmentDoesNotChangeInkOrUndoDepth() =
        runTest {
            val layer = open()
            paint(layer, red)
            repository.setSelection(SelectionMask(8, 6))
            val depth = repository.undoDepth
            assertFalse(repository.applyAdjustmentToCanvas(AdjustmentType.INVERT, emptyMap(), false))
            assertEquals(depth, repository.undoDepth)
            assertTrue(image().pixels.all { it == red })
        }

    @Test
    fun allLayerAdjustmentSkipsProtectedAndEffectLayers() =
        runTest {
            val locked = open()
            paint(locked, red)
            repository.setLayerLock(locked, true)
            val editable = repository.addLayer("Editable").id
            paint(editable, blue)
            val adjustment = requireNotNull(repository.addAdjustmentLayer(AdjustmentType.INVERT)).id
            repository.setLayerVisibility(adjustment, false)
            assertTrue(repository.applyAdjustmentToCanvas(AdjustmentType.INVERT, emptyMap(), true))
            assertTrue(requireNotNull(repository.layerPixels(locked)).pixels.all { it == red })
            assertTrue(requireNotNull(repository.layerPixels(editable)).pixels.all { it == 0xFFFFFF00.toInt() })
            assertTrue(repository.layerPixels(adjustment) == null)
        }

    @Test
    fun filterPreviewAndBakeSharePixelsAndRetainUndo() =
        runTest {
            val layer = open()
            paint(layer, red)
            val unfiltered = image()
            val effect = requireNotNull(repository.addFilterLayer(FilterType.VIGNETTE)).id
            repository.setFilterAmount(effect, 1f)
            val preview = image()
            assertFalse(unfiltered.pixels.contentEquals(preview.pixels))
            assertTrue(repository.rasterizeFilterLayer(effect))
            assertArrayEquals(preview.pixels, image().pixels)
            assertTrue(requireNotNull(repository.getActiveLayer()).filterType == null)
            assertTrue(repository.undo())
            assertEquals(2, repository.getAllLayers().size)
            assertArrayEquals(preview.pixels, image().pixels)
        }

    @Test
    fun uncommittedDragBlocksWholeCanvasMutations() =
        runTest {
            val layer = legacyInk()
            val before = image()
            val session = requireNotNull(repository.beginRasterEdit(layer))
            val depth = repository.undoDepth
            assertFalse(repository.rotateCanvas(90))
            assertFalse(repository.applyAdjustmentToCanvas(AdjustmentType.INVERT, emptyMap(), false))
            assertEquals(depth, repository.undoDepth)
            assertArrayEquals(before.pixels, image().pixels)
            repository.cancelRasterEdit(session)
        }

    @Test
    fun rotationTransformsMasksAndEveryAnimationFrameTogether() =
        runTest {
            val first = legacyInk()
            repository.createLayerMask(first, LayerMaskSource.HORIZONTAL)
            val beforeFirst = image()
            repository.addFrame(duplicateCurrent = true)
            val second = repository.getActiveLayerId()
            paint(second, blue)
            repository.setFrameDuration(1, 250)
            val beforeSecond = requireNotNull(repository.compositeFrame(1, transparentBackground = true))
            assertTrue(repository.rotateCanvas(270))
            assertArrayEquals(beforeFirst.rotated(270).pixels, image().pixels)
            assertArrayEquals(
                beforeSecond.rotated(270).pixels,
                requireNotNull(repository.compositeFrame(1, transparentBackground = true)).pixels,
            )
            assertEquals(250, repository.frames()[1].durationMs)
            assertTrue(repository.undo())
            assertArrayEquals(beforeFirst.pixels, image().pixels)
            assertArrayEquals(
                beforeSecond.pixels,
                requireNotNull(repository.compositeFrame(1, transparentBackground = true)).pixels,
            )
        }

    @Test
    fun projectLayerCapacityMatchesLoadLimitsAcrossFrames() =
        runTest {
            repository.createCanvas(1, 1, 72)
            repeat(15) { repository.addLayer() }
            repeat(63) { repository.addFrame(duplicateCurrent = true) }
            assertEquals(ProjectStorage.MAX_LAYERS, repository.frames().sumOf { it.layers.size })
            val id = repository.getActiveLayerId()
            val depth = repository.undoDepth
            assertTrue(runCatching { repository.addLayer() }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { repository.addFrame(false) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(repository.duplicateLayer(id) == null)
            assertTrue(repository.addAdjustmentLayer(AdjustmentType.INVERT) == null)
            assertTrue(repository.addFilterLayer(FilterType.NOISE) == null)
            assertEquals(depth, repository.undoDepth)
            assertEquals(ProjectStorage.MAX_LAYERS, repository.frames().sumOf { it.layers.size })
        }

    @Test
    fun stackFiltersRequireAppearancePreservingLayeredExport() =
        runTest {
            val layer = open()
            paint(layer, red)
            val effect = requireNotNull(repository.addFilterLayer(FilterType.VIGNETTE)).id
            val snapshot = repository.exportSnapshot(false, false, true)
            assertTrue(snapshot.hasAdjustmentLayers)
            assertEquals(1, snapshot.layers.size)
            assertArrayEquals(image().pixels, snapshot.frames.single().pixels)
            repository.setLayerVisibility(effect, false)
            assertFalse(repository.exportSnapshot(false, false, true).hasAdjustmentLayers)
            assertTrue(repository.exportSnapshot(false, true, true).hasAdjustmentLayers)
        }

    @Test
    fun ordinaryRasterLayersDoNotRequireAFlattenedExportFallback() =
        runTest {
            val layer = open()
            paint(layer, red)
            assertFalse(repository.exportSnapshot(false, false, true).hasAdjustmentLayers)
        }
}
