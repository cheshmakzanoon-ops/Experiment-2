package com.artflow.studio.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.export.ExportArea
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.export.PsdCodec
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.export.LayerImports
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.StorageFileTree
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.presentation.ui.viewmodel.renderedExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/** Exercises the same snapshot, crop, exporter and importer used by the Share panel. */
@RunWith(AndroidJUnit4::class)
class PsdMasksDeviceTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var storage: ProjectStorage

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        directory = Files.createTempDirectory(application.cacheDir.toPath(), "psd-masks-").toFile()
        context =
            object : ContextWrapper(application) {
                override fun getFilesDir(): File = directory
            }
        storage = ProjectStorage(context)
    }

    @After
    fun tearDown() = StorageFileTree.delete(directory)

    @Test
    fun disabledMaskExportsAndImportsAsEditableCoverageWithoutDestroyingHiddenInk() =
        runBlocking(Dispatchers.Main) {
            val source = CanvasRepositoryImpl(storage)
            val target = CanvasRepositoryImpl(storage)
            try {
                source.loadOrCreate(1, 4, 3, 72)
                target.loadOrCreate(2, 4, 3, 72)
                val id = source.getActiveLayerId()
                source.setLayerName(id, "Recoverable ink")
                val ink = PixelBuffer.filled(4, 3, 0xCC336699.toInt())
                assertTrue(source.setLayerPixels(id, ink, "Ink"))
                val coverage = ByteArray(12) { if (it % 4 < 2) 0 else 128.toByte() }
                assertTrue(source.addLayerMask(id, SelectionMask(4, 3, coverage)))
                source.setLayerMaskEnabled(id, false)
                val result =
                    renderedExport(source, ArtworkExporter(context, storage), 1, "Masks", ExportOptions(format = ExportFormat.PSD))
                        .getOrThrow()
                val bytes = File(result.filePath).readBytes()
                val decoded = requireNotNull(PsdCodec.read(bytes))
                val layer = decoded.layers.single()
                assertArrayEquals(ink.pixels, layer.pixels.pixels)
                val mask = requireNotNull(layer.mask)
                assertFalse(mask.isEnabled)
                assertArrayEquals(coverage, mask.coverage)
                assertEquals(1, LayerImports.importPsd(target, bytes))
                val imported = target.getAllLayers().single { it.name == "Recoverable ink" }
                assertFalse(imported.maskEnabled)
                assertTrue(imported.hasMask())
                target.setLayerMaskEnabled(imported.id, true)
                val masked = target.exportSnapshot(false, false, false).frames.single()
                assertEquals(0, masked.pixels[0] ushr 24)
                assertEquals(102, masked.pixels[3] ushr 24)
                target.setLayerMaskEnabled(imported.id, false)
                assertArrayEquals(
                    ink.pixels,
                    target
                        .exportSnapshot(false, false, false)
                        .frames
                        .single()
                        .pixels,
                )
            } finally {
                source.dispose()
                target.dispose()
            }
        }

    @Test
    fun selectionExportClipsOriginalAlphaOnceAndKeepsIndependentMaskCrop() =
        runBlocking(Dispatchers.Main) {
            val source = CanvasRepositoryImpl(storage)
            try {
                source.loadOrCreate(3, 4, 3, 72)
                val id = source.getActiveLayerId()
                assertTrue(source.setLayerPixels(id, PixelBuffer.filled(4, 3, -1), "Ink"))
                assertTrue(source.addLayerMask(id, SelectionMask(4, 3, ByteArray(12) { 128.toByte() })))
                val selected = SelectionMask(4, 3)
                selected.coverage[5] = 128.toByte()
                selected.coverage[6] = 128.toByte()
                source.setSelection(selected)
                val options = ExportOptions(format = ExportFormat.PSD, area = ExportArea.SELECTION)
                val result = renderedExport(source, ArtworkExporter(context, storage), 3, "Selection", options).getOrThrow()
                val decoded = requireNotNull(PsdCodec.read(File(result.filePath).readBytes()))
                assertEquals(2, decoded.width)
                assertEquals(1, decoded.height)
                assertArrayEquals(
                    intArrayOf(0x80FFFFFF.toInt(), 0x80FFFFFF.toInt()),
                    decoded.layers
                        .single()
                        .pixels.pixels,
                )
                assertArrayEquals(
                    byteArrayOf(128.toByte(), 128.toByte()),
                    decoded.layers
                        .single()
                        .mask!!
                        .coverage,
                )
                assertEquals(64, decoded.composite!!.pixels[0] ushr 24)
            } finally {
                source.dispose()
            }
        }

    @Test
    fun folderMaskSurvivesExportAndImport() =
        runBlocking(Dispatchers.Main) {
            val source = CanvasRepositoryImpl(storage)
            val target = CanvasRepositoryImpl(storage)
            try {
                source.loadOrCreate(4, 4, 3, 72)
                target.loadOrCreate(5, 4, 3, 72)
                val id = source.getActiveLayerId()
                assertTrue(source.setLayerPixels(id, PixelBuffer.filled(4, 3, -1), "Ink"))
                val group = requireNotNull(source.groupLayers(listOf(id)))
                source.setLayerName(group, "Masked folder")
                assertTrue(source.addLayerMask(group, SelectionMask(4, 3, ByteArray(12) { 100 })))
                val result =
                    renderedExport(source, ArtworkExporter(context, storage), 4, "Folder", ExportOptions(format = ExportFormat.PSD))
                        .getOrThrow()
                val bytes = File(result.filePath).readBytes()
                val decoded = requireNotNull(PsdCodec.read(bytes))
                assertNotNull(decoded.layers.single { it.section.isGroupHeader }.mask)
                LayerImports.importPsd(target, bytes)
                assertTrue(target.getAllLayers().single { it.name == "Masked folder" }.hasMask())
            } finally {
                source.dispose()
                target.dispose()
            }
        }
}
