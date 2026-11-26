package com.artflow.studio.data.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Real files and dispatcher handoffs: successful shares remain readable until explicitly removed. */
class ShareExportFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun repeatedSharesKeepTheOriginalNameAndEveryEarlierCopy() =
        runBlocking {
            val directory = temporary.newFolder("exports")
            val source = temporary.newFile("canvas.png")
            val firstBytes = ByteArray(8192 * 19 + 3) { (it * 31).toByte() }
            source.writeBytes(firstBytes)
            val first = ShareExportFiles.copy(directory, "Ink study.png", source)
            ShareExportFiles.markPublished(first)
            ShareExportFiles.finish(first)
            assertTrue(requireNotNull(first.parentFile).setLastModified(1))
            val newerBytes = firstBytes.reversedArray()
            source.writeBytes(newerBytes)

            val second = ShareExportFiles.copy(directory, first.name, source)
            ShareExportFiles.markPublished(second)
            ShareExportFiles.finish(second)

            assertEquals("Ink study.png", second.name)
            assertNotEquals(first, second)
            assertArrayEquals(firstBytes, first.readBytes())
            assertArrayEquals(newerBytes, second.readBytes())
            assertArrayEquals(newerBytes, source.readBytes())
            assertEquals(2, directory.listFiles()!!.size)
        }

    @Test
    fun overlappingWritersWithTheSameNameNeverReplaceEachOthersContent() =
        runBlocking {
            val directory = temporary.newFolder("exports")
            val ready = AtomicInteger()
            val allReady = CompletableDeferred<Unit>()
            val files =
                (1..4)
                    .map { value ->
                        async {
                            ShareExportFiles.create(directory, "Swatches.ase") { output ->
                                output.write(value)
                                if (ready.incrementAndGet() == 4) allReady.complete(Unit)
                                withTimeout(10_000) { allReady.await() }
                                output.write(value + 1)
                            }
                        }
                    }.awaitAll()

            assertEquals(files.size, files.toSet().size)
            files.forEachIndexed { index, file ->
                ShareExportFiles.markPublished(file)
                ShareExportFiles.finish(file)
                assertEquals("Swatches.ase", file.name)
                assertArrayEquals(byteArrayOf((index + 1).toByte(), (index + 2).toByte()), file.readBytes())
            }
        }

    @Test
    fun cancellationDuringWritingDeletesOnlyThatUnpublishedShare() =
        runBlocking {
            val directory = temporary.newFolder("exports")
            val previous = ShareExportFiles.create(directory, "Artwork.png") { it.write(byteArrayOf(7, 8, 9)) }
            ShareExportFiles.markPublished(previous)
            ShareExportFiles.finish(previous)
            val writing = CompletableDeferred<Unit>()
            val operation =
                async {
                    ShareExportFiles.create(directory, previous.name) {
                        it.write(byteArrayOf(1, 2, 3))
                        writing.complete(Unit)
                        awaitCancellation()
                    }
                }
            withTimeout(10_000) { writing.await() }
            operation.cancelAndJoin()

            assertTrue(operation.isCancelled)
            assertArrayEquals(byteArrayOf(7, 8, 9), previous.readBytes())
            assertEquals(listOf(previous.parentFile), directory.listFiles()!!.toList())
        }

    @Test
    fun cancellationAfterWritingStillRemovesTheUnpublishedFile() =
        runBlocking {
            val directory = temporary.newFolder("exports")
            val operation =
                async {
                    ShareExportFiles.create(directory, "Artwork.png") {
                        it.write(byteArrayOf(1, 2, 3))
                        coroutineContext.cancel(CancellationException("Gallery closed"))
                    }
                }
            val failure = runCatching { operation.await() }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertTrue(directory.listFiles()!!.isEmpty())
        }

    @Test
    fun cancelledDeliveryToTheUiRemovesTheAlreadyCompletedFile() =
        runBlocking {
            val directory = temporary.newFolder("exports")
            val resumes = LinkedBlockingQueue<Runnable>()
            val ui =
                object : CoroutineDispatcher() {
                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable,
                    ) {
                        resumes.add(block)
                    }
                }
            var delivered = false
            val operation =
                async(ui, start = CoroutineStart.UNDISPATCHED) {
                    ShareExportFiles.create(directory, "Artwork.png") { it.write(byteArrayOf(1, 2, 3)) }
                    delivered = true
                }
            val completedWrite = requireNotNull(resumes.poll(10, TimeUnit.SECONDS))
            val pending = directory.walkTopDown().single { it.isFile && it.name == "Artwork.png" }
            assertArrayEquals(byteArrayOf(1, 2, 3), pending.readBytes())
            operation.cancel(CancellationException("Navigation before UI delivery"))
            completedWrite.run()
            while (!operation.isCompleted) requireNotNull(resumes.poll(10, TimeUnit.SECONDS)).run()

            assertTrue(operation.isCancelled)
            assertFalse(delivered)
            assertTrue(directory.listFiles()!!.isEmpty())
        }

    @Test
    fun aFailedWriterKeepsItsOriginalFailureAndRemovesItsDirectory() =
        runBlocking {
            val directory = temporary.newFolder("exports")
            val expected = IOException("Storage is full")

            val failure =
                runCatching {
                    ShareExportFiles.create(directory, "Artwork.png") {
                        it.write(1)
                        throw expected
                    }
                }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertEquals(expected.message, failure?.message)
            assertTrue(directory.listFiles()!!.isEmpty())
        }

    @Test
    fun emptyAndLinkedSourcesCannotLeaveAShareOrChangeTheSource() =
        runBlocking {
            val directory = temporary.newFolder("exports")
            val empty = temporary.newFile("empty.png")
            assertTrue(runCatching { ShareExportFiles.copy(directory, "Artwork.png", empty) }.isFailure)
            val source = temporary.newFile("private.png").apply { writeBytes(byteArrayOf(9, 7)) }
            val link = File(temporary.root, "linked.png")
            Files.createSymbolicLink(link.toPath(), source.toPath())
            assertTrue(runCatching { ShareExportFiles.copy(directory, "Artwork.png", link) }.isFailure)

            assertTrue(directory.listFiles()!!.isEmpty())
            assertTrue(empty.exists())
            assertArrayEquals(byteArrayOf(9, 7), source.readBytes())
        }

    @Test
    fun invalidNamesAndLinkedExportRootsCannotWriteOutsideTheirDirectory() =
        runBlocking {
            val directory = temporary.newFolder("exports")
            for (name in listOf("../escaped.png", "/absolute.png", "..", "", "bad\u0000name.png")) {
                assertTrue(runCatching { ShareExportFiles.create(directory, name) { it.write(1) } }.isFailure)
            }
            val linked = File(temporary.root, "linked")
            Files.createSymbolicLink(linked.toPath(), directory.toPath())
            assertTrue(runCatching { ShareExportFiles.create(linked, "Artwork.png") { it.write(1) } }.isFailure)

            assertTrue(directory.listFiles()!!.isEmpty())
            assertFalse(File(temporary.root, "escaped.png").exists())
        }
}
