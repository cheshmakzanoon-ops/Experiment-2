package com.artflow.studio.data

import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artflow.studio.TestEvidence
import com.artflow.studio.core.export.BinaryPlist
import com.artflow.studio.core.export.KeyedArchiveBuilder
import com.artflow.studio.core.export.Lz4
import com.artflow.studio.core.export.ProcreateWriter
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.data.export.LayerImports
import com.artflow.studio.data.export.ProcreateImport
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.StorageFileTree
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
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
import org.junit.Assert.assertNotNull
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
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
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
    fun damagedFallbackClosesItsStagedArchiveAndPreservesSavedArtworkAndHistory() =
        runBlocking(Dispatchers.Main) {
            val application = ApplicationProvider.getApplicationContext<Context>()
            val context =
                object : ContextWrapper(application) {
                    override fun getFilesDir(): File = directory
                }
            val storage = ProjectStorage(context)
            var repository = CanvasRepositoryImpl(storage)
            val expected = pixels()
            try {
                assertNotNull(repository.loadOrCreate(PROJECT, WIDTH, HEIGHT, 72))
                assertTrue(repository.setLayerPixels(repository.getActiveLayerId(), expected, "Existing paint"))
                assertNotNull(repository.saveCanvas(PROJECT))
                repository.addLayer("Keep this redo")
                assertTrue(repository.undo())
                assertTrue(repository.undoDepth > 0)
                assertTrue(repository.redoDepth > 0)
                val before = state(repository)
                ProcreateImport.open(archive(expected).inputStream(), staging, BUDGET).use { live ->
                    val filesBefore = fileSnapshot()
                    val failure =
                        runCatching {
                            ProcreateImport.useOpened(staging, { damagedArchive().inputStream() }) { opened, _ ->
                                assertEquals(0, opened.document.damagedTileCount)
                                try {
                                    LayerImports.importProcreate(repository, opened.document, rasterBudgetBytes = 0)
                                } finally {
                                    assertEquals(1, opened.document.damagedTileCount)
                                }
                            }
                        }.exceptionOrNull()
                    assertTrue(failure.toString(), failure is IllegalStateException)
                    assertTrue(failure.toString(), failure?.message.orEmpty().contains("damaged tile(s)"))
                    assertEquals(before, state(repository))
                    assertEquals(filesBefore, fileSnapshot())
                    assertEquals(1, stagedFiles().size)
                    assertArrayEquals(expected.pixels, requireNotNull(live.document.composite()).pixels)
                }
                assertNoStagedFiles()
                assertTrue(repository.redo())
                assertTrue(repository.getAllLayers().any { it.name == "Keep this redo" })
                assertTrue(repository.undo())
                assertArrayEquals(expected.pixels, requireNotNull(repository.layerPixels(repository.getActiveLayerId())).pixels)
                repository.dispose()
                repository = CanvasRepositoryImpl(storage)
                assertNotNull(repository.loadCanvas(PROJECT))
                assertEquals(1, repository.getAllLayers().size)
                assertArrayEquals(expected.pixels, requireNotNull(repository.layerPixels(repository.getActiveLayerId())).pixels)
            } finally {
                repository.dispose()
            }
        }

    @Test
    fun decodableStoredZipCorruptionPreservesSavedArtworkAndCleansOnlyItsOwnStaging() =
        runBlocking(Dispatchers.Main) {
            val application = ApplicationProvider.getApplicationContext<Context>()
            val context =
                object : ContextWrapper(application) {
                    override fun getFilesDir(): File = directory
                }
            val storage = ProjectStorage(context)
            var repository = CanvasRepositoryImpl(storage)
            val expected = pixels()
            try {
                assertNotNull(repository.loadOrCreate(CHECKSUM_PROJECT, WIDTH, HEIGHT, 72))
                assertTrue(repository.setLayerPixels(repository.getActiveLayerId(), expected, "Existing paint"))
                assertNotNull(repository.saveCanvas(CHECKSUM_PROJECT))
                repository.addLayer("Keep this redo")
                assertTrue(repository.undo())
                val before = state(repository)
                ProcreateImport.open(archive(expected).inputStream(), staging, BUDGET).use { live ->
                    for (path in listOf("Document.archive", "INK/0~0.lz4")) {
                        val source = storedArchiveWithChangedPayload(path)
                        assertDecodableChecksumDamage(source, path)
                        val filesBefore = fileSnapshot()
                        var openingReturned = false
                        val failure =
                            runCatching {
                                ProcreateImport.useOpened(staging, { source.inputStream() }) { opened, _ ->
                                    openingReturned = true
                                    LayerImports.importProcreate(repository, opened.document)
                                }
                            }.exceptionOrNull()
                        assertTrue(failure.toString(), failure is IllegalArgumentException)
                        assertTrue(failure.toString(), failure?.message.orEmpty().contains("checksum is damaged"))
                        assertEquals(path != "Document.archive", openingReturned)
                        assertEquals(before, state(repository))
                        assertEquals(filesBefore, fileSnapshot())
                        assertEquals(1, stagedFiles().size)
                        assertArrayEquals(expected.pixels, requireNotNull(live.document.composite()).pixels)
                    }
                }
                assertNoStagedFiles()
                assertTrue(repository.redo())
                assertTrue(repository.getAllLayers().any { it.name == "Keep this redo" })
                assertTrue(repository.undo())
                repository.dispose()
                repository = CanvasRepositoryImpl(storage)
                assertNotNull(repository.loadCanvas(CHECKSUM_PROJECT))
                assertEquals(1, repository.getAllLayers().size)
                assertArrayEquals(expected.pixels, requireNotNull(repository.layerPixels(repository.getActiveLayerId())).pixels)
            } finally {
                repository.dispose()
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

    /** The ZIP is valid; only the stored composite's independent zlib checksum is truncated. */
    private fun damagedArchive(): ByteArray {
        val archive = KeyedArchiveBuilder()
        val layer = archive.instance("SilicaLayer", mapOf("name" to "Ink", "UUID" to "INK"))
        val composite = archive.instance("SilicaLayer", mapOf("UUID" to "COMPOSITE"))
        val metadata =
            archive.encode(
                mapOf(
                    "size" to "{$WIDTH, $HEIGHT}",
                    "tileSize" to 16L,
                    "layers" to archive.array(listOf(layer)),
                    "composite" to composite,
                ),
            )
        val raw = ByteArray(WIDTH * HEIGHT * 4) { if (it % 4 == 3) 255.toByte() else 68 }
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { it.write(raw) }
        val packed = compressed.toByteArray()
        val entries =
            mapOf(
                "Document.archive" to metadata,
                "INK/0~0.tile" to raw,
                "COMPOSITE/0~0.tile" to packed.copyOf(packed.size - 1),
            )
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    /** Repackages the independent Python fixture, then changes decoded data without changing either ZIP CRC. */
    private fun storedArchiveWithChangedPayload(path: String): ByteArray {
        val output = ByteArrayOutputStream()
        var changedOffset = -1
        val source =
            InstrumentationRegistry
                .getInstrumentation()
                .context.assets
                .open("procreate/color/display-p3-derived.procreate")
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(source).use { original ->
            var entry = original.nextEntry
            while (entry != null) {
                entries[entry.name] = original.readBytes()
                entry = original.nextEntry
            }
        }
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                val checksum = CRC32().apply { update(bytes) }
                zip.putNextEntry(
                    ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        crc = checksum.value
                    },
                )
                // Change the plist's title or one raw colour byte after the LZ4 stored-block header.
                val relative =
                    if (name == "Document.archive") {
                        String(bytes, Charsets.ISO_8859_1).indexOf("Synthetic P3 import")
                    } else {
                        8
                    }
                if (name == path) {
                    assertTrue(relative >= 0)
                    changedOffset = output.size() + relative
                }
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        assertTrue(changedOffset >= 0)
        return output.toByteArray().apply { this[changedOffset] = (this[changedOffset].toInt() xor 1).toByte() }
    }

    private fun assertDecodableChecksumDamage(
        source: ByteArray,
        path: String,
    ) {
        val fixture = File(directory, "stored-checksum.procreate").apply { writeBytes(source) }
        ZipFile(fixture).use { zip ->
            val entry = requireNotNull(zip.getEntry(path))
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            assertEquals(ZipEntry.STORED, entry.method)
            assertEquals(entry.size, bytes.size.toLong())
            assertTrue(entry.crc != CRC32().apply { update(bytes) }.value)
            if (path == "Document.archive") {
                assertTrue(BinaryPlist.read(bytes) is Map<*, *>)
            } else {
                assertEquals(64 * 64 * 4, Lz4.decompressApple(bytes, 64 * 64 * 4).size)
            }
        }
    }

    private suspend fun state(repository: CanvasRepositoryImpl): List<Any?> =
        listOf(
            repository.frames(),
            repository.getAllLayers().associate { it.id to repository.layerPixels(it.id)?.pixels?.toList() },
            repository.getColorProfile(),
            repository.getBackgroundColor(),
            repository.contentRevision,
            repository.undoDepth,
            repository.redoDepth,
            repository.historyMark,
            repository.getActiveLayerId(),
        )

    private fun fileSnapshot(): Map<String, List<Byte>> =
        directory.walkTopDown().filter { it.isFile }.associate { it.relativeTo(directory).path to it.readBytes().toList() }

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
        const val PROJECT = 133L
        const val CHECKSUM_PROJECT = 134L
        const val WIDTH = 16
        const val HEIGHT = 12
        const val BUDGET = 4L * 1024 * 1024
        const val BRUSH_BUDGET = 2L * 1024 * 1024
        const val COMMENT_LENGTH = 60_000
    }
}
