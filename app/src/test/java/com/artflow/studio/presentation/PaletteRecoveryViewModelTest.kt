package com.artflow.studio.presentation

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.dao.SettingsDao
import com.artflow.studio.data.local.entity.SettingsEntity
import com.artflow.studio.data.repository.settings.SettingsRepositoryImpl
import com.artflow.studio.domain.repository.ProjectRepository
import com.artflow.studio.presentation.ui.viewmodel.MainUiState
import com.artflow.studio.presentation.ui.viewmodel.MainViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class PaletteRecoveryViewModelTest {
    @Test
    fun damagedPalettesDoNotPreventGalleryStartupAndBackupClosesBeforeReset() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = Fixture()
            val viewModel = fixture.viewModel
            try {
                assertTrue(viewModel.uiState.first { it is MainUiState.Success } is MainUiState.Success)
                assertTrue(viewModel.settings.value.paletteRecoveryRequired)
                var closed = false
                val output =
                    object : ByteArrayOutputStream() {
                        override fun close() {
                            assertTrue(fixture.repository.current().paletteRecoveryRequired)
                            closed = true
                            super.close()
                        }
                    }
                val resolver = mockk<ContentResolver>()
                val destination = mockk<Uri>()
                every { resolver.openOutputStream(destination, "wt") } returns output
                viewModel.backUpAndResetPalettes(resolver, destination)
                viewModel.paletteRecoveryRunning.first { !it }
                assertTrue(closed)
                assertEquals(fixture.original, output.toString(Charsets.UTF_8.name()))
                assertFalse(fixture.repository.current().paletteRecoveryRequired)
                assertEquals(1, fixture.writes)
            } finally {
                viewModel.viewModelScope.cancel()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun providerWriteFailureDoesNotResetAnything() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = Fixture()
            try {
                val resolver = mockk<ContentResolver>()
                val destination = mockk<Uri>()
                val output =
                    object : OutputStream() {
                        override fun write(value: Int): Unit = throw IOException("Storage is full")
                    }
                every { resolver.openOutputStream(destination, "wt") } returns output
                fixture.viewModel.backUpAndResetPalettes(resolver, destination)
                fixture.viewModel.paletteRecoveryRunning.first { !it }
                assertEquals(0, fixture.writes)
                assertTrue(fixture.repository.current().paletteRecoveryRequired)
                assertEquals(fixture.original, fixture.rows.value.single().value)
            } finally {
                fixture.viewModel.viewModelScope.cancel()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun providerCloseFailureDoesNotResetAnything() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = Fixture()
            try {
                val resolver = mockk<ContentResolver>()
                val destination = mockk<Uri>()
                val output =
                    object : ByteArrayOutputStream() {
                        override fun close(): Unit = throw IOException("Could not finalize the document")
                    }
                every { resolver.openOutputStream(destination, "wt") } returns output
                fixture.viewModel.backUpAndResetPalettes(resolver, destination)
                fixture.viewModel.paletteRecoveryRunning.first { !it }
                assertEquals(0, fixture.writes)
                assertTrue(fixture.repository.current().paletteRecoveryRequired)
                assertEquals(fixture.original, output.toString(Charsets.UTF_8.name()))
            } finally {
                fixture.viewModel.viewModelScope.cancel()
                Dispatchers.resetMain()
            }
        }

    private class Fixture {
        val original = "  {unreadable;; 🎨\n"
        val rows = MutableStateFlow(listOf(SettingsEntity(key = "color.palettes", value = original)))
        var writes = 0
        private val dao =
            mockk<SettingsDao>().also { dao ->
                every { dao.getAllSettings() } returns rows
                coEvery { dao.insertSettings(any()) } answers {
                    writes++
                    rows.value = firstArg()
                }
            }
        val repository = SettingsRepositoryImpl(dao)
        private val projects = mockk<ProjectRepository>().also { every { it.getAllProjects() } returns flowOf(emptyList()) }
        private val storage = mockk<ProjectStorage>().also { every { it.totalStorageBytes() } returns 0L }
        val viewModel = MainViewModel(projects, repository, storage)
    }
}
