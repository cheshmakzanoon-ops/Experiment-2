package com.artflow.studio.data

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.animation.AnimationSettings
import com.artflow.studio.domain.model.brush.BrushParams
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class PersistenceRegressionTest {
    private lateinit var storage: ProjectStorage
    private lateinit var repository: CanvasRepositoryImpl
    private var projectId = 0L

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

    @Test
    fun firstSaveRetainsRasterPixelsAfterReopen() =
        runBlocking(Dispatchers.Main) {
            open()
            paint(Color.RED)
            repository.saveCanvas(projectId)
            val document = requireNotNull(storage.loadDocument(projectId))
            assertNotNull(
                document.frames
                    .first()
                    .layers
                    .first()
                    .rasterFile,
            )
            repository.loadCanvas(projectId)
            assertPixel(Color.RED)
            assertFalse(repository.hasUnsavedChanges())
        }

    @Test
    fun autosaveCannotOverwriteExplicitlySavedArtwork() =
        runBlocking(Dispatchers.Main) {
            open()
            paint(Color.RED)
            repository.saveCanvas(projectId)
            val savedPath =
                storage
                    .loadDocument(projectId)!!
                    .frames
                    .first()
                    .layers
                    .first()
                    .rasterFile!!
            paint(Color.BLUE)
            repository.autosave(projectId)
            val recoveryPath =
                storage
                    .loadAutosave(projectId)!!
                    .frames
                    .first()
                    .layers
                    .first()
                    .rasterFile!!
            assertNotEquals(savedPath, recoveryPath)
            assertTrue(storage.resolve(projectId, savedPath).isFile)
            repository.loadCanvas(projectId)
            assertPixel(Color.RED)
            repository.recoverAutosave(projectId)
            assertPixel(Color.BLUE)
            assertTrue(repository.hasUnsavedChanges())
        }

    @Test
    fun savesEveryFrameNotJustTheActiveFrame() =
        runBlocking(Dispatchers.Main) {
            open()
            paint(Color.RED)
            repository.addFrame(duplicateCurrent = false)
            paint(Color.BLUE)
            repository.setFrameDuration(1, 250)
            repository.selectFrame(0)
            repository.saveCanvas(projectId)
            repository.loadCanvas(projectId)
            assertEquals(2, repository.frames().size)
            assertPixel(Color.RED)
            repository.selectFrame(1)
            assertPixel(Color.BLUE)
            assertEquals(250, repository.frames()[1].durationMs)
        }

    @Test
    fun newerEditsRemainDirtyWhenAnOlderSnapshotFinishesSaving() =
        runBlocking(Dispatchers.Main) {
            open()
            paint(Color.RED)
            val saving = async(start = CoroutineStart.UNDISPATCHED) { repository.saveCanvas(projectId) }
            paint(Color.BLUE)
            saving.await()
            assertTrue(repository.hasUnsavedChanges())
            assertPixel(Color.BLUE)
            repository.loadCanvas(projectId)
            assertPixel(Color.RED)
        }

    @Test
    fun provisionalPixelSessionIsNotSavedAndCancellationDoesNotRemoveUnrelatedUndo() =
        runBlocking(Dispatchers.Main) {
            open()
            paint(Color.RED)
            repository.saveCanvas(projectId)
            val layerId = repository.getActiveLayerId()
            val session = requireNotNull(repository.beginRasterEdit(layerId))
            session.buffer.fill(Color.BLUE)
            repository.setLayerName(layerId, "Renamed while editing")
            val history = repository.undoDepth
            repository.autosave(projectId)
            repository.cancelRasterEdit(session)
            assertEquals(history, repository.undoDepth)
            repository.recoverAutosave(projectId)
            assertPixel(Color.RED)
        }

    @Test
    fun failedToolDoesNotChangePixelsOrHistory() =
        runBlocking(Dispatchers.Main) {
            open()
            paint(Color.RED)
            val history = repository.undoDepth
            expectFailure {
                repository.applyRasterEdit(repository.getActiveLayerId(), "Injected failure") { buffer ->
                    buffer.fill(Color.BLUE)
                    throw IOException("Test failure")
                }
            }
            assertPixel(Color.RED)
            assertEquals(history, repository.undoDepth)
        }

    @Test
    fun staleSessionCannotOverwriteAnInterveningEdit() =
        runBlocking(Dispatchers.Main) {
            open()
            paint(Color.RED)
            val session = requireNotNull(repository.beginRasterEdit(repository.getActiveLayerId()))
            session.buffer.fill(Color.BLUE)
            paint(Color.GREEN)
            assertFalse(repository.commitRasterEdit(session, "Stale edit"))
            assertPixel(Color.GREEN)
            assertFalse(repository.commitRasterEdit(session, "Duplicate commit"))
        }

    @Test
    fun callerOwnedBuffersCannotMutateCommittedArtwork() =
        runBlocking(Dispatchers.Main) {
            open()
            val input = PixelBuffer.filled(SIZE, SIZE, Color.RED)
            repository.setLayerPixels(repository.getActiveLayerId(), input, "Import")
            input.fill(Color.BLUE)
            repository.layerPixels(repository.getActiveLayerId())!!.fill(Color.GREEN)
            assertPixel(Color.RED)
        }

    @Test
    fun lockedLayersCannotStartPixelTools() =
        runBlocking(Dispatchers.Main) {
            open()
            repository.setLayerLock(repository.getActiveLayerId(), true)
            assertNull(repository.beginRasterEdit(repository.getActiveLayerId()))
        }

    @Test
    fun maskPixelsAndSettingsRoundTrip() =
        runBlocking(Dispatchers.Main) {
            open()
            paint(Color.RED)
            val id = repository.getActiveLayerId()
            repository.addLayerMask(id)
            repository.paintLayerMask(id, 16f, 16f, 8f, reveal = false)
            repository.setLayerMaskDensity(id, 0.7f)
            repository.setLayerMaskFeather(id, 2f)
            val before = repository.compositeBuffer()!!.pixels.copyOf()
            repository.saveCanvas(projectId)
            val maskPath =
                storage
                    .loadDocument(projectId)!!
                    .frames
                    .first()
                    .layers
                    .first()
                    .maskFile
            assertNotNull(maskPath)
            repository.loadCanvas(projectId)
            assertArrayEquals(before, repository.compositeBuffer()!!.pixels)
            assertEquals(0.7f, repository.getActiveLayer()!!.maskDensity, 0f)
        }

    @Test
    fun selectionDoesNotErasePixelsFromTheSavedComposite() =
        runBlocking(Dispatchers.Main) {
            open()
            paint(Color.RED)
            repository.setSelection(SelectionMask.rectangle(SIZE, SIZE, 4f, 4f, 8f, 8f))
            assertPixel(Color.RED)
            repository.saveCanvas(projectId)
            val preview = BitmapPixelBridge.fromEncodedBytes(storage.loadFlattenedBytes(projectId)!!)!!
            assertEquals(Color.RED, preview.getSafe(0, 0))
        }

    @Test
    fun corruptDocumentIsNeverReplacedWithABlankCanvas() =
        runBlocking(Dispatchers.Main) {
            open()
            val bytes = "{invalid document".toByteArray()
            storage.documentFile(projectId).writeBytes(bytes)
            expectFailure { repository.loadOrCreate(projectId, SIZE, SIZE, 72) }
            assertArrayEquals(bytes, storage.documentFile(projectId).readBytes())
        }

    @Test
    fun missingRasterIsAnErrorNotAnEmptyLayer() =
        runBlocking(Dispatchers.Main) {
            open()
            paint(Color.RED)
            repository.saveCanvas(projectId)
            val path =
                storage
                    .loadDocument(projectId)!!
                    .frames
                    .first()
                    .layers
                    .first()
                    .rasterFile!!
            storage.resolve(projectId, path).delete()
            expectFailure { repository.loadCanvas(projectId) }
            assertPixel(Color.RED) // A failed load also preserves the currently open document.
        }

    @Test
    fun explicitSaveClearsRecoveryOnlyAfterCommit() =
        runBlocking(Dispatchers.Main) {
            open()
            paint(Color.RED)
            repository.autosave(projectId)
            assertNotNull(storage.loadAutosave(projectId))
            repository.saveCanvas(projectId)
            assertNull(storage.loadAutosave(projectId))
            repository.loadCanvas(projectId)
            assertPixel(Color.RED)
        }

    @Test
    fun animationSettingsAndFrameDurationAreUndoable() =
        runBlocking(Dispatchers.Main) {
            open()
            val original = repository.timeline.value.settings
            repository.updateAnimationSettings(AnimationSettings(fps = 20))
            assertEquals(50, repository.frames()[0].durationMs)
            assertTrue(repository.undo())
            assertEquals(original, repository.timeline.value.settings)
            assertTrue(repository.redo())
            assertEquals(20, repository.timeline.value.settings.fps)
            repository.setFrameDuration(0, 300)
            assertTrue(repository.undo())
            assertEquals(50, repository.frames()[0].durationMs)
        }

    @Test
    fun cancelledBrushStrokeDoesNotCommitOrAddHistory() =
        runBlocking(Dispatchers.Main) {
            open()
            val id = repository.beginStroke(10f, 10f, 1f, BrushParams(), repository.getActiveLayerId(), false)
            repository.continueStroke(id, 20f, 20f, 1f, 0f, 0f)
            repository.cancelStroke(id)
            assertNull(repository.activeStroke(id))
            assertEquals(0, repository.undoDepth)
            assertFalse(repository.hasUnsavedChanges())
        }

    @Test
    fun projectAndExportPathsCannotEscapeTheirDirectory() =
        runBlocking(Dispatchers.Main) {
            open()
            expectFailure { storage.resolve(projectId, "../../outside") }
            expectFailure { storage.resolve(projectId, "/absolute") }
            expectFailure { storage.saveExport(projectId, "../canvas.artflow", byteArrayOf(1)) }
            expectFailure { storage.saveExport(projectId, "..\\canvas.artflow", byteArrayOf(1)) }
        }

    @Test
    fun failedAtomicWritePreservesOriginalBytesAndRemovesTemporaryFile() {
        val target = File(storage.projectDir(projectId), "atomic-test.bin")
        target.writeBytes(byteArrayOf(1, 2, 3))
        try {
            storage.writeAtomically(target) { output ->
                output.write(byteArrayOf(4, 5))
                throw IOException("Injected mid-write failure")
            }
            fail("Expected the write to fail")
        } catch (expected: IOException) {
            assertEquals("Injected mid-write failure", expected.message)
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
        assertFalse(storage.projectDir(projectId).listFiles()!!.any { it.extension == "tmp" })
    }

    @Test
    fun androidBitmapBridgeIsMutableAndPngPreservesLowAlphaColors() {
        val source = PixelBuffer(256, 1, IntArray(256) { (it shl 24) or 0x13579B })
        val decoded = BitmapPixelBridge.fromEncodedBytes(BitmapPixelBridge.toPngBytes(source))!!
        assertArrayEquals(source.pixels, decoded.pixels)
        val bitmap = BitmapPixelBridge.toBitmap(source)
        try {
            assertTrue(bitmap.isMutable)
            Canvas(bitmap).drawColor(Color.RED)
            assertEquals(Color.RED, bitmap.getPixel(0, 0))
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun open() {
        repository.loadOrCreate(projectId, SIZE, SIZE, 72)
    }

    private suspend fun paint(color: Int) {
        assertTrue(repository.setLayerPixels(repository.getActiveLayerId(), PixelBuffer.filled(SIZE, SIZE, color), "Fixture"))
    }

    private suspend fun assertPixel(color: Int) {
        assertEquals(color, repository.compositeBuffer()!!.getSafe(0, 0))
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        var error: Exception? = null
        try {
            block()
        } catch (caught: Exception) {
            error = caught
        }
        assertNotNull("Operation must fail rather than silently corrupt artwork", error)
    }

    companion object {
        private const val SIZE = 32
        private val IDS = AtomicLong(8_000_000L)
    }
}
