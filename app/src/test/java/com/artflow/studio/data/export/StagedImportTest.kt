package com.artflow.studio.data.export

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
}
