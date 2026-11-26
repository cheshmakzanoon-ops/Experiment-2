package com.artflow.studio.data.export

import android.content.Context
import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.color.ColorProfiles
import com.artflow.studio.core.export.ExportArea
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.export.FitMode
import com.artflow.studio.core.export.ProcreateReader
import com.artflow.studio.core.export.PsdCodec
import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.three.MaterialChannel
import com.artflow.studio.core.three.ObjExport
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.domain.model.layer.Layer
import com.artflow.studio.domain.repository.canvas.CanvasExportSnapshot
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.presentation.ui.viewmodel.renderedExport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile
import javax.imageio.ImageIO

class StillExportMemoryTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun oversizedStillFormatsRefuseBeforeScalingProfileConversionOrWriting() =
        runBlocking {
            val source = spy(painted(16, 16))
            val storage = mock(ProjectStorage::class.java)
            val exporter = ArtworkExporter(mock(Context::class.java), storage, 8 * MIB)
            for (format in STILL_FORMATS) {
                val result =
                    exporter.exportStill(
                        7,
                        "Large",
                        source,
                        emptyList(),
                        ExportOptions(format = format, targetWidth = 6000, colorProfile = ColorProfile.DISPLAY_P3),
                    )
                assertTrue(
                    "$format must report a useful refusal",
                    result
                        .exceptionOrNull()
                        ?.message
                        .orEmpty()
                        .contains("Reduce the output scale"),
                )
            }
            verify(source, never()).scaled(anyInt(), anyInt())
            verify(source, never()).pixels
            verifyNoInteractions(storage)
            assertArrayEquals(painted(16, 16).pixels, source.pixels)
        }

    @Test
    fun aSuppliedLimitCannotWidenLiveHeapAdmission() =
        runBlocking {
            val source = spy(painted(8, 8))
            val layers = List(64) { layer(it.toLong(), source) }
            val storage = mock(ProjectStorage::class.java)
            val exporter = ArtworkExporter(mock(Context::class.java), storage, Long.MAX_VALUE)
            val result = exporter.exportStill(7, "Too many pixels", source, layers, ExportOptions(ExportFormat.PSD, targetWidth = 6000))
            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("Reduce the output scale"),
            )
            verify(source, never()).scaled(anyInt(), anyInt())
            verify(source, never()).pixels
            verifyNoInteractions(storage)
        }

    @Test
    fun fillIntermediateIsAdmittedBeforeEvenTheCompositeIsPrepared() =
        runBlocking {
            val source = spy(painted(128, 128))
            val narrow = spy(painted(16, 512))
            val storage = mock(ProjectStorage::class.java)
            val exporter = ArtworkExporter(mock(Context::class.java), storage, 8 * MIB)
            val result =
                exporter.exportStill(
                    7,
                    "Fill",
                    source,
                    listOf(layer(1, narrow)),
                    ExportOptions(ExportFormat.PSD, scale = 2f, fitMode = FitMode.FILL),
                )
            // The 256 square output is safe; the 256 by 8192 fill intermediate exceeds the allowance.
            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("Reduce the output scale"),
            )
            verify(source, never()).scaled(anyInt(), anyInt())
            verify(narrow, never()).scaled(anyInt(), anyInt())
            verifyNoInteractions(storage)
        }

    @Test
    fun pdfOversamplingIsAdmittedBeforeBaseScalingOrPlatformRendering() =
        runBlocking {
            val source = spy(painted(256, 256))
            val storage = mock(ProjectStorage::class.java)
            val exporter = ArtworkExporter(mock(Context::class.java), storage, 16 * MIB)
            val options = ExportOptions(ExportFormat.PDF, scale = 2f)
            // The same base size with normal rasterization fits this allowance.
            StillExportMemory.requireFits(StillExportInput(source, emptyList(), options), 16 * MIB)
            val result = exporter.exportStill(7, "PDF", source, emptyList(), options.copy(pdfOversample = 3f))
            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("Reduce the output scale"),
            )
            verify(source, never()).scaled(anyInt(), anyInt())
            verify(source, never()).pixels
            verifyNoInteractions(storage)
        }

    @Test
    fun editableLayersAndMasksAreBudgetedTogetherBeforeAnyPixelCopies() =
        runBlocking {
            val source = spy(painted(16, 16))
            val coverage = spy(PixelBuffer.filled(16, 16, -1))
            val layers = List(12) { layer(it.toLong(), source) }
            val masks = layers.associate { it.layer.id to coverage }
            val storage = mock(ProjectStorage::class.java)
            val exporter = ArtworkExporter(mock(Context::class.java), storage, 8 * MIB)
            for (format in listOf(ExportFormat.PSD, ExportFormat.PROCREATE, ExportFormat.LAYER_PNGS)) {
                val result =
                    exporter.exportStill(
                        7,
                        "Layers",
                        source,
                        layers,
                        ExportOptions(format, targetWidth = 512),
                        layerMasks = masks,
                    )
                assertTrue(
                    "$format",
                    result
                        .exceptionOrNull()
                        ?.message
                        .orEmpty()
                        .contains("Reduce the output scale"),
                )
            }
            verify(source, never()).scaled(anyInt(), anyInt())
            verify(coverage, never()).scaled(anyInt(), anyInt())
            verify(source, never()).pixels
            verify(coverage, never()).pixels
            verifyNoInteractions(storage)
        }

    @Test
    fun featheredSelectionAppearanceIsBudgetedBeforeReadingOrEncodingPixels() =
        runBlocking<Unit> {
            val source = spy(painted(16, 16))
            val layers = listOf(layer(1, source), layer(2, source))
            for (format in listOf(ExportFormat.PSD, ExportFormat.PROCREATE)) {
                val options = ExportOptions(format)
                val input = StillExportInput(source, layers, options)
                // Use the smallest allowance admitting only the editable sources. Adding the
                // once-selected appearance must be refused before any encoder sees their pixels.
                var minimum = 0L
                var maximum = 64 * MIB
                while (minimum < maximum) {
                    val candidate = minimum + (maximum - minimum) / 2
                    if (runCatching { StillExportMemory.requireFits(input, candidate) }.isSuccess) {
                        maximum = candidate
                    } else {
                        minimum = candidate + 1
                    }
                }
                StillExportMemory.requireFits(input, minimum)
                val storage = mock(ProjectStorage::class.java)
                val exporter = ArtworkExporter(mock(Context::class.java), storage, minimum)
                val result = exporter.exportStill(7, "Selected", source, layers, options, renderedSelection = true)
                assertTrue(
                    "$format: $result",
                    result
                        .exceptionOrNull()
                        ?.message
                        .orEmpty()
                        .contains("Reduce the output scale"),
                )
                verifyNoInteractions(storage)
            }
            verify(source, never()).pixels
            verify(source, never()).scaled(anyInt(), anyInt())
        }

    @Test
    fun materialResolutionIsAdmittedBeforeModelIOOrTextureScaling() =
        runBlocking {
            val source = spy(painted(16, 16))
            val map = mock(PixelBuffer::class.java)
            `when`(map.width).thenReturn(2048)
            `when`(map.height).thenReturn(2048)
            val storage = mock(ProjectStorage::class.java)
            val exporter = ArtworkExporter(mock(Context::class.java), storage, 8 * MIB)
            val result =
                exporter.exportStill(
                    7,
                    "Model",
                    source,
                    emptyList(),
                    ExportOptions(ExportFormat.MODEL_OBJ, scale = 2f),
                    materialMaps = mapOf(MaterialChannel.ROUGHNESS to map),
                )
            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("Reduce the output scale"),
            )
            verify(source, never()).scaled(anyInt(), anyInt())
            verify(map, never()).pixels
            verifyNoInteractions(storage)
        }

    @Test
    fun selectedExportIsRejectedBeforeCompositeLayerOrMaskCrop() =
        runBlocking {
            val composite = spy(painted(16, 16))
            val paint = spy(painted(16, 16))
            val mask = spy(PixelBuffer.filled(16, 16, -1))
            val repository = mock(CanvasRepository::class.java)
            val snapshot =
                CanvasExportSnapshot(
                    listOf(composite),
                    listOf(100),
                    listOf(Layer(1, "Paint", 0) to paint),
                    SelectionMask(16, 16, ByteArray(256) { -1 }),
                    false,
                    layerMasks = mapOf(1L to mask),
                )
            `when`(repository.exportSnapshot(false, false, true, true, false, false)).thenReturn(snapshot)
            val storage = mock(ProjectStorage::class.java)
            val exporter = ArtworkExporter(mock(Context::class.java), storage, 8 * MIB)
            val failure =
                runCatching {
                    renderedExport(
                        repository,
                        exporter,
                        7,
                        "Selected",
                        ExportOptions(ExportFormat.PSD, area = ExportArea.SELECTION, targetWidth = 6000),
                    )
                }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("Reduce the output scale"))
            for (pixels in listOf(composite, paint, mask)) {
                verify(pixels, never()).crop(IntBounds(0, 0, 15, 15))
                verify(pixels, never()).scaled(anyInt(), anyInt())
                assertArrayEquals(if (pixels === mask) IntArray(256) { -1 } else painted(16, 16).pixels, pixels.pixels)
            }
            verifyNoInteractions(storage)
        }

    @Test
    fun boundedModelInputRefusesBeforeTextureScalingAndKeepsPriorExport() =
        runBlocking {
            val (storage, exporter) = exporter(8 * MIB)
            val model = storage.resolve(7, ProjectStorage.MODEL_NAME)
            RandomAccessFile(model, "rw").use { it.setLength(2 * MIB) }
            val prior = storage.saveExport(7, "existing.zip", byteArrayOf(1, 2, 3))
            val source = spy(painted(16, 16))
            val result =
                exporter.exportStill(
                    7,
                    "Model",
                    source,
                    emptyList(),
                    ExportOptions(ExportFormat.MODEL_OBJ, scale = 2f, fileName = "existing.zip"),
                )
            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("Reduce the output scale"),
            )
            verify(source, never()).scaled(anyInt(), anyInt())
            assertArrayEquals(byteArrayOf(1, 2, 3), prior.readBytes())
            assertEquals(2 * MIB, model.length())
            assertEquals(listOf("existing.zip"), storage.exportsDir(7).list()!!.toList())
        }

    @Test
    fun supportedPngTiffAndLayerZipKeepScaledPixelsAndSources() =
        runBlocking {
            val (_, exporter) = exporter(16 * MIB)
            val source = painted(8, 6)
            val before = source.pixels.copyOf()
            val expected = source.scaled(16, 12)
            for (format in listOf(ExportFormat.PNG, ExportFormat.TIFF, ExportFormat.LAYER_PNGS)) {
                val result =
                    exporter.exportStill(7, "Paint", source, listOf(layer(1, source)), ExportOptions(format, scale = 2f)).getOrThrow()
                assertEquals(16, result.width)
                assertEquals(12, result.height)
                if (format == ExportFormat.LAYER_PNGS) {
                    ZipFile(result.filePath).use { zip ->
                        val entries = zip.entries().toList()
                        assertEquals(2, entries.size)
                        entries.forEach { entry -> zip.getInputStream(entry).use { assertPixels(expected, ImageIO.read(it)) } }
                    }
                } else {
                    assertPixels(expected, ImageIO.read(File(result.filePath)))
                }
                assertArrayEquals(before, source.pixels)
            }
        }

    @Test
    fun supportedLayeredFilesKeepPixelsMasksAndSourceOwnership() =
        runBlocking {
            val (_, exporter) = exporter(16 * MIB)
            val source = painted(8, 6)
            val before = source.pixels.copyOf()
            val mask = PixelBuffer.filled(8, 6, -1).also { it.pixels[0] = 0x7fffffff }
            val maskBefore = mask.pixels.copyOf()
            val coverage = ByteArray(mask.size) { (mask.pixels[it] ushr 24).toByte() }
            for (rle in listOf(false, true)) {
                val psd =
                    exporter
                        .exportStill(
                            7,
                            "PSD",
                            source,
                            listOf(layer(1, source)),
                            ExportOptions(ExportFormat.PSD, psdUseRle = rle),
                            layerMasks = mapOf(1L to mask),
                        ).getOrThrow()
                val document = requireNotNull(PsdCodec.read(File(psd.filePath).readBytes()))
                assertArrayEquals(
                    before,
                    document.layers
                        .single()
                        .pixels.pixels,
                )
                assertArrayEquals(coverage, requireNotNull(document.layers.single().mask).coverage)
                assertArrayEquals(before, requireNotNull(document.composite).pixels)
            }
            val native =
                exporter
                    .exportStill(
                        7,
                        "Native",
                        source,
                        listOf(layer(1, source)),
                        ExportOptions(ExportFormat.PROCREATE),
                        layerMasks = mapOf(1L to mask),
                    ).getOrThrow()
            ZipFile(native.filePath).use { zip ->
                val files =
                    object : ProcreateReader.Files {
                        override val names =
                            zip
                                .entries()
                                .asSequence()
                                .map { it.name }
                                .toSet()

                        override fun read(name: String): ByteArray? =
                            zip.getEntry(name)?.let { zip.getInputStream(it).use { input -> input.readBytes() } }
                    }
                val document = ProcreateReader.open(files) { null }
                val nativeLayer = document.layers().single()
                assertArrayEquals(before, document.pixels(nativeLayer).pixels)
                assertArrayEquals(coverage, requireNotNull(document.mask(nativeLayer)).coverage)
            }
            assertArrayEquals(before, source.pixels)
            assertArrayEquals(maskBefore, mask.pixels)
        }

    @Test
    fun supportedModelExportKeepsOriginalObjAndResolvesEachMaterialMap() =
        runBlocking {
            val (storage, exporter) = exporter(16 * MIB)
            val model = "v 0 0 0\nv 1 0 0\nv 0 1 0\nvt 0 0\nvt 1 0\nvt 0 1\nf 1/1 2/2 3/3\n"
            storage.saveModel(7, model)
            val source = painted(8, 6)
            val map = PixelBuffer.filled(4, 3, 0x80404040.toInt())
            val original = map.pixels.copyOf()
            val result =
                exporter
                    .exportStill(
                        7,
                        "Model",
                        source,
                        emptyList(),
                        ExportOptions(ExportFormat.MODEL_OBJ),
                        materialMaps = mapOf(MaterialChannel.METALLIC to map, MaterialChannel.ROUGHNESS to map),
                    ).getOrThrow()
            ZipFile(result.filePath).use { zip ->
                assertEquals(ObjExport.withMaterial(model), zip.getInputStream(zip.getEntry(ObjExport.OBJ)).reader().use { it.readText() })
                zip.getInputStream(zip.getEntry(ObjExport.TEXTURE)).use { assertPixels(source, ImageIO.read(it)) }
                for (channel in listOf(MaterialChannel.METALLIC, MaterialChannel.ROUGHNESS)) {
                    val expected = MaterialChannel.resolved(map, channel).scaled(8, 6)
                    zip.getInputStream(zip.getEntry(ObjExport.mapName(channel))).use { assertPixels(expected, ImageIO.read(it)) }
                }
            }
            assertEquals(model, storage.loadModel(7))
            assertArrayEquals(original, map.pixels)
        }

    @Test
    fun supportedProfileConversionKeepsSourcePixelsAndOutputColor() =
        runBlocking {
            val (_, exporter) = exporter(16 * MIB)
            val source = painted(8, 6)
            val original = source.pixels.copyOf()
            val options = ExportOptions(ExportFormat.TIFF, scale = 2f, colorProfile = ColorProfile.DISPLAY_P3)
            assertFalse(options.colorProfile == options.outputProfile)
            val expected = ColorProfiles.convert(source, options.colorProfile, options.outputProfile).scaled(16, 12)
            val result = exporter.exportStill(7, "Profile", source, emptyList(), options).getOrThrow()
            assertPixels(expected, ImageIO.read(File(result.filePath)))
            assertArrayEquals(original, source.pixels)
        }

    private fun exporter(limit: Long): Pair<ProjectStorage, ArtworkExporter> {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporary.root)
        val storage = ProjectStorage(context)
        return storage to ArtworkExporter(context, storage, limit)
    }

    private fun layer(
        id: Long,
        source: PixelBuffer,
    ) = LayerRaster("Paint $id", source, Layer(id, "Paint $id", id.toInt()))

    private fun painted(
        width: Int,
        height: Int,
    ): PixelBuffer = PixelBuffer(width, height, IntArray(width * height) { 0xff000000.toInt() or ((it * 7919) and 0xffffff) })

    private fun assertPixels(
        expected: PixelBuffer,
        actual: java.awt.image.BufferedImage,
    ) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        assertArrayEquals(expected.pixels, actual.getRGB(0, 0, actual.width, actual.height, null, 0, actual.width))
    }

    companion object {
        private const val MIB = 1024L * 1024
        private val STILL_FORMATS =
            listOf(
                ExportFormat.PNG,
                ExportFormat.JPEG,
                ExportFormat.WEBP,
                ExportFormat.PDF,
                ExportFormat.PSD,
                ExportFormat.TIFF,
                ExportFormat.LAYER_PNGS,
                ExportFormat.PROCREATE,
                ExportFormat.MODEL_OBJ,
            )
    }
}
