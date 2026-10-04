package com.artflow.studio.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.export.LayerImports
import com.artflow.studio.data.export.LayerRaster
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.StorageFileTree
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

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
}
