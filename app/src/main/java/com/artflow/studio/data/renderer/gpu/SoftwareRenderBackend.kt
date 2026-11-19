package com.artflow.studio.data.renderer.gpu

import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer

/**
 * Pure CPU software rendering backend for headless JVM/unit testing and software fallback.
 * Composites virtual tiles directly on CPU into [PixelBuffer] without requiring an EGL context.
 */
class SoftwareRenderBackend : IRenderBackend {
    override val backendType: RenderBackendType = RenderBackendType.SOFTWARE
    override var isContextInitialized: Boolean = false
        private set

    private var canvasWidth: Int = 0
    private var canvasHeight: Int = 0
    private val trackedTileIndices = HashSet<Int>()

    override fun initialize(
        width: Int,
        height: Int,
    ) {
        require(width > 0 && height > 0) { "Dimensions must be positive (got ${width}x$height)" }
        canvasWidth = width
        canvasHeight = height
        isContextInitialized = true
    }

    override fun resize(
        width: Int,
        height: Int,
    ) {
        require(width > 0 && height > 0) { "Dimensions must be positive (got ${width}x$height)" }
        canvasWidth = width
        canvasHeight = height
    }

    override fun uploadDirtyTiles(
        canvas: VirtualTileCanvas,
        tileIndices: List<Int>,
    ) {
        if (!isContextInitialized || tileIndices.isEmpty()) return
        trackedTileIndices.addAll(tileIndices)
    }

    override fun compositeLayers(
        layers: List<VirtualTileCanvas>,
        target: PixelBuffer,
        bounds: IntBounds?,
    ) {
        if (!isContextInitialized || layers.isEmpty()) return
        target.clear()
        for (layer in layers) {
            layer.compositeToBuffer(target, bounds)
        }
    }

    override fun present() {
        // Software backend operates strictly on CPU PixelBuffers; no swapchain/EGL presentation.
    }

    override fun release() {
        trackedTileIndices.clear()
        isContextInitialized = false
    }
}
