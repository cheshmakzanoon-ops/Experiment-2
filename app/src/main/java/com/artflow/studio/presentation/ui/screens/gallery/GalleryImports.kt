package com.artflow.studio.presentation.ui.screens.gallery

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.viewModelScope
import com.artflow.studio.R
import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.core.color.PaletteCodec
import com.artflow.studio.core.color.ProcreateSwatches
import com.artflow.studio.core.export.FileKind
import com.artflow.studio.core.export.FileKinds
import com.artflow.studio.core.export.PsdCodec
import com.artflow.studio.core.export.PsdImportSource
import com.artflow.studio.core.export.readAtMost
import com.artflow.studio.core.pixels.ImportMemoryBudget
import com.artflow.studio.core.pixels.ImportPreparation
import com.artflow.studio.core.three.ModelPackage
import com.artflow.studio.core.three.ObjParser
import com.artflow.studio.core.three.PreparedModelImport
import com.artflow.studio.data.export.ArtflowPackage
import com.artflow.studio.data.export.ModelImports
import com.artflow.studio.data.export.PendingImports
import com.artflow.studio.data.export.ProcreateImport
import com.artflow.studio.data.export.StagedImport
import com.artflow.studio.data.export.importFailureMessage
import com.artflow.studio.data.local.BrushArchives
import com.artflow.studio.data.local.BrushImports
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.domain.model.brush.SavedBrush
import com.artflow.studio.presentation.ui.viewmodel.MainViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

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
    /** Adds the brushes and retains their assets at the durable library commit boundary. */
    private val addBrushes: suspend (List<SavedBrush>, () -> Unit) -> Unit,
    /** Opens an artwork that has just been created. */
    private val openArtwork: (Long) -> Unit,
) {
    /** Awaits one complete incoming import; true means the collector must stop for artwork navigation. */
    suspend fun open(uri: Uri): Boolean =
        runCatching {
            val (name, type, head) =
                withContext(Dispatchers.IO) {
                    // Metadata is provider IPC too: a cloud-backed query or MIME lookup must not
                    // block the gallery dispatcher. Check cancellation between blocking calls.
                    val name = displayName(uri)
                    coroutineContext.ensureActive()
                    val type = optionalMetadata { context.contentResolver.getType(uri) }
                    coroutineContext.ensureActive()
                    Triple(name, type, readHead(uri))
                }
            if (head == null) {
                say(context.getString(R.string.incoming_file_unreadable))
                return false
            }
            when (val kind = FileKinds.classify(name, type, head)) {
                FileKind.IMAGE -> importPhoto(uri)
                FileKind.PSD -> importPsd(uri)
                FileKind.PROCREATE_DOCUMENT -> importProcreate(uri)
                FileKind.ARTFLOW_ARTWORK -> importArtwork(uri)
                FileKind.MODEL -> importModel(uri)
                FileKind.BRUSH_FILE, FileKind.PALETTE_FILE -> {
                    smallFile(uri, kind, name)
                    false
                }
                FileKind.ZIP -> archive(uri, name)
                FileKind.UNKNOWN -> {
                    say(context.getString(R.string.incoming_file_unknown))
                    false
                }
            }
        }.getOrElse { failure ->
            val message = importFailureMessage(failure, context.getString(R.string.galleryscreen_file_could_not_be_read))
            Timber.e(failure, "Incoming gallery import failed")
            say(message)
            false
        }

    /** Opens a photo from the device as a new canvas of the same size. */
    fun photo(uri: Uri) {
        scope.launch { importPhoto(uri) }
    }

    private suspend fun importPhoto(uri: Uri): Boolean {
        val failure =
            runCatching {
                val image =
                    withContext(Dispatchers.IO) {
                        requireNotNull(BitmapPixelBridge.decodeUri(context.contentResolver, uri)) {
                            context.getString(R.string.galleryscreen_image_could_not_be_opened)
                        }
                    }
                PendingImports.reserveImage(image, viewModel.viewModelScope).use { pending ->
                    val id = viewModel.createImportedProject("Imported photo", image.width, image.height, 72)
                    pending.publish(id)
                    var navigated = false
                    try {
                        openArtwork(id)
                        navigated = true
                    } finally {
                        if (!navigated) PendingImports.discardImage(id, image)
                    }
                }
            }.exceptionOrNull() ?: return true
        val message = importFailureMessage(failure, context.getString(R.string.galleryscreen_image_could_not_be_opened))
        Timber.e(failure, "Photo import failed")
        say(message)
        return false
    }

    /** Opens a layered Photoshop document as a new artwork of the same size, keeping its layers. */
    fun psd(uri: Uri) {
        scope.launch { importPsd(uri) }
    }

    private suspend fun importPsd(uri: Uri): Boolean {
        val failure =
            runCatching {
                val (bytes, document) =
                    withContext(Dispatchers.IO) {
                        val bytes = PsdImportSource.read { context.contentResolver.openInputStream(uri) }
                        val metadata = requireNotNull(PsdCodec.inspect(bytes)) { "That file is not a supported Photoshop document" }
                        require(metadata.requiredMemoryBytes <= PsdCodec.defaultReadBudgetBytes()) {
                            "This PSD needs too much memory. Reduce its dimensions or layers in the source application."
                        }
                        bytes to metadata
                    }
                val id = viewModel.createImportedProject("Imported PSD", document.width, document.height, document.dpi)
                PendingImports.putPsd(id, bytes, viewModel.viewModelScope)
                var navigated = false
                try {
                    openArtwork(id)
                    navigated = true
                } finally {
                    if (!navigated) PendingImports.discardPsd(id, bytes)
                }
            }.exceptionOrNull() ?: return true
        if (failure is CancellationException) throw failure
        if (failure is Error) throw failure
        Timber.e(failure, "PSD import failed")
        val explained = failure is IllegalArgumentException || failure is IllegalStateException
        say(failure.message?.takeIf { explained } ?: context.getString(R.string.galleryscreen_not_a_photoshop_document))
        return false
    }

    /**
     * Opens a Procreate document as a new artwork of the same size, with its layers, groups, blend
     * modes, masks and background colour.
     */
    fun procreate(uri: Uri) {
        scope.launch { importProcreate(uri) }
    }

    private suspend fun importProcreate(uri: Uri): Boolean {
        val failure =
            runCatching {
                ProcreateImport.useOpened(context.cacheDir, { context.contentResolver.openInputStream(uri) }) { document, transfer ->
                    val size = document.document
                    require(CanvasOperations.isSizeSafe(size.width, size.height)) {
                        context.getString(R.string.galleryscreen_procreate_too_large)
                    }
                    val name = size.name?.takeIf { it.isNotBlank() } ?: "Imported Procreate"
                    val dpi = size.dpi.coerceIn(CanvasOperations.MIN_DPI, CanvasOperations.MAX_DPI)
                    val id = viewModel.createImportedProject(name, size.width, size.height, dpi)
                    PendingImports.putProcreate(id, document, viewModel.viewModelScope)
                    transfer()
                    var navigated = false
                    try {
                        // Navigate in this job: a cancelled child must not strand a pending archive.
                        openArtwork(id)
                        navigated = true
                    } finally {
                        if (!navigated) PendingImports.discardProcreate(id, document)
                    }
                }
            }.exceptionOrNull() ?: return true
        if (failure is CancellationException) throw failure
        if (failure is Error) throw failure
        val explained =
            (failure is IllegalArgumentException || failure is IllegalStateException) && failure !is NumberFormatException
        Timber.e(failure, "Procreate import failed")
        say(failure.message?.takeIf { explained } ?: "That Procreate document could not be imported")
        return false
    }

    /** Opens an `.artflow` file as a new artwork with all its layers, frames and settings. */
    fun artwork(uri: Uri) {
        scope.launch { importArtwork(uri) }
    }

    private suspend fun importArtwork(uri: Uri): Boolean {
        var navigated = false
        stagedArchive(uri) { file ->
            importArtwork(file)
            navigated = true
        }
        return navigated
    }

    /** Unlabelled ZIPs are classified on disk before the small-file parsers may allocate their payloads. */
    private suspend fun archive(
        uri: Uri,
        name: String?,
    ): Boolean {
        var navigated = false
        stagedArchive(uri) { file ->
            if (withContext(Dispatchers.IO) { ArtflowPackage.isPackageFile(file) }) {
                importArtwork(file)
                navigated = true
            } else {
                val bytes =
                    withContext(Dispatchers.IO) {
                        requireNotNull(file.inputStream().use { it.readAtMost(BrushImports.MAX_BYTES.toLong()) }) {
                            "This brush, palette or model archive is too large to import"
                        }
                    }
                when (withContext(Dispatchers.IO) { zipKind(bytes) }) {
                    FileKind.BRUSH_FILE -> brushes(bytes)
                    FileKind.PALETTE_FILE -> palette(bytes, name?.substringBeforeLast('.'))
                    FileKind.MODEL -> navigated = importModel(uri)
                    else -> say(context.getString(R.string.incoming_file_unknown))
                }
            }
        }
        return navigated
    }

    private suspend fun stagedArchive(
        uri: Uri,
        consume: suspend (File) -> Unit,
    ) {
        val failure =
            runCatching {
                StagedImport.use(context.cacheDir, { context.contentResolver.openInputStream(uri) }, consume = consume)
            }.exceptionOrNull() ?: return
        // This UI boundary reports storage/provider failures, but cancellation and VM errors must propagate.
        if (failure is CancellationException) throw failure
        if (failure is Error) throw failure
        if (failure is IllegalArgumentException || failure is IllegalStateException) {
            say(failure.message ?: context.getString(R.string.galleryscreen_file_could_not_be_read))
        } else {
            Timber.e(failure, "Gallery archive import failed")
            say(context.getString(R.string.galleryscreen_file_could_not_be_read))
        }
    }

    private suspend fun importArtwork(file: File) {
        val result = viewModel.importPackageFile(file)
        openArtwork(result.projectId)
    }

    /** Opens a 3D model (OBJ, glTF, USD, or a zip with the model, its materials and textures) as an artwork that is its texture. */
    fun model(uri: Uri) {
        scope.launch { importModel(uri) }
    }

    private suspend fun importModel(uri: Uri): Boolean {
        val failure =
            runCatching {
                val prepared = withContext(Dispatchers.IO) { readModel(uri) }
                PendingImports.reserveModelBundle(prepared, viewModel.viewModelScope).use { pending ->
                    val id = viewModel.createImportedProject("3D model", prepared.width, prepared.height, MODEL_DPI)
                    pending.publish(id)
                    var navigated = false
                    try {
                        openArtwork(id)
                        navigated = true
                    } finally {
                        if (!navigated) PendingImports.discardModelBundle(id, prepared)
                    }
                }
            }.exceptionOrNull() ?: return true
        val message = importFailureMessage(failure, "That 3D model could not be imported")
        Timber.e(failure, "Model import failed")
        say(message)
        return false
    }

    private fun readModel(uri: Uri): PreparedModelImport =
        ImportPreparation.withAdmission {
            val budget = ImportMemoryBudget.currentBytes()
            // Stream growth and its returned copy happen before model/texture metadata exists.
            val limit = minOf(MAX_MODEL_BYTES, budget / 4)
            require(limit > 0) { "Not enough memory to import this model. Save your artwork and try a smaller model." }
            val bytes =
                requireNotNull(context.contentResolver.openInputStream(uri)?.use { it.readAtMost(limit) }) {
                    "This model file could not be read within the available memory. Save your artwork and try a smaller model."
                }
            PreparedModelImport
                .prepare(
                    ModelPackage.read(bytes),
                    BitmapPixelBridge::decodeModelTexture,
                    BitmapPixelBridge::encodedDimensions,
                    MAX_MODEL_TEXTURE,
                    MODEL_TEXTURE,
                    budget,
                ).also(ModelImports::requireSupportedSize)
        }

    /** Brush and palette payloads have their own small-file limits. Artwork ZIPs never enter this path. */
    private suspend fun smallFile(
        uri: Uri,
        kind: FileKind,
        name: String?,
    ) {
        val bytes =
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readAtMost(BrushImports.MAX_BYTES.toLong())
                    }
                }.getOrNull()
            }
        if (bytes == null) {
            say(context.getString(R.string.incoming_file_unreadable))
            return
        }
        when (kind) {
            FileKind.BRUSH_FILE -> brushes(bytes)
            FileKind.PALETTE_FILE -> palette(bytes, name?.substringBeforeLast('.'))
            else -> say(context.getString(R.string.incoming_file_unknown))
        }
    }

    /** What an unlabelled zip holds, judged by its contents, cheapest and most specific test first. */
    private fun zipKind(bytes: ByteArray): FileKind =
        when {
            ProcreateSwatches.read(bytes).isNotEmpty() -> FileKind.PALETTE_FILE
            runCatching { BrushArchives.read(bytes, BitmapPixelBridge::fromEncodedBytes).isNotEmpty() }.getOrDefault(false) ->
                FileKind.BRUSH_FILE
            runCatching { ObjParser.parse(ModelPackage.read(bytes).objText) }.isSuccess -> FileKind.MODEL
            else -> FileKind.UNKNOWN
        }

    private suspend fun brushes(bytes: ByteArray) {
        val count = BrushImports.importInto(context, bytes, addBrushes)
        if (count == null) {
            say(context.getString(R.string.brushfilebuttons_not_a_brush_file))
            return
        }
        say(context.getString(R.string.incoming_brushes_added, count))
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
        optionalMetadata {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } ?: uri.lastPathSegment

    /** Missing metadata may fall back to the URI/header; cancellation must stop the import. */
    private fun <T> optionalMetadata(read: () -> T): T? =
        runCatching(read)
            .onFailure { failure ->
                if (failure is CancellationException || failure !is Exception) throw failure
            }.getOrNull()

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
        const val MAX_MODEL_BYTES = 64L * 1024 * 1024
        const val MODEL_TEXTURE = 2048
        const val MAX_MODEL_TEXTURE = 4096
        const val MODEL_DPI = 132
    }
}
