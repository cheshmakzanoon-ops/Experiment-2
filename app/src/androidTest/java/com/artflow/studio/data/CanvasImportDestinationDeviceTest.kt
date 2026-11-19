package com.artflow.studio.data

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.export.PngCodec
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.StorageFileTree
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.presentation.ui.viewmodel.CanvasImportController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/** The picker's controller reads a real Android URI inside its document-bound transaction. */
@RunWith(AndroidJUnit4::class)
class CanvasImportDestinationDeviceTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var storage: ProjectStorage
    private lateinit var repository: CanvasRepositoryImpl

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        directory = Files.createTempDirectory(application.cacheDir.toPath(), "canvas-import-destination-").toFile()
        context =
            object : ContextWrapper(application) {
                override fun getFilesDir(): File = directory
            }
        storage = ProjectStorage(context)
        repository = CanvasRepositoryImpl(storage)
    }

    @After
    fun tearDown() =
        runBlocking(Dispatchers.Main) {
            repository.dispose()
            StorageFileTree.delete(directory)
            Unit
        }

    @Test
    fun uriDecodeRunsOffMainAndPrivatePhotoSurvivesUndoRedoAndReopen() =
        runBlocking(Dispatchers.Main) {
            assertNotNull(repository.loadOrCreate(1, 4, 4, 72))
            val source = imageUri()
            val messages = mutableListOf<String>()
            val controller = CanvasImportController(repository, this, messages::add, {})
            val request = controller.beginImage(isPrivate = true)
            request.complete {
                assertNotSame(Looper.getMainLooper(), Looper.myLooper())
                BitmapPixelBridge.decodeUri(context.contentResolver, source)
            }
            request.job!!.join()
            val imported = repository.getAllLayers().single { it.isPrivate }
            val pixels = repository.layerPixels(imported.id)!!.pixels.copyOf()
            assertEquals(RED, repository.layerPixels(imported.id)!!.getSafe(1, 1))
            assertEquals(BLUE, repository.layerPixels(imported.id)!!.getSafe(2, 2))
            assertEquals(1, repository.undoDepth)
            assertTrue(repository.undo())
            assertEquals(1, repository.getAllLayers().size)
            assertTrue(repository.redo())
            assertArrayEquals(pixels, repository.layerPixels(imported.id)!!.pixels)
            assertNotNull(repository.saveCanvas(1))
            repository.dispose()
            repository = CanvasRepositoryImpl(storage)
            assertNotNull(repository.loadCanvas(1))
            assertTrue(repository.getAllLayers().single { it.id == imported.id }.isPrivate)
            assertArrayEquals(pixels, repository.layerPixels(imported.id)!!.pixels)
            assertTrue(messages.single().startsWith("Private photo added"))
        }

    @Test
    fun delayedAndroidUriDecodeCannotLandInAnArtworkOpenedWhileWaiting() =
        runBlocking(Dispatchers.Main) {
            assertNotNull(repository.loadOrCreate(1, 4, 4, 72))
            val source = imageUri()
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val messages = mutableListOf<String>()
            val controller = CanvasImportController(repository, this, messages::add, {})
            val request = controller.beginImage()
            request.complete {
                entered.complete(Unit)
                resume.await()
                BitmapPixelBridge.decodeUri(context.contentResolver, source)
            }
            entered.await()
            assertNotNull(repository.loadOrCreate(2, 4, 4, 72))
            assertTrue(repository.setLayerPixels(repository.getActiveLayerId(), PixelBuffer.filled(4, 4, BLUE), "Other artwork"))
            val layers = repository.getAllLayers()
            val revision = repository.contentRevision
            val history = repository.historyMark
            val pixels = repository.layerPixels(repository.getActiveLayerId())!!.pixels.copyOf()
            resume.complete(Unit)
            request.job!!.join()
            assertEquals(2L, repository.projectId())
            assertEquals(layers, repository.getAllLayers())
            assertEquals(revision, repository.contentRevision)
            assertEquals(history, repository.historyMark)
            assertArrayEquals(pixels, repository.layerPixels(repository.getActiveLayerId())!!.pixels)
            assertTrue(messages.single().contains("artwork changed"))
        }

    private fun imageUri(): Uri {
        val file = File(directory, "picked.png")
        file.writeBytes(PngCodec.encode(PixelBuffer(2, 2, intArrayOf(RED, BLUE, RED, BLUE))))
        return Uri.fromFile(file)
    }

    private companion object {
        val RED = 0xFFFF0000.toInt()
        val BLUE = 0xFF0000FF.toInt()
    }
}
