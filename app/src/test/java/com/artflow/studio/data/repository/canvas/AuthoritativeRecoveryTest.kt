package com.artflow.studio.data.repository.canvas

import android.content.Context
import com.artflow.studio.core.export.ExportArea
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.export.TimelapseRecorder
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.presentation.ui.viewmodel.CanvasExportController
import com.artflow.studio.presentation.ui.viewmodel.ExportUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class AuthoritativeRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var storage: ProjectStorage
    private lateinit var repository: CanvasRepositoryImpl
    private lateinit var exporter: ArtworkExporter
    private lateinit var recorder: TimelapseRecorder
    private val testDispatcher = UnconfinedTestDispatcher()

    private val projectId = 42L

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporary.newFolder("files"))
        storage = ProjectStorage(context)
        repository = CanvasRepositoryImpl(storage)
        exporter = ArtworkExporter(context, storage)
        recorder = TimelapseRecorder(storage)
    }

    @After
    fun tearDown() {
        repository.dispose()
        Dispatchers.resetMain()
    }

    @Test
    fun art006_recoveryFailureRemainsRetryable() =
        runTest {
            repository.loadOrCreate(projectId, 64, 64, 72)
            repository.saveCanvas(projectId)

            // Make an edit and autosave
            repository.addLayer("Autosaved Layer")
            repository.autosave(projectId)

            assertTrue(storage.hasUnsavedRecovery(projectId))
            val autosaveFile = storage.autosaveFile(projectId)
            val validBytes = autosaveFile.readBytes()

            // Simulate a corrupted/truncated autosave
            autosaveFile.writeBytes(validBytes.copyOf(validBytes.size / 3))

            // Recovery fails
            var recoverFailed = false
            try {
                repository.recoverAutosave(projectId)
            } catch (ex: Exception) {
                recoverFailed = true
            }
            assertTrue("recoverAutosave should fail on corrupted snapshot", recoverFailed)

            // Autosave file must NOT have been deleted!
            assertTrue(autosaveFile.exists())
            assertTrue(storage.hasUnsavedRecovery(projectId))

            // Restore valid snapshot and retry
            autosaveFile.writeBytes(validBytes)
            val recovered = repository.recoverAutosave(projectId)
            assertNotNull(recovered)
            assertTrue(repository.getAllLayers().any { it.name == "Autosaved Layer" })
        }

    @Test
    fun art007_exportAndSaveProtectedOnUnresolvedRecovery() =
        runTest {
            // Initialize base project and save
            repository.loadOrCreate(projectId, 64, 64, 72)
            repository.saveCanvas(projectId)

            // Add a layer and autosave to simulate an uncommitted recovery snapshot
            repository.addLayer("Crash Layer")
            repository.autosave(projectId)

            // Re-load the saved canvas from disk (simulating fresh launch after crash)
            repository.loadCanvas(projectId)
            assertFalse(repository.getAllLayers().any { it.name == "Crash Layer" })

            // Recovery is available and unresolved
            assertTrue(repository.hasRecovery(projectId))
            assertTrue(repository.hasUnresolvedRecovery(projectId))

            // 1. Attempting to saveCanvas must be blocked to prevent destroying the autosave
            var saveFailed = false
            try {
                repository.saveCanvas(projectId)
            } catch (ex: IllegalStateException) {
                saveFailed = true
                assertTrue(
                    "Expected message to contain 'unresolved recovery', was: '${ex.message}'",
                    ex.message.orEmpty().contains("unresolved recovery"),
                )
            }
            assertTrue("saveCanvas should fail when unresolved recovery exists", saveFailed)

            // Autosave must still exist on disk!
            assertTrue(storage.hasUnsavedRecovery(projectId))

            // 2. Attempting to export via CanvasExportController must fail
            val exportState = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
            val controller =
                CanvasExportController(
                    repository = repository,
                    exporter = exporter,
                    recorder = recorder,
                    state = exportState,
                    scope = this,
                    currentProject = { projectId to "Test Project" },
                    onDocumentSaved = {},
                )

            controller.export(ExportOptions(format = ExportFormat.PNG, area = ExportArea.FULL_CANVAS))
            testScheduler.advanceUntilIdle()
            assertTrue(exportState.value is ExportUiState.Failed)
            val failed = exportState.value as ExportUiState.Failed
            assertTrue(failed.message.contains("Resolve recovery before exporting"))

            // 3. Now resolve recovery by calling recoverAutosave
            val recovered = repository.recoverAutosave(projectId)
            assertNotNull(recovered)
            assertFalse(repository.hasUnresolvedRecovery(projectId))

            // 4. Saving now succeeds and safely clears autosave
            val savedPath = repository.saveCanvas(projectId)
            assertNotNull(savedPath)
            assertFalse(storage.hasUnsavedRecovery(projectId))
            assertFalse(repository.hasRecovery(projectId))
        }

    @Test
    fun art008_backgroundingDuringSaveCommitsNewestDirtyCheckpoint() =
        runTest {
            repository.loadOrCreate(projectId, 64, 64, 72)
            repository.saveCanvas(projectId)

            // Modify canvas
            repository.addLayer("Background Dirty Layer")
            assertTrue(repository.hasUnsavedChanges())

            // Trigger autosave (as would happen on background)
            repository.autosave(projectId)

            // Verify autosave was created with the new layer
            assertTrue(storage.hasUnsavedRecovery(projectId))
            val autosaveDoc = storage.loadAutosave(projectId)
            assertNotNull(autosaveDoc)
            assertTrue(autosaveDoc!!.resolvedFrames().flatMap { it.layers }.any { it.name == "Background Dirty Layer" })
        }
}
