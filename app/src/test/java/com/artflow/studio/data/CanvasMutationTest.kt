package com.artflow.studio.data

import android.content.Context
import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.pixels.AdjustmentProcessor
import com.artflow.studio.core.pixels.LayerMaskSource
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.symmetry.SymmetryEngine
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.model.layer.AdjustmentType
import com.artflow.studio.domain.model.layer.FilterType
import com.artflow.studio.domain.model.layer.LayerEffects
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

    private suspend fun firstPixel(frame: Int): Int {
        val image = requireNotNull(repository.compositeFrame(frame, transparentBackground = true))
        return image.pixels.first()
    }

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
    fun backgroundFrameShowsBehindOtherFrames() =
        runTest {
            paint(open(), red)
            repository.addFrame(duplicateCurrent = false)
            assertEquals(0, firstPixel(1) ushr 24)
            val settings = repository.timeline.value.settings
            repository.updateAnimationSettings(settings.copy(backgroundFrame = true))
            assertEquals(red, firstPixel(1))
            assertEquals(red, firstPixel(0))
        }

    @Test
    fun neighbouringLayersMergeInOneStep() =
        runTest {
            val bottom = open()
            val middle = repository.addLayer("Middle").id
            val top = repository.addLayer("Top").id
            paint(middle, red)
            assertFalse("Layers with a gap between them do not merge", repository.mergeLayerRange(listOf(bottom, top)))
            val depth = repository.undoDepth
            assertTrue(repository.mergeLayerRange(listOf(top, middle, bottom)))
            assertEquals(depth + 1, repository.undoDepth)
            assertEquals(listOf(bottom), repository.getAllLayers().map { it.id })
            assertEquals(red, image().pixels.first())
        }

    @Test
    fun oneOpacityDragIsOneUndoStep() =
        runTest {
            val layer = open()
            val depth = repository.undoDepth
            listOf(0.9f, 0.7f, 0.5f).forEach { assertTrue(repository.setLayerOpacity(layer, it)) }
            assertEquals(depth + 1, repository.undoDepth)
            assertTrue(repository.undo())
            assertEquals(1f, repository.getAllLayers().first { it.id == layer }.opacity, 0f)
            // After an undo the next change starts a new step.
            assertTrue(repository.setLayerOpacity(layer, 0.3f))
            assertEquals(depth + 1, repository.undoDepth)
        }

    @Test
    fun privateLayersStayInTheArtworkButOutOfTheTimeLapse() =
        runTest {
            val base = open()
            paint(base, red)
            val photo = repository.addLayer("Photo").id
            paint(photo, blue)
            assertTrue(repository.setLayerPrivate(photo, true))
            assertTrue(repository.getAllLayers().first { it.id == photo }.isPrivate)
            assertEquals(blue, requireNotNull(repository.compositeBuffer()).pixels.first())
            assertEquals(red, requireNotNull(repository.compositeWithoutPrivateLayers()).pixels.first())
            // Undo brings the photo back into the recording.
            assertTrue(repository.undo())
            assertEquals(blue, requireNotNull(repository.compositeWithoutPrivateLayers()).pixels.first())
        }

    @Test
    fun layerEffectsDrawAroundThePixelsAndAreUndoable() =
        runTest {
            val layer = open()
            val dot = PixelBuffer(8, 6).also { it.pixels[2 * 8 + 3] = blue }
            assertTrue(repository.setLayerPixels(layer, dot, "Fixture"))
            val outline = LayerEffects(outline = LayerEffects.Outline(color = red, width = 2f))
            assertTrue(repository.setLayerEffects(layer, outline))
            assertEquals(outline, repository.getAllLayers().first { it.id == layer }.effects)
            val pixels = image().pixels
            assertEquals(blue, pixels[2 * 8 + 3])
            assertEquals(red, pixels[2 * 8 + 4])
            assertEquals(0, pixels[0] ushr 24)
            // Duplicates keep the effects; undo removes them from the original.
            val copy = requireNotNull(repository.duplicateLayer(layer))
            assertEquals(outline, repository.getAllLayers().first { it.id == copy }.effects)
            assertTrue(repository.undo())
            assertTrue(repository.undo())
            assertEquals(null, repository.getAllLayers().first { it.id == layer }.effects)
            assertEquals(0, image().pixels[2 * 8 + 4] ushr 24)
        }

    @Test
    fun symmetryMirrorsOnlyOnAssistedLayers() =
        runTest {
            val layer = open()
            repository.setSymmetry(SymmetryEngine.Settings(type = SymmetryEngine.SymmetryType.VERTICAL))
            val plain = repository.beginStroke(1.5f, 3f, 1f, BrushParams(size = 2f), layer)
            repository.endStroke(plain)
            val right = image().pixels.indices.filter { it % 8 >= 4 }
            assertTrue("Without Drawing Assist nothing is mirrored", right.all { (image().pixels[it] ushr 24) == 0 })
            assertTrue(repository.setLayerDrawingAssist(layer, true))
            assertTrue(repository.isDrawingAssisted(layer))
            val mirrored = repository.beginStroke(1.5f, 3f, 1f, BrushParams(size = 2f), layer)
            repository.endStroke(mirrored)
            val pixels = image().pixels
            assertTrue("With Drawing Assist the stroke is mirrored", right.any { (pixels[it] ushr 24) != 0 })
        }

    @Test
    fun onlyOneLayerIsTheFillReference() =
        runTest {
            val lines = open()
            val colours = repository.addLayer("Colours").id
            assertTrue(repository.setLayerFillReference(lines, true))
            assertTrue(repository.setLayerFillReference(colours, true))
            val flags = repository.getAllLayers().associate { it.id to it.isFillReference }
            assertEquals(mapOf(lines to false, colours to true), flags)
            assertTrue(repository.undo())
            assertTrue(repository.getAllLayers().first { it.id == lines }.isFillReference)
        }

    @Test
    fun editsToSeveralLayersCommitAsOneStep() =
        runTest {
            val bottom = open()
            val top = repository.addLayer("Top").id
            val depth = repository.undoDepth
            val moved =
                repository.applyRasterEdits(listOf(bottom, top), "Transform") { id, buffer ->
                    buffer.fill(if (id == bottom) red else blue)
                }
            assertTrue(moved)
            assertEquals(depth + 1, repository.undoDepth)
            assertEquals(red, repository.layerPixels(bottom)?.pixels?.first())
            assertEquals(blue, repository.layerPixels(top)?.pixels?.first())
            assertTrue(repository.undo())
            assertTrue(repository.layerPixels(top)?.isEmpty() != false)
            // A stale session discards the whole group.
            val first = requireNotNull(repository.beginRasterEdit(bottom))
            val second = requireNotNull(repository.beginRasterEdit(top))
            paint(top, green)
            assertFalse(repository.commitRasterEdits(listOf(first, second), "Stale"))
            assertEquals(green, repository.layerPixels(top)?.pixels?.first())
        }

    @Test
    fun deletingSeveralLayersIsOneStepAndKeepsALayer() =
        runTest {
            val bottom = open()
            val middle = repository.addLayer("Middle").id
            val top = repository.addLayer("Top").id
            val depth = repository.undoDepth
            assertEquals(2, repository.removeLayers(listOf(middle, top)))
            assertEquals(depth + 1, repository.undoDepth)
            assertEquals(listOf(bottom), repository.getAllLayers().map { it.id })
            assertEquals(bottom, repository.getActiveLayerId())
            assertTrue(repository.undo())
            assertEquals(3, repository.getAllLayers().size)
            // Choosing every layer still leaves the lowest one.
            assertEquals(2, repository.removeLayers(listOf(bottom, middle, top)))
            assertEquals(listOf(bottom), repository.getAllLayers().map { it.id })
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

    @Test
    fun groupsNestAndLayersJoinTheGroupTheyAreDraggedInto() =
        runTest {
            val bottom = open()
            val middle = repository.addLayer("Middle").id
            val top = repository.addLayer("Top").id
            val inner = requireNotNull(repository.groupLayers(listOf(middle, top)))
            val outer = requireNotNull(repository.groupLayers(listOf(bottom, inner)))

            suspend fun parent(id: Long) = repository.getAllLayers().first { it.id == id }.parentGroupId
            assertEquals(listOf(bottom, middle, top, inner, outer), repository.getAllLayers().sortedBy { it.index }.map { it.id })
            assertEquals(outer, parent(inner))
            assertEquals(inner, parent(top))
            assertEquals(outer, parent(bottom))
            assertTrue(repository.ungroupLayers(inner))
            assertEquals(outer, parent(top))
            assertTrue(repository.undo())
            // Above the outer header is outside both groups; just under the inner header is inside it.
            assertTrue(repository.reorderLayer(top, 4))
            assertEquals(null, parent(top))
            assertTrue(repository.reorderLayer(top, 2))
            assertEquals(inner, parent(top))
            // A group moves with its members.
            assertTrue(repository.reorderLayer(inner, 0))
            assertEquals(listOf(middle, top, inner, bottom, outer), repository.getAllLayers().sortedBy { it.index }.map { it.id })
            assertEquals(null, parent(inner))
            assertEquals(inner, parent(middle))
        }

    @Test
    fun theColourProfileIsAssignedAndUndone() =
        runTest {
            open()
            assertEquals(ColorProfile.SRGB, repository.getColorProfile())
            assertTrue(repository.setColorProfile(ColorProfile.DISPLAY_P3))
            assertEquals(ColorProfile.DISPLAY_P3, repository.getColorProfile())
            assertTrue(repository.undo())
            assertEquals(ColorProfile.SRGB, repository.getColorProfile())
        }

    @Test
    fun trackedTimeAddsUpAndStartsAtZeroOnANewCanvas() =
        runTest {
            open()
            repository.addTrackedTime(10_000L)
            repository.addTrackedTime(5_000L)
            assertEquals(15_000L, repository.trackedTimeMs())
            repository.createCanvas(16, 16, 72)
            assertEquals(0L, repository.trackedTimeMs())
        }
}
