package com.artflow.studio.data

import android.content.Context
import android.content.ContextWrapper
import android.graphics.ColorSpace
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.export.PngCodec
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.StorageFileTree
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.presentation.ui.viewmodel.CanvasImportController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
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
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.roundToInt

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

    @Test
    fun taggedPhotoColorsConvertOnceIntoEitherDocumentProfileAndSurviveReopen() =
        runBlocking(Dispatchers.Main) {
            val source = taggedImageFile()
            val original = source.readBytes()
            ColorProfile.entries.forEachIndexed { index, profile ->
                for (fromFile in listOf(false, true)) {
                    verifyTaggedImport(source, profile, fromFile, 20L + index * 2 + if (fromFile) 1 else 0)
                }
            }
            assertArrayEquals(original, source.readBytes())
        }

    private suspend fun verifyTaggedImport(
        source: File,
        profile: ColorProfile,
        fromFile: Boolean,
        projectId: Long,
    ) = coroutineScope {
        assertNotNull(repository.loadOrCreate(projectId, 64, 48, 72))
        assertTrue(repository.setColorProfile(profile, undoable = false))
        val depth = repository.undoDepth
        val messages = mutableListOf<String>()
        val controller = CanvasImportController(repository, this, messages::add, {})
        val request = controller.beginImage()
        request.complete {
            assertNotSame(Looper.getMainLooper(), Looper.myLooper())
            if (fromFile) {
                BitmapPixelBridge.decodeFile(source.path)
            } else {
                BitmapPixelBridge.decodeUri(context.contentResolver, Uri.fromFile(source))
            }
        }
        request.job!!.join()
        assertTrue(messages.toString(), messages.isEmpty())
        val id = repository.getActiveLayerId()
        val pixels = requireNotNull(repository.layerPixels(id)).pixels.copyOf()
        assertTaggedColors(pixels, profile)
        assertEquals(depth + 1, repository.undoDepth)
        assertTrue(repository.undo())
        assertEquals(1, repository.getAllLayers().size)
        assertTrue(repository.redo())
        assertArrayEquals(pixels, requireNotNull(repository.layerPixels(id)).pixels)
        assertNotNull(repository.saveCanvas(projectId))
        repository.dispose()
        repository = CanvasRepositoryImpl(storage)
        assertNotNull(repository.loadCanvas(projectId))
        assertEquals(profile, repository.getColorProfile())
        assertArrayEquals(pixels, requireNotNull(repository.layerPixels(id)).pixels)
    }

    private fun assertTaggedColors(
        pixels: IntArray,
        profile: ColorProfile,
    ) {
        // Independent LittleCMS values for the authored fixture's P3-to-sRGB conversion.
        val samples = mapOf(12 * 64 + 12 to 0xFFFA3117.toInt(), 12 * 64 + 40 to 0xFF00B773.toInt(), 36 * 64 + 12 to 0xFF1D47EF.toInt())
        val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
        val target = ColorSpace.get(if (profile == ColorProfile.SRGB) ColorSpace.Named.SRGB else ColorSpace.Named.DISPLAY_P3)
        val connector = ColorSpace.connect(srgb, target)
        samples.forEach { (index, color) ->
            val expected = connector.transform((color ushr 16 and 255) / 255f, (color ushr 8 and 255) / 255f, (color and 255) / 255f)
            assertEquals(255, pixels[index] ushr 24)
            listOf(16, 8, 0).forEachIndexed { component, shift ->
                val actual = pixels[index] ushr shift and 255
                assertTrue(
                    "$profile pixel=$index channel=$shift expected=${expected[component]} actual=$actual",
                    abs(actual - (expected[component] * 255).roundToInt()) <= 2,
                )
            }
        }
        assertEquals(0, pixels[0] ushr 24)
        assertEquals(128, pixels[32 * 64 + 48] ushr 24)
    }

    private fun taggedImageFile(): File {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val encoded =
            assets.open("procreate/color/display-p3-derived.procreate").use { input ->
                ZipInputStream(input).use { zip ->
                    generateSequence { zip.nextEntry }.first { it.name == "QuickLook/Thumbnail.png" }
                    zip.readBytes()
                }
            }
        return File(directory, "tagged-photo.png").apply { writeBytes(encoded) }
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
