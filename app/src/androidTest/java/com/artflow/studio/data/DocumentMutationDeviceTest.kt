package com.artflow.studio.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.export.PsdCodec
import com.artflow.studio.core.pixels.LayerMaskSource
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.export.LayerRaster
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.model.layer.FilterType
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
import java.util.concurrent.atomic.AtomicLong

/** Real Android codecs and private-storage round trips after destructive document operations. */
@RunWith(AndroidJUnit4::class)
class DocumentMutationDeviceTest {
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
    fun tearDown() =
        runBlocking(Dispatchers.Main) {
            repository.dispose()
            storage.deleteProjectFiles(projectId)
        }

    @Test
    fun transformedLegacyInkMasksAndFramesSurviveRealStorageReload() =
        runBlocking(Dispatchers.Main) {
            repository.loadOrCreate(projectId, 32, 24, 144)
            val layer = repository.getActiveLayerId()
            repository.replaceLayerStrokes(
                layer,
                listOf(
                    Stroke(
                        id = 72,
                        layerId = layer,
                        color = 0xFFDD3311.toInt(),
                        points = listOf(StrokePoint(16f, 12f, timestamp = 0)),
                        brushParams = BrushParams(size = 14f, pressureToSize = 0f),
                        timestamp = 0,
                    ),
                ),
            )
            assertTrue(repository.createLayerMask(layer, LayerMaskSource.HORIZONTAL))
            repository.addFrame(true)
            assertTrue(
                repository.setLayerPixels(
                    repository.getActiveLayerId(),
                    PixelBuffer.filled(32, 24, 0xBB1144DD.toInt()),
                    "Second frame",
                ),
            )
            repository.setFrameDuration(1, 250)
            assertTrue(repository.rotateCanvas(90))
            val expected = (0..1).map { requireNotNull(repository.compositeFrame(it, transparentBackground = true)) }
            assertTrue(expected[0].pixels.any { (it ushr 24) > 0 })
            repository.saveCanvas(projectId)
            repository.loadCanvas(projectId)
            assertEquals(24, repository.getCanvasSize().width)
            assertEquals(32, repository.getCanvasSize().height)
            assertEquals(250, repository.frames()[1].durationMs)
            expected.forEachIndexed { index, pixels ->
                assertArrayEquals(pixels.pixels, requireNotNull(repository.compositeFrame(index, transparentBackground = true)).pixels)
            }
        }

    @Test
    fun mergedFilterAndMaskedAlphaSurviveSaveReopen() =
        runBlocking(Dispatchers.Main) {
            repository.loadOrCreate(projectId, 32, 24, 72)
            val layer = repository.getActiveLayerId()
            repository.setLayerPixels(layer, PixelBuffer.filled(32, 24, 0xCCFF0000.toInt()), "Ink")
            repository.createLayerMask(layer, LayerMaskSource.HORIZONTAL)
            val upper = repository.addLayer("Upper").id
            repository.setLayerPixels(upper, PixelBuffer.filled(32, 24, 0x660000FF), "Upper")
            val before = requireNotNull(repository.compositeFrame(0, transparentBackground = true))
            assertTrue(repository.mergeLayerDown(upper))
            repository.saveCanvas(projectId)
            repository.loadCanvas(projectId)
            assertArrayEquals(before.pixels, requireNotNull(repository.compositeFrame(0, transparentBackground = true)).pixels)
            val effect = requireNotNull(repository.addFilterLayer(FilterType.NOISE)).id
            val preview = requireNotNull(repository.compositeFrame(0, transparentBackground = true))
            repository.saveCanvas(projectId)
            repository.loadCanvas(projectId)
            assertEquals(FilterType.NOISE, repository.getAllLayers().last().filterType)
            assertArrayEquals(preview.pixels, requireNotNull(repository.compositeFrame(0, transparentBackground = true)).pixels)
            // A backdrop-dependent bake may be rejected, but a successful bake must preserve preview.
            if (repository.rasterizeFilterLayer(effect)) {
                assertArrayEquals(preview.pixels, requireNotNull(repository.compositeFrame(0, transparentBackground = true)).pixels)
            }
        }

    @Test
    fun layeredPsdContainsTheVisibleFilteredAppearanceAndHiddenOriginals() =
        runBlocking(Dispatchers.Main) {
            repository.loadOrCreate(projectId, 32, 24, 72)
            repository.setLayerPixels(repository.getActiveLayerId(), PixelBuffer.filled(32, 24, -1), "White ink")
            val effect = requireNotNull(repository.addFilterLayer(FilterType.VIGNETTE)).id
            repository.setFilterAmount(effect, 1f)
            val snapshot = repository.exportSnapshot(false, false, true)
            assertTrue(snapshot.hasAdjustmentLayers)
            val result =
                ArtworkExporter(ApplicationProvider.getApplicationContext<Context>(), storage)
                    .exportStill(
                        projectId,
                        "Filtered layers",
                        snapshot.frames.single(),
                        snapshot.layers.map { (layer, pixels) -> LayerRaster(layer.name, pixels, layer) },
                        ExportOptions(format = ExportFormat.PSD),
                        snapshot.hasAdjustmentLayers,
                    ).getOrThrow()
            assertTrue(requireNotNull(result.warning).contains("filter"))
            val parsed = requireNotNull(PsdCodec.read(File(result.filePath).readBytes()))
            val visible = parsed.layers.filter { it.isVisible }
            assertEquals(1, visible.size)
            assertTrue(parsed.layers.any { !it.isVisible })
            assertArrayEquals(snapshot.frames.single().pixels, visible.single().pixels.pixels)
            assertArrayEquals(snapshot.frames.single().pixels, requireNotNull(parsed.composite).pixels)
        }

    private companion object {
        val IDS = AtomicLong(9_700_000L)
    }
}
