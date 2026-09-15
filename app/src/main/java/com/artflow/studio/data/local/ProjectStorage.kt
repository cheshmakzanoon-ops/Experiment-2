package com.artflow.studio.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.artflow.studio.domain.model.layer.Layer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The on-disk representation of a project's canvas.
 *
 * Kept in the data layer (not domain) because it is purely a storage concern: it records the
 * editable stroke model plus enough canvas metadata to restore a session exactly.
 */
@Serializable
data class CanvasDocument(
    val version: Int = CURRENT_VERSION,
    val width: Int,
    val height: Int,
    val dpi: Int,
    val backgroundColor: Int,
    val activeLayerId: Long,
    val nextLayerId: Long,
    val layers: List<Layer>,
    val savedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/**
 * Reads and writes project files under the app's private storage.
 *
 * Layout per project:
 * ```
 * filesDir/projects/<id>/canvas.artflow   - JSON document (layers + strokes)
 * filesDir/projects/<id>/canvas.png       - flattened composite (fast preview / recovery)
 * filesDir/projects/<id>/thumbnail.png    - gallery thumbnail
 * ```
 */
@Singleton
class ProjectStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = false
    }

    fun projectDir(projectId: Long): File =
        File(context.filesDir, "projects/$projectId").apply { if (!exists()) mkdirs() }

    fun documentFile(projectId: Long): File = File(projectDir(projectId), DOCUMENT_NAME)

    fun flattenedFile(projectId: Long): File = File(projectDir(projectId), FLATTENED_NAME)

    fun thumbnailFile(projectId: Long): File = File(projectDir(projectId), THUMBNAIL_NAME)

    suspend fun saveDocument(projectId: Long, document: CanvasDocument): File =
        withContext(Dispatchers.IO) {
            val file = documentFile(projectId)
            writeAtomically(file) { out ->
                out.write(json.encodeToString(CanvasDocument.serializer(), document).toByteArray())
            }
            Timber.d("Saved project $projectId document (${document.layers.size} layers)")
            file
        }

    suspend fun loadDocument(projectId: Long): CanvasDocument? = withContext(Dispatchers.IO) {
        val file = documentFile(projectId)
        if (!file.exists()) return@withContext null
        try {
            val document = json.decodeFromString(
                CanvasDocument.serializer(),
                file.readText()
            )
            if (document.version > CanvasDocument.CURRENT_VERSION) {
                Timber.w("Project $projectId was written by a newer app version (v${document.version})")
            }
            document
        } catch (e: Exception) {
            // A corrupt document must not take the app down; the flattened PNG is still usable.
            Timber.e(e, "Failed to read project $projectId document")
            null
        }
    }

    suspend fun saveFlattened(projectId: Long, bitmap: Bitmap): File =
        withContext(Dispatchers.IO) {
            val file = flattenedFile(projectId)
            writeAtomically(file) { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file
        }

    suspend fun loadFlattened(projectId: Long): Bitmap? = withContext(Dispatchers.IO) {
        val file = flattenedFile(projectId)
        if (!file.exists()) return@withContext null
        BitmapFactory.decodeFile(file.absolutePath)
    }

    suspend fun saveThumbnail(projectId: Long, bitmap: Bitmap): String =
        withContext(Dispatchers.IO) {
            val file = thumbnailFile(projectId)
            writeAtomically(file) { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            file.absolutePath
        }

    fun deleteProjectFiles(projectId: Long) {
        val dir = File(context.filesDir, "projects/$projectId")
        if (dir.exists()) {
            val deleted = dir.deleteRecursively()
            Timber.d("Deleted project $projectId files: $deleted")
        }
    }

    /**
     * Write through a temp file and rename, so an interrupted save can never leave a
     * half-written document or PNG behind.
     */
    private inline fun writeAtomically(target: File, write: (FileOutputStream) -> Unit) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(temp).use { out ->
            write(out)
            out.flush()
            out.fd.sync()
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    companion object {
        private const val DOCUMENT_NAME = "canvas.artflow"
        private const val FLATTENED_NAME = "canvas.png"
        private const val THUMBNAIL_NAME = "thumbnail.png"
    }
}
