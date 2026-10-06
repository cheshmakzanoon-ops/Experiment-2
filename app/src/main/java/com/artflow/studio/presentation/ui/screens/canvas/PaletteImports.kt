package com.artflow.studio.presentation.ui.screens.canvas

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.artflow.studio.core.color.Palette
import com.artflow.studio.core.color.PaletteCodec
import com.artflow.studio.core.color.PaletteExtractor
import com.artflow.studio.data.renderer.BitmapPixelBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The Palettes tab's ways to add a palette (from a swatch file or a photo's colours) and to share one. */
class PaletteImports(
    val fromFile: () -> Unit,
    val fromPhoto: () -> Unit,
    val share: (Palette) -> Unit,
)

@Composable
fun rememberPaletteImports(
    onPalette: (String, List<Int>) -> Unit,
    onError: (String) -> Unit,
): PaletteImports {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val file =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val palette =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val bytes = requireNotNull(context.contentResolver.openInputStream(uri)?.use { it.readBytes() })
                            require(bytes.size <= MAX_PALETTE_BYTES)
                            val name = displayName(context, uri)
                            PaletteCodec.decode(bytes, name)
                        }.getOrNull()
                    }
                if (palette == null || palette.colors.isEmpty()) {
                    onError("That file has no colours ArtFlow can read (ASE, GIMP, hex or ArtFlow palettes)")
                } else {
                    onPalette(palette.name, palette.colors)
                }
            }
        }
    val photo =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val colors =
                    withContext(Dispatchers.Default) {
                        runCatching {
                            PaletteExtractor.colors(requireNotNull(BitmapPixelBridge.decodeUri(context.contentResolver, uri)))
                        }.getOrNull()
                    }
                if (colors.isNullOrEmpty()) onError("No colours could be taken from that picture") else onPalette("Photo palette", colors)
            }
        }
    return PaletteImports(
        fromFile = { launchSafely(onError) { file.launch(arrayOf("*/*")) } },
        fromPhoto = { launchSafely(onError) { photo.launch("image/*") } },
        share = { palette ->
            scope.launch {
                val shared =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val directory = File(context.filesDir, "exports/palettes").apply { mkdirs() }
                            File(directory, paletteFileName(palette.name)).apply { writeBytes(PaletteCodec.exportAse(palette)) }
                        }.getOrNull()
                    }
                if (shared == null) onError("The palette could not be shared") else sharePaletteFile(context, shared, onError)
            }
        },
    )
}

/** Sends [file] to another app as Adobe Swatch Exchange, which Procreate and most art apps import. */
private fun sharePaletteFile(
    context: Context,
    file: File,
    onError: (String) -> Unit,
) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            clipData = ClipData.newRawUri(file.name, uri)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    launchSafely(onError) { context.startActivity(Intent.createChooser(send, "Share palette")) }
}

/** A file name for the palette called [name] that is safe on every platform. */
internal fun paletteFileName(name: String): String =
    name
        .filter { it.isLetterOrDigit() || it in " -_" }
        .trim()
        .ifEmpty { "Palette" }
        .take(MAX_NAME) + ".ase"

private fun launchSafely(
    onError: (String) -> Unit,
    launch: () -> Unit,
) {
    try {
        launch()
    } catch (missing: ActivityNotFoundException) {
        onError("No file picker is available on this device")
    }
}

private fun displayName(
    context: Context,
    uri: Uri,
): String =
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        ?.substringBeforeLast('.')
        ?.take(MAX_NAME)
        ?: "Imported palette"

private const val MAX_PALETTE_BYTES = 1_048_576
private const val MAX_NAME = 40
