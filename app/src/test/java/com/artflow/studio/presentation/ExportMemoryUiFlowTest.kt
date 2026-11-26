package com.artflow.studio.presentation

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.artflow.studio.core.export.ExportArea
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.export.TimelapseRecorder
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.Project
import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.domain.repository.ProjectRepository
import com.artflow.studio.domain.repository.canvas.CanvasExportSnapshot
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.domain.repository.settings.SettingsRepository
import com.artflow.studio.presentation.ui.viewmodel.CanvasUiState
import com.artflow.studio.presentation.ui.viewmodel.CanvasViewModel
import com.artflow.studio.presentation.ui.viewmodel.ExportUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import javax.imageio.ImageIO

/** Real editor export states, owned pixels and saved files across refusal, retry and cancellation. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportMemoryUiFlowTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun fullCanvasResultFailureReportsMemoryAndAllowsSmallerRetry() = refusalRetry(selected = false)

    @Test
    fun selectedCropThrownFailureReportsMemoryAndAllowsSmallerRetry() = refusalRetry(selected = true)

    @Test
    fun openingAnotherArtworkClearsTheOldResultAndPreservesItsExportedFile() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val fixture = Fixture(temporary.newFolder("files"))
            try {
                fixture.open()
                fixture.viewModel.export(ExportOptions(ExportFormat.TIFF))
                val previous = await { fixture.viewModel.exportState.first { it is ExportUiState.Done || it is ExportUiState.Failed } }
                assertTrue(previous.toString(), previous is ExportUiState.Done)
                val previousFile = File((previous as ExportUiState.Done).result.filePath)
                val previousBytes = previousFile.readBytes()

                fixture.viewModel.open(OTHER_PROJECT)
                assertEquals(ExportUiState.Idle, fixture.viewModel.exportState.value)
                val opened = await { fixture.viewModel.uiState.first { it is CanvasUiState.Ready || it is CanvasUiState.Error } }
                assertTrue(opened.toString(), opened is CanvasUiState.Ready)
                assertEquals(OTHER_PROJECT, (opened as CanvasUiState.Ready).projectId)

                fixture.viewModel.export(ExportOptions(ExportFormat.TIFF))
                val next = await { fixture.viewModel.exportState.first { it is ExportUiState.Done || it is ExportUiState.Failed } }
                assertTrue(next.toString(), next is ExportUiState.Done)
                assertFalse(previousFile.path == (next as ExportUiState.Done).result.filePath)
                assertArrayEquals(previousBytes, previousFile.readBytes())
            } finally {
                fixture.close()
                Dispatchers.resetMain()
            }
        }

    private fun refusalRetry(selected: Boolean) =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val fixture = Fixture(temporary.newFolder("files"))
            try {
                fixture.open()
                if (selected) fixture.canvas.setSelection(SelectionMask.rectangle(16, 16, 4f, 4f, 12f, 12f))
                val saved = fixture.savedFiles()
                val dirty = fixture.canvas.hasUnsavedChanges()
                val pixels =
                    fixture.canvas
                        .layerPixels(fixture.canvas.getActiveLayerId())!!
                        .pixels
                        .copyOf()
                val coverage =
                    fixture.canvas
                        .selection()
                        ?.coverage
                        ?.copyOf()
                val area = if (selected) ExportArea.SELECTION else ExportArea.FULL_CANVAS
                fixture.viewModel.export(ExportOptions(ExportFormat.TIFF, area = area, targetWidth = 6000))
                val failure = await { fixture.viewModel.exportState.first { it is ExportUiState.Failed } } as ExportUiState.Failed
                assertTrue(failure.message.contains("Reduce the output scale"))
                assertFalse(fixture.viewModel.exportState.value is ExportUiState.Running)
                fixture.assertPreserved(saved, pixels, coverage, dirty)
                assertEquals(listOf(fixture.previous.absolutePath), fixture.storage.listExports(PROJECT).map { it.absolutePath })

                // No reset or new ViewModel is required to retry after failure.
                fixture.viewModel.export(ExportOptions(ExportFormat.TIFF, area = area, targetWidth = if (selected) 8 else 16))
                val finished = await { fixture.viewModel.exportState.first { it is ExportUiState.Done || it is ExportUiState.Failed } }
                assertTrue(finished.toString(), finished is ExportUiState.Done)
                val result = (finished as ExportUiState.Done).result
                val width = if (selected) 8 else 16
                val image = requireNotNull(ImageIO.read(File(result.filePath)))
                assertEquals(width, image.width)
                assertEquals(width, image.height)
                assertArrayEquals(IntArray(width * width) { INK }, image.getRGB(0, 0, width, width, null, 0, width))
                fixture.assertPreserved(saved, pixels, coverage, dirty)
            } finally {
                fixture.close()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun cancelledSnapshotReturnsToIdleAndNextExportSucceedsWithoutChangingSavedArtwork() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val fixture = Fixture(temporary.newFolder("files"), gateSnapshot = true)
            try {
                fixture.open()
                val saved = fixture.savedFiles()
                val dirty = fixture.canvas.hasUnsavedChanges()
                val pixels =
                    fixture.canvas
                        .layerPixels(fixture.canvas.getActiveLayerId())!!
                        .pixels
                        .copyOf()
                fixture.viewModel.export(ExportOptions(ExportFormat.TIFF))
                await { fixture.entered.await() }
                assertTrue(fixture.viewModel.exportState.value is ExportUiState.Running)
                fixture.viewModel.export(ExportOptions(ExportFormat.TIFF))
                assertEquals(1, fixture.snapshotCalls)
                fixture.release.complete(Unit)
                await { fixture.viewModel.exportState.first { it is ExportUiState.Idle } }
                fixture.assertPreserved(saved, pixels, null, dirty)
                assertEquals(listOf(fixture.previous.absolutePath), fixture.storage.listExports(PROJECT).map { it.absolutePath })
                assertTrue(
                    fixture.viewModel.viewModelScope.coroutineContext[Job]!!
                        .isActive,
                )

                fixture.gateSnapshot = false
                fixture.viewModel.export(ExportOptions(ExportFormat.TIFF))
                val result = await { fixture.viewModel.exportState.first { it is ExportUiState.Done || it is ExportUiState.Failed } }
                assertTrue(result.toString(), result is ExportUiState.Done)
                assertEquals(2, fixture.snapshotCalls)
                fixture.assertPreserved(saved, pixels, null, dirty)
            } finally {
                fixture.close()
                Dispatchers.resetMain()
            }
        }

    private class Fixture(
        files: File,
        var gateSnapshot: Boolean = false,
    ) {
        private val context = mock(Context::class.java).also { `when`(it.filesDir).thenReturn(files) }
        val storage = ProjectStorage(context)
        val canvas = CanvasRepositoryImpl(storage)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var snapshotCalls = 0
        lateinit var previous: File
        private val repo =
            object : CanvasRepository by canvas {
                override suspend fun exportSnapshot(
                    allFrames: Boolean,
                    includeHidden: Boolean,
                    includeLayers: Boolean,
                    preserveLayerMasks: Boolean,
                    animationPlayback: Boolean,
                    includeMaterials: Boolean,
                ): CanvasExportSnapshot {
                    snapshotCalls++
                    val snapshot =
                        canvas.exportSnapshot(
                            allFrames,
                            includeHidden,
                            includeLayers,
                            preserveLayerMasks,
                            animationPlayback,
                            includeMaterials,
                        )
                    if (gateSnapshot) {
                        entered.complete(Unit)
                        release.await()
                        throw CancellationException("Cancelled snapshot delivery")
                    }
                    return snapshot
                }
            }
        private val projects =
            object : ProjectRepository by mock(ProjectRepository::class.java) {
                override suspend fun getProjectById(projectId: Long): Project? =
                    Project(projectId, "Memory review", "", null, 16, 16, 72, 1L, 1L)
                        .takeIf { projectId == PROJECT || projectId == OTHER_PROJECT }
            }
        private val settings =
            mock(SettingsRepository::class.java).also {
                `when`(it.settings).thenReturn(flowOf(AppSettings(autosaveEnabled = false, recordTimelapse = false)))
            }
        val viewModel =
            CanvasViewModel(
                repo,
                projects,
                settings,
                ArtworkExporter(context, storage, 8L * 1024 * 1024),
                mock(TimelapseRecorder::class.java) { call ->
                    if (call.method.name == "isRecording") true else RETURNS_DEFAULTS.answer(call)
                },
            )

        suspend fun open() {
            canvas.loadOrCreate(PROJECT, 16, 16, 72)
            assertTrue(canvas.setLayerPixels(canvas.getActiveLayerId(), PixelBuffer.filled(16, 16, INK), "Review pixels"))
            canvas.saveCanvas(PROJECT)
            previous = storage.saveExport(PROJECT, "previous.tiff", byteArrayOf(3, 1, 4, 1, 5, 9))
            viewModel.open(PROJECT)
            await { viewModel.uiState.first { it is CanvasUiState.Ready || it is CanvasUiState.Error } }
            assertTrue(viewModel.uiState.value.toString(), viewModel.uiState.value is CanvasUiState.Ready)
        }

        fun savedFiles(): Map<String, ByteArray> =
            storage.projectDir(PROJECT).walkTopDown().filter { it.isFile }.associate {
                it.relativeTo(storage.projectDir(PROJECT)).path to
                    it.readBytes()
            }

        suspend fun assertPreserved(
            saved: Map<String, ByteArray>,
            pixels: IntArray,
            coverage: ByteArray?,
            dirty: Boolean,
        ) {
            val current = savedFiles()
            assertEquals(saved.keys, current.keys)
            saved.forEach { (path, bytes) -> assertArrayEquals(path, bytes, current.getValue(path)) }
            assertArrayEquals(pixels, canvas.layerPixels(canvas.getActiveLayerId())!!.pixels)
            assertArrayEquals(coverage, canvas.selection()?.coverage?.copyOf())
            assertArrayEquals(byteArrayOf(3, 1, 4, 1, 5, 9), previous.readBytes())
            assertEquals(dirty, canvas.hasUnsavedChanges())
        }

        suspend fun close() {
            viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            canvas.dispose()
        }
    }

    private companion object {
        const val PROJECT = 17L
        const val OTHER_PROJECT = 18L
        const val INK = 0xFF235A91.toInt()

        suspend fun <T> await(block: suspend () -> T): T = withContext(Dispatchers.Default) { withTimeout(5_000L) { block() } }
    }
}
