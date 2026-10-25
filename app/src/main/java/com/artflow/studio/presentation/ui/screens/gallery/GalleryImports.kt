package com.artflow.studio.presentation.ui.screens.gallery

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.artflow.studio.R
import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.core.color.PaletteCodec
import com.artflow.studio.core.color.ProcreateSwatches
import com.artflow.studio.core.export.FileKind
import com.artflow.studio.core.export.FileKinds
import com.artflow.studio.core.export.PsdCodec
import com.artflow.studio.core.three.ModelPackage
import com.artflow.studio.core.three.ObjParser
import com.artflow.studio.core.three.PreparedModelImport
import com.artflow.studio.data.export.ArtflowPackage
import com.artflow.studio.data.export.ModelImports
import com.artflow.studio.data.export.PendingImports
import com.artflow.studio.data.export.ProcreateImport
import com.artflow.studio.data.local.BrushArchives
import com.artflow.studio.data.local.BrushImports
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.domain.model.brush.SavedBrush
import com.artflow.studio.presentation.ui.viewmodel.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Brings files into the gallery: pictures, Photoshop, Procreate and ArtFlow documents and 3D models become new
 * artworks, brush files join the brush library and swatch files become palettes. The Import menu's pickers and
 * files opened from other apps share it, so both behave the same way.
 */
internal class GalleryImports(
    private val context: Context,
    private val scope: CoroutineScope,
    private val viewModel: MainViewModel,
    /** Tells the user something, for example in a snackbar. */
    private val say: suspend (String) -> Unit,
    /** Adds [brushes] to the saved brush library. */
    private val addBrushes: suspend (List<SavedBrush>) -> Unit,
    /** Opens an artwork that has just been created. */
    private val openArtwork: (Long) -> Unit,
) {
    /** Works out what [uri] is from its name, its type and its first bytes, then imports it. */
    fun open(uri: Uri) {
        scope.launch {
            val name = displayName(uri)
            val head = withContext(Dispatchers.IO) { readHead(uri) }
            if (head == null) {
                say(context.getString(R.string.incoming_file_unreadable))
                return@launch
            }
            val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            when (val kind = FileKinds.classify(name, type, head)) {
                FileKind.IMAGE -> photo(uri)
                FileKind.PSD -> psd(uri)
                FileKind.PROCREATE_DOCUMENT -> procreate(uri)
                FileKind.ARTFLOW_ARTWORK -> artwork(uri)
                FileKind.MODEL -> model(uri)
                FileKind.BRUSH_FILE, FileKind.PALETTE_FILE, FileKind.ZIP -> smallFile(uri, kind, name)
                FileKind.UNKNOWN -> say(context.getString(R.string.incoming_file_unknown))
            }
        }
    }

    /** Opens a photo from the device as a new canvas of the same size. */
    fun photo(uri: Uri) {
        scope.launch {
            val image =
                withContext(Dispatchers.IO) {
                    runCatching { BitmapPixelBridge.decodeUri(context.contentResolver, uri) }.getOrNull()
                }
            if (image == null) {
                say(context.getString(R.string.galleryscreen_image_could_not_be_opened))
                return@launch
            }
            viewModel.createProject("Imported photo", null, image.width, image.height, 72) { id ->
                PendingImports.put(id, image)
                scope.launch { openArtwork(id) }
            }
        }
    }

    /** Opens a layered Photoshop document as a new artwork of the same size, keeping its layers. */
    fun psd(uri: Uri) {
        scope.launch {
            val opened =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)?.use { it.readBytes() })
                        val document = requireNotNull(PsdCodec.read(bytes))
                        bytes to document
                    }.getOrNull()
                }
            if (opened == null) {
                say(context.getString(R.string.galleryscreen_not_a_photoshop_document))
                return@launch
            }
            val (bytes, document) = opened
            viewModel.createProject("Imported PSD", null, document.width, document.height, document.dpi) { id ->
                PendingImports.putPsd(id, bytes)
                scope.launch { openArtwork(id) }
            }
        }
    }

    /**
     * Opens a Procreate document as a new artwork of the same size, with its layers, groups, blend
     * modes, masks and background colour.
     */
    fun procreate(uri: Uri) {
        scope.launch {
            val opened =
                withContext(Dispatchers.IO) {
                    runCatching {
                        requireNotNull(context.contentResolver.openInputStream(uri)) { "That file could not be opened" }.use { input ->
                            ProcreateImport.open(input, context.cacheDir)
                        }
                    }
                }
            val document =
                opened.getOrElse { failure ->
                    // Our own checks explain themselves; anything else means a damaged or unusual file.
                    val explained = failure is IllegalArgumentException && failure !is NumberFormatException
                    say(failure.message?.takeIf { explained } ?: "That file is not a Procreate document ArtFlow can read")
                    return@launch
                }
            val size = document.document
            if (!CanvasOperations.isSizeSafe(size.width, size.height)) {
                document.close()
                say(context.getString(R.string.galleryscreen_procreate_too_large))
                return@launch
            }
            val name = size.name?.takeIf { it.isNotBlank() } ?: "Imported Procreate"
            val dpi = size.dpi.coerceIn(CanvasOperations.MIN_DPI, CanvasOperations.MAX_DPI)
            viewModel.createProject(name, null, size.width, size.height, dpi) { id ->
                PendingImports.putProcreate(id, document)
                scope.launch { openArtwork(id) }
            }
        }
    }

    /** Opens an `.artflow` file as a new artwork with all its layers, frames and settings. */
    fun artwork(uri: Uri) {
        scope.launch {
            val bytes =
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            input.readBytes().also { require(it.size <= MAX_PACKAGE_BYTES) }
                        }
                    }.getOrNull()
                }
            if (bytes == null) {
                say(context.getString(R.string.galleryscreen_file_could_not_be_read))
                return@launch
            }
            viewModel.importPackage(bytes) { id -> scope.launch { openArtwork(id) } }
        }
    }

    /** Opens a 3D model (OBJ, glTF, USD, or a zip with the model, its materials and textures) as an artwork that is its texture. */
    fun model(uri: Uri) {
        scope.launch {
            val read = withContext(Dispatchers.IO) { runCatching { readModel(uri) } }
            read.onFailure { failure ->
                // Our own checks explain themselves; anything else means a damaged or unusual file.
                val explained =
                    (failure is IllegalArgumentException || failure is IllegalStateException) && failure !is NumberFormatException
                say(failure.message?.takeIf { explained } ?: "That 3D model could not be read")
            }
            val prepared = read.getOrNull() ?: return@launch
            viewModel.createProject("3D model", null, prepared.width, prepared.height, MODEL_DPI) { id ->
                PendingImports.putModelBundle(id, prepared)
                scope.launch { openArtwork(id) }
            }
        }
    }

    private fun readModel(uri: Uri): PreparedModelImport {
        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)?.use { it.readBytes() })
        return PreparedModelImport
            .prepare(ModelPackage.read(bytes), BitmapPixelBridge::fromEncodedBytes, MAX_MODEL_TEXTURE, MODEL_TEXTURE)
            .also(ModelImports::requireSupportedSize)
    }

    /** Brush, palette and unlabelled zip files are small enough to read whole. */
    private suspend fun smallFile(
        uri: Uri,
        kind: FileKind,
        name: String?,
    ) {
        val bytes =
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes().takeIf { it.size <= BrushImports.MAX_BYTES }
                    }
                }.getOrNull()
            }
        if (bytes == null) {
            say(context.getString(R.string.incoming_file_unreadable))
            return
        }
        val resolved = if (kind == FileKind.ZIP) withContext(Dispatchers.IO) { zipKind(bytes) } else kind
        when (resolved) {
            FileKind.BRUSH_FILE -> brushes(bytes)
            FileKind.PALETTE_FILE -> palette(bytes, name?.substringBeforeLast('.'))
            FileKind.ARTFLOW_ARTWORK -> viewModel.importPackage(bytes) { id -> scope.launch { openArtwork(id) } }
            FileKind.MODEL -> model(uri)
            else -> say(context.getString(R.string.incoming_file_unknown))
        }
    }

    /** What an unlabelled zip holds, judged by its contents, cheapest and most specific test first. */
    private fun zipKind(bytes: ByteArray): FileKind =
        when {
            ProcreateSwatches.read(bytes).isNotEmpty() -> FileKind.PALETTE_FILE
            runCatching { BrushArchives.read(bytes, BitmapPixelBridge::fromEncodedBytes).isNotEmpty() }.getOrDefault(false) ->
                FileKind.BRUSH_FILE
            runCatching { ArtflowPackage.read(bytes) }.isSuccess -> FileKind.ARTFLOW_ARTWORK
            runCatching { ObjParser.parse(ModelPackage.read(bytes).objText) }.isSuccess -> FileKind.MODEL
            else -> FileKind.UNKNOWN
        }

    private suspend fun brushes(bytes: ByteArray) {
        val found = withContext(Dispatchers.IO) { BrushImports.read(context, bytes) }
        if (found.isNullOrEmpty()) {
            say(context.getString(R.string.brushfilebuttons_not_a_brush_file))
            return
        }
        addBrushes(found)
        say(context.getString(R.string.incoming_brushes_added, found.size))
    }

    private suspend fun palette(
        bytes: ByteArray,
        name: String?,
    ) {
        val palette =
            withContext(Dispatchers.IO) {
                runCatching { PaletteCodec.decode(bytes, name?.take(MAX_NAME) ?: "Imported palette") }.getOrNull()
            }
        if (palette == null || palette.colors.isEmpty()) {
            say(context.getString(R.string.paletteimports_no_readable_colours))
            return
        }
        viewModel.addPalette(palette.name, palette.colors)
        say(context.getString(R.string.incoming_palette_added, palette.name))
    }

    private fun displayName(uri: Uri): String? =
        runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment

    private fun readHead(uri: Uri): ByteArray? =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(HEAD_BYTES)
                var filled = 0
                while (filled < buffer.size) {
                    val read = stream.read(buffer, filled, buffer.size - filled)
                    if (read < 0) break
                    filled += read
                }
                buffer.copyOf(filled)
            }
        }.getOrNull()

    private companion object {
        const val HEAD_BYTES = 64
        const val MAX_NAME = 40
        const val MAX_PACKAGE_BYTES = 512 * 1024 * 1024
        const val MODEL_TEXTURE = 2048
        const val MAX_MODEL_TEXTURE = 4096
        const val MODEL_DPI = 132
    }
}
