package com.artflow.studio.presentation

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.artflow.studio.core.three.MaterialChannel
import com.artflow.studio.core.three.PreparedModelImport
import com.artflow.studio.data.ModelImportFixture
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.export.PendingImports
import com.artflow.studio.data.export.TimelapseRecorder
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.Project
import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.domain.repository.ProjectRepository
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.domain.repository.settings.SettingsRepository
import com.artflow.studio.presentation.ui.viewmodel.CanvasUiState
import com.artflow.studio.presentation.ui.viewmodel.CanvasViewModel
import com.artflow.studio.presentation.ui.viewmodel.ModelController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.io.IOException

/** The editor cannot expose or autosave a partly installed model bundle. */
@OptIn(ExperimentalCoroutinesApi::class)
class CanvasModelInitializationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun readyAndAutosaveWaitForTheWholeBundleAndItsModelFile() =
        runTest {
            // Keep Main queued after Ready is observed so runCurrent also registers the autosave
            // timer before virtual time advances; unconfined worker resumptions can race it.
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = Fixture(temporary.newFolder("files"))
            try {
                fixture.viewModel.open(PROJECT_ID)
                fixture.awaitEntered()
                assertTrue(fixture.viewModel.uiState.value is CanvasUiState.Loading)
                assertEquals(1, fixture.document.getAllLayers().size)
                assertEquals(0, fixture.document.undoDepth)
                assertSame(fixture.prepared, PendingImports.modelBundle(PROJECT_ID))
                advanceTimeBy(20_000)
                runCurrent()
                assertEquals(0, fixture.autosaves)
                fixture.release.complete(Unit)
                fixture.awaitState { it is CanvasUiState.Ready }
                assertEquals(4, fixture.document.getAllLayers().size)
                assertEquals(1, fixture.document.undoDepth)
                assertEquals(MaterialChannel.COLOR, fixture.document.getActiveLayer()?.materialChannel)
                assertEquals(fixture.prepared.objText, fixture.document.loadModel())
                assertEquals(1, fixture.successfulSaves)
                assertEquals(4, fixture.savedProject?.layerCount)
                assertEquals("saved-thumbnail.png", fixture.savedProject?.thumbnailPath)
                assertNotNull(fixture.viewModel.model.mesh.value)
                assertNull(PendingImports.modelBundle(PROJECT_ID))
                runCurrent()
                advanceTimeBy(5_000)
                runCurrent()
                assertTrue(fixture.autosaves > 0)
            } finally {
                fixture.close()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun aFailedInitialSaveRetainsItsReceiptAndRetriesPersistenceWithoutDuplicatePlanes() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = Fixture(temporary.newFolder("files"))
            try {
                fixture.failInitialSave = true
                fixture.release.complete(Unit)
                fixture.viewModel.open(PROJECT_ID)
                val failed = fixture.awaitState { it is CanvasUiState.Error } as CanvasUiState.Error
                assertTrue(failed.message.contains("Injected initial save failure"))
                val layers = fixture.document.getAllLayers()
                val history = fixture.document.historyMark
                assertEquals(4, layers.size)
                assertEquals(1, fixture.imports)
                assertEquals(0, fixture.successfulSaves)
                assertSame(fixture.prepared, PendingImports.modelBundle(PROJECT_ID))
                fixture.failInitialSave = false
                fixture.viewModel.model.initialize(PROJECT_ID)
                assertEquals(layers, fixture.document.getAllLayers())
                assertEquals(history, fixture.document.historyMark)
                assertEquals(1, fixture.imports)
                assertEquals(2, fixture.saveAttempts)
                assertEquals(1, fixture.successfulSaves)
                assertNull(PendingImports.modelBundle(PROJECT_ID))
            } finally {
                fixture.close()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun cancellationAfterPlanePublicationKeepsTheBundleForAReopenedUnsavedCanvas() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = Fixture(temporary.newFolder("files"))
            try {
                fixture.pauseInitialSave = true
                fixture.release.complete(Unit)
                fixture.viewModel.open(PROJECT_ID)
                fixture.awaitSaveEntered()
                assertTrue(fixture.viewModel.uiState.value is CanvasUiState.Loading)
                assertEquals(4, fixture.document.getAllLayers().size)
                assertEquals(1, fixture.document.undoDepth)
                fixture.viewModel.viewModelScope.coroutineContext[Job]
                    ?.cancelAndJoin()
                assertSame(fixture.prepared, PendingImports.modelBundle(PROJECT_ID))
                assertEquals(0, fixture.successfulSaves)
                // The save never committed a document. The actual repository therefore reopens its
                // original blank canvas, while the pending payload and receipt survive navigation.
                assertNotNull(fixture.document.loadOrCreate(PROJECT_ID, fixture.prepared.width, fixture.prepared.height, 72))
                assertEquals(1, fixture.document.getAllLayers().size)
                fixture.pauseInitialSave = false
                val reopened = ModelController(fixture.repository, backgroundScope, {})
                reopened.initialize(PROJECT_ID)
                assertEquals(4, fixture.document.getAllLayers().size)
                assertEquals(1, fixture.document.undoDepth)
                assertEquals(2, fixture.imports)
                assertEquals(1, fixture.successfulSaves)
                assertNull(PendingImports.modelBundle(PROJECT_ID))
                assertNotNull(reopened.mesh.value)
            } finally {
                fixture.close()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun aPersistenceFailureStaysRecoverableAndRetryInstallsTheBundleOnlyOnce() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = Fixture(temporary.newFolder("files"))
            try {
                fixture.failPublication = true
                fixture.release.complete(Unit)
                val old = "# Existing model\n${ModelImportFixture.OBJ}"
                fixture.storage.resolve(PROJECT_ID, ProjectStorage.MODEL_NAME).writeText(old)
                fixture.viewModel.open(PROJECT_ID)
                val failed = fixture.awaitState { it is CanvasUiState.Error } as CanvasUiState.Error
                assertTrue(failed.message.contains("Injected model publication failure"))
                assertSame(fixture.prepared, PendingImports.modelBundle(PROJECT_ID))
                assertEquals(1, fixture.document.getAllLayers().size)
                assertEquals(0, fixture.document.undoDepth)
                assertEquals(old, fixture.document.loadModel())
                assertEquals(0, fixture.autosaves)
                fixture.failPublication = false
                fixture.viewModel.open(PROJECT_ID)
                fixture.awaitState { it is CanvasUiState.Ready }
                assertEquals(4, fixture.document.getAllLayers().size)
                assertEquals(1, fixture.document.undoDepth)
                assertEquals(fixture.prepared.objText, fixture.document.loadModel())
                assertNull(PendingImports.modelBundle(PROJECT_ID))
                fixture.viewModel.open(PROJECT_ID)
                runCurrent()
                assertEquals(4, fixture.document.getAllLayers().size)
                assertEquals(1, fixture.document.undoDepth)
            } finally {
                fixture.close()
                Dispatchers.resetMain()
            }
        }

    private class Fixture(
        files: File,
    ) {
        val prepared = ModelImportFixture.prepared()
        private val entered = CompletableDeferred<Unit>()
        private val saveEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        private val saveRelease = CompletableDeferred<Unit>()
        var failPublication = false
        var failInitialSave = false
        var pauseInitialSave = false
        var autosaves = 0
        var imports = 0
        var saveAttempts = 0
        var successfulSaves = 0
        var savedProject: Project? = null
        val storage =
            ProjectStorage(
                mock(Context::class.java).also { `when`(it.filesDir).thenReturn(files) },
            )
        val document =
            CanvasRepositoryImpl(storage, Dispatchers.Default) { staged ->
                if (failPublication) throw IOException("Injected model publication failure")
                staged.commit()
            }
        val repository =
            object : CanvasRepository by document {
                override suspend fun importModel(prepared: PreparedModelImport): Boolean {
                    imports++
                    entered.complete(Unit)
                    release.await()
                    return document.importModel(prepared)
                }

                override fun hasUnsavedChanges(): Boolean = true

                override suspend fun autosave(projectId: Long) {
                    autosaves++
                }

                // Android PNG persistence is covered by the fresh-repository device case. This
                // controlled boundary verifies that editor initialization really awaits saving.
                override suspend fun saveCanvas(projectId: Long): String {
                    saveAttempts++
                    saveEntered.complete(Unit)
                    if (pauseInitialSave) saveRelease.await()
                    if (failInitialSave) throw IOException("Injected initial save failure")
                    successfulSaves++
                    return "saved-thumbnail.png"
                }
            }
        private val projects =
            mock(ProjectRepository::class.java) { call ->
                when (call.method.name) {
                    "getProjectById" -> Project(PROJECT_ID, "Material package", "", null, prepared.width, prepared.height, 72, 1, 1)
                    "updateProject" -> {
                        savedProject = call.getArgument(0)
                        Unit
                    }
                    else -> error("Unexpected project call: ${call.method.name}")
                }
            }
        private val settings =
            mock(SettingsRepository::class.java).also {
                `when`(it.settings).thenReturn(flowOf(AppSettings(autosaveEnabled = true, autosaveIntervalMs = 5_000)))
            }
        val viewModel =
            CanvasViewModel(repository, projects, settings, mock(ArtworkExporter::class.java), mock(TimelapseRecorder::class.java))

        init {
            PendingImports.putModelBundle(PROJECT_ID, prepared)
        }

        suspend fun awaitEntered() =
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { entered.await() }
            }

        suspend fun awaitSaveEntered() =
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { saveEntered.await() }
            }

        suspend fun awaitState(predicate: (CanvasUiState) -> Boolean): CanvasUiState =
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { viewModel.uiState.first(predicate) }
            }

        suspend fun close() {
            release.complete(Unit)
            saveRelease.complete(Unit)
            viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            document.dispose()
            PendingImports.completeModelBundle(PROJECT_ID, prepared)
        }
    }

    private companion object {
        const val PROJECT_ID = 76001L
    }
}
