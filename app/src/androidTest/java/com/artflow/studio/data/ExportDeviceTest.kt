package com.artflow.studio.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.TestEvidence
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.export.ExportResult
import com.artflow.studio.core.export.PdfPageSize
import com.artflow.studio.core.export.PsdCodec
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipFile
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ExportDeviceTest {
    private lateinit var context: Context
    private lateinit var storage: ProjectStorage
    private lateinit var exporter: ArtworkExporter
    private var projectId = 0L

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storage = ProjectStorage(context)
        exporter = ArtworkExporter(context, storage)
        projectId = System.nanoTime().coerceAtLeast(1)
    }

    @After
    fun cleanup() {
        storage.deleteProjectFiles(projectId)
    }

    @Test
    fun pngKeepsAlphaAndFlatteningActuallyRemovesIt() =
        runBlocking {
            val source = PixelBuffer(32, 16, IntArray(512) { 0x80123456.toInt() })
            val png = still(source, ExportFormat.PNG)
            val decoded = BitmapPixelBridge.fromEncodedBytes(File(png.filePath).readBytes())!!
            assertArrayEquals(source.pixels, decoded.pixels)
            val flattened =
                exporter
                    .exportStill(
                        projectId,
                        "Flat",
                        source,
                        emptyList(),
                        ExportOptions(flattenOntoBackground = true, backgroundColor = Color.WHITE),
                    ).getOrThrow()
            val opaque = BitmapPixelBridge.fromEncodedBytes(File(flattened.filePath).readBytes())!!
            assertTrue(opaque.pixels.all { it ushr 24 == 255 })
            evidence(png, "alpha.png")
        }

    @Test
    fun exportedFilesCanBeReadAndCopiedButEditableDocumentsCannotBeShared() =
        runBlocking {
            val image = still(PixelBuffer.filled(32, 16, Color.RED), ExportFormat.PNG)
            val chooser = exporter.shareIntent(image)

            @Suppress("DEPRECATION")
            val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals("image/png", send.type)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val uri = send.clipData!!.getItemAt(0).uri
            val shared = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            assertArrayEquals(File(image.filePath).readBytes(), shared)
            val target = File(storage.exportsDir(projectId), "chosen.png").apply { writeBytes(byteArrayOf()) }
            val targetUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
            exporter.writeToDocument(image.filePath, targetUri)
            assertArrayEquals(shared, target.readBytes())
            storage.documentFile(projectId).writeText("private artwork")
            assertTrue(runCatching { exporter.shareIntent(image.copy(filePath = storage.documentFile(projectId).absolutePath)) }.isFailure)
        }

    @Test
    fun pdfUsesMultiplePagesAndPreservesAspectRatio() =
        runBlocking {
            val frames = listOf(PixelBuffer.filled(64, 32, Color.RED), PixelBuffer.filled(64, 32, Color.BLUE))
            val result =
                exporter
                    .exportAnimation(
                        projectId,
                        "Pages",
                        frames,
                        listOf(100, 100),
                        ExportOptions(format = ExportFormat.PDF, pdfPageSize = PdfPageSize.A4_PORTRAIT),
                    ).getOrThrow()
            PdfRenderer(ParcelFileDescriptor.open(File(result.filePath), ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
                assertEquals(2, pdf.pageCount)
                pdf.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        assertEquals(Color.RED, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
                        assertEquals(Color.WHITE, bitmap.getPixel(bitmap.width / 2, 10))
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
            evidence(result, "pages.pdf")
        }

    @Test
    fun sequenceContainsEveryFrameWithItsActualPixels() =
        runBlocking {
            val frames = listOf(PixelBuffer.filled(32, 16, Color.RED), PixelBuffer.filled(32, 16, Color.BLUE))
            val result =
                exporter
                    .exportAnimation(
                        projectId,
                        "Frames",
                        frames,
                        listOf(80, 270),
                        ExportOptions(format = ExportFormat.FRAME_SEQUENCE),
                    ).getOrThrow()
            ZipFile(result.filePath).use { zip ->
                val entries = zip.entries().toList().sortedBy { it.name }
                assertEquals(2, entries.size)
                entries.forEachIndexed { index, entry ->
                    val decoded = BitmapPixelBridge.fromEncodedBytes(zip.getInputStream(entry).use { it.readBytes() })!!
                    assertArrayEquals(frames[index].pixels, decoded.pixels)
                }
            }
            evidence(result, "sequence.zip")
        }

    @Test
    fun mp4ContainsAllFramesCorrectTimingAndDecodedColours() =
        runBlocking {
            val frames = listOf(Color.RED, Color.GREEN, Color.BLUE).map { PixelBuffer.filled(65, 33, it) }
            val result =
                exporter
                    .exportAnimation(
                        projectId,
                        "Video",
                        frames,
                        listOf(80, 240, 160),
                        ExportOptions(format = ExportFormat.MP4),
                    ).getOrThrow()
            evidence(result, "timing-diagnostic.mp4")
            assertEquals(66, result.width)
            assertEquals(34, result.height)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(result.filePath)
                assertEquals(1, extractor.trackCount)
                val format = extractor.getTrackFormat(0)
                assertEquals("video/avc", format.getString(MediaFormat.KEY_MIME))
                assertEquals(66, format.getInteger(MediaFormat.KEY_WIDTH))
                assertEquals(34, format.getInteger(MediaFormat.KEY_HEIGHT))
                val duration = format.getLong(MediaFormat.KEY_DURATION)
                assertTrue("Expected 480000 microseconds, got $duration", abs(duration - 480_000L) <= 4000L)
                extractor.selectTrack(0)
                val times = mutableListOf<Long>()
                val bytes = ByteBuffer.allocate(512 * 1024)
                while (extractor.readSampleData(bytes, 0) >= 0) {
                    times += extractor.sampleTime
                    extractor.advance()
                    bytes.clear()
                }
                assertEquals(3, times.size)
                listOf(0L, 80_000L, 320_000L).forEachIndexed { i, expected -> assertTrue(abs(times[i] - expected) <= 1000L) }
            } finally {
                extractor.release()
            }
            val decoder = MediaMetadataRetriever()
            try {
                decoder.setDataSource(result.filePath)
                val bitmap = requireNotNull(decoder.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST))
                try {
                    val pixel = bitmap.getPixel(20, 15)
                    assertTrue(Color.red(pixel) > 200)
                    assertTrue(Color.green(pixel) < 45)
                    assertTrue(Color.blue(pixel) < 45)
                } finally {
                    bitmap.recycle()
                }
            } finally {
                decoder.release()
            }
            evidence(result, "video.mp4")
        }

    @Test
    fun invalidVideoSizeReturnsFailureWithoutPublishingAFile() =
        runBlocking {
            val result =
                exporter.exportAnimation(
                    projectId,
                    "Invalid",
                    listOf(PixelBuffer(2, 2)),
                    listOf(100),
                    ExportOptions(format = ExportFormat.MP4),
                )
            assertTrue(result.isFailure)
            assertTrue(storage.listExports(projectId).isEmpty())
        }

    @Test
    fun galleryPublishesPngAsImageAndMp4AsVideo() =
        runBlocking {
            assumeTrue(Build.VERSION.SDK_INT >= 29)
            val frame = PixelBuffer.filled(64, 32, Color.RED)
            val png = still(frame, ExportFormat.PNG)
            val video =
                exporter
                    .exportAnimation(
                        projectId,
                        "Gallery video",
                        listOf(frame),
                        listOf(250),
                        ExportOptions(format = ExportFormat.MP4),
                    ).getOrThrow()
            for (result in listOf(png, video)) {
                val uri = Uri.parse(requireNotNull(exporter.publishToGallery(result, result.fileName)))
                try {
                    assertEquals(result.format.mimeType, context.contentResolver.getType(uri))
                    assertTrue(uri.path!!.contains(if (result.format == ExportFormat.MP4) "video" else "images"))
                    assertArrayEquals(
                        File(result.filePath).readBytes(),
                        context.contentResolver.openInputStream(uri)!!.use { it.readBytes() },
                    )
                } finally {
                    context.contentResolver.delete(uri, null, null)
                }
            }
        }

    @Test
    fun jpegWebpGifAndPsdAreRealDecodableFiles() =
        runBlocking {
            val image = PixelBuffer.filled(32, 16, Color.BLUE)
            for (format in listOf(ExportFormat.JPEG, ExportFormat.WEBP)) {
                val result = still(image, format)
                val bitmap = BitmapPixelBridge.fromEncodedBytes(File(result.filePath).readBytes())!!
                assertEquals(32, bitmap.width)
                assertEquals(16, bitmap.height)
                evidence(result, "image.${format.extension}")
            }
            val gif =
                exporter
                    .exportAnimation(
                        projectId,
                        "GIF",
                        listOf(image, PixelBuffer.filled(32, 16, Color.RED)),
                        listOf(80, 240),
                        ExportOptions(format = ExportFormat.GIF),
                    ).getOrThrow()
            assertEquals("GIF89a", File(gif.filePath).inputStream().use { String(it.readBytes().copyOfRange(0, 6), Charsets.US_ASCII) })
            val psd = still(image, ExportFormat.PSD)
            assertArrayEquals(image.pixels, PsdCodec.read(File(psd.filePath).readBytes())!!.composite!!.pixels)
            evidence(gif, "animation.gif")
            evidence(psd, "image.psd")
        }

    @Test
    fun snapshotContainsMaskedPixelsBeforeTheFirstSaveAndIsIndependentOfLaterEdits() =
        runBlocking(Dispatchers.Main) {
            val canvas = CanvasRepositoryImpl(storage)
            try {
                canvas.loadOrCreate(projectId, 32, 32, 72)
                canvas.setLayerPixels(canvas.getActiveLayerId(), PixelBuffer.filled(32, 32, Color.RED), "Fixture")
                canvas.addLayerMask(canvas.getActiveLayerId())
                canvas.paintLayerMask(canvas.getActiveLayerId(), 16f, 16f, 12f, reveal = false)
                assertTrue(canvas.getActiveLayer()!!.hasMask())
                val exported = canvas.exportSnapshot(allFrames = false, includeHidden = false, includeLayers = true)
                assertTrue(exported.frames.single().getSafe(16, 16) ushr 24 < 20)
                assertTrue(
                    exported.layers
                        .single()
                        .second
                        .getSafe(16, 16) ushr 24 < 20,
                )
                val before =
                    exported.frames
                        .single()
                        .pixels
                        .copyOf()
                canvas.setLayerPixels(canvas.getActiveLayerId(), PixelBuffer.filled(32, 32, Color.BLUE), "Later edit")
                assertArrayEquals(before, exported.frames.single().pixels)
            } finally {
                canvas.dispose()
            }
        }

    private suspend fun still(
        pixels: PixelBuffer,
        format: ExportFormat,
    ): ExportResult =
        exporter.exportStill(projectId, format.name, pixels, emptyList(), ExportOptions(format = format, dpi = 300)).getOrThrow()

    private fun evidence(
        result: ExportResult,
        name: String,
    ) {
        val directory = TestEvidence.directory()
        File(result.filePath).copyTo(File(directory, name), overwrite = true)
    }
}
