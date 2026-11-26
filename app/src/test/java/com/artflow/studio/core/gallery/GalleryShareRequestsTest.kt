package com.artflow.studio.core.gallery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryShareRequestsTest {
    @Test
    fun deletionCancelsTheWholeOverlappingBatchAndDrainsCleanupBeforeRemovingFiles() =
        runTest {
            val requests = GalleryShareRequests()
            val cleanup = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val share =
                launch {
                    requests.run(listOf(1, 2)) {
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                events += "cleanup started"
                                cleanup.await()
                                events += "cleanup finished"
                            }
                        }
                    }
                }
            runCurrent()

            val deletion = requireNotNull(requests.beginDeletion(2))
            val remove = launch { deletion.complete { events += "files removed" } }
            runCurrent()

            assertEquals(listOf("cleanup started"), events)
            assertFalse(remove.isCompleted)
            assertNull(requests.beginDeletion(2))
            var startedAnotherShare = false
            val rejected = runCatching { requests.run(listOf(2)) { startedAnotherShare = true } }.exceptionOrNull()
            assertTrue(rejected is CancellationException)
            assertFalse(startedAnotherShare)

            cleanup.complete(Unit)
            remove.join()
            share.join()
            assertTrue(share.isCancelled)
            assertEquals(listOf("cleanup started", "cleanup finished", "files removed"), events)
        }

    @Test
    fun unrelatedShareAndGalleryScopeSurviveDeletionOfAnotherProject() =
        runTest {
            val requests = GalleryShareRequests()
            val release = CompletableDeferred<Unit>()
            val unrelated =
                async {
                    requests.run(listOf(9)) {
                        release.await()
                        "published"
                    }
                }
            val overlap = launch { requests.run(listOf(1)) { awaitCancellation() } }
            runCurrent()

            requireNotNull(requests.beginDeletion(1)).complete {}

            assertTrue(overlap.isCancelled)
            assertFalse(unrelated.isCancelled)
            release.complete(Unit)
            assertEquals("published", unrelated.await())
            assertEquals("new request", requests.run(listOf(3)) { "new request" })
        }

    @Test
    fun aFailedDeletionReleasesItsReservationForAnExplicitRetry() =
        runTest {
            val requests = GalleryShareRequests()
            val expected = IOException("Database unavailable")
            val failure =
                runCatching {
                    requireNotNull(requests.beginDeletion(1)).complete { throw expected }
                }.exceptionOrNull()

            assertSame(expected, failure)
            assertEquals("still available", requests.run(listOf(1)) { "still available" })
            var retried = false
            requireNotNull(requests.beginDeletion(1)).complete { retried = true }
            assertTrue(retried)
        }

    @Test
    fun completedOrFailedSharesDoNotStayRegisteredForLaterDeletion() =
        runTest {
            val requests = GalleryShareRequests()
            assertEquals("published", requests.run(listOf(1)) { "published" })
            val expected = IOException("Provider unavailable")
            assertSame(expected, runCatching { requests.run(listOf(1)) { throw expected } }.exceptionOrNull())

            var deleted = false
            requireNotNull(requests.beginDeletion(1)).complete { deleted = true }

            assertTrue(deleted)
            assertEquals("active", requests.run(listOf(3)) { "active" })
        }

    @Test
    fun cancelledBeforeDeletionBodyStartsStillReleasesItsReservation() =
        runTest {
            val requests = GalleryShareRequests()
            val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
            scope.cancel()
            val deletion = requireNotNull(requests.beginDeletion(1))
            var started = false
            val job = scope.launch { deletion.complete { started = true } }
            job.invokeOnCompletion { deletion.release() }

            job.join()

            assertFalse(started)
            assertEquals("available", requests.run(listOf(1)) { "available" })
        }

    @Test
    fun lateCompletionOfAnEarlierDeletionCannotReleaseItsReplacementReservation() =
        runTest {
            val requests = GalleryShareRequests()
            val previous = requireNotNull(requests.beginDeletion(1))
            previous.complete {}
            val replacement = requireNotNull(requests.beginDeletion(1))

            previous.release()

            assertTrue(runCatching { requests.run(listOf(1)) {} }.exceptionOrNull() is CancellationException)
            replacement.complete {}
            assertEquals("released", requests.run(listOf(1)) { "released" })
        }
}
