package com.artflow.studio.presentation

import com.artflow.studio.core.animation.AnimationTimeline
import com.artflow.studio.core.animation.OnionSkinMemory
import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.animation.AnimationFrame
import com.artflow.studio.domain.model.animation.AnimationSettings
import com.artflow.studio.domain.model.layer.Layer
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.domain.repository.canvas.CanvasSize
import com.artflow.studio.presentation.ui.components.canvas.OnionSkinPreview
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify

class OnionSkinPreviewOwnershipTest {
    @Test
    fun insufficientCombinedMemoryRefusesBeforeAnyFrameIsComposited() =
        runTest {
            val repository = PreviewRepository()
            val preview = OnionSkinPreview(repository, { BUDGET }, { BUDGET })
            val error = runCatching { preview.render(preview.capture()) { true } }.exceptionOrNull()
            assertEquals(OnionSkinMemory.LIMIT_MESSAGE, error?.message)
            assertEquals(0, repository.composites)
        }

    @Test
    fun aLateFrameFromAnotherDocumentIsDiscardedBeforeAnyFollowingFrame() =
        runTest {
            val repository = PreviewRepository()
            repository.gate = CompletableDeferred()
            val preview = OnionSkinPreview(repository, { 0 }, { BUDGET }, StandardTestDispatcher(testScheduler))
            val work = async { preview.render(preview.capture()) { true } }
            repository.entered.await()
            repository.project = 2
            repository.gate?.complete(Unit)
            assertNull(work.await())
            assertEquals(1, repository.composites)
        }

    @Test
    fun changedRevisionProfileAndActiveFrameCannotPublishAnOldRequest() =
        runTest {
            val repository = PreviewRepository()
            val preview = OnionSkinPreview(repository, { 0 }, { BUDGET }, StandardTestDispatcher(testScheduler))
            val revision = preview.capture()
            repository.revision++
            assertNull(preview.render(revision) { true })
            val profile = preview.capture()
            repository.profile = ColorProfile.DISPLAY_P3
            assertNull(preview.render(profile) { true })
            val frame = preview.capture()
            repository.timeline.value = repository.timeline.value.copy(activeIndex = 0)
            assertNull(preview.render(frame) { true })
            assertEquals(0, repository.composites)
        }

    @Test
    fun cancellationWaitsForTheActualCompositorBeforeStartingItsReplacement() =
        runTest {
            val repository = PreviewRepository()
            repository.gate = CompletableDeferred()
            val preview = OnionSkinPreview(repository, { 0 }, { BUDGET }, StandardTestDispatcher(testScheduler))
            val first = async { preview.render(preview.capture()) { true } }
            repository.entered.await()
            first.cancel()
            // Recreating the canvas creates a new helper while the detached one is cancelling.
            val replacement = OnionSkinPreview(repository, { 0 }, { BUDGET }, StandardTestDispatcher(testScheduler))
            val second = async { replacement.render(replacement.capture()) { true } }
            try {
                runCurrent()
                assertEquals(1, repository.composites)
            } finally {
                repository.gate?.complete(Unit)
            }
            first.join()
            assertNotNull(second.await())
            assertEquals(3, repository.composites)
            assertEquals(1, repository.maximumActive)
        }

    @Test
    fun lostHeadroomAfterCompositionRefusesBeforeTintReadsTheSourcePixels() =
        runTest {
            var memory = BUDGET
            val repository = PreviewRepository()
            repository.afterComposite = { memory = 0 }
            val preview = OnionSkinPreview(repository, { 0 }, { memory }, StandardTestDispatcher(testScheduler))
            val failure = runCatching { preview.render(preview.capture()) { true } }.exceptionOrNull()
            assertEquals(OnionSkinMemory.LIMIT_MESSAGE, failure?.message)
            assertEquals(1, repository.composites)
            verify(repository.source, never()).pixels
        }

    @Test
    fun anEmptyGhostSetCanClearTheRendererWithoutAllocatingEvenAtZeroHeadroom() =
        runTest {
            val repository = PreviewRepository()
            repository.timeline.value = repository.timeline.value.copy(settings = AnimationSettings(onionSkinFrames = 0))
            val preview = OnionSkinPreview(repository, { BUDGET }, { 0 }, StandardTestDispatcher(testScheduler))
            val result = requireNotNull(preview.render(preview.capture()) { true })
            assertEquals(emptyList<Pair<PixelBuffer, Float>>(), result.frames)
            assertEquals(1f, result.primaryAlpha, 0f)
            result.requirePublicationFits(0, BUDGET)
            assertEquals(0, repository.composites)
        }

    private class PreviewRepository : CanvasRepository by mock(CanvasRepository::class.java) {
        var project = 1L
        var revision = 1L
        var profile = ColorProfile.SRGB
        var composites = 0
        var active = 0
        var maximumActive = 0
        var gate: CompletableDeferred<Unit>? = null
        var afterComposite: () -> Unit = {}
        val entered = CompletableDeferred<Unit>()
        val source = spy(PixelBuffer.filled(32, 16, 0x804080C0.toInt()))
        override val timeline =
            MutableStateFlow(
                AnimationTimeline.State(
                    List(3) { AnimationFrame(it + 1L, "Frame", listOf(Layer(it + 1L, "Drawing", 0))) },
                    activeIndex = 1,
                    settings = AnimationSettings(onionSkinFrames = 1, onionSkinTinted = true),
                ),
            )

        override val contentRevision: Long get() = revision

        override fun projectId(): Long = project

        override fun getCanvasSize(): CanvasSize = CanvasSize(32, 16, 72)

        override fun getColorProfile(): ColorProfile = profile

        override suspend fun compositeOnionFrame(index: Int): PixelBuffer {
            composites++
            active++
            maximumActive = maxOf(maximumActive, active)
            try {
                entered.complete(Unit)
                withContext(NonCancellable) { gate?.await() }
                afterComposite()
                return source
            } finally {
                active--
            }
        }
    }

    private companion object {
        const val BUDGET = 64L * 1024 * 1024
    }
}
