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
import com.artflow.studio.core.export.AbrReader
import com.artflow.studio.data.local.BrushArchives
import com.artflow.studio.data.local.BrushFiles
import com.artflow.studio.data.local.BrushShareFiles
import com.artflow.studio.data.local.BrushShareFormat
import com.artflow.studio.data.local.GrainStorage
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.SavedBrush
import com.artflow.studio.domain.model.brush.StudioBrushes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
                val brushes =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val bytes = context.contentResolver.openInputStream(uri)?.use { BrushArchives.readInput(it) }
                            require(bytes != null && bytes.size <= MAX_ARCHIVE_BYTES) { "Unreadable brush file" }
                            if (BrushArchives.isArchive(bytes)) {
                                importArchive(context, bytes)
                            } else if (AbrReader.isAbr(bytes)) {
                                importAbr(context, bytes)
                            } else {
                                require(bytes.size <= BrushFiles.MAX_BYTES) { "Unreadable brush file" }
                                importBrushes(context, bytes.toString(Charsets.UTF_8))
                            }
                        }.getOrNull()
                    }
                if (brushes == null) {
                    onFailure(context.getString(R.string.brushfilebuttons_not_a_brush_file))
                } else {
                    latestControls.importAll(brushes)
                }
            }
        }
    TextButton(onClick = { choosingFormat = true }, enabled = enabled) { Text(stringResource(R.string.brush_share_confirm)) }
    TextButton(onClick = { runCatching { picker.launch("*/*") } }, enabled = enabled) { Text("Import") }
    if (choosingFormat) {
        BrushShareDialog(
            isSet = false,
            onDismiss = { choosingFormat = false },
            onShare = { format ->
                choosingFormat = false
                scope.launch {
                    val name = StudioBrushes.presets.firstOrNull { it.parameters == parameters }?.name ?: "Custom brush"
                    if (!shareBrushes(context, name, listOf(SavedBrush("shared", name, parameters)), format)) {
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
    return try {
        val file =
            withContext(Dispatchers.IO) {
                val encoded = BrushShareFiles.encode(name, brushes, format)
                val directory = File(context.filesDir, "exports/brushes")
                check(directory.isDirectory || directory.mkdirs()) { "The brush export directory could not be created" }
                File(directory, encoded.fileName).apply { writeBytes(encoded.bytes) }
            }
        withContext(Dispatchers.Main) { share(context, file) }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failed: IOException) {
        false
    } catch (failed: IllegalArgumentException) {
        false
    } catch (failed: IllegalStateException) {
        false
    } catch (failed: ActivityNotFoundException) {
        false
    } catch (failed: SecurityException) {
        false
    }
}

/** Procreate `.brush` and `.brushset` files: each brush comes in with its name, settings, shape and grain. */
private fun importArchive(
    context: Context,
    bytes: ByteArray,
): List<SavedBrush> {
    val directory = GrainStorage.directory(context.filesDir)
    return BrushArchives.read(bytes, BitmapPixelBridge::fromEncodedBytes).mapIndexed { index, brush ->
        val parameters = brush.parameters { GrainStorage.save(directory, it) }
        SavedBrush("imported-$index", brush.name ?: "Imported brush ${index + 1}", parameters)
    }
}

/** Photoshop `.abr` files: each sampled tip comes in as a brush shape. */
private fun importAbr(
    context: Context,
    bytes: ByteArray,
): List<SavedBrush> {
    val directory = GrainStorage.directory(context.filesDir)
    return BrushArchives.readAbr(bytes).mapIndexed { index, brush ->
        val shape = brush.shape?.let { GrainStorage.save(directory, it) }
        SavedBrush("imported-abr-$index", brush.name ?: "Photoshop brush ${index + 1}", BrushArchives.parameters(shape, null))
    }
}

private const val MAX_ARCHIVE_BYTES = 64 * 1024 * 1024

/** Stores the file's images under their content identities and points the brushes at them. */
private fun importBrushes(
    context: Context,
    text: String,
): List<SavedBrush> {
    val (brushes, images) = BrushFiles.decode(text)
    val directory = GrainStorage.directory(context.filesDir)
    val renamed = images.mapValues { (_, tile) -> GrainStorage.save(directory, tile) }
    return brushes.map { brush ->
        brush.copy(parameters = remapImages(brush.parameters, renamed))
    }
}

private fun remapImages(
    params: BrushParams,
    renamed: Map<String, String>,
): BrushParams =
    params.copy(
        textureId = params.textureId?.let { renamed[it] ?: it },
        shapeId = params.shapeId?.let { renamed[it] ?: it },
        dual = params.dual?.let { it.copy(params = remapImages(it.params, renamed)) },
    )

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
