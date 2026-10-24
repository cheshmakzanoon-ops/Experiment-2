package com.artflow.studio.data

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.TestEvidence
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.presentation.ui.viewmodel.ClipboardController
import com.artflow.studio.presentation.ui.viewmodel.SavedSelections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Save & Load across real document storage, a fresh repository, editing and Android image decoding. */
@RunWith(AndroidJUnit4::class)
class SavedSelectionWorkflowDeviceTest {
    private lateinit var storage: ProjectStorage
    private lateinit var repository: CanvasRepositoryImpl
    private var projectId = 0L

    @Before
    fun setUp() {
        storage = ProjectStorage(ApplicationProvider.getApplicationContext<Context>())
        repository = CanvasRepositoryImpl(storage)
        projectId = System.nanoTime().coerceAtLeast(1)
    }

    @After
    fun tearDown() =
        runBlocking(Dispatchers.Main) {
            repository.dispose()
            storage.deleteProjectFiles(projectId)
            storage.deleteProjectFiles(projectId + 1)
        }

    @Test
    fun savedSoftSelectionReopensConstrainsEditingAndKeepsItsPixelsThroughExport() =
        runBlocking(Dispatchers.Main) {
            repository.loadOrCreate(projectId, WIDTH, HEIGHT, 72)
            val soft = softSelection()
            val ellipse = SelectionMask.ellipse(WIDTH, HEIGHT, 8f, 4f, 24f, 20f)
            val original = SavedSelections(repository) {}
            listOf(soft, ellipse).forEach { mask ->
                repository.setSelection(mask)
                assertTrue(original.save())
            }
            assertTrue(repository.hasUnsavedChanges())
            repository.saveCanvas(projectId)
            assertEquals(2, requireNotNull(storage.loadDocument(projectId)).savedSelections.size)

            // A new repository has no in-memory slots, raster cache or controller state to reuse.
            reopen()
            assertNull(repository.selection())
            assertFalse(repository.hasUnsavedChanges())
            val restored = SavedSelections(repository) {}
            assertEquals(2, restored.saved.value.size)
            assertArrayEquals(soft.coverage, restored.saved.value[0].coverage)
            assertArrayEquals(ellipse.coverage, restored.saved.value[1].coverage)
            restored.load(0)
            assertArrayEquals(soft.coverage, requireNotNull(repository.selection()).coverage)

            // Fill Layer is driven by the same controller as the layer menu. The reloaded mask,
            // including its fractional coverage, must restrict the edit after the artwork reopens.
            val queued = mutableListOf<suspend () -> Unit>()
            ClipboardController(repository) { queued += it }.fill(Color.BLUE)
            assertEquals(1, queued.size)
            queued.single().invoke()
            val expected =
                IntArray(WIDTH * HEIGHT) { index ->
                    val alpha = soft.coverage[index].toInt() and 0xFF
                    if (alpha == 0) 0 else (alpha shl 24) or 0x000000FF
                }
            assertArrayEquals(expected, pixels())
            assertEquals(1, repository.undoDepth)
            assertTrue(repository.undo())
            assertTrue(pixels().all { it == 0 })
            assertTrue(repository.redo())
            assertArrayEquals(expected, pixels())
            assertPngPixels(expected)

            repository.saveCanvas(projectId)
            reopen()
            assertArrayEquals(expected, pixels())
            val afterEditing = SavedSelections(repository) {}
            assertArrayEquals(soft.coverage, afterEditing.saved.value[0].coverage)
            afterEditing.delete(0)
            assertTrue(repository.hasUnsavedChanges())
            repository.saveCanvas(projectId)
            reopen()
            val afterDeletion = SavedSelections(repository) {}
            assertEquals(1, afterDeletion.saved.value.size)
            val remaining = afterDeletion.saved.value.single()
            assertArrayEquals(ellipse.coverage, remaining.coverage)
        }

    @Test
    fun switchingArtworkCannotReuseAnotherDocumentsSavedSelection() =
        runBlocking(Dispatchers.Main) {
            repository.loadOrCreate(projectId, WIDTH, HEIGHT, 72)
            val slots = SavedSelections(repository) {}
            val first = softSelection()
            repository.setSelection(first)
            assertTrue(slots.save())
            repository.saveCanvas(projectId)

            repository.loadOrCreate(projectId + 1, WIDTH, HEIGHT, 72)
            slots.refresh()
            assertTrue(slots.saved.value.isEmpty())
            slots.load(0)
            assertNull(repository.selection())
            val second = SelectionMask.ellipse(WIDTH, HEIGHT, 8f, 4f, 24f, 20f)
            repository.setSelection(second)
            assertTrue(slots.save())
            repository.saveCanvas(projectId + 1)

            repository.loadCanvas(projectId)
            slots.refresh()
            slots.load(0)
            assertArrayEquals(first.coverage, requireNotNull(repository.selection()).coverage)
            repository.loadCanvas(projectId + 1)
            slots.refresh()
            slots.load(0)
            assertArrayEquals(second.coverage, requireNotNull(repository.selection()).coverage)
        }

    private suspend fun reopen() {
        repository.dispose()
        repository = CanvasRepositoryImpl(storage)
        requireNotNull(repository.loadCanvas(projectId))
    }

    private suspend fun pixels(): IntArray = requireNotNull(repository.layerPixels(repository.getActiveLayerId())).pixels

    private suspend fun assertPngPixels(expected: IntArray) {
        val composite = requireNotNull(repository.compositeFrame(0, transparentBackground = true))
        val result =
            ArtworkExporter(ApplicationProvider.getApplicationContext<Context>(), storage)
                .exportStill(projectId, "Saved selection", composite, emptyList(), ExportOptions(format = ExportFormat.PNG))
                .getOrThrow()
        val options =
            BitmapFactory.Options().apply {
                inPremultiplied = false
                inScaled = false
            }
        val decoded = requireNotNull(BitmapFactory.decodeFile(result.filePath, options))
        try {
            assertEquals(WIDTH, decoded.width)
            assertEquals(HEIGHT, decoded.height)
            val actual = IntArray(WIDTH * HEIGHT)
            decoded.getPixels(actual, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
            assertArrayEquals(expected, actual)
        } finally {
            decoded.recycle()
        }
        File(result.filePath).copyTo(File(TestEvidence.directory(), "saved-selection-export.png"), overwrite = true)
    }

    private fun softSelection(): SelectionMask =
        SelectionMask(
            WIDTH,
            HEIGHT,
            ByteArray(WIDTH * HEIGHT) { index ->
                when (index % WIDTH) {
                    in 0..10 -> 255.toByte()
                    11 -> 191.toByte()
                    12 -> 128.toByte()
                    13 -> 64.toByte()
                    else -> 0
                }
            },
        )

    private companion object {
        const val WIDTH = 32
        const val HEIGHT = 24
    }
}
