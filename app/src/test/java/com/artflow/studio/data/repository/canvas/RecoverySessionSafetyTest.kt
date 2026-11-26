package com.artflow.studio.data.repository.canvas

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.export.TimelapseRecorder
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.domain.model.Project
import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.domain.repository.ProjectRepository
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.domain.repository.canvas.CanvasState
import com.artflow.studio.domain.repository.settings.SettingsRepository
import com.artflow.studio.presentation.ui.viewmodel.CanvasRecoveryController
import com.artflow.studio.presentation.ui.viewmodel.CanvasUiState
import com.artflow.studio.presentation.ui.viewmodel.CanvasViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import kotlin.coroutines.CoroutineContext

/** Recovery decoded on a worker must never replace a different or newly edited live document. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecoverySessionSafetyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun delayedRecoveryCannotReplaceANewlyOpenedArtworkOrItsUnsavedEdits() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val fixture = Fixture(temporary.newFolder("switched"))
            try {
                fixture.prepareRecovery()
                val oldRecovery = fixture.storage.autosaveFile(FIRST).readBytes()
                val gate = fixture.io.pauseNext()
                val recovery = async(start = CoroutineStart.UNDISPATCHED) { runCatching { fixture.canvas.recoverAutosave(FIRST) } }
                gate.awaitEntered()
                fixture.canvas.loadOrCreate(SECOND, 12, 10, 300)
                fixture.canvas.saveCanvas(SECOND)
                val saved = fixture.storage.documentFile(SECOND).readBytes()
                fixture.canvas.addLayer("Second artwork's newer work")
                val history = fixture.canvas.historyMark
                gate.resume()
                val result = recovery.await()

                assertEquals(SECOND, fixture.canvas.projectId())
                assertEquals(12, fixture.canvas.getCanvasSize().width)
                assertTrue(fixture.canvas.getAllLayers().any { it.name == "Second artwork's newer work" })
                assertEquals(history, fixture.canvas.historyMark)
                assertTrue(fixture.canvas.hasUnsavedChanges())
                assertTrue(result.exceptionOrNull() is IllegalStateException)
                assertArrayEquals(oldRecovery, fixture.storage.autosaveFile(FIRST).readBytes())
                assertArrayEquals(saved, fixture.storage.documentFile(SECOND).readBytes())
            } finally {
                fixture.close()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun editsMadeDuringRecoveryRemainLiveAndTheRecoveryCanBeRetried() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val fixture = Fixture(temporary.newFolder("edited"))
            try {
                fixture.prepareRecovery()
                val original = fixture.storage.autosaveFile(FIRST).readBytes()
                val gate = fixture.io.pauseNext()
                val recovery = async(start = CoroutineStart.UNDISPATCHED) { runCatching { fixture.canvas.recoverAutosave(FIRST) } }
                gate.awaitEntered()
                fixture.canvas.addLayer("Newer live edit")
                val history = fixture.canvas.historyMark
                gate.resume()
                val result = recovery.await()

                assertTrue(fixture.canvas.getAllLayers().any { it.name == "Newer live edit" })
                assertEquals(history, fixture.canvas.historyMark)
                assertTrue(fixture.canvas.hasUnsavedChanges())
                assertTrue(fixture.canvas.hasUnresolvedRecovery(FIRST))
                assertTrue(result.exceptionOrNull() is IllegalStateException)
                assertArrayEquals(original, fixture.storage.autosaveFile(FIRST).readBytes())
                // An explicit retry captures the current revision and can replace it with recovery.
                fixture.canvas.recoverAutosave(FIRST)
                assertTrue(fixture.canvas.getAllLayers().any { it.name == "Recovered work" })
            } finally {
                fixture.close()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun openingCancelsRecoveryBeforeTheNextProjectLookupCompletes() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val fixture = Fixture(temporary.newFolder("opening"))
            val releaseLookup = CompletableDeferred<Unit>()
            val lookupEntered = CompletableDeferred<Unit>()
            val recoveryFinished = CompletableDeferred<Unit>()
            val repository =
                object : CanvasRepository by fixture.canvas {
                    override suspend fun recoverAutosave(projectId: Long): CanvasState? =
                        try {
                            fixture.canvas.recoverAutosave(projectId)
                        } finally {
                            recoveryFinished.complete(Unit)
                        }
                }
            val projects =
                object : ProjectRepository by mock(ProjectRepository::class.java) {
                    override suspend fun getProjectById(projectId: Long): Project {
                        if (projectId == SECOND) {
                            lookupEntered.complete(Unit)
                            releaseLookup.await()
                        }
                        return Project(projectId, "Artwork $projectId", "", null, 8, 6, 72, 1, 1)
                    }
                }
            val viewModel = fixture.viewModel(repository, projects)
            try {
                fixture.prepareRecovery()
                val recovery = fixture.storage.autosaveFile(FIRST).readBytes()
                viewModel.open(FIRST)
                await { viewModel.uiState.first { it is CanvasUiState.Ready } }
                val gate = fixture.io.pauseNext()
                viewModel.recoverAutosave()
                gate.awaitEntered()
                viewModel.open(SECOND)
                await { lookupEntered.await() }
                gate.resume()
                await { recoveryFinished.await() }

                assertTrue(viewModel.uiState.value is CanvasUiState.Loading)
                assertFalse(fixture.canvas.getAllLayers().any { it.name == "Recovered work" })
                assertArrayEquals(recovery, fixture.storage.autosaveFile(FIRST).readBytes())
                releaseLookup.complete(Unit)
                val ready = await { viewModel.uiState.first { it is CanvasUiState.Ready } } as CanvasUiState.Ready
                assertEquals(SECOND, ready.projectId)
                assertEquals(SECOND, fixture.canvas.projectId())
            } finally {
                releaseLookup.complete(Unit)
                fixture.io.resumeAll()
                viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
                fixture.close()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun anAdmittedBackgroundRecoveryFinishesAfterTheEditorScopeCloses() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val fixture = Fixture(temporary.newFolder("background"))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val completed = CompletableDeferred<Unit>()
            val repository =
                object : CanvasRepository by fixture.canvas {
                    override suspend fun autosave(projectId: Long) {
                        fixture.canvas.autosave(projectId)
                        completed.complete(Unit)
                    }
                }
            val controller = CanvasRecoveryController(repository, scope, {}, {}, {})
            try {
                fixture.prepareRecovery()
                fixture.canvas.recoverAutosave(FIRST)
                fixture.canvas.addLayer("Admitted background edit")
                controller.onProjectOpened(FIRST, false)
                val gate = fixture.io.pauseNext()
                controller.saveOnBackground(FIRST)
                gate.awaitEntered()
                scope.cancel()
                gate.resume()
                await { completed.await() }

                val recovered = fixture.storage.loadAutosave(FIRST)!!
                assertTrue(recovered.resolvedFrames().flatMap { it.layers }.any { it.name == "Admitted background edit" })
                assertTrue(fixture.storage.hasUnsavedRecovery(FIRST))
            } finally {
                fixture.io.resumeAll()
                scope.cancel()
                fixture.close()
                Dispatchers.resetMain()
            }
        }

    private class Fixture(
        files: File,
    ) {
        val io = PausedIo()
        private val context = mock(Context::class.java).also { `when`(it.filesDir).thenReturn(files) }
        val storage = ProjectStorage(context, io)
        val canvas = CanvasRepositoryImpl(storage)

        fun viewModel(
            repository: CanvasRepository,
            projects: ProjectRepository,
        ): CanvasViewModel {
            val settings = mock(SettingsRepository::class.java)
            `when`(settings.settings).thenReturn(flowOf(AppSettings(autosaveEnabled = false)))
            val timelapse =
                mock(TimelapseRecorder::class.java) { call ->
                    if (call.method.name == "isRecording") true else RETURNS_DEFAULTS.answer(call)
                }
            return CanvasViewModel(repository, projects, settings, ArtworkExporter(context, storage), timelapse)
        }

        suspend fun prepareRecovery() {
            canvas.loadOrCreate(FIRST, 8, 6, 72)
            canvas.saveCanvas(FIRST)
            canvas.addLayer("Recovered work")
            canvas.autosave(FIRST)
            canvas.loadCanvas(FIRST)
        }

        fun close() {
            io.resumeAll()
            canvas.dispose()
        }
    }

    private class PausedIo : CoroutineDispatcher() {
        private var pending: Gate? = null
        private val gates = mutableListOf<Gate>()

        @Synchronized
        fun pauseNext(): Gate {
            check(pending == null)
            return Gate().also {
                pending = it
                gates.add(it)
            }
        }

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            val gate = synchronized(this) { pending.also { pending = null } }
            if (gate == null) Dispatchers.IO.dispatch(context, block) else gate.hold { Dispatchers.IO.dispatch(context, block) }
        }

        @Synchronized
        fun resumeAll() = gates.forEach(Gate::resume)
    }

    private class Gate {
        private val entered = CompletableDeferred<Unit>()
        private var resume: (() -> Unit)? = null
        private var released = false

        @Synchronized
        fun hold(block: () -> Unit) {
            if (released) block() else resume = block
            entered.complete(Unit)
        }

        suspend fun awaitEntered() = withContext(Dispatchers.Default) { withTimeout(5_000) { entered.await() } }

        @Synchronized
        fun resume() {
            released = true
            resume?.invoke()
            resume = null
        }
    }

    private companion object {
        const val FIRST = 79001L
        const val SECOND = 79002L

        suspend fun <T> await(block: suspend () -> T): T = withContext(Dispatchers.Default) { withTimeout(5_000) { block() } }
    }
}
