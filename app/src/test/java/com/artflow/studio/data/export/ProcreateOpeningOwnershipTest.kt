package com.artflow.studio.data.export

import com.artflow.studio.core.export.KeyedArchiveBuilder
import com.artflow.studio.core.export.ProcreateReadBudget
import com.artflow.studio.core.export.ProcreateWriter
import com.artflow.studio.core.pixels.ImportPreparation
import com.artflow.studio.core.pixels.PixelBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProcreateOpeningOwnershipTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun oversizedInflatedMetadataIsRejectedAndItsStagedArchiveRemoved() {
        val limit = ProcreateReadBudget(BUDGET).metadataEntryBytes
        val archive = zip(mapOf("Document.archive" to ByteArray(limit.toInt() + 1)))
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                ProcreateImport.open(archive.inputStream(), temporary.root, BUDGET)
            }
        assertTrue(failure.message.orEmpty().contains("entry too large"))
        assertEmpty()
    }

    @Test
    fun oversizedCanvasIsRejectedBeforeAnyTileReadAndCleansUp() {
        val metadata = KeyedArchiveBuilder().encode(mapOf("size" to "{1024, 1024}", "tileSize" to 16L))
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                ProcreateImport.open(zip(mapOf("Document.archive" to metadata)).inputStream(), temporary.root, BUDGET)
            }
        assertTrue(failure.message.orEmpty().contains("more memory"))
        assertEmpty()
    }

    @Test
    fun ordinaryOpeningRemainsReentrantWithinAnExistingImportAdmission() {
        val pixels = PixelBuffer.filled(4, 4, -0xff0100)
        val source = ProcreateWriter.write(4, 4, listOf(ProcreateWriter.Layer("Ink", pixels)), pixels)
        ImportPreparation.withAdmission {
            ProcreateImport.open(source.inputStream(), temporary.root, BUDGET).use { opened ->
                assertArrayEquals(pixels.pixels, requireNotNull(opened.document.composite()).pixels)
            }
        }
        assertEmpty()
    }

    @Test
    fun invalidOpeningAllowanceDoesNotCreateAStagedFile() {
        assertThrows(IllegalArgumentException::class.java) {
            ProcreateImport.open(byteArrayOf().inputStream(), temporary.root, 0)
        }
        assertEmpty()
    }

    @Test
    fun oversizedZipDirectoryIsRejectedBeforeMetadataParsingAndCleansUp() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for (name in listOf("Document.archive", "extra-one", "extra-two")) {
                zip.putNextEntry(ZipEntry(name).apply { comment = "x".repeat(60_000) })
                zip.closeEntry()
            }
        }
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                ProcreateImport.open(output.toByteArray().inputStream(), temporary.root, BUDGET)
            }
        assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("archive index too large"))
        assertEmpty()
    }

    @Test
    fun smallZip64ArchiveStillOpensWithinTheSameAllowance() {
        val pixels = PixelBuffer.filled(4, 4, -0xff0100)
        val source = ProcreateWriter.write(4, 4, listOf(ProcreateWriter.Layer("Ink", pixels)), pixels)
        ProcreateImport.open(zip64(source).inputStream(), temporary.root, BUDGET).use { opened ->
            assertArrayEquals(pixels.pixels, requireNotNull(opened.document.composite()).pixels)
        }
        assertEmpty()
    }

    @Test
    fun conflictingZip64AndLegacyDirectorySizesAreRefusedBeforeZipFileAllocation() {
        val source = zip64(zip(mapOf("Document.archive" to byteArrayOf())))
        ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN).putInt(source.size - 22 + 12, 1024 * 1024 * 1024)
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                ProcreateImport.open(source.inputStream(), temporary.root, BUDGET)
            }
        assertTrue(failure.message.orEmpty().contains("index is damaged"))
        assertEmpty()
    }

    @Test
    fun harmlessTrailingPaddingKeepsTheSameZipFileFooterSelection() {
        val pixels = PixelBuffer.filled(4, 4, -0xff0100)
        val source = ProcreateWriter.write(4, 4, listOf(ProcreateWriter.Layer("Ink", pixels)), pixels) + ByteArray(8)
        ProcreateImport.open(source.inputStream(), temporary.root, BUDGET).use { opened ->
            assertArrayEquals(pixels.pixels, requireNotNull(opened.document.composite()).pixels)
        }
        assertEmpty()
    }

    @Test
    fun zeroLengthProviderReadsStillMakeProgressAndCleanUp() {
        val pixels = PixelBuffer.filled(4, 4, -0xff0100)
        val source = ProcreateWriter.write(4, 4, listOf(ProcreateWriter.Layer("Ink", pixels)), pixels)
        val input =
            object : ByteArrayInputStream(source) {
                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int = if (available() == 0) -1 else 0
            }
        ProcreateImport.open(input, temporary.root, BUDGET).use { opened ->
            assertArrayEquals(pixels.pixels, requireNotNull(opened.document.composite()).pixels)
        }
        assertEmpty()
    }

    private fun zip64(source: ByteArray): ByteArray {
        val footerOffset = source.size - 22
        val footer = ByteBuffer.wrap(source.copyOfRange(footerOffset, source.size)).order(ByteOrder.LITTLE_ENDIAN)
        val entries = footer.getShort(10).toLong() and 0xffff
        val bytes = footer.getInt(12).toLong() and 0xffffffffL
        val offset = footer.getInt(16).toLong() and 0xffffffffL
        val extension =
            ByteBuffer
                .allocate(76)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x06064b50)
                .putLong(44)
                .putShort(45)
                .putShort(45)
                .putInt(0)
                .putInt(0)
                .putLong(entries)
                .putLong(entries)
                .putLong(bytes)
                .putLong(offset)
                .putInt(0x07064b50)
                .putInt(0)
                .putLong(footerOffset.toLong())
                .putInt(1)
                .array()
        footer.putShort(8, -1)
        footer.putShort(10, -1)
        footer.putInt(12, -1)
        footer.putInt(16, -1)
        return source.copyOfRange(0, footerOffset) + extension + footer.array()
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
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

    private fun assertEmpty() {
        assertEquals(
            emptyList<String>(),
            temporary.root
                .listFiles()
                .orEmpty()
                .map { it.name },
        )
    }

    private companion object {
        const val BUDGET = 4L * 1024 * 1024
    }
}
