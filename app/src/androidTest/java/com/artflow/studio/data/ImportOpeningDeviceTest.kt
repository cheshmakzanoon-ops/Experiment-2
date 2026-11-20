package com.artflow.studio.data

import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.TestEvidence
import com.artflow.studio.core.export.ProcreateWriter
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.data.export.ProcreateImport
import com.artflow.studio.data.local.StorageFileTree
import com.artflow.studio.data.renderer.BitmapPixelBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Exercises Android ZIP and bitmap decoding with small document and brush fixtures at admission. */
@RunWith(AndroidJUnit4::class)
class ImportOpeningDeviceTest {
    private lateinit var directory: File
    private lateinit var staging: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        directory = Files.createTempDirectory(context.cacheDir.toPath(), "procreate-opening-").toFile()
        staging = File(directory, "staged").apply { check(mkdir()) }
    }

    @After
    fun tearDown() = StorageFileTree.delete(directory)

    @Test
    fun admittedAndroidThumbnailCalibratesAndRetainsPixelsUntilTheOwnerCloses() {
        val expected = pixels()
        val source = archive(expected)
        val opened = ProcreateImport.open(source.inputStream(), staging, BUDGET)
        try {
            val document = opened.document
            assertEquals(WIDTH, document.width)
            assertEquals(HEIGHT, document.height)
            assertEquals(0.0, document.calibrationError, 0.01)
            assertArrayEquals(expected.pixels, document.pixels(document.layers().single()).pixels)
            val composite = requireNotNull(document.composite())
            assertArrayEquals(expected.pixels, composite.pixels)
            assertEquals(1, stagedFiles().size)
            File(TestEvidence.directory(), "procreate-opening-source.procreate").writeBytes(source)
            File(TestEvidence.directory(), "procreate-opening-composite.png").writeBytes(BitmapPixelBridge.toPngBytes(composite))
        } finally {
            opened.close()
            opened.close()
        }
        assertNoStagedFiles()
    }

    @Test
    fun oversizedValidThumbnailIsRefusedWithoutDeletingAnExistingLiveImport() {
        val expected = pixels()
        val thumbnail = BitmapPixelBridge.toPngBytes(PixelBuffer.filled(512, 512, 0xFF2765A1.toInt()))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(thumbnail, 0, thumbnail.size, bounds)
        assertEquals(512, bounds.outWidth)
        assertEquals(512, bounds.outHeight)
        val rejected = archive(expected, thumbnail)
        ProcreateImport.open(archive(expected).inputStream(), staging, BUDGET).use { live ->
            val existing = stagedFiles().single()
            val existingBytes = existing.readBytes()
            val failure =
                runCatching {
                    ProcreateImport.open(rejected.inputStream(), staging, BUDGET).use { error("Oversized thumbnail was admitted") }
                }.exceptionOrNull()
            assertTrue(failure.toString(), failure is IllegalArgumentException)
            assertTrue(failure.toString(), failure?.message.orEmpty().contains("more memory"))
            assertEquals(listOf(existing), stagedFiles())
            assertArrayEquals(existingBytes, existing.readBytes())
            assertArrayEquals(expected.pixels, requireNotNull(live.document.composite()).pixels)
        }
        assertNoStagedFiles()
    }

    @Test
    fun oversizedAndroidZipDirectoryIsRefusedBeforeDocumentReadingAndLeavesNoStagedFile() {
        val source = archive(pixels(), comments = true)
        val fixture = File(directory, "large-directory.procreate").apply { writeBytes(source) }
        // Android accepts the ZIP structure; application admission must reject its directory cost.
        ZipFile(fixture).use { zip ->
            assertEquals(4, zip.size())
            assertTrue(zip.entries().asSequence().all { it.comment?.length == COMMENT_LENGTH })
        }
        val failure =
            runCatching {
                ProcreateImport.open(source.inputStream(), staging, BUDGET).use { error("Oversized directory was admitted") }
            }.exceptionOrNull()
        assertTrue(failure.toString(), failure is IllegalArgumentException)
        assertTrue(failure.toString(), failure?.message.orEmpty().contains("archive index too large"))
        assertNoStagedFiles()
    }

    @Test
    fun cancellationAfterAndroidDecodeClosesTheProviderAndUnclaimedStagedArchive() =
        runBlocking {
            withTimeout(30_000) {
                val expected = pixels()
                val closed = AtomicBoolean()
                val input =
                    object : ByteArrayInputStream(archive(expected)) {
                        override fun close() {
                            closed.set(true)
                            super.close()
                        }
                    }
                val entered = CompletableDeferred<Unit>()
                val import =
                    launch(Dispatchers.Default) {
                        ProcreateImport.useOpened(staging, { input }) { opened, _ ->
                            assertArrayEquals(expected.pixels, requireNotNull(opened.document.composite()).pixels)
                            assertTrue(opened.document.calibrationError <= 0.01)
                            entered.complete(Unit)
                            awaitCancellation()
                        }
                    }
                entered.await()
                assertTrue(closed.get())
                assertEquals(1, stagedFiles().size)
                assertFalse(import.isCompleted)
                import.cancelAndJoin()
                assertTrue(import.isCancelled)
                assertNoStagedFiles()
            }
        }

    @Test
    fun brushBitmapDecoderKeepsExactPixelsAndRejectsOversizedImagesWithoutPublishingATile() {
        val expected =
            PixelBuffer(
                3,
                2,
                intArrayOf(
                    0xFF123456.toInt(),
                    0xFF789ABC.toInt(),
                    0xFF254769.toInt(),
                    0xFFAB8253.toInt(),
                    0xFF3276AD.toInt(),
                    0xFF807A24.toInt(),
                ),
            )
        val cached = CustomGrains.ids().associateWith(CustomGrains::get)
        val decoded = requireNotNull(BitmapPixelBridge.decodeBrushImage(BitmapPixelBridge.toPngBytes(expected), BRUSH_BUDGET))
        assertEquals(expected.width, decoded.width)
        assertEquals(expected.height, decoded.height)
        assertArrayEquals(expected.pixels, decoded.pixels)
        val encoded = BitmapPixelBridge.toPngBytes(PixelBuffer.filled(512, 512, 0xFF2765A1.toInt()))
        var published: PixelBuffer? = null
        val failure = runCatching { published = BitmapPixelBridge.decodeBrushImage(encoded, BRUSH_BUDGET) }.exceptionOrNull()
        assertTrue(failure.toString(), failure is IllegalArgumentException)
        assertTrue(failure.toString(), failure?.message.orEmpty().contains("too much memory"))
        assertNull(published)
        assertEquals(cached, CustomGrains.ids().associateWith(CustomGrains::get))
        assertNoStagedFiles()
    }

    private fun archive(
        pixels: PixelBuffer,
        thumbnail: ByteArray = BitmapPixelBridge.toPngBytes(pixels),
        comments: Boolean = false,
    ): ByteArray {
        val written = ProcreateWriter.write(WIDTH, HEIGHT, listOf(ProcreateWriter.Layer("Ink", pixels)), pixels)
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { destination ->
            ZipInputStream(written.inputStream()).use { original ->
                var entry = original.nextEntry
                while (entry != null) {
                    destination.putNextEntry(zipEntry(entry.name, comments))
                    original.copyTo(destination)
                    destination.closeEntry()
                    entry = original.nextEntry
                }
            }
            destination.putNextEntry(zipEntry("QuickLook/Thumbnail.png", comments))
            destination.write(thumbnail)
            destination.closeEntry()
        }
        return output.toByteArray()
    }

    private fun zipEntry(
        name: String,
        comments: Boolean,
    ): ZipEntry = ZipEntry(name).apply { if (comments) comment = "x".repeat(COMMENT_LENGTH) }

    private fun pixels(): PixelBuffer =
        PixelBuffer(
            WIDTH,
            HEIGHT,
            IntArray(WIDTH * HEIGHT) { index ->
                val x = index % WIDTH
                val y = index / WIDTH
                0xFF000000.toInt() or ((17 + x * 13) shl 16) or ((23 + y * 17) shl 8) or (11 + x * 3 + y * 5)
            },
        )

    private fun stagedFiles(): List<File> = requireNotNull(staging.listFiles()) { "Cannot inspect staged imports" }.sortedBy(File::getName)

    private fun assertNoStagedFiles() = assertEquals(emptyList<File>(), stagedFiles())

    private companion object {
        const val WIDTH = 16
        const val HEIGHT = 12
        const val BUDGET = 4L * 1024 * 1024
        const val BRUSH_BUDGET = 2L * 1024 * 1024
        const val COMMENT_LENGTH = 60_000
    }
}
