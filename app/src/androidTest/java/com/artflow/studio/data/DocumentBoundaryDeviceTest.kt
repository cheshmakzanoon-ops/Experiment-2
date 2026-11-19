package com.artflow.studio.data

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.pixels.SelectionMaskCodec
import com.artflow.studio.data.local.CanvasDocumentCodec
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRasterBudget
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.presentation.ui.viewmodel.SavedSelections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random

/** Real Android storage and PNG decoding at small, deterministic admission boundaries. */
@RunWith(AndroidJUnit4::class)
class DocumentBoundaryDeviceTest {
    private lateinit var context: Context
    private lateinit var storage: ProjectStorage
    private lateinit var repository: CanvasRepositoryImpl
    private var projectId = 0L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storage = ProjectStorage(context)
        repository = newRepository()
        projectId = System.nanoTime().coerceAtLeast(1)
    }

    @After
    fun tearDown() =
        runBlocking(Dispatchers.Main) {
            repository.dispose()
            storage.deleteProjectFiles(projectId)
        }

    @Test
    fun rejectedMetadataWritesPreserveBothManifestsAndTheirReopenablePixels() =
        runBlocking(Dispatchers.Main) {
            openAndPaint(Color.RED)
            repository.saveCanvas(projectId)
            paint(Color.BLUE)
            repository.autosave(projectId)
            val recovery = requireNotNull(storage.loadAutosave(projectId))
            val coverage = ByteArray(SIZE * SIZE).also { Random(17L).nextBytes(it) }
            val oversized = recovery.copy(savedSelections = listOf(SelectionMaskCodec.encode(SelectionMask(SIZE, SIZE, coverage))))
            val limit = CanvasDocumentCodec.encode(oversized).size.toLong() - 1
            val savedFile = storage.documentFile(projectId)
            val recoveryFile = storage.autosaveFile(projectId)
            val savedBytes = savedFile.readBytes()
            val recoveryBytes = recoveryFile.readBytes()
            val savedTime = savedFile.lastModified()
            val recoveryTime = recoveryFile.lastModified()
            assertTrue(savedBytes.size <= limit && recoveryBytes.size <= limit)
            storage = ProjectStorage(context, documentByteLimit = limit)

            for (autosave in listOf(false, true)) {
                val failure =
                    runCatching {
                        if (autosave) storage.saveAutosave(projectId, oversized) else storage.saveDocument(projectId, oversized)
                    }.exceptionOrNull()
                assertTrue(failure is IllegalArgumentException)
                assertArrayEquals(savedBytes, savedFile.readBytes())
                assertArrayEquals(recoveryBytes, recoveryFile.readBytes())
                assertEquals(savedTime, savedFile.lastModified())
                assertEquals(recoveryTime, recoveryFile.lastModified())
                assertTrue(storage.hasUnsavedRecovery(projectId))
            }

            // New repository instances must decode the preserved real PNG generations, not a cache.
            reopen()
            assertPixels(Color.RED)
            assertNotNull(repository.recoverAutosave(projectId))
            assertPixels(Color.BLUE)
            assertArrayEquals(savedBytes, savedFile.readBytes())
        }

    @Test
    fun rejectedSelectionMemoryAndStorageBudgetsKeepVisibleAndPersistedSlots() =
        runBlocking(Dispatchers.Main) {
            openAndPaint(Color.RED)
            repository.setSavedSelections(listOf(mask(11)))
            repository.saveCanvas(projectId)

            assertRejectedSlot(CanvasRasterBudget(4L * 1024), CanvasRasterBudget.SAVED_SELECTION_LIMIT_MESSAGE)
            assertRejectedSlot(
                CanvasRasterBudget(512L * 1024, savedSelectionMetadataBytes = 400),
                CanvasRasterBudget.SAVED_SELECTION_METADATA_LIMIT_MESSAGE,
            )
        }

    @Test
    fun selectionHeavyNavigationTrimsBothStacksAndPreservesTheNearestInverseState() =
        runBlocking(Dispatchers.Main) {
            openAndPaint(Color.RED)
            for (undoing in listOf(true, false)) {
                repository.saveCanvas(projectId)
                reopen()
                repository.setSelection(mask(1))
                val layer = repository.getActiveLayerId()
                repeat(20) { assertTrue(repository.setLayerName(layer, "$undoing revision $it")) }
                if (!undoing) repeat(20) { assertTrue(repository.undo()) }
                assertEquals(20, if (undoing) repository.undoDepth else repository.redoDepth)
                var moves = 0
                var previousName = ""
                var previousSelection = 0
                while ((if (undoing) repository.undoDepth else repository.redoDepth) > 0) {
                    repository.setSelection(mask(50 + moves))
                    previousName = layerName()
                    previousSelection = requireNotNull(repository.selection()).coverageAt(0, 0)
                    assertTrue(if (undoing) repository.undo() else repository.redo())
                    moves++
                    assertTrue(moves <= 20)
                    assertNavigationBudget(undoing)
                }
                assertTrue(moves in 1 until 20)
                assertEquals(8, if (undoing) repository.redoDepth else repository.undoDepth)
                assertTrue(if (undoing) repository.redo() else repository.undo())
                assertEquals(previousName, layerName())
                assertEquals(previousSelection, requireNotNull(repository.selection()).coverageAt(0, 0))
                assertPixels(Color.RED)
            }
        }

    private suspend fun assertRejectedSlot(
        budget: CanvasRasterBudget,
        message: String,
    ) {
        reopen(budget)
        val failures = mutableListOf<String>()
        val slots = SavedSelections(repository, onFailure = failures::add) {}
        assertEquals(1, slots.saved.value.size)
        repository.setSelection(mask(22))
        val revision = repository.contentRevision
        val committed =
            storage
                .documentFile(projectId)
                .readBytes()

        assertFalse(slots.save())
        assertEquals(listOf(message), failures)
        assertEquals(revision, repository.contentRevision)
        assertEquals(1, slots.saved.value.size)
        assertArrayEquals(
            mask(11).coverage,
            slots.saved.value
                .single()
                .coverage,
        )
        assertArrayEquals(mask(11).coverage, repository.savedSelections().single().coverage)
        assertArrayEquals(committed, storage.documentFile(projectId).readBytes())

        repository.saveCanvas(projectId)
        reopen(budget)
        assertArrayEquals(mask(11).coverage, repository.savedSelections().single().coverage)
        assertPixels(Color.RED)
    }

    private fun assertNavigationBudget(undoing: Boolean) {
        val originals = if (undoing) repository.undoDepth else repository.redoDepth
        val inverses = if (undoing) repository.redoDepth else repository.undoDepth
        // Original entries share one mask; each inverse entry owns a new mask. All share one raster.
        val distinctMasks = inverses + if (originals > 0) 1 else 0
        val bytes = SIZE.toLong() * SIZE * (Int.SIZE_BYTES + distinctMasks)
        assertTrue("Retained history uses $bytes bytes", bytes <= HEAP_BYTES / 4)
        assertTrue(originals + inverses <= 30)
    }

    private suspend fun openAndPaint(color: Int) {
        repository.loadOrCreate(projectId, SIZE, SIZE, 72)
        paint(color)
    }

    private suspend fun paint(color: Int) {
        assertTrue(repository.setLayerPixels(repository.getActiveLayerId(), PixelBuffer.filled(SIZE, SIZE, color), "Fixture"))
    }

    private suspend fun assertPixels(color: Int) {
        assertArrayEquals(IntArray(SIZE * SIZE) { color }, requireNotNull(repository.layerPixels(repository.getActiveLayerId())).pixels)
    }

    private suspend fun reopen(budget: CanvasRasterBudget = CanvasRasterBudget(HEAP_BYTES)) {
        repository.dispose()
        repository = newRepository(budget)
        assertNotNull(repository.loadCanvas(projectId))
    }

    private fun newRepository(budget: CanvasRasterBudget = CanvasRasterBudget(HEAP_BYTES)): CanvasRepositoryImpl =
        CanvasRepositoryImpl(storage, Dispatchers.Default, rasterBudget = budget)

    private fun layerName(): String = repository.getAllLayers().first { it.id == repository.getActiveLayerId() }.name

    private fun mask(value: Int): SelectionMask = SelectionMask(SIZE, SIZE).apply { coverage.fill(value.toByte()) }

    private companion object {
        const val SIZE = 16
        const val HEAP_BYTES = 12L * 1024
    }
}
