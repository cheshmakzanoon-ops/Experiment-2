package com.artflow.studio.presentation.ui.screens.gallery

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.lifecycle.viewModelScope
import com.artflow.studio.R
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.domain.model.Project
import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.domain.repository.ProjectRepository
import com.artflow.studio.domain.repository.settings.SettingsRepository
import com.artflow.studio.presentation.ui.viewmodel.MainViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.io.IOException
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryShareTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun preparingSavedImagesReportsStorageFailure() =
        runTest {
            val viewModel = mock(MainViewModel::class.java)
            val project = project(1)
            `when`(viewModel.shareablePng(project)).thenAnswer { throw IOException("No space left") }
            val snackbar = SnackbarHostState()
            val operation = async { shareProjects(viewModel, listOf(project), context(), snackbar) }

            runCurrent()

            assertEquals(SHARE_FAILED, snackbar.currentSnackbarData?.visuals?.message)
            snackbar.currentSnackbarData?.dismiss()
            operation.await()
        }

    @Test
    fun singleAndMultipleImageHandoffsReportProviderFailure() =
        runTest {
            for (count in 1..2) {
                val viewModel = mock(MainViewModel::class.java)
                val projects = (1..count).map { project(it.toLong()) }
                projects.forEach { `when`(viewModel.shareablePng(it)).thenReturn(File("${it.id}.png")) }
                val context = context()
                // Fail before FileProvider or the platform chooser can run on this JVM.
                `when`(context.packageName).thenThrow(SecurityException("Sharing unavailable"))
                val snackbar = SnackbarHostState()
                val operation = async { shareProjects(viewModel, projects, context, snackbar) }

                runCurrent()

                assertEquals(SHARE_FAILED, snackbar.currentSnackbarData?.visuals?.message)
                snackbar.currentSnackbarData?.dismiss()
                operation.await()
            }
        }

    @Test
    fun cancellationDuringPreparationOrHandoffDoesNotBecomeAnErrorSnackbar() =
        runTest {
            for (preparing in listOf(true, false)) {
                val viewModel = mock(MainViewModel::class.java)
                val project = project(1)
                val context = context()
                val cancellation = CancellationException("Leave gallery")
                if (preparing) {
                    `when`(viewModel.shareablePng(project)).thenThrow(cancellation)
                } else {
                    `when`(viewModel.shareablePng(project)).thenReturn(File("artwork.png"))
                    `when`(context.packageName).thenThrow(cancellation)
                }
                val snackbar = SnackbarHostState()

                val failure = runCatching { shareProjects(viewModel, listOf(project), context, snackbar) }.exceptionOrNull()

                assertSame(cancellation, failure)
                assertNull(snackbar.currentSnackbarData)
            }
        }

    @Test
    fun unsavedArtworkKeepsItsExistingSaveFirstMessage() =
        runTest {
            val viewModel = mock(MainViewModel::class.java)
            val snackbar = SnackbarHostState()
            val operation = async { shareProjects(viewModel, listOf(project(1)), context(), snackbar) }

            runCurrent()

            assertEquals(SAVE_FIRST, snackbar.currentSnackbarData?.visuals?.message)
            snackbar.currentSnackbarData?.dismiss()
            operation.await()
        }

    @Test
    fun galleryCopiesTheSavedPngAndKeepsTheArtworkSource() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val context = context()
            `when`(context.filesDir).thenReturn(temporary.newFolder("files"))
            val storage = ProjectStorage(context)
            val projects = mock(ProjectRepository::class.java)
            `when`(projects.getAllProjects()).thenReturn(flowOf(emptyList()))
            val settings = mock(SettingsRepository::class.java)
            `when`(settings.settings).thenReturn(flowOf(AppSettings()))
            val viewModel = MainViewModel(projects, settings, storage)
            try {
                val artwork = project(1).copy(name = "Ink / study")
                val source = storage.flattenedFile(artwork.id)
                val block = ByteArray(8192) { (it * 31).toByte() }
                source.outputStream().use { output ->
                    repeat(257) { output.write(block) }
                    output.write(byteArrayOf(7, 8, 9))
                }
                val before = digest(source)

                val shared = requireNotNull(viewModel.shareablePng(artwork))

                assertEquals("Ink  study.png", shared.name)
                assertEquals(source.length(), shared.length())
                assertFalse(source.canonicalFile == shared.canonicalFile)
                assertArrayEquals(before, digest(shared))
                assertArrayEquals(before, digest(source))
                assertNull(viewModel.shareablePng(project(2)))
            } finally {
                viewModel.viewModelScope.cancel()
                Dispatchers.resetMain()
            }
        }

    private fun context(): Context =
        mock(Context::class.java).also {
            `when`(it.getString(R.string.gallery_share_failed)).thenReturn(SHARE_FAILED)
            `when`(it.getString(R.string.galleryscreen_open_artwork_to_share)).thenReturn(SAVE_FIRST)
        }

    private fun project(id: Long) =
        Project(
            id = id,
            name = "Artwork",
            filePath = "",
            thumbnailPath = null,
            width = 32,
            height = 32,
            dpi = 300,
            createdAt = 0,
            modifiedAt = 0,
        )

    private fun digest(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private companion object {
        const val SHARE_FAILED = "The artwork could not be shared."
        const val SAVE_FIRST = "Open and save the artwork first."
    }
}
