package com.artflow.studio.presentation.ui.components.brush

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import com.artflow.studio.R
import com.artflow.studio.data.local.BrushFiles
import com.artflow.studio.data.local.BrushImports
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.SavedBrush
import com.artflow.studio.domain.model.brush.StudioBrushes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Share the brush being edited as an `.artbrush` file, or import brushes (ArtFlow, Procreate or Photoshop). */
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
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val brushes =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            require(bytes != null && bytes.size <= BrushImports.MAX_BYTES) { "Unreadable brush file" }
                            requireNotNull(BrushImports.read(context, bytes)) { "Unreadable brush file" }
                        }.getOrNull()
                    }
                if (brushes == null) {
                    onFailure(context.getString(R.string.brushfilebuttons_not_a_brush_file))
                } else {
                    latestControls.importAll(brushes)
                }
            }
        }
    TextButton(onClick = {
        scope.launch {
            val name = StudioBrushes.presets.firstOrNull { it.parameters == parameters }?.name ?: "Custom brush"
            val file =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val directory = File(context.filesDir, "exports/brushes").apply { mkdirs() }
                        File(directory, BrushFiles.fileName(name)).apply {
                            writeText(BrushFiles.encode(listOf(SavedBrush("shared", name, parameters))))
                        }
                    }.getOrNull()
                }
            if (file == null) {
                onFailure(context.getString(R.string.brushfilebuttons_could_not_share))
            } else {
                runCatching { share(context, file) }
            }
        }
    }, enabled = enabled) { Text(stringResource(R.string.export_share)) }
    TextButton(
        onClick = { runCatching { picker.launch("*/*") } },
        enabled = enabled,
    ) { Text(stringResource(R.string.brushfilebuttons_import)) }
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
