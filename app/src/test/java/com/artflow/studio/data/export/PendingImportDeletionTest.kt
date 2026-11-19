package com.artflow.studio.data.export

import androidx.lifecycle.viewModelScope
import com.artflow.studio.data.ModelImportFixture
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.domain.model.Project
import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.domain.repository.ProjectRepository
import com.artflow.studio.domain.repository.settings.SettingsRepository
import com.artflow.studio.presentation.ui.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PendingImportDeletionTest {
    @Test
    fun successfulDeletionReleasesTheClaimedModel() = deletion(fails = false)

    @Test
    fun aFailedRowDeletionKeepsTheClaimedModelAndArtworkFiles() = deletion(fails = true)

    private fun deletion(fails: Boolean) =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val project = Project(98_770, "Pending model", "", null, 4, 2, 72, 1, 1)
            val projects =
                mock(ProjectRepository::class.java) { call ->
                    when (call.method.name) {
                        "getAllProjects" -> flowOf(listOf(project))
                        "deleteProjectById" -> if (fails) throw IOException("Database could not delete this artwork") else Unit
                        else -> error("Unexpected project call: ${call.method.name}")
                    }
                }
            val settings = mock(SettingsRepository::class.java)
            `when`(settings.settings).thenReturn(flowOf(AppSettings()))
            val storage = mock(ProjectStorage::class.java)
            `when`(storage.totalStorageBytes()).thenReturn(0L)
            val viewModel = MainViewModel(projects, settings, storage)
            val model = ModelImportFixture.prepared()
            try {
                PendingImports.putModelBundle(project.id, model, backgroundScope)
                PendingImports.claimModelBundle(project.id)
                viewModel.delete(project)
                val result = withContext(Dispatchers.Default) { withTimeout(5_000) { viewModel.messageFlow.first() } }
                if (fails) {
                    assertEquals("Database could not delete this artwork", result)
                    assertSame(model, PendingImports.modelBundle(project.id))
                    verify(storage, never()).deleteProjectFiles(project.id)
                } else {
                    assertEquals("Deleted Pending model", result)
                    assertNull(PendingImports.modelBundle(project.id))
                    verify(storage).deleteProjectFiles(project.id)
                }
            } finally {
                viewModel.viewModelScope.cancel()
                PendingImports.discardProject(project.id)
                Dispatchers.resetMain()
            }
        }
}
