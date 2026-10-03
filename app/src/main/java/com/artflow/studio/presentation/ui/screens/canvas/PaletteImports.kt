package com.artflow.studio.presentation.ui.screens.canvas

import android.content.ActivityNotFoundException
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.artflow.studio.core.color.PaletteCodec
import com.artflow.studio.core.color.PaletteExtractor
import com.artflow.studio.data.renderer.BitmapPixelBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Palettes tab's ways to add a palette: from a swatch file, or from a photo's colours. */
class PaletteImports(
    val fromFile: () -> Unit,
    val fromPhoto: () -> Unit,
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
                            PaletteCodec.importAse(bytes, name) ?: PaletteCodec.importAuto(bytes.decodeToString(), name)
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
                        runCatching { PaletteExtractor.colors(BitmapPixelBridge.decodeUri(context.contentResolver, uri)) }.getOrNull()
                    }
                if (colors.isNullOrEmpty()) onError("No colours could be taken from that picture") else onPalette("Photo palette", colors)
            }
        }
    return PaletteImports(
        fromFile = { launchSafely(onError) { file.launch(arrayOf("*/*")) } },
        fromPhoto = { launchSafely(onError) { photo.launch("image/*") } },
    )
}

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
    context: android.content.Context,
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
