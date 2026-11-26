package com.artflow.studio.presentation

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.data.export.ArtflowPackage
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.export.TimelapseRecorder
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.Project
import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.domain.repository.ProjectRepository
import com.artflow.studio.domain.repository.canvas.SavedCanvas
import com.artflow.studio.domain.repository.settings.SettingsRepository
import com.artflow.studio.presentation.ui.viewmodel.CanvasUiState
import com.artflow.studio.presentation.ui.viewmodel.CanvasViewModel
import com.artflow.studio.presentation.ui.viewmodel.ExportUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
import java.io.IOException
import javax.imageio.ImageIO
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class CommittedSaveMetadataTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun editsDuringSaveKeepGalleryMetadataBoundToTheCommittedDocumentAndThumbnail() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val fixture = Fixture(temporary.newFolder("files"))
            try {
                fixture.open()
                fixture.canvas.setCanvasBackgroundColor(RED)
                val before = fixture.canvas.getAllLayers().size
                val gate = fixture.io.pauseNext()
                var navigated = false
                fixture.viewModel.save { navigated = true }
                gate.awaitEntered()
                fixture.editWhileSaving()
                gate.resume()
                fixture.awaitSaveFinished()

                val document = requireNotNull(fixture.storage.loadDocument(PROJECT))
                val gallery = requireNotNull(fixture.published)
                assertEquals(8, document.width)
                assertEquals(6, document.height)
                assertEquals(72, document.dpi)
                assertEquals(before, document.frames[document.activeFrameIndex].layers.size)
                val thumbnail = requireNotNull(ImageIO.read(File(requireNotNull(gallery.thumbnailPath))))
                assertEquals(document.width, thumbnail.width)
                assertEquals(document.height, thumbnail.height)
                assertArrayEquals(
                    IntArray(document.width * document.height) { RED },
                    thumbnail.getRGB(0, 0, document.width, document.height, null, 0, document.width),
                )

                assertEquals(document.width, gallery.width)
                assertEquals(document.height, gallery.height)
                assertEquals(document.dpi, gallery.dpi)
                assertEquals(before, gallery.layerCount)
                assertEquals(document.savedAt, gallery.modifiedAt)
                assertEquals(12, fixture.canvas.getCanvasSize().width)
                assertEquals(10, fixture.canvas.getCanvasSize().height)
                assertEquals(300, fixture.canvas.getCanvasSize().dpi)
                assertEquals(before + 1, fixture.canvas.getAllLayers().size)
                assertTrue(fixture.canvas.hasUnsavedChanges())
                assertTrue(fixture.viewModel.dirty.value)
                assertFalse(navigated)
            } finally {
                fixture.close()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun nativeExportPublishesTheSameCommittedMetadataBeforeReturningItsPackage() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val fixture = Fixture(temporary.newFolder("export"))
            try {
                fixture.open()
                fixture.canvas.setCanvasBackgroundColor(RED)
                val gate = fixture.io.pauseNext()
                fixture.viewModel.export(ExportOptions(format = ExportFormat.ARTFLOW))
                gate.awaitEntered()
                fixture.editWhileSaving()
                gate.resume()
                val state = await { fixture.viewModel.exportState.first { it is ExportUiState.Done || it is ExportUiState.Failed } }
                assertTrue(state.toString(), state is ExportUiState.Done)
                val result = (state as ExportUiState.Done).result
                val document = requireNotNull(fixture.storage.loadDocument(PROJECT))
                val gallery = requireNotNull(fixture.published)
                assertEquals(document.width, gallery.width)
                assertEquals(document.height, gallery.height)
                assertEquals(document.dpi, gallery.dpi)
                assertEquals(document.frames[document.activeFrameIndex].layers.size, gallery.layerCount)
                assertEquals(document.savedAt, gallery.modifiedAt)
                assertEquals(document.width, result.width)
                assertEquals(document.height, result.height)
                ArtflowPackage.openFile(File(result.filePath)).use { opened ->
                    assertArrayEquals(fixture.storage.documentFile(PROJECT).readBytes(), opened.readBytes(ArtflowPackage.DOCUMENT))
                }
                assertTrue(fixture.canvas.hasUnsavedChanges())
                assertTrue(fixture.viewModel.dirty.value)
            } finally {
                fixture.close()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun resultRetainsTheSavedActiveFrameRevisionAndDimensions() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val fixture = Fixture(temporary.newFolder("frame"))
            try {
                fixture.open()
                fixture.canvas.addFrame(false)
                fixture.canvas.addLayer("Second frame layer")
                val revision = fixture.canvas.contentRevision
                val gate = fixture.io.pauseNext()
                val saving = async(start = CoroutineStart.UNDISPATCHED) { fixture.canvas.saveCanvasSnapshot(PROJECT) }
                gate.awaitEntered()
                fixture.canvas.selectFrame(0)
                fixture.canvas.setCanvasDpi(300)
                gate.resume()
                val saved = saving.await()
                val document = requireNotNull(fixture.storage.loadDocument(PROJECT))
                assertEquals(PROJECT, saved.projectId)
                assertEquals(revision, saved.revision)
                assertEquals(1, saved.activeFrameIndex)
                assertEquals(2, saved.layerCount)
                assertEquals(document.frames[1].layers.size, saved.layerCount)
                assertEquals(1, fixture.canvas.getAllLayers().size)
                assertEquals(document.width, saved.size.width)
                assertEquals(document.height, saved.size.height)
                assertEquals(document.dpi, saved.size.dpi)
                assertEquals(72, saved.size.dpi)
                assertEquals(document.savedAt, saved.savedAt)
                assertEquals(fixture.storage.thumbnailFile(PROJECT).absolutePath, saved.thumbnailPath)
                assertTrue(fixture.canvas.hasUnsavedChanges())
                assertEquals(saved.thumbnailPath, fixture.canvas.saveCanvas(PROJECT))
            } finally {
                fixture.close()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun failedMetadataPublicationPreservesTheCommitAndNewerDirtyEdits() = callbackFailurePreservesCommit(cancel = false)

    @Test
    fun cancelledMetadataPublicationPreservesTheCommitAndNewerDirtyEdits() = callbackFailurePreservesCommit(cancel = true)

    private fun callbackFailurePreservesCommit(cancel: Boolean) =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val fixture = Fixture(temporary.newFolder("callback"))
            val release = CompletableDeferred<Unit>()
            try {
                fixture.open()
                fixture.canvas.setCanvasBackgroundColor(RED)
                val entered = CompletableDeferred<SavedCanvas>()
                val saving =
                    async {
                        runCatching {
                            fixture.canvas.saveCanvasSnapshot(PROJECT) { saved ->
                                entered.complete(saved)
                                release.await()
                                throw IOException("Gallery update refused")
                            }
                        }
                    }
                val saved = await { entered.await() }
                val document = fixture.storage.documentFile(PROJECT).readBytes()
                val flattened = fixture.storage.resolve(PROJECT, ProjectStorage.FLATTENED_NAME).readBytes()
                val thumbnail = File(saved.thumbnailPath).readBytes()
                fixture.editWhileSaving()
                if (cancel) {
                    saving.cancelAndJoin()
                    assertTrue(saving.isCancelled)
                } else {
                    release.complete(Unit)
                    assertEquals("Gallery update refused", saving.await().exceptionOrNull()?.message)
                }
                assertArrayEquals(document, fixture.storage.documentFile(PROJECT).readBytes())
                assertArrayEquals(flattened, fixture.storage.resolve(PROJECT, ProjectStorage.FLATTENED_NAME).readBytes())
                assertArrayEquals(thumbnail, File(saved.thumbnailPath).readBytes())
                assertTrue(fixture.canvas.hasUnsavedChanges())
                assertEquals(12, fixture.canvas.getCanvasSize().width)
                assertEquals(300, fixture.canvas.getCanvasSize().dpi)
                assertEquals(2, fixture.canvas.getAllLayers().size)
                // Both exceptional exits release the save admission; the real newer snapshot can be saved.
                val retry = await { fixture.canvas.saveCanvasSnapshot(PROJECT) }
                assertEquals(12, retry.size.width)
                assertFalse(fixture.canvas.hasUnsavedChanges())
            } finally {
                release.complete(Unit)
                fixture.close()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun aSecondSaveCannotPublishItsMetadataBeforeTheFirstSaveCallbackFinishes() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val fixture = Fixture(temporary.newFolder("ordered"))
            val releaseFirst = CompletableDeferred<Unit>()
            try {
                fixture.open()
                val firstEntered = CompletableDeferred<SavedCanvas>()
                val publications = mutableListOf<SavedCanvas>()
                val first =
                    async {
                        fixture.canvas.saveCanvasSnapshot(PROJECT) { saved ->
                            firstEntered.complete(saved)
                            releaseFirst.await()
                            publications.add(saved)
                        }
                    }
                val original = await { firstEntered.await() }
                fixture.editWhileSaving()
                // UNDISPATCHED executes the second operation up to its first suspension: the save lock.
                val second =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        fixture.canvas.saveCanvasSnapshot(PROJECT) { publications.add(it) }
                    }
                assertFalse(second.isCompleted)
                assertTrue(publications.isEmpty())
                assertEquals(original.size.width, fixture.storage.loadDocument(PROJECT)!!.width)
                releaseFirst.complete(Unit)
                assertEquals(original, first.await())
                val latest = second.await()
                assertEquals(listOf(original, latest), publications)
                assertEquals(12, latest.size.width)
                assertEquals(latest.size.width, fixture.storage.loadDocument(PROJECT)!!.width)
                assertFalse(fixture.canvas.hasUnsavedChanges())
            } finally {
                releaseFirst.complete(Unit)
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
        private var row = Project(PROJECT, "Saved artwork", "", null, 8, 6, 72, 1, 1)
        var published: Project? = null
        private val projects =
            object : ProjectRepository by mock(ProjectRepository::class.java) {
                override suspend fun getProjectById(projectId: Long): Project? = row.takeIf { it.id == projectId }

                override suspend fun updateSavedArtwork(project: Project): Project {
                    published = project
                    row = project
                    return project
                }
            }
        private val settings =
            mock(SettingsRepository::class.java).also {
                `when`(it.settings).thenReturn(flowOf(AppSettings(autosaveEnabled = false)))
            }
        private val timelapse =
            mock(TimelapseRecorder::class.java) { call ->
                if (call.method.name == "isRecording") true else RETURNS_DEFAULTS.answer(call)
            }
        val viewModel =
            CanvasViewModel(canvas, projects, settings, ArtworkExporter(context, storage), timelapse)

        suspend fun open() {
            viewModel.open(PROJECT)
            await { viewModel.uiState.first { it is CanvasUiState.Ready } }
        }

        suspend fun editWhileSaving() {
            assertTrue(canvas.resizeCanvas(12, 10, false))
            assertTrue(canvas.setCanvasDpi(300))
            canvas.addLayer("Newer unsaved layer")
            canvas.setCanvasBackgroundColor(BLUE)
        }

        suspend fun awaitSaveFinished() {
            await { viewModel.saving.first { !it } }
        }

        suspend fun close() {
            io.resumeAll()
            viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
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
            if (gate == null) {
                Dispatchers.IO.dispatch(context, block)
            } else {
                gate.hold { Dispatchers.IO.dispatch(context, block) }
            }
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

        suspend fun awaitEntered() = await { entered.await() }

        @Synchronized
        fun resume() {
            released = true
            resume?.invoke()
            resume = null
        }
    }

    private companion object {
        const val PROJECT = 78001L
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()

        suspend fun <T> await(block: suspend () -> T): T = withContext(Dispatchers.Default) { withTimeout(5_000) { block() } }
    }
}
