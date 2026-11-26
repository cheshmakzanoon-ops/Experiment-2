package com.artflow.studio.data.export

import com.artflow.studio.core.lifecycle.withCleanup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException

/** Real disk failures exercise the helper also used by both recorder replay and MP4 export. */
class TimelapseFrameSnapshotTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun partialCopyFailureRemovesOwnedCopiesAndPreservesSources() {
        val parent = temporary.newFolder("snapshots")
        val first = temporary.newFile("first.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val missing = File(temporary.root, "missing.jpg")

        val failure =
            runCatching {
                TimelapseFrameSnapshot(parent).use { snapshot -> snapshot.copyFrames(listOf(first, missing)) {} }
            }.exceptionOrNull()

        assertTrue(failure is NoSuchFileException)
        assertTrue(parent.list()!!.isEmpty())
        assertArrayEquals(byteArrayOf(1, 2, 3), first.readBytes())
        assertFalse(missing.exists())
    }

    @Test
    fun cancellationAfterTheLastCopyRemovesACompletedSnapshotBeforeItCanEscape() {
        val parent = temporary.newFolder("snapshots")
        val first = temporary.newFile("first.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val failure = CancellationException("Replay closed during the last file copy")
        var checkpoints = 0
        var exposed = false

        val thrown =
            runCatching {
                TimelapseFrameSnapshot(parent).use { snapshot ->
                    snapshot.copyFrames(listOf(first)) {
                        if (++checkpoints == 2) throw failure
                    }
                    exposed = true
                }
            }.exceptionOrNull()

        assertSame(failure, thrown)
        assertEquals(2, checkpoints)
        assertFalse(exposed)
        assertTrue(parent.list()!!.isEmpty())
        assertArrayEquals(byteArrayOf(1, 2, 3), first.readBytes())
    }

    @Test
    fun failedCleanupRejectsSuccessAndCannotDeleteOutsideTheOwnedSnapshot() {
        BlockedCleanup(temporary).use { fixture ->
            var closedSuccessfully = false
            val failure =
                runCatching {
                    withCleanup(fixture.snapshot::close) { Unit }
                    closedSuccessfully = true
                }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertFalse(closedSuccessfully)
            fixture.assertRetained()
        }
    }

    @Test
    fun failedCleanupRetainsDecodeFailureAndDiagnosticsAcrossDispatchers() =
        runBlocking { verifyPrimaryFailure(IOException("Replay frame could not be decoded")) }

    @Test
    fun failedCleanupRetainsCancellationAndDiagnosticsAcrossDispatchers() =
        runBlocking { verifyPrimaryFailure(CancellationException("Replay was dismissed")) }

    @Test
    fun successfulCleanupDeletesAnOwnedLinkWithoutFollowingItToAnotherDirectory() {
        val parent = temporary.newFolder("snapshots")
        val source = temporary.newFile("source.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val outside = temporary.newFolder("outside")
        val sentinel = File(outside, "keep.jpg").apply { writeBytes(byteArrayOf(4, 5, 6)) }

        val snapshot = TimelapseFrameSnapshot(parent)
        val copies = snapshot.copyFrames(listOf(source)) {}
        Files.createSymbolicLink(File(copies.single().parentFile, "outside").toPath(), outside.toPath())
        snapshot.close()

        assertTrue(parent.list()!!.isEmpty())
        assertArrayEquals(byteArrayOf(4, 5, 6), sentinel.readBytes())
        assertArrayEquals(byteArrayOf(1, 2, 3), source.readBytes())
    }

    private suspend fun verifyPrimaryFailure(original: Exception) {
        BlockedCleanup(temporary).use { fixture ->
            val failure =
                runCatching {
                    withContext(Dispatchers.IO) {
                        withCleanup(fixture.snapshot::close) {
                            withContext(Dispatchers.Default) { throw original }
                        }
                    }
                }.exceptionOrNull()

            assertEquals(original.javaClass, failure?.javaClass)
            assertEquals(original.message, failure?.message)
            assertEquals(1, original.suppressed.size)
            assertTrue(original.suppressed.single() is IOException)
            fixture.assertRetained()
        }
    }

    /** A real linked-parent failure, independent of the process's permission or administrator status. */
    private class BlockedCleanup(
        temporary: TemporaryFolder,
    ) : Closeable {
        private val parent = temporary.newFolder("snapshots")
        private val source = temporary.newFile("source.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val snapshot = TimelapseFrameSnapshot(parent)
        private val copy = snapshot.copyFrames(listOf(source)) {}.single()
        private val relocated = File(temporary.root, "relocated")
        private val outside = temporary.newFolder("outside")
        private val sentinel =
            File(File(outside, copy.parentFile!!.name).apply { mkdirs() }, copy.name).apply { writeBytes(byteArrayOf(4, 5, 6)) }

        init {
            Files.move(parent.toPath(), relocated.toPath())
            Files.createSymbolicLink(parent.toPath(), outside.toPath())
        }

        fun assertRetained() {
            assertArrayEquals(byteArrayOf(4, 5, 6), sentinel.readBytes())
            assertArrayEquals(byteArrayOf(1, 2, 3), source.readBytes())
            assertArrayEquals(byteArrayOf(1, 2, 3), File(relocated, copy.relativeTo(parent).path).readBytes())
        }

        override fun close() {
            Files.delete(parent.toPath())
            Files.move(relocated.toPath(), parent.toPath())
            snapshot.close()
            assertTrue(parent.list()!!.isEmpty())
        }
    }
}
