package com.artflow.studio.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.pixels.LayerMaskSource
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokeDestination
import com.artflow.studio.domain.model.brush.StrokePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong

/** Exercise the real repository, document codec and preview/commit paths; not a renderer mock. */
@RunWith(AndroidJUnit4::class)
class WetPaintDeviceTest {
    private lateinit var storage: ProjectStorage
    private lateinit var repository: CanvasRepositoryImpl
    private var projectId = 0L
    private val brush = BrushParams(size = 12f, wetMix = 0.5f, pressureToSize = 0f, pressureToOpacity = 0f)

    @Before
    fun setUp() {
        storage = ProjectStorage(ApplicationProvider.getApplicationContext<Context>())
        repository = CanvasRepositoryImpl(storage)
        projectId = IDS.incrementAndGet()
        storage.deleteProjectFiles(projectId)
    }

    @After
    fun tearDown() =
        runBlocking(Dispatchers.Main) {
            repository.dispose()
            storage.deleteProjectFiles(projectId)
        }

    @Test
    fun wetPreviewCommitUndoRedoAndReopenAgreeWithoutResampling() =
        runBlocking(Dispatchers.Main) {
            val layer = openBlueLayer()
            val before = preview()
            val depth = repository.undoDepth
            val id = begin(layer)
            val provisional = preview()
            assertEquals(PURPLE, provisional[16 * 32 + 16])
            assertArrayEquals(provisional, preview())
            assertArrayEquals(before, exported())
            assertEquals(depth, repository.undoDepth)
            repository.endStroke(id)
            assertArrayEquals(provisional, preview())
            assertArrayEquals(provisional, exported())
            assertEquals(depth + 1, repository.undoDepth)
            assertTrue(repository.undo())
            assertArrayEquals(before, preview())
            assertTrue(repository.redo())
            assertArrayEquals(provisional, preview())
            repository.saveCanvas(projectId)
            repository.loadCanvas(projectId)
            assertArrayEquals(provisional, preview())
            assertFalse(repository.hasUnsavedChanges())
        }

    @Test
    fun cancelledWetPaintCannotEnterSavesExportsOrHistory() =
        runBlocking(Dispatchers.Main) {
            val layer = openBlueLayer()
            repository.saveCanvas(projectId)
            val before = preview()
            val depth = repository.undoDepth
            val id = begin(layer)
            assertFalse(before.contentEquals(preview()))
            repository.saveCanvas(projectId)
            assertArrayEquals(before, exported())
            repository.cancelStroke(id)
            assertArrayEquals(before, preview())
            assertEquals(depth, repository.undoDepth)
            repository.loadCanvas(projectId)
            assertArrayEquals(before, preview())
        }

    @Test
    fun pickupReadsOnlyTheActiveLayerNotTheFlattenedCanvas() =
        runBlocking(Dispatchers.Main) {
            openBlueLayer()
            val upper = repository.addLayer("Transparent paint layer").id
            val id = begin(upper, brush.copy(wetMix = 1f))
            assertEquals(RED, preview()[16 * 32 + 16])
            repository.endStroke(id)
            assertEquals(RED, exported()[16 * 32 + 16])
        }

    @Test
    fun maskPaintingAndEraserNeverPickUpLayerPigment() =
        runBlocking(Dispatchers.Main) {
            val layer = openBlueLayer()
            assertTrue(repository.createLayerMask(layer, LayerMaskSource.REVEAL_ALL))
            val id = begin(layer, brush.copy(wetMix = 1f), destination = StrokeDestination.MASK_HIDE)
            val provisional = preview()
            assertEquals(0, provisional[16 * 32 + 16] ushr 24)
            repository.endStroke(id)
            assertArrayEquals(provisional, preview())
            assertTrue(repository.undo())
            val erase = begin(layer, brush.copy(wetMix = 1f), eraser = true)
            val erased = preview()
            assertEquals(0, erased[16 * 32 + 16] ushr 24)
            repository.endStroke(erase)
            assertArrayEquals(erased, preview())
        }

    @Test
    fun savedLegacyWetMixValuesKeepTheirOriginalAppearance() =
        runBlocking(Dispatchers.Main) {
            val layer = openBlueLayer()
            val old = Stroke(44L, listOf(StrokePoint(16f, 16f, timestamp = 0L)), brush, layer, RED, 0L)
            repository.replaceLayerStrokes(layer, listOf(old))
            val before = preview()
            assertEquals(RED, before[16 * 32 + 16])
            repository.saveCanvas(projectId)
            repository.loadCanvas(projectId)
            assertArrayEquals(before, preview())
            repository.setStrokeColor(BLUE)
            val id = repository.beginStroke(16f, 16f, 1f, brush, layer)
            val mixed = preview()
            assertEquals(PURPLE, mixed[16 * 32 + 16])
            repository.endStroke(id)
            assertArrayEquals(mixed, preview())
            assertTrue(repository.getActiveLayer()!!.strokes.isEmpty())
            assertTrue(repository.undo())
            assertArrayEquals(before, preview())
        }

    private suspend fun openBlueLayer(): Long {
        repository.loadOrCreate(projectId, 32, 32, 72)
        val layer = repository.getActiveLayerId()
        repository.setLayerPixels(layer, PixelBuffer.filled(32, 32, BLUE), "Blue paint fixture")
        return layer
    }

    private fun begin(
        layer: Long,
        params: BrushParams = brush,
        eraser: Boolean = false,
        destination: StrokeDestination = StrokeDestination.LAYER,
    ): Long {
        repository.setStrokeColor(RED)
        return repository.beginStroke(16f, 16f, 1f, params, layer, eraser, destination)
    }

    private suspend fun preview(): IntArray = requireNotNull(repository.compositePreview()).pixels.copyOf()

    private suspend fun exported(): IntArray = repository.exportSnapshot(false, false, false).frames.single().pixels

    companion object {
        private val IDS = AtomicLong(9_700_000)
        private const val RED = 0xFFFF0000.toInt()
        private const val BLUE = 0xFF0000FF.toInt()
        private const val PURPLE = 0xFF800080.toInt()
    }
}
