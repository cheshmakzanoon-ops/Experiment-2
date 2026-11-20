package com.artflow.studio.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

class FontFingerprintSessionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun manyLegacyAliasesReadTheManagedFileOnlyOncePerOperation() {
        val bytes = ByteArray(32 * 1024) { (it % 251).toByte() }
        val managed = managedFont(bytes)
        var opens = 0
        var bytesRead = 0
        val session =
            FontLibrary.FingerprintSession(temporary.root) { file ->
                assertEquals(managed, file)
                opens++
                object : FilterInputStream(file.inputStream()) {
                    override fun read(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int = super.read(bytes, offset, length).also { if (it > 0) bytesRead += it }
                }
            }
        val aliases =
            List(64) { "file:${temporary.root}/old-device-$it/fonts/Poster.ttf" } +
                listOf("font:Poster.ttf", "file:${managed.absolutePath}")
        val expected = digest(bytes)

        assertEquals(expected, session.sharedFingerprint(aliases))
        aliases.forEach { assertEquals(expected, session.fingerprint(it)) }
        assertEquals(1, opens)
        assertEquals(bytes.size, bytesRead)
    }

    @Test
    fun cachedManagedIdentityNeverVerifiesAnOutsideAliasOrLinkedFile() {
        val managed = managedFont(byteArrayOf(1, 2, 3))
        val outside = File(temporary.newFolder("outside"), "Poster.ttf").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val linked = File(managed.parentFile, "Linked.ttf")
        Files.createSymbolicLink(linked.toPath(), outside.toPath())
        var opens = 0
        val session =
            FontLibrary.FingerprintSession(temporary.root) { file ->
                assertEquals(managed, file)
                opens++
                file.inputStream()
            }

        assertEquals(digest(managed.readBytes()), session.fingerprint("font:Poster.ttf"))
        assertNull(session.fingerprint("file:${outside.absolutePath}"))
        assertNull(session.fingerprint("font:Linked.ttf"))
        assertNull(session.sharedFingerprint(listOf("font:Poster.ttf", "file:${outside.absolutePath}")))
        assertNull(session.fingerprint("file:/invalid\u0000/Poster.ttf"))
        assertEquals(1, opens)
    }

    @Test
    fun unreadableFileDoesNotGetReopenedForEveryLegacyAlias() {
        managedFont(byteArrayOf(1, 2, 3))
        var opens = 0
        val session =
            FontLibrary.FingerprintSession(temporary.root) {
                opens++
                throw IOException("Unavailable font")
            }

        repeat(64) { assertNull(session.fingerprint("file:${temporary.root}/old-device-$it/Poster.ttf")) }
        assertNull(session.fingerprint("font:Poster.ttf"))
        assertEquals(1, opens)
    }

    @Test
    fun aNewOperationObservesChangedFontBytes() {
        val managed = managedFont(byteArrayOf(1, 2, 3))
        val first = FontLibrary.FingerprintSession(temporary.root).fingerprint("font:Poster.ttf")
        managed.writeBytes(byteArrayOf(9, 8, 7))

        val second = FontLibrary.FingerprintSession(temporary.root).fingerprint("font:Poster.ttf")

        assertNotEquals(first, second)
        assertEquals(digest(managed.readBytes()), second)
    }

    @Test
    fun differentManagedFilesEachReceiveTheirOwnDigestAndSingleRead() {
        managedFont(byteArrayOf(1, 2, 3))
        val second = File(temporary.root, "fonts/Other.ttf").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val opens = mutableListOf<File>()
        val session =
            FontLibrary.FingerprintSession(temporary.root) { file ->
                opens.add(file)
                file.inputStream()
            }

        assertNull(session.sharedFingerprint(listOf("font:Poster.ttf", "font:Other.ttf")))
        assertEquals(digest(second.readBytes()), session.fingerprint("file:${temporary.root}/old-device/Other.ttf"))
        assertEquals(2, opens.size)
        assertEquals(2, opens.distinct().size)
    }

    private fun managedFont(bytes: ByteArray): File =
        File(temporary.root, "fonts/Poster.ttf").apply {
            parentFile.mkdirs()
            writeBytes(bytes)
        }

    private fun digest(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
}
