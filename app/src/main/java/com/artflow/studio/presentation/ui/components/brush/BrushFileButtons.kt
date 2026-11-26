package com.artflow.studio.presentation.ui.components.brush

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import com.artflow.studio.R
import com.artflow.studio.core.lifecycle.withCleanup
import com.artflow.studio.data.export.importFailureMessage
import com.artflow.studio.data.local.BrushArchives
import com.artflow.studio.data.local.BrushImports
import com.artflow.studio.data.local.BrushShareFiles
import com.artflow.studio.data.local.BrushShareFormat
import com.artflow.studio.data.local.ShareExportFiles
import com.artflow.studio.data.local.StorageFileTree
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.SavedBrush
import com.artflow.studio.domain.model.brush.StudioBrushes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException

/** Share the brush being edited as an `.artbrush` or Procreate `.brush` file, or import brushes (ArtFlow, Procreate or Photoshop). */
@Composable
internal fun BrushFileButtons(
    parameters: BrushParams,
    controls: BrushLibraryControls,
    enabled: Boolean,
    onFailure: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestControls by rememberUpdatedState(controls)
    var choosingFormat by remember { mutableStateOf(false) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val failure =
                    runCatching {
                        val bytes =
                            withContext(Dispatchers.IO) {
                                requireNotNull(context.contentResolver.openInputStream(uri)?.use { BrushArchives.readInput(it) }) {
                                    "Unreadable brush file"
                                }
                            }
                        requireNotNull(BrushImports.importInto(context, bytes, latestControls.importAll)) {
                            context.getString(R.string.brushfilebuttons_not_a_brush_file)
                        }
                    }.exceptionOrNull()
                if (failure != null) {
                    onFailure(importFailureMessage(failure, context.getString(R.string.brushfilebuttons_not_a_brush_file)))
                }
            }
        }
    TextButton(onClick = { choosingFormat = true }, enabled = enabled) { Text(stringResource(R.string.brush_share_confirm)) }
    TextButton(onClick = { runCatching { picker.launch("*/*") } }, enabled = enabled) { Text("Import") }
    if (choosingFormat) {
        val name = StudioBrushes.presets.firstOrNull { it.parameters == parameters }?.name ?: "Custom brush"
        val brushes = listOf(SavedBrush("shared", name, parameters))
        BrushShareDialog(
            isSet = false,
            brushes = brushes,
            onDismiss = { choosingFormat = false },
            onShare = { format ->
                choosingFormat = false
                scope.launch {
                    if (!shareBrushes(context, name, brushes, format)) {
                        onFailure(context.getString(R.string.brushfilebuttons_could_not_share))
                    }
                }
            },
        )
    }
}

/** Writes the chosen format off the UI thread, then opens the platform chooser on the main thread. */
internal suspend fun shareBrushes(
    context: Context,
    name: String,
    brushes: List<SavedBrush>,
    format: BrushShareFormat = BrushShareFormat.ARTFLOW,
): Boolean {
    if (brushes.isEmpty()) return false
    var file: File? = null
    return try {
        withCleanup({ file?.let { ShareExportFiles.finish(it) } }) {
            val (root, encoded) =
                withContext(Dispatchers.IO) {
                    val encoded = BrushShareFiles.encode(name, brushes, format)
                    val root = File(context.filesDir, "exports/brushes")
                    StorageFileTree.requireDirectory(root, context.filesDir, "The brush export directory could not be created")
                    root to encoded
                }
            val prepared = ShareExportFiles.create(root, encoded.fileName) { it.write(encoded.bytes) }
            file = prepared
            withContext(Dispatchers.Main) {
                share(context, prepared)
                ShareExportFiles.markPublished(prepared)
            }
            true
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failed: IOException) {
        brushShareFailure(failed)
    } catch (failed: IllegalArgumentException) {
        brushShareFailure(failed)
    } catch (failed: IllegalStateException) {
        brushShareFailure(failed)
    } catch (failed: ActivityNotFoundException) {
        brushShareFailure(failed)
    } catch (failed: SecurityException) {
        brushShareFailure(failed)
    } catch (failed: UnsupportedOperationException) {
        brushShareFailure(failed)
    }
}

private fun brushShareFailure(failure: Exception): Boolean {
    Timber.w(failure, "Could not share brush")
    return false
}

private fun share(
    context: Context,
    file: File,
) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            clipData = ClipData.newRawUri(file.name, uri)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    context.startActivity(Intent.createChooser(send, "Share brush"))
}
