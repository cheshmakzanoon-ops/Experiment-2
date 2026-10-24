package com.artflow.studio.presentation

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.presentation.ui.viewmodel.CanvasPreviewController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito.mock

/** Controlled renderer completion exercises coalescing and rejection of cancelled gesture frames. */
@OptIn(ExperimentalCoroutinesApi::class)
class CanvasPreviewControllerTest {
    @Test
    fun growingSamplesCoalesceWhileOneRenderContinuesAndStillPublishDuringTheGesture() =
        runTest {
            val repository = DeferredPreviewRepository()
            val controller = CanvasPreviewController(repository, backgroundScope, StandardTestDispatcher(testScheduler))
            controller.refreshProvisional()
            runCurrent()
            val first = repository.started.receive()
            assertEquals(true, first.provisional)
            repeat(80) { controller.refreshProvisional() }
            runCurrent()
            assertEquals(1, repository.startedCount)
            val growing = PixelBuffer.filled(2, 2, 0xFF224466.toInt())
            first.result.complete(growing)
            runCurrent()
            assertSame("New samples must not starve an in-flight provisional result", growing, controller.preview.value)

            advanceTimeBy(32)
            runCurrent()
            val second = repository.started.receive()
            assertEquals(true, second.provisional)
            assertEquals(2, repository.startedCount)
            val longer = PixelBuffer.filled(2, 2, 0xFF6688AA.toInt())
            second.result.complete(longer)
            runCurrent()
            assertSame(longer, controller.preview.value)
            advanceTimeBy(1000)
            runCurrent()
            assertEquals("The burst needs only one follow-up render", 2, repository.startedCount)
            assertEquals("Raster work is serial", 1, repository.maximumConcurrent)
        }

    @Test
    fun cancellingRejectsTheLateProvisionalFrameAndImmediatelyRequestsCommittedPixels() =
        runTest {
            val repository = DeferredPreviewRepository()
            val controller = CanvasPreviewController(repository, backgroundScope, StandardTestDispatcher(testScheduler))
            controller.refreshProvisional()
            runCurrent()
            val pending = repository.started.receive()
            controller.finishProvisional()
            runCurrent()
            assertEquals("The committed render cannot overlap the old render", 1, repository.startedCount)
            pending.result.complete(PixelBuffer.filled(2, 2, 0xFFFF0000.toInt()))
            runCurrent()
            assertNull("An old gesture cannot reappear after cancellation", controller.preview.value)

            val committed = repository.started.receive()
            assertEquals(false, committed.provisional)
            val original = PixelBuffer.filled(2, 2, 0xFFEEEEEE.toInt())
            committed.result.complete(original)
            runCurrent()
            assertSame(original, controller.preview.value)
            assertEquals(1, repository.maximumConcurrent)
        }

    @Test
    fun ordinaryReferenceRefreshStillDebouncesButAProvisionalRequestBypassesTheSettleWait() =
        runTest {
            val repository = DeferredPreviewRepository()
            val controller = CanvasPreviewController(repository, backgroundScope, StandardTestDispatcher(testScheduler))
            controller.refresh()
            runCurrent()
            advanceTimeBy(200)
            controller.refresh()
            runCurrent()
            advanceTimeBy(249)
            runCurrent()
            assertEquals(0, repository.startedCount)
            advanceTimeBy(1)
            runCurrent()
            val settled = repository.started.receive()
            assertEquals(false, settled.provisional)
            settled.result.complete(PixelBuffer.filled(2, 2, 0xFFFFFFFF.toInt()))
            runCurrent()

            controller.refresh()
            runCurrent()
            controller.refreshProvisional()
            runCurrent()
            val provisional = repository.started.receive()
            assertEquals(true, provisional.provisional)
            assertEquals(2, repository.startedCount)
            provisional.result.complete(PixelBuffer.filled(2, 2, 0xFF112233.toInt()))
            runCurrent()
        }

    private class DeferredPreviewRepository : CanvasRepository by mock(CanvasRepository::class.java) {
        val started = Channel<Render>(Channel.UNLIMITED)
        var startedCount = 0
        var maximumConcurrent = 0
        private var concurrent = 0

        override fun activeFrameIndex(): Int = 0

        override suspend fun compositePreview(): PixelBuffer = render(true)

        override suspend fun compositeFrame(
            index: Int,
            transparentBackground: Boolean,
        ): PixelBuffer = render(false)

        private suspend fun render(provisional: Boolean): PixelBuffer {
            concurrent++
            maximumConcurrent = maxOf(maximumConcurrent, concurrent)
            startedCount++
            val request = Render(provisional)
            started.send(request)
            return try {
                request.result.await()
            } finally {
                concurrent--
            }
        }
    }

    private class Render(
        val provisional: Boolean,
    ) {
        val result = CompletableDeferred<PixelBuffer>()
    }
}
