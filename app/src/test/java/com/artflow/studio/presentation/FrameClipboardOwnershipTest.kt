package com.artflow.studio.presentation

import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.presentation.ui.viewmodel.FrameClipboardController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class FrameClipboardOwnershipTest {
    @Test
    fun resetCancelsQueuedCommandsBeforeTheyCanReadOrChangeTheNextArtwork() =
        runTest {
            var project = 1L
            val visits = mutableListOf<String>()
            val controller =
                controller(
                    backgroundScope,
                    project = { project },
                    copy = {
                        visits += "copy $project"
                        true
                    },
                    paste = {
                        visits += "paste $project"
                        true
                    },
                )

            controller.copy(0)
            controller.paste(1)
            controller.reset()
            project = 2L
            runCurrent()
            assertTrue(visits.isEmpty())
            assertFalse(controller.canPaste.value)

            controller.copy(0)
            runCurrent()
            assertEquals(listOf("copy 2"), visits)
            assertTrue(controller.canPaste.value)
        }

    @Test
    fun queuedCommandsAlsoRejectAnArtworkChangedOutsideTheController() =
        runTest {
            var project = 1L
            var visits = 0
            val controller =
                controller(
                    backgroundScope,
                    project = { project },
                    copy = {
                        visits++
                        true
                    },
                    paste = {
                        visits++
                        true
                    },
                )
            controller.copy(0)
            controller.paste(1)
            project = 2L
            runCurrent()
            assertEquals(0, visits)
            assertFalse(controller.canPaste.value)
        }

    @Test
    fun anOldNonCooperativeCopyCannotEnablePasteAfterReset() =
        runTest {
            val finish = CompletableDeferred<Unit>()
            val controller =
                controller(backgroundScope, copy = {
                    withContext(NonCancellable) { finish.await() }
                    true
                })
            try {
                controller.copy(0)
                runCurrent()
                controller.reset()
                finish.complete(Unit)
                runCurrent()
                assertFalse(controller.canPaste.value)
            } finally {
                finish.complete(Unit)
                controller.reset()
            }
        }

    @Test
    fun anOldPasteCannotRefreshOrReportFailureInTheNewSession() =
        runTest {
            val finish = CompletableDeferred<Unit>()
            val messages = mutableListOf<String>()
            var refreshes = 0
            val controller =
                controller(
                    backgroundScope,
                    paste = {
                        withContext(NonCancellable) { finish.await() }
                        false
                    },
                    notify = messages::add,
                    refresh = { refreshes++ },
                )
            try {
                controller.paste(1)
                runCurrent()
                controller.reset()
                controller.copy(0)
                runCurrent()
                assertTrue(controller.canPaste.value)

                finish.complete(Unit)
                runCurrent()
                assertTrue(controller.canPaste.value)
                assertTrue(messages.isEmpty())
                assertEquals(0, refreshes)
            } finally {
                finish.complete(Unit)
                controller.reset()
            }
        }

    @Test
    fun aMissingRepositoryClipboardClearsTheStalePasteAffordance() =
        runTest {
            val messages = mutableListOf<String>()
            var refreshes = 0
            val controller =
                controller(
                    backgroundScope,
                    paste = { false },
                    notify = messages::add,
                    refresh = { refreshes++ },
                )
            controller.copy(0)
            runCurrent()
            assertTrue(controller.canPaste.value)
            controller.paste(1)
            runCurrent()
            assertFalse(controller.canPaste.value)
            assertEquals(listOf("Copy a frame before pasting it here"), messages)
            assertEquals(1, refreshes)
        }

    private fun controller(
        scope: CoroutineScope,
        project: () -> Long = { 1L },
        copy: suspend () -> Boolean = { true },
        paste: suspend () -> Boolean = { true },
        notify: (String) -> Unit = {},
        refresh: () -> Unit = {},
    ): FrameClipboardController {
        val repository =
            object : CanvasRepository by mock(CanvasRepository::class.java) {
                override fun projectId(): Long = project()

                override suspend fun copyFrame(index: Int): Boolean = copy()

                override suspend fun pasteFrame(insertAt: Int): Boolean = paste()
            }
        return FrameClipboardController(repository, scope, notify, refresh, {})
    }
}
