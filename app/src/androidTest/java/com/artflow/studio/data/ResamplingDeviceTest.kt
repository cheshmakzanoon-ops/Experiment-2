package com.artflow.studio.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.tool.LiquifyTool
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.export.LayerRaster
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.StorageFileTree
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/** Actual repository, Android PNG decoding and export; no replacement renderer or codec. */
@RunWith(AndroidJUnit4::class)
class ResamplingDeviceTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var storage: ProjectStorage
    private lateinit var repository: CanvasRepositoryImpl

    @Before fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        directory = Files.createTempDirectory(application.cacheDir.toPath(), "resampling-").toFile()
        context =
            object : ContextWrapper(application) {
                override fun getFilesDir(): File = directory
            }
        storage = ProjectStorage(context)
        repository = CanvasRepositoryImpl(storage)
    }

    @After fun tearDown() =
        runBlocking(Dispatchers.Main) {
            repository.dispose()
            StorageFileTree.delete(directory)
        }

    @Test fun resizeEdgesSurviveUndoStorageAndScaledPngExport() =
        runBlocking(Dispatchers.Main) {
            val red = 0xFFFF0000.toInt()
            val source = intArrayOf(red, 0x000000FF)
            val expected = intArrayOf(red, 0xBFFF0000.toInt(), 0x40FF0000, 0x000000FF)
            repository.loadOrCreate(1, 2, 1, 144)
            val layer = repository.getActiveLayerId()
            assertTrue(repository.setLayerPixels(layer, PixelBuffer(2, 1, source.copyOf()), "Alpha edge"))
            val depth = repository.undoDepth
            assertTrue(repository.resizeCanvas(4, 1, true, CanvasOperations.Anchor.CENTER))
            assertEquals(depth + 1, repository.undoDepth)
            assertArrayEquals(expected, requireNotNull(repository.layerPixels(layer)).pixels)
            assertTrue(repository.undo())
            assertArrayEquals(source, requireNotNull(repository.layerPixels(layer)).pixels)
            assertTrue(repository.redo())
            assertArrayEquals(expected, requireNotNull(repository.layerPixels(layer)).pixels)
            assertTrue(repository.saveCanvas(1) != null)
            assertTrue(repository.loadCanvas(1) != null)
            assertArrayEquals(expected, requireNotNull(repository.layerPixels(layer)).pixels)
            val snapshot = repository.exportSnapshot(false, false, true)
            val result =
                ArtworkExporter(context, storage)
                    .exportStill(
                        1,
                        "Alpha edge",
                        snapshot.frames.single(),
                        snapshot.layers.map { (metadata, pixels) -> LayerRaster(metadata.name, pixels, metadata) },
                        ExportOptions(format = ExportFormat.PNG, scale = 2f, dpi = 144),
                        snapshot.hasAdjustmentLayers,
                    ).getOrThrow()
            val exported = requireNotNull(BitmapPixelBridge.fromEncodedBytes(File(result.filePath).readBytes()))
            assertEquals(8, exported.width)
            assertEquals(2, exported.height)
            val row = intArrayOf(255, 239, 207, 159, 96, 48, 16, 0).map { if (it == 0) 0 else (it shl 24) or 0x00FF0000 }.toIntArray()
            assertArrayEquals(row + row, exported.pixels)
        }

    @Test fun displacedEdgesSurviveRealPngEncodingWithoutDarkening() {
        val source = PixelBuffer(2, 1, intArrayOf(0xFFFF0000.toInt(), 0x000000FF))
        val map = LiquifyTool.DisplacementMap(2, 1)
        map.add(0, 0, 0.5f, 0f)
        val result = map.apply(source)
        assertEquals(0x80FF0000.toInt(), result.pixels[0])
        val decoded = requireNotNull(BitmapPixelBridge.fromEncodedBytes(BitmapPixelBridge.toPngBytes(result)))
        assertArrayEquals(result.pixels, decoded.pixels)
        assertEquals(0xFFFF0000.toInt(), source.pixels[0])
    }
}
