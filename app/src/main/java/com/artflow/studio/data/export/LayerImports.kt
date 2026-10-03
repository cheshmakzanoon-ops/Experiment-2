package com.artflow.studio.data.export

import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.color.ColorProfiles
import com.artflow.studio.core.export.PsdCodec
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Brings outside pictures and Photoshop documents into the open canvas as new layers. */
object LayerImports {
    /** Adds [image] as a new layer, centred and scaled down to fit the canvas if needed. */
    suspend fun insertImage(
        repository: CanvasRepository,
        image: PixelBuffer,
    ) {
        val size = repository.getCanvasSize()
        val fit = minOf(1f, size.width.toFloat() / image.width, size.height.toFloat() / image.height)
        val scaled =
            if (fit < 1f) {
                withContext(Dispatchers.Default) {
                    image.scaled((image.width * fit).toInt().coerceAtLeast(1), (image.height * fit).toInt().coerceAtLeast(1))
                }
            } else {
                image
            }
        // Decoded pictures are sRGB; a Display P3 canvas stores the same colours in its own space.
        val placed = withContext(Dispatchers.Default) { ColorProfiles.convert(scaled, ColorProfile.SRGB, repository.getColorProfile()) }
        val layer = repository.addLayer(name = "Photo")
        val drawn =
            repository.applyRasterEdit(layer.id, "Insert photo") { target ->
                target.drawInto(placed, (size.width - placed.width) / 2, (size.height - placed.height) / 2)
            }
        check(drawn) { "The photo could not be placed on the new layer" }
    }

    /** Adds every PSD layer (or the flattened composite) as new layers; returns how many were added. */
    suspend fun importPsd(
        repository: CanvasRepository,
        bytes: ByteArray,
    ): Int {
        val document =
            withContext(Dispatchers.Default) { PsdCodec.read(bytes) }
                ?: error("This file is not a supported PSD document")
        val size = repository.getCanvasSize()
        val profile = repository.getColorProfile()
        val dx = (size.width - document.width) / 2
        val dy = (size.height - document.height) / 2
        val sources =
            document.layers.ifEmpty {
                listOfNotNull(document.composite?.let { PsdCodec.PsdLayer("Background", it) })
            }
        check(sources.isNotEmpty()) { "The PSD document has no readable layers" }
        sources.forEach { source ->
            val layer = repository.addLayer(name = source.name.ifBlank { "PSD layer" })
            repository.applyRasterEdit(layer.id, "Import PSD layer") { target ->
                target.drawInto(ColorProfiles.convert(source.pixels, ColorProfile.SRGB, profile), dx + source.left, dy + source.top)
            }
            if (source.opacity < 255) repository.setLayerOpacity(layer.id, source.opacity / 255f)
            if (source.blendMode != BlendMode.NORMAL) repository.setLayerBlendMode(layer.id, source.blendMode)
            if (!source.isVisible) repository.setLayerVisibility(layer.id, false)
        }
        return sources.size
    }
}
