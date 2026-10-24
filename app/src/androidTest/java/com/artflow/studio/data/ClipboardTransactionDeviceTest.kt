package com.artflow.studio.data

import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.TestEvidence
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.presentation.ui.viewmodel.ClipboardController
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
import java.io.File

/** Actual document storage, Android PNG decoding and the editor clipboard commands on every device lane. */
@RunWith(AndroidJUnit4::class)
class ClipboardTransactionDeviceTest {
    private lateinit var storage: ProjectStorage
    private lateinit var repository: CanvasRepositoryImpl
    private var projectId = 0L
    private val red = 0xFFFF0000.toInt()

    @Before
    fun setUp() {
        storage = ProjectStorage(ApplicationProvider.getApplicationContext<Context>())
        repository = CanvasRepositoryImpl(storage)
        projectId = System.nanoTime().coerceAtLeast(1)
    }

    @After
    fun tearDown(): Unit =
        runBlocking(Dispatchers.Main) {
            repository.dispose()
            storage.deleteProjectFiles(projectId)
        }

    @Test
    fun selectedCutPasteUndoRedoAndFreshReopenKeepBothLayersAndExportedPixels(): Unit =
        runBlocking(Dispatchers.Main) {
            repository.loadOrCreate(projectId, WIDTH, HEIGHT, 72)
            val source = repository.getActiveLayerId()
            repository.setLayerPixels(source, PixelBuffer.filled(WIDTH, HEIGHT, red), "Source")
            repository.setLayerAlphaLock(source, true)
            val selected = SelectionMask(WIDTH, HEIGHT, ByteArray(WIDTH * HEIGHT) { if (it % WIDTH < WIDTH / 2) -1 else 0 })
            repository.setSelection(selected)
            val before = repository.getAllLayers()
            val depth = repository.undoDepth
            execute { cutAndPaste() }
            val pasted = repository.getActiveLayerId()
            val expectedSource = IntArray(WIDTH * HEIGHT) { if (it % WIDTH < WIDTH / 2) 0x00FF0000 else red }
            val expectedPaste = IntArray(WIDTH * HEIGHT) { if (it % WIDTH < WIDTH / 2) red else 0 }
            assertArrayEquals(expectedSource, pixels(source))
            assertArrayEquals(expectedPaste, pixels(pasted))
            assertEquals(depth + 1, repository.undoDepth)
            assertTrue(repository.undo())
            assertEquals(before, repository.getAllLayers())
            assertArrayEquals(IntArray(WIDTH * HEIGHT) { red }, pixels(source))
            assertTrue(repository.redo())
            assertArrayEquals(expectedSource, pixels(source))
            assertArrayEquals(expectedPaste, pixels(pasted))
            repository.saveCanvas(projectId)
            storage.documentFile(projectId).copyTo(File(TestEvidence.directory(), "clipboard-transaction-document.json"), overwrite = true)
            repository.dispose()
            repository = CanvasRepositoryImpl(storage)
            requireNotNull(repository.loadCanvas(projectId))
            assertEquals(before.size + 1, repository.getAllLayers().size)
            assertArrayEquals(expectedSource, pixels(source))
            assertArrayEquals(expectedPaste, pixels(pasted))
            assertTrue(repository.getAllLayers().first { it.id == source }.isAlphaLocked)
            assertFalse(repository.hasUnsavedChanges())
            val composite = requireNotNull(repository.compositeFrame(0, transparentBackground = true))
            assertArrayEquals(IntArray(WIDTH * HEIGHT) { red }, composite.pixels)
            val result =
                ArtworkExporter(ApplicationProvider.getApplicationContext<Context>(), storage)
                    .exportStill(projectId, "Clipboard", composite, emptyList(), ExportOptions(format = ExportFormat.PNG))
                    .getOrThrow()
            val options = BitmapFactory.Options().apply { inPremultiplied = false }
            val decoded = requireNotNull(BitmapFactory.decodeFile(result.filePath, options))
            try {
                val actual = IntArray(WIDTH * HEIGHT)
                decoded.getPixels(actual, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                assertArrayEquals(IntArray(WIDTH * HEIGHT) { red }, actual)
            } finally {
                decoded.recycle()
            }
            File(result.filePath).copyTo(File(TestEvidence.directory(), "clipboard-transaction-export.png"), overwrite = true)
        }

    @Test
    fun cutPasteAtLayerCapacityKeepsTheSavedSourceAndDoesNotAddHistory(): Unit =
        runBlocking(Dispatchers.Main) {
            repository.loadOrCreate(projectId, WIDTH, HEIGHT, 72)
            val source = repository.getActiveLayerId()
            repository.setLayerPixels(source, PixelBuffer.filled(WIDTH, HEIGHT, red), "Source")
            while (repository.getAllLayers().size < ProjectStorage.MAX_LAYERS) repository.addLayer("Spare")
            repository.setActiveLayer(source)
            repository.saveCanvas(projectId)
            val document = storage.documentFile(projectId).readBytes()
            val mark = repository.historyMark
            val depth = repository.undoDepth
            val error = runCatching { execute { cutAndPaste() } }.exceptionOrNull()
            assertEquals("Maximum project layer count reached", error?.message)
            assertEquals(ProjectStorage.MAX_LAYERS, repository.getAllLayers().size)
            assertEquals(mark, repository.historyMark)
            assertEquals(depth, repository.undoDepth)
            assertEquals(source, repository.getActiveLayerId())
            assertArrayEquals(IntArray(WIDTH * HEIGHT) { red }, pixels(source))
            assertFalse(repository.hasUnsavedChanges())
            assertArrayEquals(document, storage.documentFile(projectId).readBytes())
            File(TestEvidence.directory(), "clipboard-capacity.txt").writeText(
                "layers=${ProjectStorage.MAX_LAYERS}\nsourcePreserved=true\nhistoryPreserved=true\nsavedDocumentPreserved=true\n",
            )
        }

    private suspend fun execute(action: ClipboardController.() -> Unit) {
        val queued = ArrayDeque<suspend () -> Unit>()
        ClipboardController(repository) { queued.addLast(it) }.action()
        queued.removeFirst().invoke()
    }

    private suspend fun pixels(layer: Long): IntArray = requireNotNull(repository.layerPixels(layer)).pixels

    private companion object {
        const val WIDTH = 16
        const val HEIGHT = 8
    }
}
