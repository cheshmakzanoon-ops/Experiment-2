package com.artflow.studio.presentation

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.three.MaterialChannel
import com.artflow.studio.domain.repository.canvas.CanvasClipboardSnapshot
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.presentation.ui.viewmodel.ClipboardController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class ClipboardOwnershipTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun queuedCopyRejectsAnArtworkChangedOutsideTheController() =
        runTest {
            var project = 1L
            var reads = 0
            val controller =
                controller(backgroundScope, project = { project }, copy = {
                    reads++
                    CanvasClipboardSnapshot(PixelBuffer(1, 1), false)
                })
            controller.copyMerged()
            project = 2L
            runCurrent()
            assertEquals(0, reads)
            assertFalse(controller.hasContent.value)
        }

    @Test
    fun aSlowEarlierCopyCannotReplaceANewerCompletedCopy() =
        runTest {
            val finish = CompletableDeferred<Unit>()
            val pasted = mutableListOf<Pair<Int, Boolean>>()
            var copies = 0
            val controller =
                controller(
                    backgroundScope,
                    copy = {
                        if (++copies == 1) {
                            withContext(NonCancellable) { finish.await() }
                            CanvasClipboardSnapshot(PixelBuffer.filled(1, 1, RED), true)
                        } else {
                            CanvasClipboardSnapshot(PixelBuffer.filled(1, 1, BLUE), false)
                        }
                    },
                    paste = { pixels, isPrivate -> pasted += pixels.pixels.single() to isPrivate },
                )
            try {
                controller.copyMerged()
                runCurrent()
                controller.copyMerged()
                runCurrent()
                finish.complete(Unit)
                runCurrent()
                controller.paste()
                runCurrent()
                assertEquals(listOf(BLUE to false), pasted)
            } finally {
                finish.complete(Unit)
                controller.cancelPending()
            }
        }

    @Test
    fun aCancelledNonCooperativeCopyCannotPublishOrReportFailureInTheNextArtwork() =
        runTest {
            for (fails in listOf(false, true)) {
                val finish = CompletableDeferred<Unit>()
                val messages = mutableListOf<String>()
                val pasted = mutableListOf<Pair<Int, Boolean>>()
                var project = 1L
                val controller =
                    controller(
                        backgroundScope,
                        project = { project },
                        copy = {
                            if (project == 1L) {
                                withContext(NonCancellable) {
                                    finish.await()
                                    check(!fails) { "Old copy failed" }
                                    CanvasClipboardSnapshot(PixelBuffer.filled(1, 1, RED), true)
                                }
                            } else {
                                CanvasClipboardSnapshot(PixelBuffer.filled(1, 1, BLUE), false)
                            }
                        },
                        paste = { pixels, isPrivate -> pasted += pixels.pixels.single() to isPrivate },
                        notify = messages::add,
                    )
                try {
                    controller.copyMerged()
                    runCurrent()
                    controller.cancelPending()
                    project = 2L
                    controller.copyMerged()
                    runCurrent()
                    finish.complete(Unit)
                    runCurrent()
                    controller.paste()
                    runCurrent()
                    assertTrue(messages.isEmpty())
                    assertEquals(listOf(BLUE to false), pasted)
                } finally {
                    finish.complete(Unit)
                    controller.cancelPending()
                }
            }
        }

    private fun controller(
        scope: CoroutineScope,
        project: () -> Long = { 1L },
        copy: suspend () -> CanvasClipboardSnapshot,
        paste: suspend (PixelBuffer, Boolean) -> Unit = { _, _ -> },
        notify: (String) -> Unit = {},
    ): ClipboardController {
        val repository =
            object : CanvasRepository by mock(CanvasRepository::class.java) {
                override fun projectId(): Long = project()

                override suspend fun copyMergedSelection(): CanvasClipboardSnapshot = copy()

                override suspend fun pasteLayer(
                    pixels: PixelBuffer,
                    materialChannel: MaterialChannel,
                    isPrivate: Boolean,
                ): Long {
                    paste(pixels, isPrivate)
                    return 1L
                }
            }
        return ClipboardController(repository) { operation ->
            scope.launch {
                try {
                    operation()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: IllegalStateException) {
                    notify(error.message.orEmpty())
                }
            }
        }
    }

    private companion object {
        const val RED = -0x10000
        const val BLUE = -0xffff01
    }
}
