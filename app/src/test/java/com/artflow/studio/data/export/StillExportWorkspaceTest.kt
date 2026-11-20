package com.artflow.studio.data.export

import android.content.Context
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.export.FitMode
import com.artflow.studio.core.export.PsdCodec
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.domain.model.layer.Layer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.io.File
import java.util.zip.ZipFile
import javax.imageio.ImageIO

class StillExportWorkspaceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun groupMaskScratchIsAdmittedAfterAllPreparedLayersAreRetained() =
        runBlocking {
            val width = 512
            val height = 16
            val count = 16
            val allowance = 16_940_000L
            val source = spy(PixelBuffer.filled(32, 1, -1))
            val group = Layer(100, "G", count, isGroup = true, hasInMemoryMask = true)
            val layers =
                List(count) { index ->
                    LayerRaster("L", source, Layer(index + 1L, "L", index, parentGroupId = group.id))
                }
            val mask = spy(PixelBuffer.filled(1, 16, -1))
            var reachedLateAllocation = false
            var measuredMinimum = 0L
            doAnswer { call ->
                val drawnWidth = call.getArgument<Int>(0)
                val drawnHeight = call.getArgument<Int>(1)
                assertEquals(512, drawnWidth)
                assertEquals(8192, drawnHeight)
                reachedLateAllocation = true
                // The completed composite and every prepared child remain referenced by the
                // PSD record builder when its group header is finally emitted.
                measuredMinimum = (count + 1L) * width * height * 4 + drawnWidth.toLong() * drawnHeight * 4
                error("Stopped before allocating group-mask scratch: $measuredMinimum")
            }.`when`(mask).scaled(anyInt(), anyInt())
            val storage = mock(ProjectStorage::class.java)
            val exporter = ArtworkExporter(mock(Context::class.java), storage, allowance)
            val result =
                exporter.exportStill(
                    7,
                    "Late group mask",
                    source,
                    layers,
                    ExportOptions(ExportFormat.PSD, targetWidth = width, fitMode = FitMode.FILL, psdUseRle = false),
                    groups = listOf(group),
                    layerMasks = mapOf(group.id to mask),
                )
            verifyNoInteractions(storage)
            verify(source, never()).scaled(anyInt(), anyInt())
            verify(source, never()).pixels
            verify(mask, never()).pixels
            assertFalse(
                "The admitted operation reaches a $measuredMinimum-byte live pixel minimum under a $allowance-byte allowance",
                reachedLateAllocation,
            )
            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("Reduce the output scale"),
            )
        }

    @Test
    fun fitsAndFillsKeepExpectedLayerPixelsWithTransparentLetterboxes() =
        runBlocking {
            val exporter = exporter()
            val composite = PixelBuffer.filled(4, 4, -1)
            val wide = PixelBuffer(4, 2, intArrayOf(RED, RED, RED, RED, BLUE, BLUE, BLUE, BLUE))
            val before = wide.pixels.copyOf()
            for (fit in listOf(FitMode.FIT, FitMode.FILL)) {
                val result =
                    exporter
                        .exportStill(
                            7,
                            "Fit",
                            composite,
                            listOf(LayerRaster("Wide", wide, Layer(1, "Wide", 0))),
                            ExportOptions(ExportFormat.LAYER_PNGS, fitMode = fit),
                        ).getOrThrow()
                ZipFile(result.filePath).use { zip ->
                    val entry = zip.entries().toList().single { it.name.endsWith("_Wide.png") }
                    val image = zip.getInputStream(entry).use(ImageIO::read)
                    assertEquals(4, image.width)
                    assertEquals(4, image.height)
                    val pixels = image.getRGB(0, 0, 4, 4, null, 0, 4)
                    val expectedRows = if (fit == FitMode.FIT) intArrayOf(0, RED, BLUE, 0) else intArrayOf(RED, RED, BLUE, BLUE)
                    // FILL uses bilinear interpolation, so only the outer rows are expected to
                    // remain exact; the independently placed FIT rows require no resampling.
                    if (fit == FitMode.FIT) {
                        assertArrayEquals(IntArray(16) { expectedRows[it / 4] }, pixels)
                    } else {
                        assertTrue(pixels.take(4).all { it == RED })
                        assertTrue(pixels.takeLast(4).all { it == BLUE })
                        assertTrue(pixels.all { it ushr 24 == 255 })
                    }
                }
                assertArrayEquals(before, wide.pixels)
            }
        }

    @Test
    fun hiddenGroupsDoNotSpendMemoryOnTheirMasksOrLayers() =
        runBlocking {
            val source = PixelBuffer.filled(16, 16, RED)
            val hiddenPixels = mock(PixelBuffer::class.java)
            `when`(hiddenPixels.width).thenReturn(8192)
            `when`(hiddenPixels.height).thenReturn(8192)
            val hiddenMask = mock(PixelBuffer::class.java)
            `when`(hiddenMask.width).thenReturn(8192)
            `when`(hiddenMask.height).thenReturn(8192)
            val group = Layer(100, "Hidden", 2, isVisible = false, isGroup = true, hasInMemoryMask = true)
            val layers =
                listOf(
                    LayerRaster("Visible", source, Layer(1, "Visible", 0)),
                    LayerRaster("Hidden", hiddenPixels, Layer(2, "Hidden", 1, parentGroupId = group.id)),
                )
            val result =
                exporter()
                    .exportStill(
                        7,
                        "Visibility",
                        source,
                        layers,
                        ExportOptions(ExportFormat.PSD),
                        groups = listOf(group),
                        layerMasks =
                            mapOf(
                                group.id to hiddenMask,
                            ),
                    ).getOrThrow()
            val psd = requireNotNull(PsdCodec.read(File(result.filePath).readBytes()))
            assertEquals(listOf("Visible"), psd.layers.map { it.name })
            assertArrayEquals(
                source.pixels,
                psd.layers
                    .single()
                    .pixels.pixels,
            )
            verify(hiddenPixels, never()).pixels
            verify(hiddenPixels, never()).scaled(anyInt(), anyInt())
            verify(hiddenMask, never()).pixels
            verify(hiddenMask, never()).scaled(anyInt(), anyInt())
            Unit
        }

    private fun exporter(): ArtworkExporter {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporary.newFolder())
        return ArtworkExporter(context, ProjectStorage(context), 24L * 1024 * 1024)
    }

    companion object {
        const val RED = -65536
        const val BLUE = -16776961
    }
}
