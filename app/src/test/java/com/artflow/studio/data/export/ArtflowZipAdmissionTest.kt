package com.artflow.studio.data.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ArtflowZipAdmissionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun oversizedDirectoryCommentsAreRejectedBeforeEitherZipFileConstructor() {
        val source = archive(comment = "x".repeat(6_000))
        val file = file(source)
        ZipFile(file).use { assertEquals(2, it.size()) }
        assertRejectedBeforeOpening(file, "archive index too large", indexBytes = 8_192)
    }

    @Test
    fun entryCountIsAdmittedBeforeEitherZipFileConstructor() {
        val source = archive()
        ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(source.size - 22 + 8, 4_201)
            putShort(source.size - 22 + 10, 4_201)
        }
        assertRejectedBeforeOpening(file(source), "too many files")
    }

    @Test
    fun actualDirectoryCountCannotEvadeTheDeclaredAdmission() {
        val source = archive()
        ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(source.size - 22 + 8, 1)
            putShort(source.size - 22 + 10, 1)
        }
        assertRejectedBeforeOpening(file(source), "index is damaged")
    }

    @Test
    fun variableFieldsCannotExceedTheAdmittedDirectorySpan() {
        val source = archive()
        val view = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN)
        val directory = view.getInt(source.size - 22 + 16)
        view.putShort(directory + 32, 32_000)
        assertRejectedBeforeOpening(file(source), "index is damaged")
    }

    @Test
    fun validPackageKeepsRecognitionPayloadFreeAndStreamingPayloadIntact() {
        val source = file(archive())
        var opens = 0
        var payloadReads = 0
        var closes = 0
        val opener: (File) -> ZipFile = { file ->
            opens++
            object : ZipFile(file) {
                override fun getInputStream(entry: ZipEntry): InputStream {
                    payloadReads++
                    return super.getInputStream(entry)
                }

                override fun close() {
                    closes++
                    super.close()
                }
            }
        }
        assertTrue(ArtflowPackage.isPackageFile(source, INDEX_BYTES, opener))
        assertEquals(1, opens)
        assertEquals(0, payloadReads)
        assertEquals(1, closes)
        ArtflowPackage.openFile(source, TOTAL_BYTES, INDEX_BYTES, opener).use { contents ->
            assertEquals("Index fixture", contents.name)
            assertEquals(1, payloadReads)
            assertArrayEquals(DOCUMENT, contents.readBytes(ArtflowPackage.DOCUMENT))
            assertEquals(2, payloadReads)
        }
        assertEquals(2, opens)
        assertEquals(2, closes)
    }

    @Test
    fun genericZipWithoutMarkerStillReturnsFalseWithoutReadingItsPayload() {
        val source = file(archive(marker = false))
        var reads = 0
        assertFalse(
            ArtflowPackage.isPackageFile(source, INDEX_BYTES) { file ->
                object : ZipFile(file) {
                    override fun getInputStream(entry: ZipEntry): InputStream {
                        reads++
                        return super.getInputStream(entry)
                    }
                }
            },
        )
        assertEquals(0, reads)
    }

    @Test
    fun nameInspectionDoesNotOpenDamagedLocalPayloadHeaders() {
        val source = archive()
        val view = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN)
        for (offset in 0..source.size - Int.SIZE_BYTES) {
            if (view.getInt(offset) == 0x04034b50) view.putInt(offset, 0)
        }
        val file = file(source)
        assertTrue(
            ArtflowPackage.inspectArchiveEntries(file) { entries ->
                entries.toSet() == setOf(ArtflowPackage.DOCUMENT, "artflow-package.json")
            },
        )
        assertThrows(IllegalArgumentException::class.java) { ArtflowPackage.openFile(file) }
    }

    @Test
    fun smallZip64AndOrdinaryArchivesKeepIdenticalPayloads() {
        val original = archive()
        for (bytes in listOf(original, zip64(original))) {
            val source = file(bytes)
            assertTrue(ArtflowPackage.isPackageFile(source))
            ArtflowPackage.openFile(source).use { contents ->
                assertEquals("Index fixture", contents.name)
                assertArrayEquals(DOCUMENT, contents.readBytes(ArtflowPackage.DOCUMENT))
            }
        }
    }

    @Test
    fun conflictingZip64DirectorySizeCannotReachThePlatformParser() {
        val source = zip64(archive())
        ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN).putInt(source.size - 22 + 12, 1024 * 1024 * 1024)
        assertRejectedBeforeOpening(file(source), "index is damaged")
    }

    @Test
    fun harmlessTrailingPaddingUsesTheSameFooterAsZipFile() {
        val source = file(archive() + ByteArray(8))
        assertTrue(ArtflowPackage.isPackageFile(source))
        ArtflowPackage.openFile(source).use { contents ->
            assertArrayEquals(DOCUMENT, contents.readBytes(ArtflowPackage.DOCUMENT))
        }
    }

    @Test
    fun invalidAllowancesCannotOpenThePlatformArchive() {
        val source = file(archive())
        for (budget in listOf(0L, Long.MAX_VALUE)) {
            assertRejectedBeforeOpening(source, "Invalid artwork archive index allowance", budget)
        }
        var opens = 0
        assertThrows(IllegalArgumentException::class.java) {
            ArtflowPackage.openFile(source, 0, INDEX_BYTES) { file ->
                opens++
                ZipFile(file)
            }
        }
        assertEquals(0, opens)
    }

    private fun assertRejectedBeforeOpening(
        file: File,
        message: String,
        indexBytes: Long = INDEX_BYTES,
    ) {
        var opens = 0
        val opener: (File) -> ZipFile = { source ->
            opens++
            ZipFile(source)
        }
        val recognition =
            assertThrows(IllegalArgumentException::class.java) { ArtflowPackage.isPackageFile(file, indexBytes, opener) }
        assertTrue(recognition.message.orEmpty(), recognition.message.orEmpty().contains(message))
        assertEquals(0, opens)
        val opening =
            assertThrows(IllegalArgumentException::class.java) { ArtflowPackage.openFile(file, TOTAL_BYTES, indexBytes, opener) }
        assertTrue(opening.message.orEmpty(), opening.message.orEmpty().contains(message))
        assertEquals(0, opens)
    }

    private fun file(bytes: ByteArray): File = temporary.newFile().apply { writeBytes(bytes) }

    private fun archive(
        comment: String = "",
        marker: Boolean = true,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val entries = linkedMapOf(ArtflowPackage.DOCUMENT to DOCUMENT)
            if (marker) entries["artflow-package.json"] = MARKER.toByteArray()
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name).apply { this.comment = comment })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
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

    private companion object {
        const val INDEX_BYTES = 256L * 1024
        const val TOTAL_BYTES = 1024L * 1024
        const val MARKER = """{"format":"artflow-package","version":1,"name":"Index fixture"}"""
        val DOCUMENT = "fixture document".toByteArray()
    }
}
