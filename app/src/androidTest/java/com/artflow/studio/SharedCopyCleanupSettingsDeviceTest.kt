package com.artflow.studio

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.ShareExportFiles
import com.artflow.studio.data.local.StorageFileTree
import com.artflow.studio.data.local.database.ArtFlowDatabase
import com.artflow.studio.data.repository.ProjectRepositoryImpl
import com.artflow.studio.data.repository.settings.SettingsRepositoryImpl
import com.artflow.studio.presentation.ui.screens.settings.SettingsScreen
import com.artflow.studio.presentation.ui.viewmodel.MainUiState
import com.artflow.studio.presentation.ui.viewmodel.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Full SettingsScreen -> real MainViewModel -> real private storage, including cancel and partial failure. */
@RunWith(AndroidJUnit4::class)
class SharedCopyCleanupSettingsDeviceTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var application: Context
    private lateinit var directory: File
    private lateinit var storage: ProjectStorage
    private lateinit var database: ArtFlowDatabase
    private lateinit var viewModel: MainViewModel
    private val pending = mutableListOf<File>()

    @Before
    fun setup() {
        application = ApplicationProvider.getApplicationContext()
        directory = File(application.filesDir, "shared-copy-settings-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context =
            object : ContextWrapper(application) {
                override fun getFilesDir(): File = directory
            }
        storage = ProjectStorage(context)
        database = Room.inMemoryDatabaseBuilder(application, ArtFlowDatabase::class.java).build()
        viewModel =
            MainViewModel(
                ProjectRepositoryImpl(database.projectDao(), storage, database),
                SettingsRepositoryImpl(database.settingsDao()),
                storage,
            )
    }

    @After
    fun cleanup() {
        runBlocking {
            ShareExportFiles.finishAll(pending)
            viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        }
        database.close()
        StorageFileTree.delete(directory, application.filesDir)
    }

    @Test
    fun cancellingKeepsLinksAndConfirmingRemovesOnlySharedCopiesWithAccurateStorage() {
        val completed = runBlocking { published() }
        val active = runBlocking { ShareExportFiles.create(storage.exportsDir(19), "Pending.png") { it.write(4) } }
        pending += active
        val preserved =
            listOf(
                storage.documentFile(19),
                storage.flattenedFile(19),
                File(storage.exportsDir(19), "Ordinary.png"),
                File(storage.exportsDir(19), "timelapse_snap_19/frame.png"),
                File(storage.exportsDir(19), "share-old/Legacy.png"),
            ).associateWith { file ->
                file.parentFile!!.mkdirs()
                "Keep ${file.name}".toByteArray().also(file::writeBytes)
            }
        val before = storage.totalStorageBytes()
        val sharedBytes = StorageFileTree.sizeBytes(requireNotNull(completed.parentFile), directory)
        showSettings()
        waitForCount(1)
        compose
            .onNodeWithText("Remove shared copies")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.onNodeWithText("Old shared links will stop opening.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()

        compose.onNodeWithText("Remove shared copies?").assertDoesNotExist()
        assertTrue(completed.exists())
        assertEquals(before, storage.totalStorageBytes())

        compose.onNodeWithText("Remove shared copies").performScrollTo().performClick()
        compose.onNodeWithText("Remove copies").performClick()
        waitForCount(0)

        assertFalse(requireNotNull(completed.parentFile).exists())
        assertArrayEquals(byteArrayOf(4), active.readBytes())
        preserved.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        assertEquals(before - sharedBytes, storage.totalStorageBytes())
        assertEquals(storage.totalStorageBytes(), (viewModel.uiState.value as MainUiState.Success).storageBytes)
        assertEquals(
            1,
            viewModel.sharedCopyStorage.value.summary
                ?.activeCount,
        )
        compose.onNodeWithText("Removed 1 shared copy", substring = true).assertIsDisplayed()
    }

    @Test
    fun partialCleanupKeepsTheProblemCopyAndRefreshesTheRealSettingsWarningAndTotal() {
        val good = runBlocking { published() }
        val blocked = runBlocking { published() }
        val sibling = File(blocked.parentFile, "Keep.txt").apply { writeText("Unexpected data") }
        val before = storage.totalStorageBytes()
        val removedBytes = StorageFileTree.sizeBytes(requireNotNull(good.parentFile), directory)
        showSettings()
        waitForCount(1)
        compose.onNodeWithText("Remove shared copies").performScrollTo().performClick()
        compose.onNodeWithText("Remove copies").performClick()
        waitForCount(0)

        assertFalse(good.exists())
        assertArrayEquals(byteArrayOf(1, 2, 3), blocked.readBytes())
        assertEquals("Unexpected data", sibling.readText())
        assertEquals(
            1,
            viewModel.sharedCopyStorage.value.summary
                ?.unreadableCount,
        )
        assertEquals(before - removedBytes, storage.totalStorageBytes())
        assertEquals(storage.totalStorageBytes(), (viewModel.uiState.value as MainUiState.Success).storageBytes)
        compose
            .onNodeWithText("Some shared-copy folders could not be inspected. Their files will be kept.")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Some shared-copy folders could not be cleaned", substring = true).assertIsDisplayed()
    }

    private fun showSettings() {
        compose.setContent { MaterialTheme { SettingsScreen(onNavigateBack = {}, viewModel = viewModel) } }
    }

    private fun waitForCount(count: Int) {
        compose.waitUntil(10_000) {
            val state = viewModel.sharedCopyStorage.value
            !state.busy && state.summary?.count == count && viewModel.uiState.value is MainUiState.Success
        }
        compose.waitForIdle()
    }

    private suspend fun published(): File =
        ShareExportFiles.create(storage.exportsDir(19), "Artwork.png") { it.write(byteArrayOf(1, 2, 3)) }.also {
            ShareExportFiles.markPublished(it)
            ShareExportFiles.finish(it)
        }
}
