package com.artflow.studio.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.TestEvidence
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.export.KeyedArchiveBuilder
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.export.LayerImports
import com.artflow.studio.data.export.ProcreateImport
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.StorageFileTree
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.presentation.ui.viewmodel.renderedExport
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Real archive import, editable-mask persistence and Android PNG decode for one-channel mask tiles. */
@RunWith(AndroidJUnit4::class)
class ProcreateMaskImportDeviceTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var storage: ProjectStorage
    private lateinit var repository: CanvasRepositoryImpl

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        directory = Files.createTempDirectory(application.cacheDir.toPath(), "procreate-mask-").toFile()
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
        }

    @Test
    fun nativeMaskCoverageSurvivesImportSaveReopenAndPngExport() =
        runBlocking(Dispatchers.Main) {
            repository.loadOrCreate(PROJECT, WIDTH, HEIGHT, 72)
            val incoming = fixture()
            File(TestEvidence.directory(), "procreate-single-channel-mask-input.procreate").writeBytes(incoming)
            ProcreateImport.open(incoming.inputStream(), directory).use { imported ->
                val layer = imported.document.layers().single()
                assertArrayEquals(coverage(), requireNotNull(imported.document.mask(layer)).coverage)
                val result = LayerImports.importProcreate(repository, imported.document)
                assertEquals(1, result.layers)
                assertFalse(result.flattened)
            }
            assertEditableMask()
            repository.saveCanvas(PROJECT)
            val expectedFlattened =
                IntArray(WIDTH * HEIGHT) { at ->
                    val gray = 255 - (coverageAt(at % WIDTH, at / WIDTH))
                    0xFF000000.toInt() or (gray * 0x010101)
                }
            val savedPng = requireNotNull(storage.loadFlattenedBytes(PROJECT))
            assertArrayEquals(expectedFlattened, requireNotNull(BitmapPixelBridge.fromEncodedBytes(savedPng)).pixels)

            repository.dispose()
            storage = ProjectStorage(context)
            repository = CanvasRepositoryImpl(storage)
            requireNotNull(repository.loadCanvas(PROJECT))
            val id = assertEditableMask()
            val result =
                renderedExport(
                    repository,
                    ArtworkExporter(context, storage),
                    PROJECT,
                    "Single-channel mask",
                    ExportOptions(format = ExportFormat.PNG),
                ).getOrThrow()
            val png = File(result.filePath).readBytes()
            File(TestEvidence.directory(), "procreate-single-channel-mask-export.png").writeBytes(png)
            assertArrayEquals(maskedPixels(), requireNotNull(BitmapPixelBridge.fromEncodedBytes(png)).pixels)

            // Concealed source ink remains editable after reopening; it has not been flattened away.
            repository.setLayerMaskEnabled(id, false)
            assertArrayEquals(
                IntArray(WIDTH * HEIGHT) { 0xFF000000.toInt() },
                repository
                    .exportSnapshot(false, false, false)
                    .frames
                    .single()
                    .pixels,
            )
            repository.setLayerMaskEnabled(id, true)
            assertArrayEquals(
                maskedPixels(),
                repository
                    .exportSnapshot(false, false, false)
                    .frames
                    .single()
                    .pixels,
            )
        }

    private suspend fun assertEditableMask(): Long {
        val layer = repository.getAllLayers().single()
        assertEquals("Ink", layer.name)
        assertTrue(layer.hasMask())
        assertTrue(layer.maskEnabled)
        assertArrayEquals(IntArray(WIDTH * HEIGHT) { 0xFF000000.toInt() }, requireNotNull(repository.layerPixels(layer.id)).pixels)
        val snapshot = repository.exportSnapshot(false, false, true, preserveLayerMasks = true)
        val mask = requireNotNull(snapshot.layerMasks[layer.id])
        assertArrayEquals(coverage(), ByteArray(mask.pixels.size) { (mask.pixels[it] ushr 24).toByte() })
        assertArrayEquals(maskedPixels(), snapshot.frames.single().pixels)
        return layer.id
    }

    /** Self-authored fixture: native one-byte mask layout, independent of ProcreateWriter's RGBA masks. */
    private fun fixture(): ByteArray {
        val archive = KeyedArchiveBuilder()
        val mask = archive.instance("SilicaLayer", mapOf("UUID" to archive.add("MASK"), "type" to 3L))
        val ink = archive.instance("SilicaLayer", mapOf("UUID" to archive.add("INK"), "name" to archive.add("Ink"), "mask" to mask))
        val composite = archive.instance("SilicaLayer", mapOf("UUID" to archive.add("COMPOSITE")))
        val root =
            mapOf(
                "\$class" to archive.classOf("SilicaDocument"),
                "size" to archive.add("{$WIDTH, $HEIGHT}"),
                "tileSize" to 16L,
                "layers" to archive.array(listOf(ink)),
                "composite" to composite,
                "backgroundColor" to
                    archive.add(
                        ByteBuffer
                            .allocate(16)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .apply { repeat(4) { putFloat(1f) } }
                            .array(),
                    ),
                "backgroundHidden" to false,
            )
        val entries = linkedMapOf("Document.archive" to archive.encode(root))
        entries["QuickLook/Thumbnail.png"] = BitmapPixelBridge.toPngBytes(PixelBuffer(WIDTH, HEIGHT, maskedPixels()))
        for (row in 0..1) {
            for (column in 0..1) {
                val width = minOf(16, WIDTH - column * 16)
                val height = minOf(16, HEIGHT - row * 16)
                val maskBytes = ByteArray(width * height) { at -> coverageAt(column * 16 + at % width, row * 16 + at / width).toByte() }
                if (column != 0 || row != 0) entries["MASK/$column~$row.lz4"] = apple(maskBytes)
                entries["INK/$column~$row.lz4"] = apple(ByteArray(width * height * 4) { if (it % 4 == 3) 255.toByte() else 0 })
                entries["COMPOSITE/$column~$row.lz4"] = apple(ByteArray(width * height * 4) { if (it % 4 == 3) maskBytes[it / 4] else 0 })
            }
        }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun apple(bytes: ByteArray): ByteArray =
        "bvx-".toByteArray() +
            ByteBuffer
                .allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(bytes.size)
                .array() + bytes +
            "bvx$".toByteArray()

    private fun coverage(): ByteArray = ByteArray(WIDTH * HEIGHT) { coverageAt(it % WIDTH, it / WIDTH).toByte() }

    private fun maskedPixels(): IntArray = IntArray(WIDTH * HEIGHT) { coverageAt(it % WIDTH, it / WIDTH) shl 24 }

    private fun coverageAt(
        x: Int,
        y: Int,
    ): Int = if (x < 16 && y < 16) 255 else intArrayOf(0, 1, 128, 254, 255)[(x + 3 * y) % 5]

    private companion object {
        const val WIDTH = 19
        const val HEIGHT = 18
        const val PROJECT = 619L
    }
}
