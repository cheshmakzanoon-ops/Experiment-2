package com.artflow.studio.presentation

import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.export.TimelapseRecorder
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.presentation.ui.viewmodel.CanvasExportController
import com.artflow.studio.presentation.ui.viewmodel.ExportUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.IOException

/** An export's delayed completion must never replace the next artwork's result. */
@OptIn(ExperimentalCoroutinesApi::class)
class CanvasExportOwnershipTest {
    @Test
    fun switchingBeforeQueuedExportStartsDoesNotReadTheNewArtworkUnderTheOldId() =
        runTest {
            val visited = mutableListOf<Long>()
            var project = 1L
            val state = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
            val controller =
                controller(backgroundScope, state, { project }) {
                    visited += it
                    true
                }

            controller.export(ExportOptions(ExportFormat.PNG))
            controller.reset()
            project = 2L
            controller.export(ExportOptions(ExportFormat.PNG))
            runCurrent()

            assertEquals(listOf(2L), visited)
            assertTrue(state.value is ExportUiState.Failed)
        }

    @Test
    fun oldCancellationCannotClearANewArtworkExportWhileItIsRunning() =
        runTest {
            var project = 1L
            val oldWork = CompletableDeferred<Unit>()
            val oldCleanup = CompletableDeferred<Unit>()
            val newWork = CompletableDeferred<Unit>()
            val state = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
            val controller =
                controller(backgroundScope, state, { project }) { id ->
                    if (id == 1L) {
                        try {
                            oldWork.await()
                        } finally {
                            withContext(NonCancellable) { oldCleanup.await() }
                        }
                    } else {
                        newWork.await()
                    }
                    true
                }

            try {
                controller.export(ExportOptions(ExportFormat.PNG))
                runCurrent()
                controller.reset()
                project = 2L
                controller.export(ExportOptions(ExportFormat.PNG))
                runCurrent()
                oldCleanup.complete(Unit)
                runCurrent()
                assertEquals(ExportUiState.Running, state.value)

                newWork.complete(Unit)
                runCurrent()
                assertTrue(state.value is ExportUiState.Failed)
            } finally {
                oldWork.complete(Unit)
                oldCleanup.complete(Unit)
                newWork.complete(Unit)
                controller.reset()
            }
        }

    @Test
    fun delayedFailureCannotReplaceARetryResultEvenInTheSameArtwork() =
        runTest {
            val oldWork = CompletableDeferred<Unit>()
            val state = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
            var attempts = 0
            val controller =
                controller(backgroundScope, state, { 1L }) {
                    if (++attempts == 1) {
                        withContext(NonCancellable) {
                            oldWork.await()
                            throw IOException("Delayed failure from the canceled export")
                        }
                    }
                    true
                }

            try {
                controller.export(ExportOptions(ExportFormat.PNG))
                runCurrent()
                controller.reset()
                controller.export(ExportOptions(ExportFormat.PNG))
                runCurrent()
                val retryResult = state.value
                assertEquals(2, attempts)
                assertTrue(retryResult is ExportUiState.Failed)

                oldWork.complete(Unit)
                runCurrent()
                assertEquals(retryResult, state.value)
                controller.reset()
                assertEquals(ExportUiState.Idle, state.value)
            } finally {
                oldWork.complete(Unit)
                controller.reset()
            }
        }

    private fun controller(
        scope: CoroutineScope,
        state: MutableStateFlow<ExportUiState>,
        project: () -> Long,
        recovery: suspend (Long) -> Boolean,
    ): CanvasExportController {
        val repository =
            object : CanvasRepository by mock(CanvasRepository::class.java) {
                override suspend fun hasUnresolvedRecovery(projectId: Long): Boolean = recovery(projectId)
            }
        return CanvasExportController(
            repository,
            mock(ArtworkExporter::class.java),
            mock(TimelapseRecorder::class.java),
            state,
            scope,
            { project() to "Artwork" },
            {},
        )
    }
}
