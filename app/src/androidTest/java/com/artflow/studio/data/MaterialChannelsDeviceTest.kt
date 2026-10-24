package com.artflow.studio.data

import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.TestEvidence
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.three.MaterialChannel
import com.artflow.studio.core.three.ObjExport
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.local.CanvasDocument
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.StorageFileTree
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.brush.BrushParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

/** Actual storage and independent Android PNG decoding, including scalar data in exported OBJ archives. */
@RunWith(AndroidJUnit4::class)
class MaterialChannelsDeviceTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var storage: ProjectStorage
    private lateinit var repository: CanvasRepositoryImpl
    private val size = 32
    private val colorPixels = IntArray(size * size) { 0xFFAA4020.toInt() }
    private val project = 1L

    @Before fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        directory = Files.createTempDirectory(application.cacheDir.toPath(), "material-maps-").toFile()
        context =
            object : ContextWrapper(application) {
                override fun getFilesDir(): File = directory
            }
        storage = ProjectStorage(context)
        repository = CanvasRepositoryImpl(storage)
    }

    @After fun cleanUp() {
        repository.dispose()
        StorageFileTree.delete(directory)
    }

    @Test fun independentMapStrokesSurviveUndoSaveFreshReopenAndObjExport(): Unit =
        runBlocking(Dispatchers.Main) {
            repository.loadOrCreate(project, size, size, 72)
            val colorLayer = repository.getActiveLayerId()
            assertTrue(repository.setLayerPixels(colorLayer, PixelBuffer(size, size, colorPixels.copyOf()), "Color"))
            assertTrue(repository.saveModel(MODEL))
            val metal = requireNotNull(repository.selectMaterialChannel(MaterialChannel.METALLIC))
            paint(metal, 1f, 8f)
            val rough = requireNotNull(repository.selectMaterialChannel(MaterialChannel.ROUGHNESS))
            paint(rough, 0.25f, 24f)
            val roughPixels = requireNotNull(repository.compositeMaterial(MaterialChannel.ROUGHNESS)).pixels.copyOf()
            assertTrue(repository.undo())
            assertTrue(requireNotNull(repository.compositeMaterial(MaterialChannel.ROUGHNESS)).pixels.all { it == 0 })
            assertTrue(repository.redo())
            assertArrayEquals(roughPixels, requireNotNull(repository.compositeMaterial(MaterialChannel.ROUGHNESS)).pixels)
            val expected =
                MaterialChannel.entries.filter { it.isScalar }.associateWith {
                    requireNotNull(repository.compositeMaterial(it)).pixels.copyOf()
                }
            repository.saveCanvas(project)
            val document = requireNotNull(storage.loadDocument(project))
            assertEquals(CanvasDocument.CURRENT_VERSION, document.version)
            assertEquals(
                setOf(MaterialChannel.COLOR, MaterialChannel.METALLIC, MaterialChannel.ROUGHNESS),
                document.layers
                    .map {
                        it.materialChannel
                    }.toSet(),
            )
            assertArrayEquals(colorPixels, decoded(requireNotNull(storage.loadFlattenedBytes(project))))
            repository.dispose()
            repository = CanvasRepositoryImpl(ProjectStorage(context))
            assertTrue(repository.loadCanvas(project) != null)
            assertEquals(MODEL, repository.loadModel())
            assertArrayEquals(colorPixels, requireNotNull(repository.compositeBuffer()).pixels)
            expected.forEach { (channel, pixels) ->
                assertArrayEquals(pixels, requireNotNull(repository.compositeMaterial(channel)).pixels)
                File(TestEvidence.directory(), "material-${channel.name.lowercase()}-reopened.png").writeBytes(
                    BitmapPixelBridge.toPngBytes(PixelBuffer(size, size, pixels)),
                )
            }
            val snapshot = repository.exportSnapshot(false, false, true, includeMaterials = true)
            assertEquals(listOf(colorLayer), snapshot.layers.map { it.first.id })
            val result =
                ArtworkExporter(context, storage)
                    .exportStill(
                        project,
                        "Materials",
                        snapshot.frames.single(),
                        emptyList(),
                        ExportOptions(format = ExportFormat.MODEL_OBJ),
                        materialMaps = snapshot.materialMaps,
                    ).getOrThrow()
            val bytes = File(result.filePath).readBytes()
            File(TestEvidence.directory(), "material-model-export.zip").writeBytes(bytes)
            val files = unzip(bytes)
            assertEquals(setOf(ObjExport.OBJ, ObjExport.MTL, ObjExport.TEXTURE, ObjExport.METALLIC, ObjExport.ROUGHNESS), files.keys)
            val mtl = files.getValue(ObjExport.MTL).decodeToString()
            assertTrue(mtl.contains("map_Pm metallic.png") && mtl.contains("map_Pr roughness.png"))
            assertArrayEquals(colorPixels, decoded(files.getValue(ObjExport.TEXTURE)))
            expected.forEach { (channel, pixels) ->
                val opaque = MaterialChannel.resolved(PixelBuffer(size, size, pixels), channel)
                assertArrayEquals(opaque.pixels, decoded(files.getValue(ObjExport.mapName(channel))))
            }
            assertEquals(255, decoded(files.getValue(ObjExport.METALLIC))[16 * size + 8] and 255)
            assertEquals(64, decoded(files.getValue(ObjExport.ROUGHNESS))[16 * size + 24] and 255)
            assertEquals(153, decoded(files.getValue(ObjExport.ROUGHNESS))[0] and 255)
            assertEquals(0, decoded(files.getValue(ObjExport.METALLIC))[0] and 255)
        }

    private fun paint(
        layer: Long,
        value: Float,
        x: Float,
    ) {
        repository.setMaterialPaintValue(value)
        val stroke = repository.beginStroke(x, 16f, 1f, BrushParams(size = 8f, pressureToSize = 0f, pressureToOpacity = 0f), layer)
        assertTrue(stroke > 0)
        repository.endStroke(stroke)
    }

    private fun decoded(bytes: ByteArray): IntArray {
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        return try {
            assertEquals(size, bitmap.width)
            assertEquals(size, bitmap.height)
            BitmapPixelBridge.fromBitmap(bitmap).pixels
        } finally {
            bitmap.recycle()
        }
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> =
        buildMap {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    put(entry.name, zip.readBytes())
                    entry = zip.nextEntry
                }
            }
        }

    private companion object {
        const val MODEL = "v -1 -1 0\nv 1 -1 0\nv 0 1 0\nvt 0 0\nvt 1 0\nvt 0.5 1\nf 1/1 2/2 3/3\n"
    }
}
