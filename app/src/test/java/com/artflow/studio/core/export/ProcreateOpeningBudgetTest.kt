package com.artflow.studio.core.export

import com.artflow.studio.core.pixels.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class ProcreateOpeningBudgetTest {
    @Test
    fun rasterAndTileScratchAreAdmittedBeforeThumbnailOrTileReads() {
        for ((width, tileSize) in listOf(1024 to 16, 16 to 4096)) {
            val files = files(document(width, width, tileSize), png(16, 16))
            var decoded = false
            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    ProcreateReader.open(files, ProcreateReadBudget(BUDGET)) { _, _ ->
                        decoded = true
                        PixelBuffer(16, 16)
                    }
                }
            assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("more memory"))
            assertFalse(decoded)
            assertEquals(listOf("Document.archive"), files.reads)
        }
    }

    @Test
    fun thumbnailHeaderIsAdmittedBeforeInvokingTheDecoder() {
        val thumbnail = png(1024, 1024)
        val files = files(document(16, 16, 16), thumbnail)
        var decoded = false
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                ProcreateReader.open(files, ProcreateReadBudget(BUDGET)) { _, _ ->
                    decoded = true
                    null
                }
            }
        assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("more memory"))
        assertFalse(decoded)
        assertFalse(files.reads.any { it.endsWith(".chunk") })
    }

    @Test
    fun admittedThumbnailReceivesOnlyItsReservedAllowance() {
        val files = files(document(16, 16, 16), png(16, 16), withTile = false)
        val budget = ProcreateReadBudget(BUDGET)
        var received = 0L
        val opened =
            ProcreateReader.open(files, budget) { _, maximum ->
                received = maximum
                PixelBuffer(16, 16)
            }
        assertEquals(16, opened.width)
        assertEquals(budget.thumbnailDecodeBytes, received)
        assertTrue(received in 1 until BUDGET)
    }

    @Test
    fun unexpectedDecoderDimensionsAreRefusedBeforeCalibration() {
        val files = files(document(16, 16, 16), png(16, 16))
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                ProcreateReader.open(files, ProcreateReadBudget(BUDGET)) { _, _ -> PixelBuffer(32, 16) }
            }
        assertTrue(failure.message.orEmpty().contains("unexpected dimensions"))
        assertFalse(files.reads.any { it.endsWith(".chunk") })
    }

    @Test
    fun encodedMetadataIsBoundedBeforePlistDecoding() {
        val budget = ProcreateReadBudget(BUDGET)
        val source = files(ByteArray(budget.metadataEntryBytes.toInt() + 1), null)
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                ProcreateReader.open(source, budget) { _, _ -> error("Must not decode thumbnail") }
            }
        assertTrue(failure.message.orEmpty().contains("entry too large"))
    }

    @Test
    fun sharedPlistDataUsesTheNarrowOpeningAllowance() {
        val budget = ProcreateReadBudget(BUDGET)
        val encoded = repeatedData(2048, 1024)
        assertTrue(encoded.size < budget.metadataEntryBytes)
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                ProcreateReader.open(files(encoded, null), budget) { _, _ -> error("Must not decode thumbnail") }
            }
        assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("decoded memory"))
    }

    @Test
    fun fileBackedAdaptersReceiveTheEntryAllowanceBeforeReading() {
        var maximum = 0L
        val source =
            object : ProcreateReader.BoundedFiles {
                override val names = listOf("Document.archive")

                override fun read(name: String): ByteArray? = error("Must request bounded read")

                override fun read(
                    name: String,
                    maximumBytes: Long,
                ): ByteArray? {
                    if (name != "Document.archive") return null
                    maximum = maximumBytes
                    return document(16, 16, 16)
                }
            }
        val budget = ProcreateReadBudget(BUDGET)
        assertNotNull(ProcreateReader.open(source, budget) { _, _ -> null })
        assertEquals(budget.metadataEntryBytes, maximum)
    }

    @Test
    fun openingLimitsCannotBeDisabledOrWidened() {
        for (value in listOf(0L, -1L, Long.MAX_VALUE, 1024L)) {
            assertThrows(IllegalArgumentException::class.java) { ProcreateReadBudget(value) }
        }
    }

    private fun document(
        width: Int,
        height: Int,
        tileSize: Int,
    ): ByteArray {
        val archive = KeyedArchiveBuilder()
        val layer = archive.instance("SilicaLayer", mapOf("name" to "Ink", "UUID" to "ink"))
        return archive.encode(
            mapOf("size" to "{$width, $height}", "tileSize" to tileSize.toLong(), "layers" to archive.array(listOf(layer))),
        )
    }

    private fun png(
        width: Int,
        height: Int,
    ): ByteArray = PngCodec.encode(PixelBuffer(width, height))

    private fun files(
        archive: ByteArray,
        thumbnail: ByteArray?,
        withTile: Boolean = true,
    ): RecordingFiles =
        RecordingFiles(
            buildMap {
                put("Document.archive", archive)
                thumbnail?.let { put("QuickLook/Thumbnail.png", it) }
                if (withTile) put("ink/0~0.chunk", byteArrayOf(0))
            },
        )

    private class RecordingFiles(
        private val entries: Map<String, ByteArray>,
    ) : ProcreateReader.Files {
        val reads = mutableListOf<String>()
        override val names = entries.keys

        override fun read(name: String): ByteArray? {
            reads += name
            return entries[name]
        }
    }

    private fun repeatedData(
        size: Int,
        references: Int,
    ): ByteArray {
        fun sized(
            marker: Int,
            length: Int,
        ) = byteArrayOf((marker or 15).toByte(), 0x12) + ByteBuffer.allocate(4).putInt(length).array()
        val out = ByteArrayOutputStream()
        out.write("bplist00".toByteArray())
        val first = out.size()
        out.write(sized(0xA0, references) + ByteArray(references) { 1 })
        val second = out.size()
        out.write(sized(0x40, size) + ByteArray(size))
        val table = out.size()
        out.write(
            ByteBuffer
                .allocate(8)
                .putInt(first)
                .putInt(second)
                .array(),
        )
        out.write(
            ByteBuffer
                .allocate(32)
                .put(ByteArray(6))
                .put(4)
                .put(1)
                .putLong(2)
                .putLong(0)
                .putLong(table.toLong())
                .array(),
        )
        return out.toByteArray()
    }

    private companion object {
        const val BUDGET = 4L * 1024 * 1024
    }
}
