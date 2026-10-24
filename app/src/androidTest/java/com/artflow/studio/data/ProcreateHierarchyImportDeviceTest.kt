package com.artflow.studio.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.TestEvidence
import com.artflow.studio.core.export.BinaryPlist
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.export.KeyedArchiveBuilder
import com.artflow.studio.core.export.ProcreateReader
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.export.LayerImports
import com.artflow.studio.data.export.ProcreateImport
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.StorageFileTree
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.layer.BlendMode
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

/** Native flat-index/tree separation through the actual import, storage and editable group paths. */
@RunWith(AndroidJUnit4::class)
class ProcreateHierarchyImportDeviceTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var storage: ProjectStorage
    private lateinit var repository: CanvasRepositoryImpl

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        directory = Files.createTempDirectory(application.cacheDir.toPath(), "procreate-hierarchy-").toFile()
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
    fun nativeHierarchyAndHiddenGroupsRemainEditableAfterSaveReopenAndExport() =
        runBlocking(Dispatchers.Main) {
            repository.loadOrCreate(PROJECT, SIDE, SIDE, 72)
            val incoming = fixture()
            File(TestEvidence.directory(), "procreate-native-hierarchy-input.procreate").writeBytes(incoming)
            ProcreateImport.open(incoming.inputStream(), directory).use { imported ->
                assertEquals(listOf("Loose", "Outer"), imported.document.nodes.map { it.name })
                val outer = imported.document.nodes.last() as ProcreateReader.Group
                assertEquals(listOf("Concealed", "Base"), outer.children.map { it.name })
                assertFalse(outer.children.first().visible)
                assertEquals(listOf("Loose", "Ink", "Base"), imported.document.layers().map { it.name })
                val result = LayerImports.importProcreate(repository, imported.document)
                assertEquals(3, result.layers)
                assertFalse(result.flattened)
            }
            assertHierarchy()
            assertArrayEquals(expectedPixels(reveal = false), pixels())
            repository.saveCanvas(PROJECT)
            storage
                .documentFile(
                    PROJECT,
                ).copyTo(File(TestEvidence.directory(), "procreate-native-hierarchy-saved.artflow"), overwrite = true)

            repository.dispose()
            storage = ProjectStorage(context)
            repository = CanvasRepositoryImpl(storage)
            requireNotNull(repository.loadCanvas(PROJECT))
            val concealed = assertHierarchy()
            assertArrayEquals(expectedPixels(reveal = false), pixels())
            val exported =
                renderedExport(
                    repository,
                    ArtworkExporter(context, storage),
                    PROJECT,
                    "Native hierarchy",
                    ExportOptions(format = ExportFormat.PNG),
                ).getOrThrow()
            val png = File(exported.filePath).readBytes()
            File(TestEvidence.directory(), "procreate-native-hierarchy-export.png").writeBytes(png)
            assertArrayEquals(expectedPixels(reveal = false), requireNotNull(BitmapPixelBridge.fromEncodedBytes(png)).pixels)

            // A concealed source layer still belongs to its nested folder and can be revealed and undone.
            repository.setLayerVisibility(concealed, true)
            assertArrayEquals(expectedPixels(reveal = true), pixels())
            assertTrue(repository.undo())
            assertArrayEquals(expectedPixels(reveal = false), pixels())
            assertTrue(repository.redo())
            assertArrayEquals(expectedPixels(reveal = true), pixels())
        }

    private suspend fun assertHierarchy(): Long {
        val layers = repository.getAllLayers()
        assertEquals(5, layers.size)
        assertEquals(5, layers.map { it.id }.toSet().size)
        assertEquals(setOf("Loose", "Outer", "Concealed", "Ink", "Base"), layers.map { it.name }.toSet())
        val outer = layers.single { it.name == "Outer" }
        val concealed = layers.single { it.name == "Concealed" }
        assertTrue(outer.isGroup)
        assertTrue(concealed.isGroup)
        assertTrue(outer.isVisible)
        assertFalse(concealed.isVisible)
        assertEquals(0.5f, outer.opacity, 0f)
        assertEquals(1f, concealed.opacity, 0f)
        assertEquals(BlendMode.PASS_THROUGH, outer.blendMode)
        assertEquals(BlendMode.PASS_THROUGH, concealed.blendMode)
        assertEquals(null, outer.parentGroupId)
        assertEquals(outer.id, concealed.parentGroupId)
        assertEquals(outer.id, layers.single { it.name == "Base" }.parentGroupId)
        assertEquals(concealed.id, layers.single { it.name == "Ink" }.parentGroupId)
        assertEquals(null, layers.single { it.name == "Loose" }.parentGroupId)
        for ((name, color) in listOf("Ink" to RED, "Base" to GREEN)) {
            val id = layers.single { it.name == name }.id
            assertArrayEquals(IntArray(SIDE * SIDE) { color }, requireNotNull(repository.layerPixels(id)).pixels)
        }
        return concealed.id
    }

    private suspend fun pixels(): IntArray =
        repository
            .exportSnapshot(false, false, false)
            .frames
            .single()
            .pixels

    private fun expectedPixels(reveal: Boolean): IntArray =
        IntArray(SIDE * SIDE) { at ->
            if (isLoose(at)) {
                BLUE
            } else if (reveal) {
                0x80FF0000.toInt()
            } else {
                0x8000FF00.toInt()
            }
        }

    /** Self-authored native field layout, independent of the existing ArtFlow ProcreateWriter. */
    private fun fixture(): ByteArray {
        val archive = KeyedArchiveBuilder()

        fun layer(name: String) = archive.instance("SilicaLayer", mapOf("name" to archive.add(name), "UUID" to archive.add(name)))

        fun group(
            name: String,
            children: List<BinaryPlist.Uid>,
            hidden: Boolean,
            opacity: Double,
        ) = archive.instance(
            "SilicaGroup",
            mapOf(
                "name" to archive.add(name),
                "children" to archive.array(children),
                "isHidden" to hidden,
                "opacity" to opacity,
            ),
        )

        val loose = layer("Loose")
        val ink = layer("Ink")
        val base = layer("Base")
        val concealed = group("Concealed", listOf(ink), hidden = true, opacity = 1.0)
        val outer = group("Outer", listOf(concealed, base), hidden = false, opacity = 0.5)
        val document =
            mapOf(
                "\$class" to archive.classOf("SilicaDocument"),
                "size" to archive.add("{$SIDE, $SIDE}"),
                "tileSize" to SIDE.toLong(),
                "layers" to archive.array(listOf(loose, ink, base)),
                "unwrappedLayers" to archive.array(listOf(loose, outer)),
                "backgroundHidden" to true,
            )
        val entries = linkedMapOf("Document.archive" to archive.encode(document))
        for ((name, color) in listOf("Loose" to BLUE, "Ink" to RED, "Base" to GREEN)) {
            val raw =
                ByteArray(SIDE * SIDE * 4) { at ->
                    val pixel = if (name == "Loose" && !isLoose(at / 4)) 0 else color
                    (pixel ushr intArrayOf(16, 8, 0, 24)[at % 4]).toByte()
                }
            entries["$name/0~0.lz4"] =
                "bvx-".toByteArray() +
                ByteBuffer
                    .allocate(4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(raw.size)
                    .array() +
                raw + "bvx$".toByteArray()
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

    private fun isLoose(at: Int): Boolean = at % SIDE >= 24 && at / SIDE < 8

    private companion object {
        const val SIDE = 32
        const val PROJECT = 623L
        const val RED = 0xFFFF0000.toInt()
        const val GREEN = 0xFF00FF00.toInt()
        const val BLUE = 0xFF0000FF.toInt()
    }
}
