package com.artflow.studio.data.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real owned directories, concurrent handoffs and deterministic failures; no recursive deletion. */
class SharedCopyCleanupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun cleanupCountsAndRemovesOnlyOwnedInactiveCopiesInTheThreeExportBuckets() =
        runBlocking {
            val files = temporary.newFolder("files")
            val exports = File(files, "exports").apply { mkdirs() }
            val project = File(exports, "19").apply { mkdirs() }
            val palettes = File(exports, "palettes").apply { mkdirs() }
            val brushes = File(exports, "brushes").apply { mkdirs() }
            val completed = listOf(published(project), published(palettes, "Swatches.ase"), published(brushes, "Ink.artbrush"))
            val pending = ShareExportFiles.create(project, "Pending.png") { it.write(9) }
            val protected =
                listOf(
                    File(files, "projects/19/canvas.png"),
                    File(project, "Ordinary.png"),
                    File(project, "timelapse_snap_1/frame.png"),
                    File(project, "share-legacy/Old.png"),
                    File(brushes, "9d12d560-26e8-4f4b-9cfb-6ef5d356f90a/Old.artbrush"),
                ).onEach {
                    it.parentFile!!.mkdirs()
                    it.writeText("Keep ${it.name}")
                }
            val unknownBucket = File(exports, "unrelated").apply { mkdirs() }
            val unknown = published(unknownBucket)
            val originals = (protected + unknown).associateWith(File::readBytes)
            val bytes = completed.sumOf { StorageFileTree.sizeBytes(requireNotNull(it.parentFile), files) }

            assertEquals(SharedCopySummary(3, bytes, 1), ShareExportFiles.summary(exports, files))
            val result = ShareExportFiles.removeCompleted(exports, files)

            assertEquals(3, result.removedCount)
            assertEquals(bytes, result.removedBytes)
            assertEquals(0, result.failedCount)
            assertEquals(SharedCopySummary(activeCount = 1), result.remaining)
            completed.forEach { assertFalse(requireNotNull(it.parentFile).exists()) }
            originals.forEach { (file, expected) -> assertArrayEquals(expected, file.readBytes()) }
            assertArrayEquals(byteArrayOf(9), pending.readBytes())
            ShareExportFiles.finish(pending)
        }

    @Test
    fun aWriterAndItsCompletedButUnlaunchedHandoffRemainProtectedUntilFinish() =
        runBlocking {
            val files = temporary.newFolder("files")
            val exports = File(files, "exports").apply { mkdirs() }
            val bucket = File(exports, "19").apply { mkdirs() }
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val writing =
                async {
                    ShareExportFiles.create(bucket, "Artwork.png") {
                        it.write(1)
                        started.complete(Unit)
                        release.await()
                        it.write(2)
                    }
                }
            try {
                withTimeout(10_000) { started.await() }
                assertEquals(1, ShareExportFiles.removeCompleted(exports, files).remaining.activeCount)
                release.complete(Unit)
                val file = writing.await()
                assertEquals(0, ShareExportFiles.removeCompleted(exports, files).removedCount)
                ShareExportFiles.markPublished(file)
                assertEquals(0, ShareExportFiles.removeCompleted(exports, files).removedCount)
                assertArrayEquals(byteArrayOf(1, 2), file.readBytes())
                ShareExportFiles.finish(file)

                assertEquals(1, ShareExportFiles.removeCompleted(exports, files).removedCount)
                assertFalse(file.exists())
            } finally {
                release.complete(Unit)
                writing.cancel()
            }
        }

    @Test
    fun markingAHandoffDoesNotWaitForAnUnrelatedBlockedCleanupScan() =
        runBlocking {
            val files = temporary.newFolder("files")
            val exports = File(files, "exports").apply { mkdirs() }
            val bucket = File(exports, "19").apply { mkdirs() }
            val file = ShareExportFiles.create(bucket, "Artwork.png") { it.write(1) }
            val scanning = CountDownLatch(1)
            val release = CountDownLatch(1)
            val blockedRoot =
                object : File(exports.absolutePath) {
                    override fun toPath(): Path {
                        scanning.countDown()
                        check(release.await(10, TimeUnit.SECONDS)) { "Cleanup scan was not released" }
                        return super.toPath()
                    }
                }
            val cleanup = async(Dispatchers.IO) { ShareExportFiles.removeCompleted(blockedRoot, files) }
            try {
                assertTrue(withContext(Dispatchers.IO) { scanning.await(10, TimeUnit.SECONDS) })
                withTimeout(2_000) {
                    withContext(Dispatchers.Default) { ShareExportFiles.markPublished(file) }
                }
                assertFalse(cleanup.isCompleted)
            } finally {
                release.countDown()
                cleanup.await()
                ShareExportFiles.finish(file)
            }
            assertTrue(file.exists())
            assertEquals(1, ShareExportFiles.removeCompleted(exports, files).removedCount)
        }

    @Test
    fun ownershipMarkersLetExplicitCleanupReclaimPreparationsAbandonedByAPreviousProcess() =
        runBlocking {
            val files = temporary.newFolder("files")
            val exports = File(files, "exports").apply { mkdirs() }
            val unfinished = File(exports, "19/share-abandoned").apply { mkdirs() }
            File(unfinished, ShareExportFiles.OWNER_MARKER).writeText("Interrupted.png")
            File(unfinished, "Interrupted.png").writeBytes(byteArrayOf(3, 4))
            val notStarted = File(exports, "brushes/share-unwritten").apply { mkdirs() }
            File(notStarted, ShareExportFiles.OWNER_MARKER).writeText("Interrupted.artbrush")
            val before = StorageFileTree.sizeBytes(exports, files)

            val result = ShareExportFiles.removeCompleted(exports, files)

            assertEquals(2, result.removedCount)
            assertEquals(before, result.removedBytes)
            assertEquals(SharedCopySummary(), result.remaining)
            assertFalse(unfinished.exists())
            assertFalse(notStarted.exists())
        }

    @Test
    fun failedFinalizationReleasesTheRequestAndKeepsItsMarkerForExplicitRetry() =
        runBlocking {
            val files = temporary.newFolder("files")
            val exports = File(files, "exports").apply { mkdirs() }
            val bucket = File(exports, "19").apply { mkdirs() }
            val file = ShareExportFiles.create(bucket, "Artwork.png") { it.write(byteArrayOf(1, 2, 3)) }
            val sibling = File(file.parentFile, "Other.txt").apply { writeText("Not owned by this share") }

            val failure = runCatching { ShareExportFiles.finish(file) }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
            assertTrue(File(file.parentFile, ShareExportFiles.OWNER_MARKER).isFile)
            val blocked = ShareExportFiles.removeCompleted(exports, files)
            assertEquals(0, blocked.remaining.activeCount)
            assertEquals(1, blocked.failedCount)
            assertEquals("Not owned by this share", sibling.readText())
            assertTrue(sibling.delete())

            val retried = ShareExportFiles.removeCompleted(exports, files)
            assertEquals(1, retried.removedCount)
            assertEquals(0, retried.failedCount)
            assertFalse(file.exists())
        }

    @Test
    fun failedWriterRetainsTheOriginalFailureAndItsCleanupDiagnostic() =
        runBlocking {
            val files = temporary.newFolder("files")
            val exports = File(files, "exports").apply { mkdirs() }
            val bucket = File(exports, "19").apply { mkdirs() }
            val expected = IOException("The encoder failed")
            val failure =
                runCatching {
                    ShareExportFiles.create(bucket, "Artwork.png") {
                        it.write(1)
                        File(bucket.listFiles()!!.single(), "Other.txt").writeText("Keep")
                        throw expected
                    }
                }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertEquals(expected.message, failure?.message)
            assertTrue(expected.suppressed.any { it is IOException && it.message == "The shared-copy directory contains other files" })
            assertEquals(0, ShareExportFiles.summary(exports, files).activeCount)
            val directory = bucket.listFiles()!!.single()
            assertTrue(File(directory, "Other.txt").delete())
            assertEquals(1, ShareExportFiles.removeCompleted(exports, files).removedCount)
        }

    @Test
    fun cleanupReportsPartialFailureAndKeepsTheUnreadableCopyAndItsUnexpectedSibling() =
        runBlocking {
            val files = temporary.newFolder("files")
            val exports = File(files, "exports").apply { mkdirs() }
            val bucket = File(exports, "19").apply { mkdirs() }
            val good = published(bucket)
            val bad = published(bucket)
            val unexpected = File(bad.parentFile, "Other.png").apply { writeBytes(byteArrayOf(8, 9)) }
            val before = StorageFileTree.sizeBytes(exports, files)
            val result = ShareExportFiles.removeCompleted(exports, files)

            assertEquals(1, result.removedCount)
            assertEquals(1, result.failedCount)
            assertEquals(1, result.remaining.unreadableCount)
            assertEquals(before - result.removedBytes, StorageFileTree.sizeBytes(exports, files))
            assertFalse(good.exists())
            assertArrayEquals(byteArrayOf(1, 2, 3), bad.readBytes())
            assertArrayEquals(byteArrayOf(8, 9), unexpected.readBytes())
        }

    @Test
    fun linkedBucketsMarkersAndPayloadsNeverReachFilesOutsideTheExportRoot() =
        runBlocking {
            val files = temporary.newFolder("files")
            val exports = File(files, "exports").apply { mkdirs() }
            val outside = temporary.newFolder("outside")
            val source = File(outside, "Private.png").apply { writeBytes(byteArrayOf(7, 8, 9)) }
            Files.createSymbolicLink(File(exports, "19").toPath(), outside.toPath())
            val palettes = File(exports, "palettes").apply { mkdirs() }
            val payload = published(palettes)
            assertTrue(payload.delete())
            Files.createSymbolicLink(payload.toPath(), source.toPath())
            val brush = published(File(exports, "brushes").apply { mkdirs() })
            val marker = File(brush.parentFile, ShareExportFiles.OWNER_MARKER)
            assertTrue(marker.delete())
            Files.createSymbolicLink(marker.toPath(), source.toPath())

            val result = ShareExportFiles.removeCompleted(exports, files)

            assertEquals(0, result.removedCount)
            assertEquals(3, result.failedCount)
            assertArrayEquals(byteArrayOf(7, 8, 9), source.readBytes())
            assertTrue(Files.isSymbolicLink(payload.toPath()))
            assertTrue(Files.isSymbolicLink(marker.toPath()))
            assertTrue(brush.exists())
        }

    @Test
    fun linkedRootInvalidMarkersAndTheReservedMarkerFilenameCannotAuthorizeDeletion() =
        runBlocking {
            val files = temporary.newFolder("files")
            val outside = temporary.newFolder("outside")
            val exports = File(files, "exports")
            Files.createSymbolicLink(exports.toPath(), outside.toPath())
            assertTrue(runCatching { ShareExportFiles.removeCompleted(exports, files) }.exceptionOrNull() is IOException)
            assertTrue(exports.delete())
            assertTrue(exports.mkdir())
            val bucket = File(exports, "19").apply { mkdirs() }
            assertTrue(runCatching { ShareExportFiles.create(bucket, ShareExportFiles.OWNER_MARKER) { it.write(1) } }.isFailure)
            val invalid = File(bucket, "share-invalid").apply { mkdirs() }
            val marker = File(invalid, ShareExportFiles.OWNER_MARKER).apply { writeText("../Private.png") }
            val source = File(bucket, "Private.png").apply { writeText("Keep") }

            val result = ShareExportFiles.removeCompleted(exports, files)

            assertEquals(0, result.removedCount)
            assertEquals(1, result.failedCount)
            assertEquals("Keep", source.readText())
            assertTrue(marker.exists())
        }

    private suspend fun published(
        bucket: File,
        name: String = "Artwork.png",
    ): File =
        ShareExportFiles.create(bucket, name) { it.write(byteArrayOf(1, 2, 3)) }.also {
            ShareExportFiles.markPublished(it)
            ShareExportFiles.finish(it)
        }
}
