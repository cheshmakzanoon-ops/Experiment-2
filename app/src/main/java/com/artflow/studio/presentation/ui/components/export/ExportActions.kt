package com.artflow.studio.presentation.ui.components.export

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.artflow.studio.core.export.ExportResult
import com.artflow.studio.presentation.ui.viewmodel.CanvasViewModel
import com.artflow.studio.presentation.ui.viewmodel.ExportUiState

/** Platform actions are lifecycle-aware and never assume an external viewer is installed. */
data class ExportActions(
    val share: (ExportResult) -> Unit,
    val open: (ExportResult) -> Unit,
    val saveFile: (ExportResult) -> Unit,
    val gallery: (ExportResult) -> Unit,
)

@Composable
fun rememberExportActions(viewModel: CanvasViewModel): ExportActions {
    val context = LocalContext.current
    var documentSource by rememberSaveable { mutableStateOf<String?>(null) }
    var gallerySource by rememberSaveable { mutableStateOf<String?>(null) }
    val saveDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { response ->
            val source = documentSource
            documentSource = null
            val destination = response.data?.data
            if (response.resultCode == Activity.RESULT_OK && source != null && destination != null) {
                viewModel.saveExportToDocument(source, destination)
            }
        }
    val requestWrite =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val source = gallerySource
            gallerySource = null
            val result = (viewModel.exportState.value as? ExportUiState.Done)?.result
            when {
                !granted -> viewModel.notify("Storage permission was not granted. Use Save file instead.")
                result != null && result.filePath == source -> viewModel.exportToGallery(result)
                else -> viewModel.notify("Please export again to save this artwork to the gallery")
            }
        }
    return ExportActions(
        share = { result -> openSafely(viewModel::notify) { context.startActivity(viewModel.shareIntent(result)) } },
        open = { result -> openSafely(viewModel::notify) { context.startActivity(viewModel.viewIntent(result)) } },
        saveFile = { result ->
            documentSource = result.filePath
            openSafely(viewModel::notify) {
                saveDocument.launch(
                    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = result.format.mimeType
                        putExtra(Intent.EXTRA_TITLE, result.fileName)
                    },
                )
            }
        },
        gallery = { result ->
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            ) {
                gallerySource = result.filePath
                requestWrite.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                viewModel.exportToGallery(result)
            }
        },
    )
}

private fun openSafely(
    notify: (String) -> Unit,
    launch: () -> Unit,
) {
    try {
        launch()
    } catch (missing: ActivityNotFoundException) {
        notify("No app can open this file here. Use Save file or install a compatible viewer.")
    } catch (denied: SecurityException) {
        notify("Android did not allow this file to be opened. Try Save file instead.")
    } catch (invalid: IllegalArgumentException) {
        notify(invalid.message ?: "This export is no longer available. Export it again.")
    }
}
