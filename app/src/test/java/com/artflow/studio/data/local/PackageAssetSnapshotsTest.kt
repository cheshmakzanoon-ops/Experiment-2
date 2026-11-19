package com.artflow.studio.data.local

import com.artflow.studio.data.export.ArtflowPackage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class PackageAssetSnapshotsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `independent readers keep their own cursor after a path replacement`() {
        val source = temporary.newFile("asset")
        val original = byteArrayOf(1, 2, 3, 4)
        source.writeBytes(original)
        PackageAssetSnapshots().use { owner ->
            val snapshot = owner.capture("model.obj", source, 100)
            val replacement = temporary.newFile("replacement").apply { writeBytes(byteArrayOf(5, 6, 7, 8)) }
            Files.move(replacement.toPath(), source.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)

            snapshot.openStream().use { first ->
                assertEquals(1, first.read())
                snapshot.openStream().use { second ->
                    assertEquals(2L, second.skip(2))
                    assertEquals(2, second.available())
                    assertArrayEquals(byteArrayOf(3, 4), second.readBytes())
                }
                assertArrayEquals(byteArrayOf(2, 3, 4), first.readBytes())
            }
            assertArrayEquals(original, snapshot.openStream().use { it.readBytes() })
        }
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), source.readBytes())
    }

    @Test
    fun `closed readers and a closed scope cannot read the retained descriptor`() {
        val source = temporary.newFile("asset").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        lateinit var snapshot: ArtflowPackage.EntrySource
        lateinit var escaped: InputStream
        PackageAssetSnapshots().use { owner ->
            snapshot = owner.capture("model.obj", source, 100)
            val closed = snapshot.openStream()
            closed.close()
            assertTrue(runCatching { closed.read() }.exceptionOrNull() is IOException)
            escaped = snapshot.openStream()
            assertEquals(1, escaped.read())
        }

        assertTrue(runCatching { escaped.read() }.exceptionOrNull() is IOException)
        assertTrue(runCatching { snapshot.openStream().use { it.read() } }.exceptionOrNull() is IOException)
    }

    @Test
    fun `failure while acquiring a later asset closes earlier descriptors`() {
        val valid = temporary.newFile("valid").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val empty = temporary.newFile("empty")
        lateinit var earlier: InputStream
        val failure =
            runCatching {
                PackageAssetSnapshots().use { owner ->
                    earlier = owner.capture("canvas.png", valid, 100).openStream()
                    owner.capture("thumbnail.png", empty, 100)
                }
            }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(runCatching { earlier.read() }.exceptionOrNull() is IOException)
    }

    @Test
    fun `failed archive callback closes descriptors and preserves its original error`() {
        val file = temporary.newFile("model").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val original = IOException("Archive disk full")
        lateinit var stream: InputStream
        val failure =
            runCatching {
                PackageAssetSnapshots().use { owner ->
                    stream = owner.capture("model.obj", file, 100).openStream()
                    throw original
                }
            }.exceptionOrNull()

        assertTrue(failure === original)
        assertTrue(runCatching { stream.read() }.exceptionOrNull() is IOException)
    }
}
