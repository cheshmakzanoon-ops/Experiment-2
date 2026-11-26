package com.artflow.studio.data.export

import android.content.Context
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.export.ExportResult
import com.artflow.studio.core.export.PngCodec
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.CanvasDocument
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.domain.model.layer.Layer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.`when`
import java.io.File

/** Exercises the native export's actual snapshot, writer and publication caller. */
class NativePackageExportLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var storage: ProjectStorage
    private lateinit var exporter: ArtworkExporter
    private val options = ExportOptions(ExportFormat.ARTFLOW, fileName = "Native.artflow")
    private val pixels = PngCodec.encode(PixelBuffer.filled(64, 64, 0xFF336699.toInt()), dpi = 72)

    @Before
    fun setUp() {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporary.newFolder("files"))
        storage = spy(ProjectStorage(context))
        exporter = ArtworkExporter(context, storage)
    }

    @Test
    fun cancellationBeforeWriterStartsKeepsPreviousExportAndReleasesSnapshot() =
        runBlocking {
            val raster = storage.writeRaster(PROJECT, 1, 1, pixels)
            storage.saveDocument(PROJECT, document(raster))
            val originalManifest = storage.documentFile(PROJECT).readBytes()
            val previousBytes = byteArrayOf(9, 8, 7, 6)
            val previous = storage.saveExport(PROJECT, "Native.artflow", previousBytes)
            val exportDirectory = requireNotNull(previous.parentFile)
            lateinit var operation: Deferred<Result<ExportResult>>
            doAnswer {
                // saveExportFromTempFile is already inside its IO scope. Cancellation must still
                // reach the synchronous writer before it can replace an existing shared file.
                operation.cancel(CancellationException("Artwork was closed"))
                exportDirectory
            }.`when`(storage).exportsDir(PROJECT)
            operation = async(start = CoroutineStart.LAZY) { exporter.exportPackage(PROJECT, "Native", options) }
            operation.start()
            val failure = runCatching { operation.await() }.exceptionOrNull()
            operation.join()

            assertTrue(failure is CancellationException)
            assertArrayEquals(previousBytes, previous.readBytes())
            assertEquals(listOf(previous.name), exportDirectory.list()!!.toList())
            assertArrayEquals(originalManifest, storage.documentFile(PROJECT).readBytes())
            assertArrayEquals(pixels, storage.readRaster(PROJECT, raster))

            storage.saveDocument(PROJECT, document(null))
            assertEquals(1, storage.pruneRasters(PROJECT, emptySet()))
            assertFalse(storage.rasterExists(PROJECT, raster))
        }

    @Test
    fun successfulNativeExportReopensTheExactSavedDocumentAndPixels() =
        runBlocking {
            val raster = storage.writeRaster(PROJECT, 1, 1, pixels)
            storage.saveDocument(PROJECT, document(raster))
            val originalManifest = storage.documentFile(PROJECT).readBytes()

            val result = exporter.exportPackage(PROJECT, "Native", options).getOrThrow()

            assertEquals(ExportFormat.ARTFLOW, result.format)
            assertEquals("Native.artflow", result.fileName)
            assertEquals(64, result.width)
            assertEquals(64, result.height)
            ArtflowPackage.openFile(File(result.filePath)).use { contents ->
                assertEquals("Native", contents.name)
                assertArrayEquals(originalManifest, contents.readBytes(ArtflowPackage.DOCUMENT))
                assertArrayEquals(pixels, contents.readBytes(raster))
            }
            assertEquals(listOf(result.fileName), storage.exportsDir(PROJECT).list()!!.toList())
        }

    @Test
    fun oversizedMarkerCannotReplaceAnExistingExportWithAnUnreadablePackage() =
        runBlocking {
            val raster = storage.writeRaster(PROJECT, 1, 1, pixels)
            storage.saveDocument(PROJECT, document(raster))
            val originalManifest = storage.documentFile(PROJECT).readBytes()
            val previousBytes = byteArrayOf(9, 8, 7, 6)
            val previous = storage.saveExport(PROJECT, "Native.artflow", previousBytes)

            val result = exporter.exportPackage(PROJECT, "N".repeat(1024 * 1024), options)

            assertTrue("The reader refuses marker metadata over 1 MiB", result.isFailure)
            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("too large"),
            )
            assertArrayEquals(previousBytes, previous.readBytes())
            assertEquals(listOf(previous.name), storage.exportsDir(PROJECT).list()!!.toList())
            assertArrayEquals(originalManifest, storage.documentFile(PROJECT).readBytes())
            assertArrayEquals(pixels, storage.readRaster(PROJECT, raster))
        }

    private fun document(raster: String?) =
        CanvasDocument(
            width = 64,
            height = 64,
            dpi = 72,
            backgroundColor = -1,
            activeLayerId = 1,
            nextLayerId = 2,
            layers = listOf(Layer(id = 1, name = "Native", index = 0, rasterFile = raster)),
        )

    companion object {
        private const val PROJECT = 7L
    }
}
