package com.artflow.studio.presentation

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.three.MaterialChannel
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

    @Test
    fun ordinaryRefreshFollowingFinishProvisionalDoesNotDowngradeOrDelayCommittedRender() =
        runTest {
            val repository = DeferredPreviewRepository()
            val controller = CanvasPreviewController(repository, backgroundScope, StandardTestDispatcher(testScheduler))
            controller.refreshProvisional()
            runCurrent()
            val pending = repository.started.receive()
            controller.finishProvisional()
            controller.refresh()
            runCurrent()
            pending.result.complete(PixelBuffer.filled(2, 2, 0xFFFF0000.toInt()))
            runCurrent()
            val committed = repository.started.receive()
            assertEquals(false, committed.provisional)
            val updated = PixelBuffer.filled(2, 2, 0xFF00FF00.toInt())
            committed.result.complete(updated)
            runCurrent()
            assertSame(updated, controller.preview.value)
        }

    @Test
    fun supersededSettledRenderDoesNotPublishBeforeTheLatestRefresh() =
        runTest {
            val repository = DeferredPreviewRepository()
            val controller = CanvasPreviewController(repository, backgroundScope, StandardTestDispatcher(testScheduler))
            controller.refresh()
            runCurrent()
            advanceTimeBy(250)
            runCurrent()
            val old = repository.started.receive()
            controller.refresh()
            old.result.complete(PixelBuffer.filled(2, 2, 0xFFFF0000.toInt()))
            runCurrent()
            assertNull(controller.preview.value)

            advanceTimeBy(250)
            runCurrent()
            val current = repository.started.receive()
            val updated = PixelBuffer.filled(2, 2, 0xFF00FF00.toInt())
            current.result.complete(updated)
            runCurrent()
            assertSame(updated, controller.preview.value)
            assertEquals(1, repository.maximumConcurrent)
        }

    @Test
    fun changedFrameRejectsACommittedRenderEvenBeforeAnotherRefreshArrives() =
        runTest {
            val repository = DeferredPreviewRepository()
            val controller = CanvasPreviewController(repository, backgroundScope, StandardTestDispatcher(testScheduler))
            controller.finishProvisional()
            runCurrent()
            val old = repository.started.receive()
            assertEquals(0, old.frameIndex)
            repository.frameIndex = 1
            old.result.complete(PixelBuffer.filled(2, 2, 0xFFFF0000.toInt()))
            runCurrent()
            assertNull(controller.preview.value)

            controller.finishProvisional()
            runCurrent()
            val current = repository.started.receive()
            assertEquals(1, current.frameIndex)
            val updated = PixelBuffer.filled(2, 2, 0xFF00FF00.toInt())
            current.result.complete(updated)
            runCurrent()
            assertSame(updated, controller.preview.value)
        }

    @Test
    fun resetClearsBothOutputsAndRejectsLateWorkWithoutOverlappingRenders() =
        runTest {
            val repository = DeferredPreviewRepository()
            val controller = CanvasPreviewController(repository, backgroundScope, StandardTestDispatcher(testScheduler))
            val oldMap = PixelBuffer.filled(2, 2, 0xFF444444.toInt())
            repository.materials = mapOf(MaterialChannel.METALLIC to oldMap)
            controller.finishProvisional()
            runCurrent()
            repository.started
                .receive()
                .result
                .complete(PixelBuffer.filled(2, 2, 0xFFFF0000.toInt()))
            runCurrent()
            assertSame(oldMap, controller.materialMaps.value[MaterialChannel.METALLIC])

            controller.finishProvisional()
            runCurrent()
            val delayed = repository.started.receive()
            controller.reset()
            assertNull(controller.preview.value)
            assertEquals(emptyMap<MaterialChannel, PixelBuffer>(), controller.materialMaps.value)
            repository.materials = emptyMap()
            controller.finishProvisional()
            runCurrent()
            assertEquals(2, repository.startedCount)
            delayed.result.complete(PixelBuffer.filled(2, 2, 0xFF0000FF.toInt()))
            runCurrent()
            assertNull(controller.preview.value)

            val current = repository.started.receive()
            val updated = PixelBuffer.filled(2, 2, 0xFF00FF00.toInt())
            current.result.complete(updated)
            runCurrent()
            assertSame(updated, controller.preview.value)
            assertEquals(emptyMap<MaterialChannel, PixelBuffer>(), controller.materialMaps.value)
            assertEquals(1, repository.maximumConcurrent)
        }

    @Test
    fun revisionChangeDuringMaterialRenderingCannotPublishMixedOutputs() =
        runTest {
            val repository = DeferredPreviewRepository()
            repository.gateMaterials = true
            val controller = CanvasPreviewController(repository, backgroundScope, StandardTestDispatcher(testScheduler))
            controller.finishProvisional()
            runCurrent()
            repository.started
                .receive()
                .result
                .complete(PixelBuffer.filled(2, 2, 0xFFFF0000.toInt()))
            runCurrent()
            val oldMap = repository.materialStarted.receive()
            assertNull("Color must wait for its matching material maps", controller.preview.value)
            repository.revision++
            oldMap.result.complete(PixelBuffer.filled(2, 2, 0xFF444444.toInt()))
            runCurrent()
            assertNull(controller.preview.value)
            assertEquals(emptyMap<MaterialChannel, PixelBuffer>(), controller.materialMaps.value)

            repository.gateMaterials = false
            val newMap = PixelBuffer.filled(2, 2, 0xFF888888.toInt())
            repository.materials = mapOf(MaterialChannel.ROUGHNESS to newMap)
            controller.finishProvisional()
            runCurrent()
            val updated = PixelBuffer.filled(2, 2, 0xFF00FF00.toInt())
            repository.started
                .receive()
                .result
                .complete(updated)
            runCurrent()
            assertSame(updated, controller.preview.value)
            assertEquals(mapOf(MaterialChannel.ROUGHNESS to newMap), controller.materialMaps.value)
        }

    private class DeferredPreviewRepository : CanvasRepository by mock(CanvasRepository::class.java) {
        val started = Channel<Render>(Channel.UNLIMITED)
        val materialStarted = Channel<Render>(Channel.UNLIMITED)
        var startedCount = 0
        var maximumConcurrent = 0
        var frameIndex = 0
        var revision = 0L
        var gateMaterials = false
        var materials = emptyMap<MaterialChannel, PixelBuffer>()
        private var concurrent = 0

        override val contentRevision: Long get() = revision

        override fun activeFrameIndex(): Int = frameIndex

        override suspend fun compositeMaterial(
            channel: MaterialChannel,
            provisional: Boolean,
        ): PixelBuffer? {
            if (!gateMaterials) return materials[channel]
            val request = Render(provisional, frameIndex)
            materialStarted.send(request)
            return request.result.await()
        }

        override suspend fun compositePreview(): PixelBuffer = render(true)

        override suspend fun compositeFrame(
            index: Int,
            transparentBackground: Boolean,
        ): PixelBuffer = render(false)

        private suspend fun render(provisional: Boolean): PixelBuffer {
            concurrent++
            maximumConcurrent = maxOf(maximumConcurrent, concurrent)
            startedCount++
            val request = Render(provisional, frameIndex)
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
        val frameIndex: Int,
    ) {
        val result = CompletableDeferred<PixelBuffer>()
    }
}
