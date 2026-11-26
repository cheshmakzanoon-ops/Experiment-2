package com.artflow.studio.data.export

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile

class ArtflowPackagePublicationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun cancellationDuringMemberReadPreservesPreviousExportAndClosesSource() {
        val destination = File(temporary.root, "previous.artflow")
        val previous = byteArrayOf(9, 8, 7, 6)
        destination.writeBytes(previous)
        val owner = Job()
        var reads = 0
        var closed = false
        val input =
            object : ByteArrayInputStream(ByteArray(128 * 1024)) {
                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    reads++
                    return super.read(buffer, offset, length).also { owner.cancel(CancellationException("Editor closed")) }
                }

                override fun close() {
                    closed = true
                    super.close()
                }
            }

        assertThrows(CancellationException::class.java) {
            ArtflowPackage.writeToFile(
                "Cancelled",
                destination,
                listOf(ArtflowPackage.EntrySource(ArtflowPackage.DOCUMENT) { input }),
                checkActive = { owner.ensureActive() },
            )
        }

        assertEquals("Cancellation must stop before the next source read", 1, reads)
        assertTrue(closed)
        assertArrayEquals(previous, destination.readBytes())
        assertEquals(listOf(destination.name), temporary.root.list()!!.toList())
    }

    @Test
    fun cancellationWhileClosingFinalSourceCannotPublishTheFinishedArchive() {
        val destination = File(temporary.root, "cancelled.artflow")
        val owner = Job()
        var closed = false
        val input =
            object : ByteArrayInputStream("{}".toByteArray()) {
                override fun close() {
                    closed = true
                    owner.cancel(CancellationException("Editor closed after its last source was read"))
                    super.close()
                }
            }

        assertThrows(CancellationException::class.java) {
            ArtflowPackage.writeToFile(
                "Cancelled at publication",
                destination,
                listOf(ArtflowPackage.EntrySource(ArtflowPackage.DOCUMENT) { input }),
                checkActive = { owner.ensureActive() },
            )
        }

        assertTrue(closed)
        assertFalse(destination.exists())
        assertTrue(temporary.root.list()!!.isEmpty())
    }

    @Test
    fun aggregateLimitCountsTheMarkerAndEveryMemberBeforeReplacingAnExport() {
        val files = mapOf(ArtflowPackage.DOCUMENT to "{}".toByteArray(), "canvas.png" to byteArrayOf(1, 2, 3, 4))
        val destination = File(temporary.root, "bounded.artflow")
        ArtflowPackage.writeToFile("Bounded", destination, sources(files))
        val exactBytes = ZipFile(destination).use { zip -> zip.entries().toList().sumOf { it.size } }

        ArtflowPackage.writeToFile("Bounded", destination, sources(files), maxTotalBytes = exactBytes)
        ArtflowPackage.openFile(destination, maxTotalBytes = exactBytes).use { contents ->
            files.forEach { (path, bytes) -> assertArrayEquals(bytes, contents.readBytes(path)) }
        }
        val previous = destination.readBytes()
        assertThrows(IllegalArgumentException::class.java) {
            ArtflowPackage.writeToFile("Bounded", destination, sources(files), maxTotalBytes = exactBytes - 1)
        }

        assertArrayEquals(previous, destination.readBytes())
        assertEquals(listOf(destination.name), temporary.root.list()!!.toList())
    }

    @Test
    fun completedArchiveMustFitTheGalleryStagingLimitBeforePublication() {
        val files = mapOf(ArtflowPackage.DOCUMENT to "{}".toByteArray())
        val destination = File(temporary.root, "bounded.artflow")
        ArtflowPackage.writeToFile("Bounded", destination, sources(files))
        val exactBytes = destination.length()

        ArtflowPackage.writeToFile("Bounded", destination, sources(files), maxTotalBytes = 1024, maxArchiveBytes = exactBytes)
        val previous = destination.readBytes()
        assertThrows(IllegalArgumentException::class.java) {
            ArtflowPackage.writeToFile("Bounded", destination, sources(files), maxTotalBytes = 1024, maxArchiveBytes = exactBytes - 1)
        }

        assertArrayEquals(previous, destination.readBytes())
        assertEquals(listOf(destination.name), temporary.root.list()!!.toList())
    }

    @Test
    fun memberThatGrowsAfterSourceAcquisitionCannotPublishAnUnreadablePackage() {
        val metadata = File(temporary.newFolder("source"), "dependencies.json")
        metadata.writeBytes("{}".toByteArray() + ByteArray(1024 * 1024 - 2) { ' '.code.toByte() })
        assertEquals(1024L * 1024, metadata.length())
        val destination = File(temporary.newFolder("exports"), "previous.artflow")
        val previous = byteArrayOf(9, 8, 7, 6)
        destination.writeBytes(previous)
        val entries =
            sources(mapOf(ArtflowPackage.DOCUMENT to "{}".toByteArray())) +
                ArtflowPackage.EntrySource(ArtflowPackage.DEPENDENCIES) {
                    metadata.appendBytes(byteArrayOf(' '.code.toByte()))
                    metadata.inputStream()
                }

        assertThrows(IllegalArgumentException::class.java) {
            ArtflowPackage.writeToFile("Growing source", destination, entries)
        }

        assertEquals(1024L * 1024 + 1, metadata.length())
        assertArrayEquals(previous, destination.readBytes())
        assertEquals(listOf(destination.name), destination.parentFile!!.list()!!.toList())
    }

    @Test
    fun testAllowancesCannotWidenTheProductionImportCeilings() {
        val destination = File(temporary.root, "bounded.artflow")
        val files = sources(mapOf(ArtflowPackage.DOCUMENT to "{}".toByteArray()))
        for (total in listOf(0L, 768L * 1024 * 1024 + 1)) {
            assertThrows(IllegalArgumentException::class.java) {
                ArtflowPackage.writeToFile("Invalid allowance", destination, files, maxTotalBytes = total)
            }
        }
        for (archive in listOf(0L, StagedImport.MAX_PACKAGE_BYTES + 1)) {
            assertThrows(IllegalArgumentException::class.java) {
                ArtflowPackage.writeToFile("Invalid allowance", destination, files, maxTotalBytes = 1024, maxArchiveBytes = archive)
            }
        }
        assertTrue(temporary.root.list()!!.isEmpty())
    }

    private fun sources(files: Map<String, ByteArray>): List<ArtflowPackage.EntrySource> =
        files.map { (path, bytes) -> ArtflowPackage.EntrySource(path) { ByteArrayInputStream(bytes) } }
}
