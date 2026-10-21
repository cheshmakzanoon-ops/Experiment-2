package com.artflow.studio.presentation.ui.viewmodel

import android.content.Context
import com.artflow.studio.core.export.ExportArea
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.export.ExportResult
import com.artflow.studio.core.export.FitMode
import com.artflow.studio.core.export.LayerMaskExport
import com.artflow.studio.core.export.PsdCodec
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

/** Exercises the editor's snapshot, layered exporter, on-disk PSD and decoder together. */
@OptIn(ExperimentalCoroutinesApi::class)
class RenderedPsdExportTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private lateinit var repository: CanvasRepositoryImpl
    private lateinit var exporter: ArtworkExporter

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporary.root)
        val storage = ProjectStorage(context)
        repository = CanvasRepositoryImpl(storage)
        exporter = ArtworkExporter(context, storage)
    }

    @After
    fun tearDown() {
        repository.dispose()
        Dispatchers.resetMain()
    }

    private fun pixels(
        position: Int,
        color: Int,
    ): PixelBuffer = PixelBuffer(8, 6).also { it.pixels[position] = color }

    private suspend fun frame(
        name: String,
        pixels: PixelBuffer,
        first: Boolean = false,
    ): Long {
        if (first) repository.createCanvas(8, 6, 72) else repository.addFrame(false)
        val layerId = repository.getActiveLayerId()
        repository.setLayerName(layerId, name)
        assertTrue(repository.setLayerPixels(layerId, pixels, "Draw PSD fixture"))
        return layerId
    }

    private suspend fun exportResult(options: ExportOptions): ExportResult {
        val result = renderedExport(repository, exporter, 1L, "Pinned layers", options).getOrThrow()
        assertEquals(1, result.frameCount)
        return result
    }

    private suspend fun export(options: ExportOptions): PsdCodec.PsdDocument = read(exportResult(options))

    private fun read(result: ExportResult): PsdCodec.PsdDocument = requireNotNull(PsdCodec.read(File(result.filePath).readBytes()))

    @Test
    fun currentFramePsdKeepsEveryPinFolderAndIndependentMaskInStackOrder() =
        runTest {
            val backgroundInk = pixels(0, 0xCCFF0000.toInt()).also { it.pixels[5] = 0xCCFF0000.toInt() }
            val background = frame("Background A", backgroundInk, first = true)
            val backgroundCoverage =
                ByteArray(48) { -1 }.also {
                    it[0] = 128.toByte()
                    it[5] = 0
                }
            assertTrue(repository.addLayerMask(background, SelectionMask(8, 6, backgroundCoverage)))
            val group = requireNotNull(repository.groupLayers(listOf(background)))
            repository.setLayerName(group, "Background folder")
            val folderCoverage = ByteArray(48) { -1 }.also { it[0] = 128.toByte() }
            assertTrue(repository.addLayerMask(group, SelectionMask(8, 6, folderCoverage)))
            frame("Background B", pixels(1, 0xFF00FF00.toInt()))
            frame("Drawing", pixels(2, 0xFF0000FF.toInt()))
            val foregroundInk = pixels(3, 0xCCFFFF00.toInt())
            val foreground = frame("Foreground A", foregroundInk)
            val disabledCoverage = ByteArray(48) { -1 }.also { it[3] = 0 }
            assertTrue(repository.addLayerMask(foreground, SelectionMask(8, 6, disabledCoverage)))
            repository.setLayerMaskEnabled(foreground, false)
            val top = frame("Foreground B", pixels(4, -1))
            val topCoverage = ByteArray(48) { -1 }.also { it[4] = 128.toByte() }
            assertTrue(repository.addLayerMask(top, SelectionMask(8, 6, topCoverage)))
            val ids = repository.frames().map { it.id }
            repository.updateAnimationSettings(
                repository.timeline.value.settings.copy(
                    backgroundFrameIds = setOf(ids[0], ids[1]),
                    foregroundFrameIds = setOf(ids[3], ids[4]),
                ),
            )
            repository.selectFrame(2)
            val before = repository.timeline.value
            val preview = requireNotNull(repository.compositeFrame(2, transparentBackground = true))
            for (rle in listOf(false, true)) {
                val document = export(ExportOptions(format = ExportFormat.PSD, area = ExportArea.CURRENT_FRAME, psdUseRle = rle))
                assertEquals(before, repository.timeline.value)
                assertEquals(
                    listOf(
                        PsdCodec.GROUP_END_NAME,
                        "Background A",
                        "Background folder",
                        "Background B",
                        "Drawing",
                        "Foreground A",
                        "Foreground B",
                    ),
                    document.layers.map { it.name },
                )
                val layers = document.layers.associateBy { it.name }
                val backgroundLayer = layers.getValue("Background A")
                assertArrayEquals(backgroundInk.pixels, backgroundLayer.pixels.pixels)
                assertArrayEquals(backgroundCoverage, requireNotNull(backgroundLayer.mask).coverage)
                val folder = layers.getValue("Background folder")
                assertTrue(folder.section.isGroupHeader)
                assertArrayEquals(folderCoverage, requireNotNull(folder.mask).coverage)
                val foregroundLayer = layers.getValue("Foreground A")
                assertArrayEquals(foregroundInk.pixels, foregroundLayer.pixels.pixels)
                val disabled = requireNotNull(foregroundLayer.mask)
                assertFalse(disabled.isEnabled)
                assertArrayEquals(disabledCoverage, disabled.coverage)
                val topLayer = layers.getValue("Foreground B")
                assertEquals(255, topLayer.pixels.pixels[4] ushr 24)
                val topMask = requireNotNull(topLayer.mask)
                assertTrue(topMask.isEnabled)
                assertArrayEquals(topCoverage, topMask.coverage)
                val composite = requireNotNull(document.composite)
                assertArrayEquals(preview.pixels, composite.pixels)
                assertEquals(51, composite.pixels[0] ushr 24)
                assertEquals(0xFF00FF00.toInt(), composite.pixels[1])
                assertEquals(0xFF0000FF.toInt(), composite.pixels[2])
                assertEquals(204, composite.pixels[3] ushr 24)
                assertEquals(128, composite.pixels[4] ushr 24)
                assertEquals(0, composite.pixels[5] ushr 24)
            }
        }

    @Test
    fun hiddenPinIsOmittedUntilSelectedAndEditableLayersAlwaysMatchThePreview() =
        runTest {
            frame("Background", pixels(0, 0xFFFF0000.toInt()), first = true)
            frame("Drawing", pixels(1, 0xFF00FF00.toInt()))
            frame("Hidden foreground", pixels(2, 0xFF0000FF.toInt()))
            frame("Foreground", pixels(3, -1))
            val ids = repository.frames().map { it.id }
            repository.updateAnimationSettings(
                repository.timeline.value.settings.copy(
                    backgroundFrameIds = setOf(ids[0]),
                    foregroundFrameIds = setOf(ids[2], ids[3]),
                    hiddenFrameIds = setOf(ids[2]),
                ),
            )
            repository.selectFrame(1)
            val options = ExportOptions(format = ExportFormat.PSD, includeHiddenLayers = true)
            val drawing = export(options)
            assertEquals(listOf("Background", "Drawing", "Foreground"), drawing.layers.map { it.name })
            assertEquals(0, requireNotNull(drawing.composite).pixels[2] ushr 24)
            repository.selectFrame(2)
            val selectedPin = export(options)
            assertEquals(listOf("Background", "Hidden foreground", "Foreground"), selectedPin.layers.map { it.name })
            val selectedComposite = requireNotNull(selectedPin.composite)
            assertEquals(0xFF0000FF.toInt(), selectedComposite.pixels[2])
            assertEquals(0, selectedComposite.pixels[1] ushr 24)
        }

    private suspend fun stripedLayer(transparentAlternate: Boolean = false): Long {
        val ink =
            PixelBuffer(
                8,
                6,
                IntArray(48) {
                    if (it % 2 == 0) {
                        0xFFFF0000.toInt()
                    } else if (transparentAlternate) {
                        0
                    } else {
                        0xFF0000FF.toInt()
                    }
                },
            )
        return frame("Recoverable ink", ink, first = true)
    }

    private fun stripedMask() = SelectionMask(8, 6, ByteArray(48) { if (it % 2 == 0) 0 else -1 })

    @Test
    fun resizedEnabledMaskCannotRevealPreviouslyInvisibleInk() =
        runTest {
            val id = stripedLayer(transparentAlternate = true)
            assertTrue(repository.addLayerMask(id, stripedMask()))
            val result = exportResult(ExportOptions(format = ExportFormat.PSD, targetWidth = 4))
            val document = read(result)
            assertEquals(4, document.width)
            assertEquals(3, document.height)
            assertTrue(requireNotNull(result.warning).contains("Resized masks"))
            val editable = document.layers.single { it.name == "Recoverable ink" }
            assertFalse(editable.isVisible)
            assertArrayEquals(IntArray(12) { 0x80FF0000.toInt() }, editable.pixels.pixels)
            val mask = requireNotNull(editable.mask)
            assertTrue(mask.isEnabled)
            assertArrayEquals(ByteArray(12) { 128.toByte() }, mask.coverage)
            // Showing the separately resized originals would reveal alpha 64 in a wholly empty image.
            val shown = document.layers.single { it.isVisible }
            assertEquals("Artwork (resized masks)", shown.name)
            assertTrue(shown.pixels.pixels.all { it ushr 24 == 0 })
            assertTrue(requireNotNull(document.composite).pixels.all { it ushr 24 == 0 })
        }

    @Test
    fun resizedFolderMaskKeepsHiddenRedOutOfTheVisibleBlueArtwork() =
        runTest {
            val id = stripedLayer()
            val folder = requireNotNull(repository.groupLayers(listOf(id)))
            repository.setLayerName(folder, "Masked folder")
            assertTrue(repository.addLayerMask(folder, stripedMask()))
            val result = exportResult(ExportOptions(format = ExportFormat.PSD, scale = 0.5f))
            val document = read(result)
            val editable = document.layers.single { it.name == "Recoverable ink" }
            assertFalse(editable.isVisible)
            assertArrayEquals(IntArray(12) { 0xFF800080.toInt() }, editable.pixels.pixels)
            val header = document.layers.single { it.section.isGroupHeader }
            assertFalse(header.isVisible)
            assertArrayEquals(ByteArray(12) { 128.toByte() }, requireNotNull(header.mask).coverage)
            val shown = document.layers.single { it.section == PsdCodec.Section.NONE && it.isVisible }
            assertEquals("Artwork (resized masks)", shown.name)
            assertArrayEquals(IntArray(12) { 0x800000FF.toInt() }, shown.pixels.pixels)
            assertArrayEquals(IntArray(12) { 0x800000FF.toInt() }, requireNotNull(document.composite).pixels)
            assertTrue(requireNotNull(result.warning).contains("editable layers and masks are included hidden"))
        }

    @Test
    fun resizedDisabledMaskStaysEditableWithoutAddingARenderedLayer() =
        runTest {
            val id = stripedLayer()
            assertTrue(repository.addLayerMask(id, stripedMask()))
            repository.setLayerMaskEnabled(id, false)
            val result = exportResult(ExportOptions(format = ExportFormat.PSD, targetWidth = 4))
            val document = read(result)
            assertNull(result.warning)
            val editable = document.layers.single()
            assertEquals("Recoverable ink", editable.name)
            assertTrue(editable.isVisible)
            assertArrayEquals(IntArray(12) { 0xFF800080.toInt() }, editable.pixels.pixels)
            val mask = requireNotNull(editable.mask)
            assertFalse(mask.isEnabled)
            assertArrayEquals(ByteArray(12) { 128.toByte() }, mask.coverage)
            assertArrayEquals(editable.pixels.pixels, requireNotNull(document.composite).pixels)
        }

    @Test
    fun integerMaskPlacementOrCroppingDoesNotNeedAResampledAppearanceLayer() {
        val coverage = PixelBuffer(2, 1)
        assertFalse(LayerMaskExport.requiresResampling(coverage, 2, 1, FitMode.STRETCH))
        assertFalse(LayerMaskExport.requiresResampling(coverage, 2, 3, FitMode.FIT))
        assertFalse(LayerMaskExport.requiresResampling(coverage, 1, 1, FitMode.FILL))
        assertTrue(LayerMaskExport.requiresResampling(coverage, 1, 1, FitMode.STRETCH))
        assertTrue(LayerMaskExport.requiresResampling(coverage, 4, 3, FitMode.FIT))
    }
}
