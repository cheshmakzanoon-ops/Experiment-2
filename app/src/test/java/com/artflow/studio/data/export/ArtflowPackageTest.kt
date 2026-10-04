package com.artflow.studio.data.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArtflowPackageTest {
    private val files =
        mapOf(
            "canvas.artflow" to "{}".toByteArray(),
            "layers/3/v2-ab12.png" to byteArrayOf(1, 2, 3),
            "thumbnail.png" to byteArrayOf(9),
        )

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test fun anArtworkSurvivesTheRoundTrip() {
        val read = ArtflowPackage.read(ArtflowPackage.write("Sunset", files))
        assertEquals("Sunset", read.name)
        assertEquals(files.keys, read.files.keys)
        files.forEach { (path, bytes) -> assertArrayEquals(bytes, read.files.getValue(path)) }
    }

    @Test fun onlyKnownFilesInsideTheProjectAreAccepted() {
        val marker = "artflow-package.json" to """{"format":"artflow-package","version":1,"name":"x"}""".toByteArray()
        val document = "canvas.artflow" to "{}".toByteArray()
        assertEquals("x", ArtflowPackage.read(zip(marker, document)).name)
        listOf("../evil.png", "layers/1/../../x.png", "/abs.png", "layers/a/v1.png", "notes.txt").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { ArtflowPackage.read(zip(marker, document, path to byteArrayOf(1))) }
        }
        assertThrows(IllegalArgumentException::class.java) { ArtflowPackage.read(zip(document)) }
        assertThrows(IllegalArgumentException::class.java) { ArtflowPackage.read(zip(marker)) }
        assertThrows(IllegalArgumentException::class.java) { ArtflowPackage.read(ArtflowPackage.write("Big", files), maxTotalBytes = 4) }
    }
}
