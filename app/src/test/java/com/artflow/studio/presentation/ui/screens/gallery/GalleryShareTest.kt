package com.artflow.studio.presentation.ui.screens.gallery

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.lifecycle.viewModelScope
import com.artflow.studio.R
import com.artflow.studio.core.gallery.GalleryShareRequests
import com.artflow.studio.core.lifecycle.assertOriginalFailure
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
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
            val viewModel = viewModel()
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
                val viewModel = viewModel()
                val projects = (1..count).map { project(it.toLong()) }
                projects.forEach { `when`(viewModel.shareablePng(it)).thenReturn(File("${it.id}.png")) }
                val context = context()
                // Fail before FileProvider or the platform chooser can run on this JVM.
                `when`(context.packageName).thenThrow(SecurityException("Sharing unavailable"))
                val messagePrepared = CompletableDeferred<Unit>()
                `when`(context.getString(R.string.gallery_share_failed)).thenAnswer {
                    messagePrepared.complete(Unit)
                    SHARE_FAILED
                }
                val snackbar = SnackbarHostState()
                val operation = async { shareProjects(viewModel, projects, context, snackbar) }

                try {
                    // Cleanup uses real IO. Wait for its actual completion before observing the
                    // snackbar, rather than assuming one test-dispatcher turn includes IO return.
                    messagePrepared.await()
                    assertEquals(SHARE_FAILED, snackbar.currentSnackbarData?.visuals?.message)
                    snackbar.currentSnackbarData?.dismiss()
                    operation.await()
                } finally {
                    snackbar.currentSnackbarData?.dismiss()
                    withContext(NonCancellable) { operation.cancelAndJoin() }
                }
            }
        }

    @Test
    fun cancellationDuringPreparationOrHandoffDoesNotBecomeAnErrorSnackbar() =
        runTest {
            for (preparing in listOf(true, false)) {
                val viewModel = viewModel()
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

                assertOriginalFailure(cancellation, failure)
                assertNull(snackbar.currentSnackbarData)
            }
        }

    @Test
    fun unsavedArtworkKeepsItsExistingSaveFirstMessage() =
        runTest {
            val viewModel = viewModel()
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
                `when`(projects.getProjectById(artwork.id)).thenReturn(artwork)
                val source = storage.flattenedFile(artwork.id)
                val block = ByteArray(8192) { (it * 31).toByte() }
                source.outputStream().use { output ->
                    repeat(257) { output.write(block) }
                    output.write(byteArrayOf(7, 8, 9))
                }
                val before = digest(source)

                val shared = requireNotNull(viewModel.shareablePng(artwork))
                ShareExportFiles.markPublished(shared)
                ShareExportFiles.finish(shared)

                assertEquals("Ink  study.png", shared.name)
                assertEquals(source.length(), shared.length())
                assertFalse(source.canonicalFile == shared.canonicalFile)
                assertArrayEquals(before, digest(shared))
                assertArrayEquals(before, digest(source))
                assertNull(viewModel.shareablePng(project(2)))

                source.writeBytes(byteArrayOf(4, 5, 6))
                val next = requireNotNull(viewModel.shareablePng(artwork))
                ShareExportFiles.markPublished(next)
                ShareExportFiles.finish(next)
                assertEquals(shared.name, next.name)
                assertFalse(shared.canonicalFile == next.canonicalFile)
                assertArrayEquals(before, digest(shared))
                assertArrayEquals(source.readBytes(), next.readBytes())
            } finally {
                viewModel.viewModelScope.cancel()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun failedOrCancelledBatchPreparationRemovesOnlyItsUnpublishedFiles() =
        runTest {
            val directory = temporary.newFolder("exports")
            val previous = ShareExportFiles.create(directory, "Artwork.png") { it.write(byteArrayOf(9, 8, 7)) }
            ShareExportFiles.markPublished(previous)
            ShareExportFiles.finish(previous)
            for (cancelled in listOf(false, true)) {
                val unpublished = ShareExportFiles.create(directory, previous.name) { it.write(byteArrayOf(1, 2, 3)) }
                val viewModel = viewModel()
                `when`(viewModel.shareablePng(project(1))).thenReturn(unpublished)
                val expected = if (cancelled) CancellationException("Gallery closed") else IOException("No space left")
                `when`(viewModel.shareablePng(project(2))).thenAnswer { throw expected }
                val snackbar = mock(SnackbarHostState::class.java)

                val failure =
                    runCatching {
                        shareProjects(
                            viewModel,
                            listOf(project(1), project(2)),
                            context(),
                            snackbar,
                        )
                    }.exceptionOrNull()

                if (cancelled) {
                    assertOriginalFailure(expected, failure)
                    verifyNoInteractions(snackbar)
                } else {
                    assertNull(failure)
                    verify(snackbar).showSnackbar(SHARE_FAILED)
                }
                assertFalse(requireNotNull(unpublished.parentFile).exists())
                assertArrayEquals(byteArrayOf(9, 8, 7), previous.readBytes())
                assertEquals(listOf(previous.parentFile), directory.listFiles()!!.toList())
            }
        }

    @Test
    fun providerFailureRemovesTheUnpublishedCopyAndRetainsAnEarlierShare() =
        runTest {
            val directory = temporary.newFolder("exports")
            val previous = ShareExportFiles.create(directory, "Artwork.png") { it.write(byteArrayOf(9, 8, 7)) }
            ShareExportFiles.markPublished(previous)
            ShareExportFiles.finish(previous)
            val unpublished = ShareExportFiles.create(directory, previous.name) { it.write(byteArrayOf(1, 2, 3)) }
            val viewModel = viewModel()
            `when`(viewModel.shareablePng(project(1))).thenReturn(unpublished)
            val context = context()
            `when`(context.packageName).thenThrow(SecurityException("Sharing unavailable"))
            val snackbar = mock(SnackbarHostState::class.java)

            shareProjects(viewModel, listOf(project(1)), context, snackbar)

            verify(snackbar).showSnackbar(SHARE_FAILED)
            assertFalse(requireNotNull(unpublished.parentFile).exists())
            assertArrayEquals(byteArrayOf(9, 8, 7), previous.readBytes())
            assertEquals(listOf(previous.parentFile), directory.listFiles()!!.toList())
        }

    @Test
    fun cancelledBatchRetainsCleanupDiagnosticsAndReleasesEveryOtherCopy() =
        runTest {
            val exports = temporary.newFolder("exports")
            val directory = File(exports, "19").apply { mkdirs() }
            val blocked = ShareExportFiles.create(directory, "First.png") { it.write(1) }
            val removable = ShareExportFiles.create(directory, "Second.png") { it.write(2) }
            val sibling = File(blocked.parentFile, "Keep.txt").apply { writeText("Other data") }
            val viewModel = viewModel()
            `when`(viewModel.shareablePng(project(1))).thenReturn(blocked)
            `when`(viewModel.shareablePng(project(2))).thenReturn(removable)
            val expected = CancellationException("Gallery closed")
            `when`(viewModel.shareablePng(project(3))).thenThrow(expected)
            val snackbar = mock(SnackbarHostState::class.java)

            val failure =
                runCatching {
                    shareProjects(viewModel, (1L..3L).map(::project), context(), snackbar)
                }.exceptionOrNull()

            assertOriginalFailure(expected, failure)
            assertTrue(expected.suppressed.any { it is IOException })
            verifyNoInteractions(snackbar)
            assertArrayEquals(byteArrayOf(1), blocked.readBytes())
            assertEquals("Other data", sibling.readText())
            assertFalse(requireNotNull(removable.parentFile).exists())
            assertEquals(0, ShareExportFiles.summary(exports, temporary.root).activeCount)
            assertTrue(sibling.delete())
            assertEquals(1, ShareExportFiles.removeCompleted(exports, temporary.root).removedCount)
        }

    private fun viewModel(): MainViewModel =
        mock(MainViewModel::class.java).also {
            `when`(it.galleryShares).thenReturn(GalleryShareRequests())
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
