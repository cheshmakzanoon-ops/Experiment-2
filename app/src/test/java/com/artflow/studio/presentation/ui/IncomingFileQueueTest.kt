package com.artflow.studio.presentation.ui

import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class IncomingFileQueueTest {
    @Test
    fun oversizedBatchReportsRejectedFilesAndPreservesEveryAcceptedFileInOrder() =
        runTest {
            val queue = IncomingFileQueue()
            val uris = List(IncomingFiles.MAX_PENDING_FILES + 7) { mock(Uri::class.java) }

            assertEquals(IncomingFiles.Receipt(IncomingFiles.MAX_PENDING_FILES, 7), queue.offer(uris))
            assertEquals(uris.take(IncomingFiles.MAX_PENDING_FILES), queue.files.take(IncomingFiles.MAX_PENDING_FILES).toList())
            // Explicitly re-offering a refused file works after the artist processes the queue.
            assertEquals(IncomingFiles.Receipt(1, 0), queue.offer(listOf(uris.last())))
            assertEquals(uris.last(), queue.files.first())
        }

    @Test
    fun repeatedSharesWithoutAGalleryCollectorCannotEvictPreviouslyAcceptedWork() =
        runTest {
            val queue = IncomingFileQueue()
            val pending = List(IncomingFiles.MAX_PENDING_FILES) { mock(Uri::class.java) }
            pending.forEach { uri -> assertEquals(IncomingFiles.Receipt(1, 0), queue.offer(listOf(uri))) }
            val refused = mock(Uri::class.java)

            repeat(1_000) {
                assertEquals(IncomingFiles.Receipt(0, 1), queue.offer(listOf(refused)))
            }
            assertEquals(pending, queue.files.take(pending.size).toList())
        }

    @Test
    fun pausedImportRetainsPendingFilesAndResumesInOrderWithoutAcceptingOverflow() =
        runTest {
            val queue = IncomingFileQueue(capacity = 2)
            val uris = List(4) { mock(Uri::class.java) }
            assertEquals(IncomingFiles.Receipt(2, 0), queue.offer(uris.take(2)))
            val resume = CompletableDeferred<Unit>()
            val imported = mutableListOf<Uri>()
            val collector =
                launch {
                    queue.files.take(uris.size).collect { uri ->
                        imported += uri
                        if (imported.size == 1) resume.await()
                    }
                }
            runCurrent()

            assertEquals(listOf(uris.first()), imported)
            assertEquals(IncomingFiles.Receipt(1, 1), queue.offer(uris.drop(2)))
            assertEquals(IncomingFiles.Receipt(0, 1), queue.offer(listOf(uris.last())))
            resume.complete(Unit)
            runCurrent()
            assertEquals(uris.take(3), imported)

            assertEquals(IncomingFiles.Receipt(1, 0), queue.offer(listOf(uris.last())))
            collector.join()
            assertEquals(uris, imported)
        }

    @Test
    fun leavingGalleryAfterOneFileKeepsTheRemainingAcceptedWorkForTheNextVisit() =
        runTest {
            val queue = IncomingFileQueue(capacity = 2)
            val first = mock(Uri::class.java)
            val second = mock(Uri::class.java)
            val next = mock(Uri::class.java)
            assertEquals(IncomingFiles.Receipt(2, 0), queue.offer(listOf(first, second)))

            assertEquals(first, queue.files.first())
            assertEquals(IncomingFiles.Receipt(1, 0), queue.offer(listOf(next)))
            assertEquals(listOf(second, next), queue.files.take(2).toList())
        }

    @Test
    fun cancelledWaitingCollectorDoesNotCloseTheQueueOrLoseAFutureShare() =
        runTest {
            val queue = IncomingFileQueue(capacity = 1)
            val waiting = launch { queue.files.first() }
            runCurrent()
            waiting.cancel()
            waiting.join()
            assertTrue(waiting.isCancelled)

            val uri = mock(Uri::class.java)
            assertEquals(IncomingFiles.Receipt(1, 0), queue.offer(listOf(uri)))
            assertEquals(uri, queue.files.first())
            assertEquals(IncomingFiles.Receipt(0, 0), queue.offer(emptyList()))
        }
}
