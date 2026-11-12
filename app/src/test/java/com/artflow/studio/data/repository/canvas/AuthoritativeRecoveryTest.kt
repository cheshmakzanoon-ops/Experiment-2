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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
        storage = ProjectStorage(context, testDispatcher)
        repository = CanvasRepositoryImpl(storage)
        exporter = ArtworkExporter(context, storage)
        recorder = TimelapseRecorder(storage)
    }

    @After
    fun tearDown() {
        if (::repository.isInitialized) {
            repository.dispose()
        }
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

    @Test
    fun art009_dismissRecoveryEditAutosaveKillProcessReopenPreservesRecovery() =
        runTest {
            // 1. Initialize canvas and save base document
            repository.loadOrCreate(projectId, 64, 64, 72)
            repository.saveCanvas(projectId)

            // 2. Add layer and autosave to simulate crash recovery snapshot
            repository.addLayer("Pre-Crash Work")
            repository.autosave(projectId)

            // 3. Re-load from disk simulating fresh launch after unexpected crash
            repository.loadCanvas(projectId)
            assertTrue(repository.hasUnresolvedRecovery(projectId))

            // 4. Simulate user dismissing recovery prompt (without recovering or discarding)
            // User edits the canvas
            repository.addLayer("Post-Dismiss Edit")
            assertTrue(repository.hasUnsavedChanges())

            // 5. Autosave attempts to run (e.g. background or timer)
            repository.autosave(projectId)

            // 6. Autosave MUST NOT have overwritten the pre-crash snapshot!
            assertTrue(storage.hasUnsavedRecovery(projectId))
            val autosaveDoc = storage.loadAutosave(projectId)
            assertNotNull(autosaveDoc)
            val layerNames = autosaveDoc!!.resolvedFrames().flatMap { it.layers }.map { it.name }
            assertTrue("Pre-crash layer must be preserved", layerNames.contains("Pre-Crash Work"))
            assertFalse("Post-dismiss edit must not overwrite recovery data", layerNames.contains("Post-Dismiss Edit"))

            // 7. Simulate process death and recreate repository from storage
            val freshRepo = CanvasRepositoryImpl(storage)
            freshRepo.loadCanvas(projectId)
            assertTrue(freshRepo.hasUnresolvedRecovery(projectId))

            // 8. Explicitly restore now
            val recovered = freshRepo.recoverAutosave(projectId)
            assertNotNull(recovered)
            assertTrue(freshRepo.getAllLayers().any { it.name == "Pre-Crash Work" })
            assertFalse(freshRepo.hasUnresolvedRecovery(projectId))
            freshRepo.dispose()
        }

    @Test
    fun art010_backgroundingWhileRecoveryUnresolvedDoesNotOverwriteSnapshot() =
        runTest {
            repository.loadOrCreate(projectId, 64, 64, 72)
            repository.saveCanvas(projectId)
            repository.addLayer("Critical Crash Snapshot")
            repository.autosave(projectId)

            // Reload fresh session
            repository.loadCanvas(projectId)
            assertTrue(repository.hasUnresolvedRecovery(projectId))

            // Backgrounding autosave trigger
            repository.autosave(projectId)

            val autosaveDoc = storage.loadAutosave(projectId)
            assertNotNull(autosaveDoc)
            assertTrue(autosaveDoc!!.resolvedFrames().flatMap { it.layers }.any { it.name == "Critical Crash Snapshot" })
        }

    @Test
    fun art011_concurrentAutosaveAttemptsAreThreadSafe() =
        runTest {
            repository.loadOrCreate(projectId, 64, 64, 72)
            repository.saveCanvas(projectId)

            val jobs =
                (1..5).map { index ->
                    launch {
                        repository.addLayer("Concurrent Layer $index")
                        repository.autosave(projectId)
                    }
                }
            jobs.joinAll()

            assertTrue(storage.hasUnsavedRecovery(projectId))
            val autosaveDoc = storage.loadAutosave(projectId)
            assertNotNull(autosaveDoc)
            assertTrue(autosaveDoc!!.resolvedFrames().isNotEmpty())
        }

    @Test
    fun art012_explicitDiscardRemovesAutosave() =
        runTest {
            repository.loadOrCreate(projectId, 64, 64, 72)
            repository.saveCanvas(projectId)
            repository.addLayer("Discardable Layer")
            repository.autosave(projectId)

            assertTrue(storage.hasUnsavedRecovery(projectId))
            repository.discardRecovery(projectId)

            assertFalse(storage.hasUnsavedRecovery(projectId))
            assertFalse(repository.hasRecovery(projectId))
            assertFalse(repository.hasUnresolvedRecovery(projectId))

            // Normal save now succeeds
            val savedPath = repository.saveCanvas(projectId)
            assertNotNull(savedPath)
        }

    @Test
    fun art013_atomicWriteProtectsAgainstFailedWrites() =
        runTest {
            repository.loadOrCreate(projectId, 64, 64, 72)
            val initialPath = repository.saveCanvas(projectId)
            assertNotNull(initialPath)

            val docFile = storage.documentFile(projectId)
            val originalBytes = docFile.readBytes()

            // Verify atomic write failure preserves original file
            val target = storage.documentFile(projectId)
            try {
                storage.writeAtomically(target) {
                    throw java.io.IOException("Simulated disk full")
                }
            } catch (ex: java.io.IOException) {
                // Expected
            }

            assertTrue(docFile.exists())
            assertEquals(originalBytes.size.toLong(), docFile.length())
            assertTrue(originalBytes.contentEquals(docFile.readBytes()))
        }
}
