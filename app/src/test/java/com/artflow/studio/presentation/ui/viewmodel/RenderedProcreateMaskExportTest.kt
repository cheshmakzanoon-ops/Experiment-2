package com.artflow.studio.presentation.ui.viewmodel

import android.content.Context
import com.artflow.studio.core.export.ExportArea
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.export.ExportResult
import com.artflow.studio.core.export.ProcreateReader
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.layer.LayerEffects
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
import java.util.zip.ZipInputStream

/** The editor's real snapshot and file adapter must preserve masks without changing visible artwork. */
@OptIn(ExperimentalCoroutinesApi::class)
class RenderedProcreateMaskExportTest {
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

    private suspend fun fixture(
        width: Int = 8,
        height: Int = 6,
        pixels: IntArray = IntArray(width * height) { RED },
        coverage: ByteArray = ByteArray(width * height) { 128.toByte() },
    ): Long {
        repository.createCanvas(width, height, 72)
        repository.setCanvasBackgroundColor(0)
        val id = repository.getActiveLayerId()
        repository.setLayerName(id, "Recoverable ink")
        assertTrue(repository.setLayerPixels(id, PixelBuffer(width, height, pixels), "Native mask fixture"))
        assertTrue(repository.addLayerMask(id, SelectionMask(width, height, coverage)))
        return id
    }

    private suspend fun export(
        options: ExportOptions = ExportOptions(format = ExportFormat.PROCREATE),
    ): Pair<ExportResult, ProcreateReader.Document> {
        val result = renderedExport(repository, exporter, 1, "Native masks", options).getOrThrow()
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(File(result.filePath).inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        val files =
            object : ProcreateReader.Files {
                override val names = entries.keys

                override fun read(name: String): ByteArray? = entries[name]
            }
        return result to ProcreateReader.open(files) { null }
    }

    @Test
    fun nativeEditorExportKeepsConcealedPaintAndTypedCoverageOnAnAmbiguous128SquareCanvas() =
        runTest {
            val coverage = ByteArray(128 * 128) { byteArrayOf(0, 128.toByte(), -1)[it % 3] }
            val id = fixture(128, 128, coverage = coverage)
            val preview = requireNotNull(repository.compositeBuffer())
            val (result, document) = export()
            assertNull(result.warning)
            val layer = document.layers().single()
            assertTrue(layer.visible)
            assertTrue(layer.maskEnabled)
            assertArrayEquals(IntArray(128 * 128) { RED }, document.pixels(layer).pixels)
            assertArrayEquals(coverage, requireNotNull(document.mask(layer)).coverage)
            assertArrayEquals(preview.pixels, requireNotNull(document.composite()).pixels)
            assertArrayEquals(IntArray(128 * 128) { RED }, requireNotNull(repository.layerPixels(id)).pixels)
        }

    @Test
    fun disabledResizedMaskRetainsCoverageWithoutChangingAppearanceOrAddingFallback() =
        runTest {
            val pixels = IntArray(48) { if (it % 2 == 0) RED else BLUE }
            val coverage = ByteArray(48) { if (it % 2 == 0) 0 else -1 }
            val id = fixture(pixels = pixels, coverage = coverage)
            repository.setLayerMaskEnabled(id, false)
            val (result, document) = export(ExportOptions(format = ExportFormat.PROCREATE, targetWidth = 4))
            assertNull(result.warning)
            val layer = document.layers().single()
            assertTrue(layer.visible)
            assertFalse(layer.maskEnabled)
            assertArrayEquals(ByteArray(12) { 128.toByte() }, requireNotNull(document.mask(layer)).coverage)
            assertArrayEquals(IntArray(12) { 0xFF800080.toInt() }, document.pixels(layer).pixels)
            assertArrayEquals(document.pixels(layer).pixels, requireNotNull(document.composite()).pixels)
        }

    @Test
    fun selectionCropClipsPaintAlphaOnlyOnceAndCropsIndependentCoverage() =
        runTest {
            fixture()
            repository.setSelection(
                SelectionMask(8, 6, ByteArray(48) { if (it % 8 in 2..5 && it / 8 in 1..4) 128.toByte() else 0 }),
            )
            val (result, document) = export(ExportOptions(format = ExportFormat.PROCREATE, area = ExportArea.SELECTION))
            assertNull(result.warning)
            assertEquals(4, document.width)
            assertEquals(4, document.height)
            val layer = document.layers().single()
            assertArrayEquals(IntArray(16) { 0x80FF0000.toInt() }, document.pixels(layer).pixels)
            assertArrayEquals(ByteArray(16) { 128.toByte() }, requireNotNull(document.mask(layer)).coverage)
            assertArrayEquals(IntArray(16) { 0x40FF0000 }, requireNotNull(document.composite()).pixels)
        }

    @Test
    fun resizingActiveMaskKeepsConcealedRedOutOfTheVisibleArtwork() =
        runTest {
            fixture(
                pixels = IntArray(48) { if (it % 2 == 0) RED else BLUE },
                coverage = ByteArray(48) { if (it % 2 == 0) 0 else -1 },
            )
            val (result, document) = export(ExportOptions(format = ExportFormat.PROCREATE, targetWidth = 4))
            assertTrue(requireNotNull(result.warning).contains("resized masks"))
            val source = document.layers().single { it.name == "Recoverable ink" }
            assertFalse(source.visible)
            assertTrue(source.maskEnabled)
            assertArrayEquals(IntArray(12) { 0xFF800080.toInt() }, document.pixels(source).pixels)
            assertArrayEquals(ByteArray(12) { 128.toByte() }, requireNotNull(document.mask(source)).coverage)
            val shown = document.layers().single { it.visible }
            assertEquals("Artwork (resized masks)", shown.name)
            assertArrayEquals(IntArray(12) { 0x800000FF.toInt() }, document.pixels(shown).pixels)
            assertArrayEquals(IntArray(12) { 0x800000FF.toInt() }, requireNotNull(document.composite()).pixels)
        }

    @Test
    fun unsupportedGroupMaskKeepsRenderedAppearanceAndHiddenEditableLeafMask() =
        runTest {
            val id = fixture()
            val groupId = requireNotNull(repository.groupLayers(listOf(id)))
            repository.setLayerName(groupId, "Masked folder")
            assertTrue(repository.addLayerMask(groupId, SelectionMask(8, 6, ByteArray(48) { 128.toByte() })))
            val preview = requireNotNull(repository.compositeBuffer())
            val (result, document) = export()
            assertTrue(requireNotNull(result.warning).contains("group blending or group masks"))
            val group = document.nodes.single { it is ProcreateReader.Group } as ProcreateReader.Group
            assertFalse(group.visible)
            val source = document.layers().single { it.name == "Recoverable ink" }
            assertFalse(source.visible)
            assertArrayEquals(IntArray(48) { RED }, document.pixels(source).pixels)
            assertArrayEquals(ByteArray(48) { 128.toByte() }, requireNotNull(document.mask(source)).coverage)
            val shown = document.layers().single { it.visible }
            assertArrayEquals(preview.pixels, document.pixels(shown).pixels)
            assertArrayEquals(preview.pixels, requireNotNull(document.composite()).pixels)
        }

    @Test
    fun effectsAfterMaskingRemainBakedWithoutApplyingTheMaskTwice() =
        runTest {
            val id = fixture(pixels = IntArray(48).also { it[19] = RED })
            assertTrue(repository.setLayerEffects(id, LayerEffects(outline = LayerEffects.Outline(color = RED, width = 1f))))
            val preview = requireNotNull(repository.compositeBuffer())
            assertTrue(preview.pixels.any { it ushr 24 != 0 })
            val (result, document) = export()
            assertTrue(requireNotNull(result.warning).contains("masks on those layers are not separately editable"))
            val layer = document.layers().single()
            assertNull(document.mask(layer))
            assertArrayEquals(preview.pixels, document.pixels(layer).pixels)
            assertArrayEquals(preview.pixels, requireNotNull(document.composite()).pixels)
        }

    @Test
    fun omittedDisabledGroupMaskIsDisclosedWithoutChangingVisiblePaintOrLeafCoverage() =
        runTest {
            val id = fixture()
            val groupId = requireNotNull(repository.groupLayers(listOf(id)))
            assertTrue(repository.addLayerMask(groupId, SelectionMask(8, 6, ByteArray(48))))
            assertTrue(repository.setLayerMaskEnabled(groupId, false))
            val preview = requireNotNull(repository.compositeBuffer())
            val (result, document) = export()
            assertEquals("Group masks are omitted from the editable hierarchy.", result.warning)
            val group = document.nodes.single() as ProcreateReader.Group
            assertTrue(group.visible)
            val source = document.layers().single()
            assertTrue(source.visible)
            assertTrue(source.maskEnabled)
            assertArrayEquals(IntArray(48) { RED }, document.pixels(source).pixels)
            assertArrayEquals(ByteArray(48) { 128.toByte() }, requireNotNull(document.mask(source)).coverage)
            assertArrayEquals(preview.pixels, requireNotNull(document.composite()).pixels)
        }

    private companion object {
        val RED = 0xFFFF0000.toInt()
        val BLUE = 0xFF0000FF.toInt()
    }
}
