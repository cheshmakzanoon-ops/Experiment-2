package com.artflow.studio.data.export

import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.color.ColorProfiles
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.three.MaterialChannel
import com.artflow.studio.core.three.ModelPackage
import com.artflow.studio.core.three.PreparedModelImport
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.domain.model.Project
import com.artflow.studio.domain.model.layer.Layer
import com.artflow.studio.domain.repository.ProjectRepository
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Installs a prepared model and its independently editable texture planes before the editor opens. */
object ModelImports {
    suspend fun install(
        repository: CanvasRepository,
        prepared: PreparedModelImport,
    ): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val receipt = PendingImports.modelReceipt(repository.projectId(), prepared)
            if (receipt != null && alreadyInstalled(repository, prepared, receipt)) return@withContext true
            repository.importModel(prepared)
        }

    /** Keeps the gallery's thumbnail and layer count aligned with a completed document save. */
    suspend fun updateProject(
        repository: CanvasRepository,
        projects: ProjectRepository,
        project: Project,
        thumbnail: String,
    ): Project {
        check(repository.projectId() == project.id) { "The saved artwork is no longer open" }
        val size = repository.getCanvasSize()
        val updated =
            project.copy(
                thumbnailPath = thumbnail,
                width = size.width,
                height = size.height,
                dpi = size.dpi,
                layerCount = repository.getAllLayers().size,
                modifiedAt = System.currentTimeMillis(),
            )
        projects.updateProject(updated)
        return updated
    }

    /** In-memory retry evidence; saved layer IDs and exact pixels survive a manifest reload without a schema change. */
    internal class Receipt(
        val projectId: Long,
        val width: Int,
        val height: Int,
        val frame: Int,
        val activeLayer: Long,
        val baseline: Set<Long>,
        val added: List<Layer>,
        val fingerprints: Map<Long, String?>,
    )

    private suspend fun alreadyInstalled(
        repository: CanvasRepository,
        prepared: PreparedModelImport,
        receipt: Receipt,
    ): Boolean {
        check(repository.projectId() == receipt.projectId && repository.activeFrameIndex() == receipt.frame) { RETRY_CHANGED }
        val size = repository.getCanvasSize()
        check(size.width == receipt.width && size.height == receipt.height) { RETRY_CHANGED }
        val revision = repository.contentRevision
        val active = repository.getActiveLayerId()
        val layers = repository.getAllLayers().associateBy { it.id }
        val addedIds = receipt.added.map { it.id }.toSet()
        check(repository.loadModel() == prepared.objText) { RETRY_CHANGED }
        check(repository.projectId() == receipt.projectId && repository.activeFrameIndex() == receipt.frame) { RETRY_CHANGED }
        check(repository.contentRevision == revision && repository.getActiveLayerId() == active) { RETRY_CHANGED }
        // A failed pre-manifest save can reopen the original canvas. Only a wholly absent insertion may be repeated.
        if (addedIds.isNotEmpty() && layers.keys == receipt.baseline && addedIds.none { it in layers }) return false
        check(layers.keys == receipt.baseline + addedIds && repository.getActiveLayerId() == receipt.activeLayer) { RETRY_CHANGED }
        for (expected in receipt.added) {
            check(layers[expected.id]?.withoutRasterPaths() == expected.withoutRasterPaths()) { RETRY_CHANGED }
            val pixels = repository.layerPixels(expected.id)
            val actual = pixels?.let { withContext(Dispatchers.Default) { fingerprint(it) } }
            check(actual == receipt.fingerprints[expected.id]) { RETRY_CHANGED }
        }
        check(repository.projectId() == receipt.projectId && repository.activeFrameIndex() == receipt.frame) { RETRY_CHANGED }
        check(repository.contentRevision == revision && repository.getActiveLayerId() == receipt.activeLayer) { RETRY_CHANGED }
        return true
    }

    private fun Layer.withoutRasterPaths(): Layer = copy(rasterFile = null, maskFile = null)

    internal fun fingerprints(planes: Map<MaterialChannel, PixelBuffer>): Map<MaterialChannel, String> =
        planes.mapValues {
            fingerprint(it.value)
        }

    private fun fingerprint(pixels: PixelBuffer): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val chunk = ByteBuffer.allocate(64 * 1024)
        var offset = 0
        while (offset < pixels.pixels.size) {
            val count = minOf(chunk.capacity() / 4, pixels.pixels.size - offset)
            chunk.asIntBuffer().put(pixels.pixels, offset, count)
            digest.update(chunk.array(), 0, count * 4)
            offset += count
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** Run before creating a gallery project, so a rejected package leaves no empty artwork behind. */
    fun requireSupportedSize(prepared: PreparedModelImport) {
        require(CanvasOperations.isSizeSafe(prepared.width, prepared.height)) { "The model texture dimensions are not supported" }
        val planes = listOfNotNull(prepared.color, prepared.metallic, prepared.roughness).size
        val workingBytes = prepared.width.toLong() * prepared.height * 4L * maxOf(6, planes * 2 + 2)
        require(workingBytes <= Runtime.getRuntime().maxMemory() * 3 / 5) {
            "This model texture is too large for this device; choose smaller dimensions"
        }
    }

    /** Every map is privately owned before the repository publishes any layer or undo entry. */
    internal fun ownedPlanes(
        prepared: PreparedModelImport,
        profile: ColorProfile,
    ): Map<MaterialChannel, PixelBuffer> {
        val sources =
            listOfNotNull(
                prepared.color?.let { MaterialChannel.COLOR to it },
                prepared.metallic?.let { MaterialChannel.METALLIC to it },
                prepared.roughness?.let { MaterialChannel.ROUGHNESS to it },
            )
        return sources.associate { (channel, source) ->
            require(source.width == prepared.width && source.height == prepared.height) { "Model maps must share their canvas dimensions" }
            val pixels = source.copy()
            if (channel.isScalar) {
                require(pixels.pixels.all(::opaqueGray)) { "Material data maps must contain opaque grayscale values" }
                channel to pixels
            } else {
                channel to ColorProfiles.convert(pixels, ColorProfile.SRGB, profile)
            }
        }
    }

    private fun opaqueGray(pixel: Int): Boolean {
        val value = pixel and 255
        return pixel ushr 24 == 255 && (pixel ushr 16 and 255) == value && (pixel ushr 8 and 255) == value
    }

    /** Writes only a unique staging file. The final repository preconditions run before [StagedModel.commit]. */
    internal fun stage(
        storage: ProjectStorage,
        projectId: Long,
        objText: String,
    ): StagedModel {
        val bytes = objText.toByteArray()
        require(bytes.size in 1..ModelPackage.MAX_BYTES) { "The 3D model is empty or too large" }
        val target = storage.resolve(projectId, ProjectStorage.MODEL_NAME)
        val temporary = storage.resolve(projectId, ".model-import-${UUID.randomUUID()}.stage")
        val staged = StagedModel(temporary, target)
        var completed = false
        try {
            storage.writeAtomically(temporary) { it.write(bytes) }
            completed = true
            return staged
        } finally {
            if (!completed) staged.close()
        }
    }

    /** Same-filesystem publication is the last fallible I/O operation before the in-memory commit. */
    internal class StagedModel(
        private val temporary: File,
        private val target: File,
    ) : AutoCloseable {
        fun commit() {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }

        override fun close() {
            try {
                Files.deleteIfExists(temporary.toPath())
            } catch (failure: IOException) {
                Timber.w(failure, "Could not remove a staged model import")
            }
        }
    }

    private const val RETRY_CHANGED = "The partially imported model has changed; its material planes were not inserted again"
}
