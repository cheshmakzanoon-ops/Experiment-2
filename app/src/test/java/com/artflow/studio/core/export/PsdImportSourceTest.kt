package com.artflow.studio.core.export

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PsdImportSourceTest {
    @Test
    fun aSourceAtTheAdmittedLimitIsReturnedExactlyAndClosedOnce() {
        val bytes = ByteArray(1024) { (it % 251).toByte() }
        val input = TrackingStream(bytes)
        var opens = 0
        val result =
            PsdImportSource.read(SMALL_BUDGET) {
                opens++
                input
            }
        assertArrayEquals(bytes, result)
        assertEquals(1, opens)
        assertEquals(1, input.closes)
    }

    @Test
    fun aSourceThatExceedsTheAdmittedLimitIsStoppedBeforeBufferGrowth() {
        val input = TrackingStream(ByteArray(4096))
        val failure = assertThrows(IllegalArgumentException::class.java) { PsdImportSource.read(SMALL_BUDGET) { input } }
        assertTrue(failure.message.orEmpty().contains("available memory"))
        assertEquals(1025, input.consumed)
        assertEquals(1, input.closes)
    }

    @Test
    fun insufficientHeadroomRefusesBeforeOpeningTheProvider() {
        var opened = false
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                PsdImportSource.read(1024L * 1024) {
                    opened = true
                    null
                }
            }
        assertFalse(opened)
        assertTrue(failure.message.orEmpty().contains("Not enough memory"))
    }

    @Test
    fun anAdmittedSourceRejectsACompetingProviderBeforeItOpens() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val input = TrackingStream(byteArrayOf(17))
        val preparing =
            worker.submit<ByteArray> {
                PsdImportSource.read(SMALL_BUDGET) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    input
                }
            }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            var opened = false
            val failure =
                assertThrows(IllegalStateException::class.java) {
                    PsdImportSource.read(SMALL_BUDGET) {
                        opened = true
                        null
                    }
                }
            assertFalse(opened)
            assertTrue(failure.message.orEmpty().contains("try again"))
        } finally {
            release.countDown()
            try {
                assertArrayEquals(byteArrayOf(17), preparing.get(5, TimeUnit.SECONDS))
                assertEquals(1, input.closes)
            } finally {
                worker.shutdownNow()
            }
        }
        assertArrayEquals(byteArrayOf(23), PsdImportSource.read(SMALL_BUDGET) { ByteArrayInputStream(byteArrayOf(23)) })
    }

    @Test
    fun ioFailureAndCancellationCloseTheirSourceWithoutChangingTheFailure() {
        listOf(IOException("Provider disconnected"), CancellationException("Import cancelled")).forEach { failure ->
            val input =
                object : TrackingStream(byteArrayOf()) {
                    override fun read(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int = throw failure
                }
            val thrown = assertThrows(failure.javaClass) { PsdImportSource.read(SMALL_BUDGET) { input } }
            assertSame(failure, thrown)
            assertEquals(1, input.closes)
        }
    }

    @Test
    fun aProviderReturningZeroStillMakesProgress() {
        val input =
            object : TrackingStream(byteArrayOf(1, 2, 3)) {
                override fun read(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int = 0
            }
        assertArrayEquals(byteArrayOf(1, 2, 3), PsdImportSource.read(SMALL_BUDGET) { input })
        assertEquals(1, input.closes)
    }

    @Test
    fun anUnexpectedAllocationFailureIsExplainedAfterTheSourceCloses() {
        val failure = OutOfMemoryError("Injected provider allocation failure")
        val input =
            object : TrackingStream(byteArrayOf()) {
                override fun read(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int = throw failure
            }
        val explained = assertThrows(IllegalArgumentException::class.java) { PsdImportSource.read(SMALL_BUDGET) { input } }
        assertSame(failure, explained.cause)
        assertTrue(explained.message.orEmpty().contains("Not enough memory"))
        assertEquals(1, input.closes)
        assertArrayEquals(byteArrayOf(29), PsdImportSource.read(SMALL_BUDGET) { ByteArrayInputStream(byteArrayOf(29)) })
    }

    @Test
    fun aMissingProviderSourceHasAReadableRefusal() {
        val failure = assertThrows(IllegalArgumentException::class.java) { PsdImportSource.read(SMALL_BUDGET) { null } }
        assertEquals("That PSD could not be opened", failure.message)
    }

    private open class TrackingStream(
        bytes: ByteArray,
    ) : ByteArrayInputStream(bytes) {
        var consumed = 0
        var closes = 0

        override fun read(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ): Int = super.read(bytes, offset, length).also { if (it > 0) consumed += it }

        override fun close() {
            closes++
            super.close()
        }
    }

    private companion object {
        const val SMALL_BUDGET = 1024L * 1024 + 4 * 1024
    }
}
