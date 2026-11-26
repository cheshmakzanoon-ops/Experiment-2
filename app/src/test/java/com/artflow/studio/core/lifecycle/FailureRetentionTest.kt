package com.artflow.studio.core.lifecycle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.IdentityHashMap

/** Deterministic failure ordering for the production scopes used by MP4 codec, muxer and output buffers. */
class FailureRetentionTest {
    @Test
    fun successReturnsValueAfterReleasingExactlyOnce() {
        val events = mutableListOf<String>()
        val result =
            withCleanup(release(events, "resource")) {
                events.add("operation")
                37
            }

        assertEquals(37, result)
        assertEquals(listOf("operation", "resource"), events)
    }

    @Test
    fun operationFailureRetainsEveryCleanupFailureInReleaseOrder() {
        val original = IOException("Sample write failed")
        val output = IllegalStateException("Output release failed")
        val muxer = RuntimeException("Muxer finalization failed")
        val codec = IllegalStateException("Codec release failed")
        val events = mutableListOf<String>()

        val failure =
            runCatching {
                withCleanup(release(events, "codec", codec)) {
                    withCleanup(release(events, "muxer", muxer)) {
                        withCleanup(release(events, "output", output)) { throw original }
                    }
                }
            }.exceptionOrNull()

        assertSame(original, failure)
        assertArrayEquals(arrayOf(output, muxer, codec), original.suppressed)
        assertEquals(listOf("output", "muxer", "codec"), events)
    }

    @Test
    fun firstCleanupFailureRejectsSuccessAndRetainsLaterFailures() {
        val output = IllegalStateException("Output release failed")
        val muxer = RuntimeException("Muxer finalization failed")
        val codec = IllegalStateException("Codec release failed")
        val events = mutableListOf<String>()
        var published = false

        val failure =
            runCatching {
                withCleanup(release(events, "codec", codec)) {
                    withCleanup(release(events, "muxer", muxer)) {
                        withCleanup(release(events, "output", output)) { events.add("encoded") }
                    }
                }
                published = true
            }.exceptionOrNull()

        assertSame(output, failure)
        assertArrayEquals(arrayOf(muxer, codec), output.suppressed)
        assertEquals(listOf("encoded", "output", "muxer", "codec"), events)
        assertFalse(published)
    }

    @Test
    fun failedInnerAcquisitionStillReleasesAcquiredResource() {
        val acquisition = IOException("Cannot open muxer output")
        val codec = IllegalStateException("Codec release failed")
        val events = mutableListOf<String>()

        val failure =
            runCatching {
                events.add("codec acquired")
                withCleanup(release(events, "codec", codec)) {
                    events.add("muxer acquisition")
                    throw acquisition
                }
            }.exceptionOrNull()

        assertSame(acquisition, failure)
        assertArrayEquals(arrayOf(codec), acquisition.suppressed)
        assertEquals(listOf("codec acquired", "muxer acquisition", "codec"), events)
    }

    @Test
    fun fatalOperationAndCleanupErrorsDoNotPreventOuterRelease() {
        val original = OutOfMemoryError("Encoder allocation failed")
        val muxer = AssertionError("Muxer release failed")
        val codec = IllegalStateException("Codec release failed")
        val events = mutableListOf<String>()

        val failure =
            runCatching {
                withCleanup(release(events, "codec", codec)) {
                    withCleanup(release(events, "muxer", muxer)) { throw original }
                }
            }.exceptionOrNull()

        assertSame(original, failure)
        assertArrayEquals(arrayOf(muxer, codec), original.suppressed)
        assertEquals(listOf("muxer", "codec"), events)
    }

    @Test
    fun cancellationDuringSuspensionReleasesEverythingAndStaysCancellation() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val original = CancellationException("Editor closed")
            val muxer = RuntimeException("Muxer finalization failed")
            val codec = IllegalStateException("Codec release failed")
            val events = mutableListOf<String>()
            val operation =
                async {
                    withCleanup(release(events, "codec", codec)) {
                        withCleanup(release(events, "muxer", muxer)) {
                            started.complete(Unit)
                            awaitCancellation()
                        }
                    }
                }
            started.await()
            operation.cancel(original)
            val failure = runCatching { operation.await() }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertTrue(causes(requireNotNull(failure)).any { it === original })
            assertArrayEquals(arrayOf(muxer, codec), original.suppressed)
            assertEquals(listOf("muxer", "codec"), events)
        }

    @Test
    fun cancellationDiagnosticsSurviveDispatcherDelivery() =
        runBlocking { verifyDispatcherDelivery(CancellationException("Export cancelled")) }

    @Test
    fun encodingFailureDiagnosticsSurviveDispatcherDelivery() = runBlocking { verifyDispatcherDelivery(IOException("Frame decode failed")) }

    @Test
    fun rethrowingOriginalDuringCleanupDoesNotSelfSuppressOrPreventOuterRelease() {
        val original = IOException("Codec failed")
        val codec = IllegalStateException("Codec release failed")
        val events = mutableListOf<String>()

        val failure =
            runCatching {
                withCleanup(release(events, "codec", codec)) {
                    withCleanup(release(events, "muxer", original)) { throw original }
                }
            }.exceptionOrNull()

        assertSame(original, failure)
        assertArrayEquals(arrayOf(codec), original.suppressed)
        assertEquals(listOf("muxer", "codec"), events)
    }

    @Test
    fun repeatedCleanupFailureIsRetainedOnceWithoutChangingOrder() {
        val original = IOException("Sample write failed")
        val shared = IllegalStateException("Codec failed")
        val codec = IllegalStateException("Codec release failed")
        val events = mutableListOf<String>()

        val failure =
            runCatching {
                withCleanup(release(events, "codec", codec)) {
                    withCleanup(release(events, "muxer", shared)) {
                        withCleanup(release(events, "output", shared)) { throw original }
                    }
                }
            }.exceptionOrNull()

        assertSame(original, failure)
        assertArrayEquals(arrayOf(shared, codec), original.suppressed)
        assertEquals(listOf("output", "muxer", "codec"), events)
    }

    @Test
    fun nonLocalReturnStillRunsEveryCleanup() {
        val events = mutableListOf<String>()

        assertEquals(37, returnFromNestedScopes(events))
        assertEquals(listOf("muxer", "codec"), events)
    }

    private suspend fun verifyDispatcherDelivery(original: Exception) {
        val muxer = RuntimeException("Muxer finalization failed")
        val codec = IllegalStateException("Codec release failed")
        val events = mutableListOf<String>()
        val failure =
            runCatching {
                withContext(Dispatchers.IO) {
                    withCleanup(release(events, "codec", codec)) {
                        withCleanup(release(events, "muxer", muxer)) {
                            withContext(Dispatchers.Default) { throw original }
                        }
                    }
                }
            }.exceptionOrNull()

        assertEquals(original.javaClass, failure?.javaClass)
        assertEquals(original.message, failure?.message)
        assertTrue(causes(requireNotNull(failure)).any { it === original })
        assertArrayEquals(arrayOf(muxer, codec), original.suppressed)
        assertEquals(listOf("muxer", "codec"), events)
    }

    private fun release(
        events: MutableList<String>,
        name: String,
        failure: Throwable? = null,
    ): () -> Unit =
        {
            events.add(name)
            if (failure != null) throw failure
        }

    private fun returnFromNestedScopes(events: MutableList<String>): Int {
        withCleanup(release(events, "codec")) {
            withCleanup(release(events, "muxer")) { return 37 }
        }
    }

    private fun causes(failure: Throwable): List<Throwable> {
        val visited = IdentityHashMap<Throwable, Unit>()
        val result = mutableListOf<Throwable>()
        var current: Throwable? = failure
        while (current != null && visited.put(current, Unit) == null) {
            result.add(current)
            current = current.cause
        }
        return result
    }
}
