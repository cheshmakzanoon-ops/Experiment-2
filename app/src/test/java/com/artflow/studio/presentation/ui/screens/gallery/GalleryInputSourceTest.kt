package com.artflow.studio.presentation.ui.screens.gallery

import com.artflow.studio.core.pixels.ImportPreparation
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GalleryInputSourceTest {
    @Test
    fun exactAdmittedInputIsPreservedAndClosed() {
        val bytes = ByteArray(256) { it.toByte() }
        val input = TrackedInput(bytes)
        assertArrayEquals(bytes, GalleryInputSource.read({ input }, 1024))
        assertTrue(input.closed)
        assertEquals(256, input.produced)
    }

    @Test
    fun oversizedInputStopsAtOneExtraByteAndReleasesItsAdmission() {
        val input = TrackedInput(ByteArray(4096))
        val failure = assertThrows(IllegalArgumentException::class.java) { GalleryInputSource.read({ input }, 1024) }
        assertEquals(GalleryInputSource.MEMORY_MESSAGE, failure.message)
        assertEquals(257, input.produced)
        assertTrue(input.closed)
        assertArrayEquals(byteArrayOf(1, 2), GalleryInputSource.read({ ByteArrayInputStream(byteArrayOf(1, 2)) }, 8))
    }

    @Test
    fun exhaustedOrInvalidAllowanceDoesNotOpenTheProvider() {
        var opened = 0
        for (budget in listOf(0L, 3L, -1L, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                GalleryInputSource.read({
                    opened++
                    ByteArrayInputStream(byteArrayOf(1))
                }, budget)
            }
        }
        assertEquals(0, opened)
    }

    @Test
    fun busyAdmissionDoesNotOpenAnotherProvider() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val owner =
            executor.submit {
                ImportPreparation.withAdmission {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
        var opened = 0
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) {
                GalleryInputSource.read({
                    opened++
                    ByteArrayInputStream(byteArrayOf(1))
                }, 1024)
            }
            assertEquals(0, opened)
        } finally {
            release.countDown()
            try {
                owner.get(5, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun zeroBulkReadsProgressWithoutLosingBytesOrTheLimit() {
        val input = ZeroReadInput(byteArrayOf(4, 3, 2, 1))
        assertArrayEquals(byteArrayOf(4, 3, 2, 1), GalleryInputSource.read({ input }, 16))
        assertTrue(input.closed)
        assertEquals(4, input.produced)
        val oversized = ZeroReadInput(byteArrayOf(4, 3, 2, 1, 0))
        assertThrows(IllegalArgumentException::class.java) { GalleryInputSource.read({ oversized }, 16) }
        assertEquals(5, oversized.produced)
        assertTrue(oversized.closed)
    }

    @Test
    fun cancellationAfterAZeroReadClosesTheProviderWithoutFallbackOrPublication() {
        var cancelled = false
        val input = ZeroReadInput(byteArrayOf(1), afterBulk = { cancelled = true })
        assertThrows(CancellationException::class.java) {
            GalleryInputSource.read({ input }, 1024) {
                if (cancelled) throw CancellationException("Input cancelled")
            }
        }
        assertEquals(0, input.produced)
        assertTrue(input.closed)
        assertArrayEquals(byteArrayOf(7), GalleryInputSource.read({ ByteArrayInputStream(byteArrayOf(7)) }, 4))
    }

    private class TrackedInput(
        bytes: ByteArray,
    ) : ByteArrayInputStream(bytes) {
        var produced = 0
        var closed = false

        override fun read(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ): Int = super.read(bytes, offset, length).also { if (it > 0) produced += it }

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class ZeroReadInput(
        bytes: ByteArray,
        private val afterBulk: () -> Unit = {},
    ) : InputStream() {
        private val source = ByteArrayInputStream(bytes)
        var produced = 0
        var closed = false

        override fun read(): Int = source.read().also { if (it >= 0) produced++ }

        override fun read(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            afterBulk()
            return 0
        }

        override fun close() {
            closed = true
        }
    }
}
