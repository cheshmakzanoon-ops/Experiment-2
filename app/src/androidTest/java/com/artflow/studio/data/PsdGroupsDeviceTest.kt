package com.artflow.studio.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.export.ProcreateReader
import com.artflow.studio.core.export.PsdCodec
import com.artflow.studio.core.pixels.BlendModes
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.export.LayerImports
import com.artflow.studio.data.export.LayerRaster
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.StorageFileTree
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.layer.BlendMode
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
import java.util.zip.ZipFile

/** Layer groups leave as Photoshop folders and come back as groups, nested and with their settings. */
@RunWith(AndroidJUnit4::class)
class PsdGroupsDeviceTest {
    private lateinit var directory: File
    private lateinit var storage: ProjectStorage
    private lateinit var context: Context

    @Before fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        directory = Files.createTempDirectory(application.cacheDir.toPath(), "psd-groups-").toFile()
        context =
            object : ContextWrapper(application) {
                override fun getFilesDir(): File = directory
            }
        storage = ProjectStorage(context)
    }

    @After fun tearDown() = StorageFileTree.delete(directory)

    @Test fun groupsRoundTripThroughPsd() =
        runBlocking(Dispatchers.Main) {
            val source = CanvasRepositoryImpl(storage)
            val bytes =
                try {
                    source.loadOrCreate(1, 8, 8, 72)
                    val paper = source.getActiveLayerId()
                    assertTrue(source.setLayerPixels(paper, PixelBuffer.filled(8, 8, 0xFFFFFFFF.toInt()), "Paper"))
                    source.setLayerName(paper, "Paper")
                    val sky = source.addLayer("Sky").id
                    assertTrue(source.setLayerPixels(sky, PixelBuffer.filled(8, 8, 0xFF3366CC.toInt()), "Sky"))
                    val sun = source.addLayer("Sun").id
                    assertTrue(source.setLayerPixels(sun, PixelBuffer.filled(8, 8, 0x80FFCC00.toInt()), "Sun"))
                    val inner = requireNotNull(source.groupLayers(listOf(sun)))
                    source.setLayerName(inner, "Inner")
                    source.setLayerOpacity(inner, 0.5f)
                    val outer = requireNotNull(source.groupLayers(listOf(sky, inner)))
                    source.setLayerName(outer, "Outer")
                    val snapshot = source.exportSnapshot(false, true, true)
                    val result =
                        ArtworkExporter(context, storage)
                            .exportStill(
                                1,
                                "Groups",
                                snapshot.frames.single(),
                                snapshot.layers.map { (layer, pixels) -> LayerRaster(layer.name, pixels, layer) },
                                ExportOptions(format = ExportFormat.PSD),
                                snapshot.hasAdjustmentLayers,
                                snapshot.groups,
                            ).getOrThrow()
                    File(result.filePath).readBytes()
                } finally {
                    source.dispose()
                }

            val target = CanvasRepositoryImpl(storage)
            try {
                target.loadOrCreate(2, 8, 8, 72)
                assertEquals(3, LayerImports.importPsd(target, bytes))
                val layers = target.getAllLayers()
                val outer = layers.single { it.isGroup && it.name == "Outer" }
                val inner = layers.single { it.isGroup && it.name == "Inner" }
                assertEquals(outer.id, inner.parentGroupId)
                assertEquals(0.5f, inner.opacity, 0.01f)
                assertEquals(outer.id, layers.single { it.name == "Sky" }.parentGroupId)
                assertEquals(inner.id, layers.single { it.name == "Sun" }.parentGroupId)
                assertEquals(null, layers.single { it.name == "Paper" }.parentGroupId)
            } finally {
                target.dispose()
            }
        }

    @Test fun normalAndPassThroughPsdFoldersRetainPixelsAcrossImportSaveReopenAndExport() =
        runBlocking(Dispatchers.Main) {
            for ((index, mode) in listOf(BlendMode.NORMAL, BlendMode.PASS_THROUGH).withIndex()) {
                val paper = PixelBuffer.filled(2, 1, 0xFF808080.toInt())
                val ink = PixelBuffer(2, 1, intArrayOf(0xFF800000.toInt(), 0x80800000.toInt()))
                val expected = paper.copy()
                BlendModes.composite(expected, ink, if (mode == BlendMode.NORMAL) BlendMode.NORMAL else BlendMode.MULTIPLY, 1f)
                val records =
                    listOf(
                        PsdCodec.PsdLayer("Paper", paper),
                        PsdCodec.PsdLayer.groupEnd(),
                        PsdCodec.PsdLayer("Ink", ink, blendMode = BlendMode.MULTIPLY),
                        PsdCodec.PsdLayer.groupHeader("Scope", blendMode = mode),
                    )
                val incoming = PsdCodec.write(2, 1, records, expected)
                val projectId = 10L + index
                val imported = CanvasRepositoryImpl(storage)
                try {
                    imported.loadOrCreate(projectId, 2, 1, 72)
                    assertEquals(2, LayerImports.importPsd(imported, incoming))
                    assertEquals(mode, imported.getAllLayers().single { it.isGroup }.blendMode)
                    assertArrayEquals(expected.pixels, requireNotNull(imported.compositeBuffer()).pixels)
                    imported.saveCanvas(projectId)
                } finally {
                    imported.dispose()
                }

                val reopened = CanvasRepositoryImpl(storage)
                try {
                    reopened.loadOrCreate(projectId, 2, 1, 72)
                    assertEquals(mode, reopened.getAllLayers().single { it.isGroup }.blendMode)
                    assertArrayEquals(expected.pixels, requireNotNull(reopened.compositeBuffer()).pixels)
                    val snapshot = reopened.exportSnapshot(false, true, true)
                    val result =
                        ArtworkExporter(context, storage)
                            .exportStill(
                                projectId,
                                "Scope-$index",
                                snapshot.frames.single(),
                                snapshot.layers.map { (layer, pixels) -> LayerRaster(layer.name, pixels, layer) },
                                ExportOptions(format = ExportFormat.PSD),
                                snapshot.hasAdjustmentLayers,
                                snapshot.groups,
                            ).getOrThrow()
                    val exported = requireNotNull(PsdCodec.read(File(result.filePath).readBytes()))
                    assertEquals(mode, exported.layers.single { it.section.isGroupHeader }.blendMode)
                    assertArrayEquals(expected.pixels, requireNotNull(exported.composite).pixels)

                    val shared = exportProcreate(reopened, projectId)
                    val visible = shared.layers().filter { it.visible }
                    if (mode == BlendMode.NORMAL) {
                        assertEquals(listOf("Artwork (rendered effects)"), visible.map { it.name })
                        assertArrayEquals(expected.pixels, shared.pixels(visible.single()).pixels)
                        assertTrue(shared.layers().any { it.name == "Ink" && !it.visible })
                    } else {
                        assertTrue(visible.none { it.name == "Artwork (rendered effects)" })
                    }
                } finally {
                    reopened.dispose()
                }
            }
        }

    @Test fun onlyAnEnabledGroupMaskRequiresProcreateAppearanceFallback() =
        runBlocking(Dispatchers.Main) {
            val repository = CanvasRepositoryImpl(storage)
            try {
                repository.loadOrCreate(50, 2, 1, 72)
                val ink = repository.getActiveLayerId()
                repository.setLayerPixels(ink, PixelBuffer.filled(2, 1, 0xFFFF0000.toInt()), "Ink")
                val group = requireNotNull(repository.groupLayers(listOf(ink)))
                repository.addLayerMask(group, SelectionMask.rectangle(2, 1, 0f, 0f, 1f, 1f))
                val enabled = exportProcreate(repository, 50)
                val appearance = enabled.layers().single { it.visible }
                assertEquals("Artwork (rendered effects)", appearance.name)
                assertArrayEquals(intArrayOf(0xFFFF0000.toInt(), 0), enabled.pixels(appearance).pixels)

                repository.setLayerMaskEnabled(group, false)
                val disabled = exportProcreate(repository, 50)
                assertTrue(disabled.layers().none { it.name == "Artwork (rendered effects)" })
            } finally {
                repository.dispose()
            }
        }

    private suspend fun exportProcreate(
        repository: CanvasRepositoryImpl,
        projectId: Long,
    ): ProcreateReader.Document {
        val snapshot = repository.exportSnapshot(false, true, true)
        val result =
            ArtworkExporter(context, storage)
                .exportStill(
                    projectId,
                    "Group interchange",
                    snapshot.frames.single(),
                    snapshot.layers.map { (layer, pixels) -> LayerRaster(layer.name, pixels, layer) },
                    ExportOptions(format = ExportFormat.PROCREATE),
                    snapshot.hasAdjustmentLayers,
                    snapshot.groups,
                ).getOrThrow()
        val entries =
            ZipFile(result.filePath).use { zip ->
                zip.entries().toList().associate { entry -> entry.name to zip.getInputStream(entry).use { it.readBytes() } }
            }
        val files =
            object : ProcreateReader.Files {
                override val names = entries.keys

                override fun read(name: String): ByteArray? = entries[name]
            }
        return ProcreateReader.open(files) { null }
    }
}
