package com.artflow.studio.presentation.ui.screens.gallery

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.core.content.FileProvider
import com.artflow.studio.R
import com.artflow.studio.core.lifecycle.withCleanup
import com.artflow.studio.data.local.ShareExportFiles
import com.artflow.studio.domain.model.Project
import com.artflow.studio.presentation.ui.viewmodel.MainViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import timber.log.Timber
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** Shares the last saved image of each artwork in [projects]; says so when none of them has been saved yet. */
internal suspend fun shareProjects(
    viewModel: MainViewModel,
    projects: List<Project>,
    context: Context,
    snackbar: SnackbarHostState,
) {
    viewModel.galleryShares.run(projects.map(Project::id)) {
        val files = mutableListOf<File>()
        val failure =
            try {
                withCleanup({ ShareExportFiles.finishAll(files) }) {
                    projects.forEach { project -> viewModel.shareablePng(project)?.let(files::add) }
                    coroutineContext.ensureActive()
                    when {
                        files.isEmpty() -> snackbar.showSnackbar(context.getString(R.string.galleryscreen_open_artwork_to_share))
                        files.size == 1 -> sharePng(context, files.single())
                        else -> sharePngs(context, files)
                    }
                    files.forEach(ShareExportFiles::markPublished)
                    return@run
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: IOException) {
                failed
            } catch (failed: IllegalArgumentException) {
                failed
            } catch (failed: IllegalStateException) {
                failed
            } catch (failed: ActivityNotFoundException) {
                failed
            } catch (failed: SecurityException) {
                failed
            } catch (failed: UnsupportedOperationException) {
                failed
            }
        Timber.w(failure, "Could not share gallery artwork")
        snackbar.showSnackbar(context.getString(R.string.gallery_share_failed))
    }
}

/** Opens the system share sheet for several exported PNGs in the app's shareable exports folder. */
private fun sharePngs(
    context: Context,
    files: List<File>,
) {
    val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) }
    val clip = ClipData.newRawUri(files.first().name, uris.first())
    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
    val send =
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/png"
            clipData = clip
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    context.startActivity(Intent.createChooser(send, context.getString(R.string.gallery_share_chooser)))
}
