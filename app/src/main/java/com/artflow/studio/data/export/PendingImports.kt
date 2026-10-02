package com.artflow.studio.data.export

import com.artflow.studio.core.pixels.PixelBuffer
import java.util.concurrent.ConcurrentHashMap

/** Hands an imported picture from the gallery to the editor that opens its new canvas. */
object PendingImports {
    private val images = ConcurrentHashMap<Long, PixelBuffer>()

    fun put(
        projectId: Long,
        image: PixelBuffer,
    ) {
        images[projectId] = image
    }

    fun take(projectId: Long): PixelBuffer? = images.remove(projectId)
}
