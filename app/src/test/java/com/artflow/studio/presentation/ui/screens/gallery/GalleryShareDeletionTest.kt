package com.artflow.studio.presentation.ui.screens.gallery

import android.content.Context
import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.lifecycle.viewModelScope
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.ShareExportFiles
import com.artflow.studio.domain.model.Project
import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.domain.repository.ProjectRepository
import com.artflow.studio.domain.repository.settings.SettingsRepository
import com.artflow.studio.presentation.ui.viewmodel.MainViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.io.File

/** Exercises the real gallery share caller and delete command with an independently blocked repository read. */
@OptIn(ExperimentalCoroutinesApi::class)
class GalleryShareDeletionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun deletingAPreparedArtworkCancelsTheBlockedBatchBeforeChooserAndPreservesOtherShares() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = Fixture(temporary.newFolder())
            try {
                val earlier = requireNotNull(fixture.viewModel.shareablePng(fixture.artworks[2]))
                ShareExportFiles.markPublished(earlier)
                ShareExportFiles.finish(earlier)
                val earlierBytes = earlier.readBytes()
                fixture.blockedProject = 2
                val snackbar = mock(SnackbarHostState::class.java)
                val share = async { shareProjects(fixture.viewModel, fixture.artworks.take(2), fixture.context, snackbar) }
                fixture.lookupStarted.await()
                val exports = File(fixture.files, "exports/1")
                assertTrue(exports.walkTopDown().any { it.extension == "png" })
                assertEquals(1, fixture.storage.sharedCopySummary().activeCount)

                val deleted = async { fixture.viewModel.messageFlow.first { it.startsWith("Deleted ") } }
                fixture.viewModel.delete(fixture.artworks.first())
                val failure = runCatching { share.await() }.exceptionOrNull()

                assertTrue(failure is CancellationException)
                assertEquals("Deleted Artwork 1", deleted.await())
                verify(fixture.context, never()).startActivity(any(Intent::class.java))
                verifyNoInteractions(snackbar)
                assertFalse(exports.exists())
                assertFalse(File(fixture.files, "projects/1").exists())
                assertArrayEquals(byteArrayOf(2, 3, 4), fixture.storage.flattenedFile(2).readBytes())
                assertArrayEquals(earlierBytes, earlier.readBytes())
                assertEquals(0, fixture.storage.sharedCopySummary().activeCount)
            } finally {
                fixture.viewModel.viewModelScope.cancel()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun staleCardAfterDeletionCannotRecreateProjectDirectoriesOrShareOrphanedBytes() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = Fixture(temporary.newFolder())
            try {
                val stale = fixture.artworks.first()
                val deleted = async { fixture.viewModel.messageFlow.first { it.startsWith("Deleted ") } }
                fixture.viewModel.delete(stale)
                deleted.await()

                assertNull(fixture.viewModel.shareablePng(stale))
                assertFalse(File(fixture.files, "projects/1").exists())
                assertFalse(File(fixture.files, "exports/1").exists())

                // A failed filesystem removal can leave bytes after the row was deleted.
                val orphan = fixture.storage.flattenedFile(stale.id).apply { writeBytes(byteArrayOf(9, 8, 7)) }
                assertNull(fixture.viewModel.shareablePng(stale))
                assertArrayEquals(byteArrayOf(9, 8, 7), orphan.readBytes())
                assertFalse(File(fixture.files, "exports/1").exists())
            } finally {
                fixture.viewModel.viewModelScope.cancel()
                Dispatchers.resetMain()
            }
        }

    private class Fixture(
        val files: File,
    ) {
        val context: Context = mock(Context::class.java).also { `when`(it.filesDir).thenReturn(files) }
        val storage = ProjectStorage(context)
        val artworks = (1L..3L).map(::project)
        private val rows = artworks.associateByTo(LinkedHashMap(), Project::id)
        val lookupStarted = CompletableDeferred<Unit>()
        var blockedProject: Long? = null
        private val repository =
            object : ProjectRepository by mock(ProjectRepository::class.java) {
                // Deliberately hold the list refresh: an existing card can remain in the UI after deletion.
                override fun getAllProjects() = flowOf(artworks)

                override suspend fun getProjectById(projectId: Long): Project? {
                    if (projectId == blockedProject) {
                        lookupStarted.complete(Unit)
                        awaitCancellation()
                    }
                    return rows[projectId]
                }

                override suspend fun deleteProjectById(projectId: Long) {
                    rows.remove(projectId)
                }
            }
        private val settings =
            mock(SettingsRepository::class.java).also {
                `when`(it.settings).thenReturn(flowOf(AppSettings()))
            }
        val viewModel = MainViewModel(repository, settings, storage)

        init {
            artworks.forEach { storage.flattenedFile(it.id).writeBytes(byteArrayOf(it.id.toByte(), 3, 4)) }
        }
    }

    private companion object {
        fun project(id: Long) =
            Project(
                id = id,
                name = "Artwork $id",
                filePath = "",
                thumbnailPath = null,
                width = 32,
                height = 32,
                dpi = 300,
                createdAt = 0,
                modifiedAt = 0,
            )
    }
}
