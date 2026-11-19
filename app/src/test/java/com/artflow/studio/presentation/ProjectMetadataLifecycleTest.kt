package com.artflow.studio.presentation

import androidx.lifecycle.viewModelScope
import com.artflow.studio.data.export.ModelImports
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.domain.model.Project
import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.domain.repository.ProjectRepository
import com.artflow.studio.domain.repository.canvas.CanvasSize
import com.artflow.studio.domain.repository.canvas.SavedCanvas
import com.artflow.studio.domain.repository.settings.SettingsRepository
import com.artflow.studio.presentation.ui.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectMetadataLifecycleTest {
    @Test
    fun renamingAnOldGallerySelectionPreservesNewerArtworkAndOrganization() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = Fixture()
            val viewModel = fixture.viewModel()
            try {
                viewModel.rename(fixture.stale, "Renamed")
                runCurrent()
                val actual = fixture.rows.value.single()
                assertEquals(fixture.latest.copy(name = "Renamed", modifiedAt = actual.modifiedAt), actual)
                assertTrue(actual.modifiedAt >= fixture.latest.modifiedAt)
            } finally {
                viewModel.viewModelScope.cancel()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun aSaveFromAnOldEditorSnapshotPreservesNewerNameFavoriteAndStack() =
        runTest {
            val fixture = Fixture()
            val saved = SavedCanvas(fixture.stale.id, 5, "new-thumbnail.png", CanvasSize(64, 48, 300), 0, 3, 10)
            val updated = ModelImports.updateProject(fixture.projects, fixture.stale, saved)
            val expected =
                fixture.latest.copy(
                    width = 64,
                    height = 48,
                    dpi = 300,
                    layerCount = 3,
                    thumbnailPath = "new-thumbnail.png",
                    modifiedAt = updated.modifiedAt,
                )
            assertEquals(expected, fixture.rows.value.single())
            assertEquals(expected, updated)
            assertTrue(updated.modifiedAt >= fixture.latest.modifiedAt)
        }

    @Test
    fun aStaleRenameReportsADeletedGalleryRowWithoutRecreatingIt() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = Fixture()
            fixture.rows.value = emptyList()
            val viewModel = fixture.viewModel()
            try {
                viewModel.rename(fixture.stale, "Renamed")
                runCurrent()
                assertTrue(fixture.rows.value.isEmpty())
                assertEquals("This artwork no longer exists", withTimeout(1_000) { viewModel.messageFlow.first() })
            } finally {
                viewModel.viewModelScope.cancel()
                Dispatchers.resetMain()
            }
        }

    private class Fixture {
        val stale = Project(42, "Before", "document.json", "old.png", 8, 6, 72, 1, 2)
        val latest =
            stale.copy(
                name = "Artist's new name",
                width = 32,
                height = 24,
                dpi = 144,
                layerCount = 2,
                thumbnailPath = "latest.png",
                modifiedAt = 4,
                isFavorite = true,
                stack = "Finished",
            )
        val rows = MutableStateFlow(listOf(latest))

        // Model a backing store with whole-row updates and separate field-scoped operations.
        // The test intentionally supplies the original stale UI snapshot to the real callers.
        val projects =
            mock(ProjectRepository::class.java) { call ->
                when (call.method.name) {
                    "getAllProjects" -> rows
                    "getProjectById" -> rows.value.singleOrNull()
                    "updateProject" -> {
                        if (rows.value.isNotEmpty()) rows.value = listOf(call.getArgument(0))
                        Unit
                    }
                    "renameProject" -> {
                        val current = requireNotNull(rows.value.singleOrNull()) { "This artwork no longer exists" }
                        rows.value =
                            listOf(current.copy(name = call.getArgument(1), modifiedAt = maxOf(current.modifiedAt, call.getArgument(2))))
                        Unit
                    }
                    "updateSavedArtwork" -> {
                        val current = requireNotNull(rows.value.singleOrNull()) { "This artwork no longer exists" }
                        val saved = call.getArgument<Project>(0)
                        val updated =
                            current.copy(
                                thumbnailPath = saved.thumbnailPath,
                                width = saved.width,
                                height = saved.height,
                                dpi = saved.dpi,
                                layerCount = saved.layerCount,
                                modifiedAt = maxOf(current.modifiedAt, saved.modifiedAt),
                            )
                        rows.value = listOf(updated)
                        updated
                    }
                    else -> error("Unexpected project call: ${call.method.name}")
                }
            }

        fun viewModel(): MainViewModel {
            val settings = mock(SettingsRepository::class.java)
            `when`(settings.settings).thenReturn(flowOf(AppSettings()))
            val storage = mock(ProjectStorage::class.java)
            `when`(storage.totalStorageBytes()).thenReturn(0L)
            return MainViewModel(projects, settings, storage)
        }
    }
}
