package com.artflow.studio.data.export

import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.pixels.PixelBuffer
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
