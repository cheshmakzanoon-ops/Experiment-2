package com.artflow.studio.data.export

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class StagedImportTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun theConsumerOwnsOneCompleteFileAndSuccessRemovesIt() =
        runTest {
            val expected = ByteArray(150_000) { (it % 251).toByte() }
            var staged: File? = null
            var closed = false
            val input =
                object : ByteArrayInputStream(expected) {
                    override fun close() {
                        closed = true
                        super.close()
                    }
                }
            val result =
                StagedImport.use(temporary.root, { input }, maxBytes = expected.size.toLong()) { file ->
                    staged = file
                    assertTrue(closed)
                    assertArrayEquals(expected, file.readBytes())
                    17
                }
            assertEquals(17, result)
            assertFalse(requireNotNull(staged).exists())
            assertTrue(
                temporary.root
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
        }

    @Test
    fun anOversizedInputStopsAtTheBoundaryAndNeverReachesTheConsumer() =
        runTest {
            var read = 0
            var consumed = false
            val input =
                object : InputStream() {
                    override fun read(): Int {
                        read++
                        return 1
                    }
                }
            val failure =
                runCatching {
                    StagedImport.use(temporary.root, { input }, maxBytes = 8) { consumed = true }
                }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals(9, read)
            assertFalse(consumed)
            assertTrue(
                temporary.root
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
        }

    @Test
    fun cancellationWhileCopyingClosesTheSourceAndRemovesThePartialFile() =
        runTest {
            lateinit var worker: Job
            var closed = false
            var consumed = false
            val input =
                object : InputStream() {
                    override fun read(): Int = error("The copy uses bounded chunks")

                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        buffer[offset] = 1
                        worker.cancel()
                        return 1
                    }

                    override fun close() {
                        closed = true
                    }
                }
            worker =
                launch(start = CoroutineStart.LAZY) {
                    StagedImport.use(temporary.root, { input }) { consumed = true }
                }
            worker.start()
            worker.join()
            assertTrue(worker.isCancelled)
            assertTrue(closed)
            assertFalse(consumed)
            assertTrue(
                temporary.root
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
        }

    @Test
    fun cancellationAfterStagingRemovesTheFileEvenWithACancelledCaller() =
        runTest {
            val started = CompletableDeferred<File>()
            val worker =
                launch {
                    StagedImport.use(temporary.root, { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }) { file ->
                        started.complete(file)
                        awaitCancellation()
                    }
                }
            val file = started.await()
            assertTrue(file.isFile)
            worker.cancelAndJoin()
            assertFalse(file.exists())
            assertTrue(
                temporary.root
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
        }

    @Test
    fun providerAndConsumerFailuresDoNotLeaveFilesBehind() =
        runTest {
            val source =
                object : InputStream() {
                    override fun read(): Int = throw IOException("Provider disconnected")
                }
            assertTrue(runCatching { StagedImport.use(temporary.root, { source }) {} }.exceptionOrNull() is IOException)
            val failure =
                runCatching {
                    StagedImport.use(temporary.root, { ByteArrayInputStream(byteArrayOf(1)) }) {
                        throw IllegalStateException("The package is invalid")
                    }
                }.exceptionOrNull()
            assertEquals("The package is invalid", failure?.message)
            assertTrue(
                temporary.root
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
        }

    @Test
    fun zeroLengthProviderReadsMakeForwardProgressAndRespectTheExactLimit() =
        runTest {
            val expected = byteArrayOf(7, 11, 19)
            var offset = 0
            var bulkReads = 0
            val source =
                object : InputStream() {
                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        check(++bulkReads <= expected.size + 1) { "The staging copy made no progress" }
                        return 0
                    }

                    override fun read(): Int = if (offset < expected.size) expected[offset++].toInt() else -1
                }
            StagedImport.use(temporary.root, { source }, maxBytes = expected.size.toLong()) { file ->
                assertArrayEquals(expected, file.readBytes())
            }
            assertEquals(expected.size + 1, bulkReads)
            assertTrue(
                temporary.root
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
        }

    @Test
    fun aZeroLengthBulkReadCannotBypassTheSizeLimit() =
        runTest {
            var singleReads = 0
            var consumed = false
            val source =
                object : InputStream() {
                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int = 0

                    override fun read(): Int = if (++singleReads < 5) 1 else error("Read beyond the admission boundary")
                }
            val failure =
                runCatching {
                    StagedImport.use(temporary.root, { source }, maxBytes = 3) { consumed = true }
                }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals(4, singleReads)
            assertFalse(consumed)
            assertTrue(
                temporary.root
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
        }

    @Test
    fun cleanupFailureIsSuppressedBehindTheOriginalConsumerFailureOrCancellation() =
        runTest {
            listOf(IllegalArgumentException("Invalid package"), CancellationException("Cancelled import")).forEach { primary ->
                val failure =
                    runCatching {
                        StagedImport.use(temporary.root, { ByteArrayInputStream(byteArrayOf(1)) }) { file ->
                            check(file.delete() && file.mkdir())
                            File(file, "cleanup-blocker").writeText("Keep this child to reject directory deletion")
                            throw primary
                        }
                    }.exceptionOrNull()
                assertSame(primary, failure)
                assertEquals(1, primary.suppressed.size)
                assertEquals("Cannot remove the temporary import file", primary.suppressed.single().message)
            }
        }

    @Test
    fun cleanupFailureAfterSuccessfulConsumptionStillSurfaces() =
        runTest {
            val failure =
                runCatching {
                    StagedImport.use(temporary.root, { ByteArrayInputStream(byteArrayOf(1)) }) { file ->
                        check(file.delete() && file.mkdir())
                        File(file, "cleanup-blocker").writeText("Keep this child to reject directory deletion")
                        42
                    }
                }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals("Cannot remove the temporary import file", failure?.message)
        }

    @Test
    fun cancellationDuringAZeroLengthReadDoesNotRequestAnotherByte() =
        runTest {
            lateinit var worker: Job
            var fallbackReads = 0
            var consumed = false
            val input =
                object : InputStream() {
                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        worker.cancel()
                        return 0
                    }

                    override fun read(): Int {
                        fallbackReads++
                        return 1
                    }
                }
            worker =
                launch(start = CoroutineStart.LAZY) {
                    StagedImport.use(temporary.root, { input }) { consumed = true }
                }
            worker.start()
            worker.join()
            assertTrue(worker.isCancelled)
            assertEquals(0, fallbackReads)
            assertFalse(consumed)
            assertTrue(
                temporary.root
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
        }
}
