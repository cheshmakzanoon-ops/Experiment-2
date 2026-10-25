package com.artflow.studio.data.export

import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.three.PreparedModelImport
import java.util.concurrent.ConcurrentHashMap

/** Hands an imported picture or document from the gallery to the editor that opens its new canvas. */
object PendingImports {
    private val images = ConcurrentHashMap<Long, PixelBuffer>()

    fun put(
        projectId: Long,
        image: PixelBuffer,
    ) {
        images[projectId] = image
    }

    fun take(projectId: Long): PixelBuffer? = images.remove(projectId)

    private val documents = ConcurrentHashMap<Long, ByteArray>()

    /** A layered Photoshop document whose layers fill the new canvas. */
    fun putPsd(
        projectId: Long,
        bytes: ByteArray,
    ) {
        documents[projectId] = bytes
    }

    fun takePsd(projectId: Long): ByteArray? = documents.remove(projectId)

    private val procreate = ConcurrentHashMap<Long, ProcreateImport>()

    /** An opened Procreate document whose layers fill the new canvas; the taker closes it. */
    fun putProcreate(
        projectId: Long,
        document: ProcreateImport,
    ) {
        procreate.put(projectId, document)?.close()
    }

    fun takeProcreate(projectId: Long): ProcreateImport? = procreate.remove(projectId)

    private val models = ConcurrentHashMap<Long, String>()

    /** A 3D model (OBJ text) whose texture the new canvas becomes. */
    fun putModel(
        projectId: Long,
        objText: String,
    ) {
        models[projectId] = objText
    }

    fun takeModel(projectId: Long): String? = models.remove(projectId)

    private class ModelBundle(
        val prepared: PreparedModelImport,
    ) {
        @Volatile var receipt: ModelImports.Receipt? = null
    }

    private val modelBundles = ConcurrentHashMap<Long, ModelBundle>()

    /** All model planes are prepared before the gallery creates their destination project. */
    fun putModelBundle(
        projectId: Long,
        prepared: PreparedModelImport,
    ) {
        modelBundles[projectId] = ModelBundle(prepared)
    }

    /** Keep the payload available until an awaited installation succeeds, including after a retryable I/O failure. */
    fun modelBundle(projectId: Long): PreparedModelImport? = modelBundles[projectId]?.prepared

    fun completeModelBundle(
        projectId: Long,
        prepared: PreparedModelImport,
    ): Boolean {
        val bundle = modelBundles[projectId]?.takeIf { it.prepared === prepared } ?: return false
        return modelBundles.remove(projectId, bundle)
    }

    internal fun modelReceipt(
        projectId: Long,
        prepared: PreparedModelImport,
    ): ModelImports.Receipt? = modelBundles[projectId]?.takeIf { it.prepared === prepared }?.receipt

    internal fun recordModelReceipt(
        projectId: Long,
        prepared: PreparedModelImport,
        receipt: ModelImports.Receipt,
    ) {
        modelBundles.computeIfPresent(projectId) { _, bundle ->
            bundle.also { if (it.prepared === prepared) it.receipt = receipt }
        }
    }

    private val profiles = ConcurrentHashMap<Long, ColorProfile>()

    /** The colour profile chosen for a new canvas, applied when it first opens. */
    fun putProfile(
        projectId: Long,
        profile: ColorProfile,
    ) {
        profiles[projectId] = profile
    }

    fun takeProfile(projectId: Long): ColorProfile? = profiles.remove(projectId)
}
