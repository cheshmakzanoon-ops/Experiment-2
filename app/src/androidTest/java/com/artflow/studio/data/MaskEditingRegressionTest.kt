package com.artflow.studio.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.pixels.LayerMaskSource
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.StrokeDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class MaskEditingRegressionTest {
    private lateinit var storage: ProjectStorage
    private lateinit var repository: CanvasRepositoryImpl
    private var projectId = 0L
    private val ink = 0xFFFF0000.toInt()
    private val center = 16 * 32 + 16
    private val brush = BrushParams(size = 24f, pressureToSize = 0f, pressureToOpacity = 0f)

    @Before
    fun setUp() {
        storage = ProjectStorage(ApplicationProvider.getApplicationContext<Context>())
        repository = CanvasRepositoryImpl(storage)
        projectId = IDS.incrementAndGet()
        storage.deleteProjectFiles(projectId)
    }

    @After
    fun cleanUp() =
        runBlocking(Dispatchers.Main) {
            repository.dispose()
            storage.deleteProjectFiles(projectId)
        }

    private suspend fun open(): Long {
        repository.loadOrCreate(projectId, 32, 32, 72)
        val layer = repository.getActiveLayerId()
        repository.setLayerPixels(layer, PixelBuffer.filled(32, 32, ink), "Ink fixture")
        return layer
    }

    private suspend fun preview() = requireNotNull(repository.compositePreview()).pixels

    private fun begin(
        layer: Long,
        destination: StrokeDestination,
        params: BrushParams = brush,
    ): Long = repository.beginStroke(16f, 16f, 1f, params, layer, false, destination)

    @Test
    fun maskStrokePreviewCommitUndoAndRedoNeverModifyInk() =
        runBlocking(Dispatchers.Main) {
            val layer = open()
            assertTrue(repository.createLayerMask(layer, LayerMaskSource.REVEAL_ALL))
            val original = requireNotNull(repository.layerPixels(layer)).pixels
            val id = begin(layer, StrokeDestination.MASK_HIDE)
            val provisional = preview()
            assertEquals(0, provisional[center] ushr 24)
            assertArrayEquals(original, requireNotNull(repository.layerPixels(layer)).pixels)
            repository.endStroke(id)
            assertArrayEquals(provisional, preview())
            assertTrue(repository.undo())
            assertEquals(ink, preview()[center])
            assertTrue(repository.redo())
            assertArrayEquals(provisional, preview())
            assertArrayEquals(original, requireNotNull(repository.layerPixels(layer)).pixels)
        }

    @Test
    fun cancelledMaskPaintCannotLeakIntoSaveExportOrUndo() =
        runBlocking(Dispatchers.Main) {
            val layer = open()
            assertTrue(repository.createLayerMask(layer, LayerMaskSource.HIDE_ALL))
            repository.saveCanvas(projectId)
            val before = preview()
            val depth = repository.undoDepth
            val id = begin(layer, StrokeDestination.MASK_REVEAL)
            assertEquals(ink, preview()[center])
            assertTrue(
                repository
                    .exportSnapshot(false, false, false)
                    .frames
                    .single()
                    .pixels
                    .all { (it ushr 24) == 0 },
            )
            repository.saveCanvas(projectId)
            repository.cancelStroke(id)
            assertArrayEquals(before, preview())
            assertEquals(depth, repository.undoDepth)
            repository.loadCanvas(projectId)
            assertArrayEquals(before, preview())
        }

    @Test
    fun revealAndHideRespectInvertedMasksAndIgnoreInkColorDynamics() =
        runBlocking(Dispatchers.Main) {
            val layer = open()
            assertTrue(repository.createLayerMask(layer, LayerMaskSource.REVEAL_ALL))
            repository.invertLayerMask(layer)
            repository.setLayerAlphaLock(layer, true)
            repository.setStrokeColor(0x0000FF00)
            val noisy = brush.copy(brightnessJitter = 1f, saturationJitter = 1f, colorPressure = true)
            assertEquals(0, preview()[center] ushr 24)
            val reveal = begin(layer, StrokeDestination.MASK_REVEAL, noisy)
            assertEquals(ink, preview()[center])
            repository.endStroke(reveal)
            assertEquals(ink, preview()[center])
            val hide = begin(layer, StrokeDestination.MASK_HIDE, noisy)
            assertEquals(0, preview()[center] ushr 24)
            repository.endStroke(hide)
            assertEquals(0, preview()[center] ushr 24)
        }

    @Test
    fun alphaAndGradientSourcesSurviveSaveReloadWithoutChangingLayerPixels() =
        runBlocking(Dispatchers.Main) {
            val layer = open()
            val partial = PixelBuffer.filled(32, 32, 0x400000FF)
            repository.setLayerPixels(layer, partial, "Alpha fixture")
            assertTrue(repository.createLayerMask(layer, LayerMaskSource.LAYER_ALPHA))
            assertEquals(16, preview()[center] ushr 24)
            repository.saveCanvas(projectId)
            repository.loadCanvas(projectId)
            assertEquals(16, preview()[center] ushr 24)
            assertArrayEquals(partial.pixels, requireNotNull(repository.layerPixels(layer)).pixels)
            repository.removeLayerMask(layer)
            assertTrue(repository.createLayerMask(layer, LayerMaskSource.HORIZONTAL))
            val gradient = preview()
            assertEquals(0, gradient[0] ushr 24)
            assertEquals(64, gradient[31] ushr 24)
            repository.saveCanvas(projectId)
            repository.loadCanvas(projectId)
            assertArrayEquals(gradient, preview())
        }

    @Test
    fun invalidSourcesAndLockedDestinationsDoNotCreateHistoryEntries() =
        runBlocking(Dispatchers.Main) {
            val layer = open()
            val depth = repository.undoDepth
            assertFalse(repository.createLayerMask(layer, LayerMaskSource.SELECTION))
            assertFalse(repository.addLayerMask(layer, SelectionMask(2, 2)))
            assertEquals(0L, begin(layer, StrokeDestination.MASK_REVEAL))
            assertEquals(depth, repository.undoDepth)
            assertTrue(repository.createLayerMask(layer, LayerMaskSource.REVEAL_ALL))
            repository.setLayerLock(layer, true)
            val lockedDepth = repository.undoDepth
            assertEquals(0L, begin(layer, StrokeDestination.MASK_HIDE))
            assertFalse(repository.paintLayerMask(layer, 16f, 16f, 4f, reveal = false))
            assertEquals(lockedDepth, repository.undoDepth)
            assertEquals(ink, preview()[center])
        }

    @Test
    fun maskPaintRespectsSoftSelectionsAndRemainsOneUndoStep() =
        runBlocking(Dispatchers.Main) {
            val layer = open()
            assertTrue(repository.createLayerMask(layer, LayerMaskSource.REVEAL_ALL))
            repository.setSelection(SelectionMask(32, 32).apply { coverage.fill(128.toByte()) })
            val before = repository.undoDepth
            val id = begin(layer, StrokeDestination.MASK_HIDE)
            repeat(12) { repository.continueStroke(id, 16f, 16f, 1f) }
            val provisional = preview()
            assertEquals(127, provisional[center] ushr 24)
            repository.endStroke(id)
            assertEquals(before + 1, repository.undoDepth)
            assertArrayEquals(provisional, preview())
        }

    private companion object {
        val IDS = AtomicLong(9_500_000L)
    }
}
