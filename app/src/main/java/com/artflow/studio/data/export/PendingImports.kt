package com.artflow.studio.data.export

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
}
