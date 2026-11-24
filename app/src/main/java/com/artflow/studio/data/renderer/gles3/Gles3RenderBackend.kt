package com.artflow.studio.data.renderer.gles3

import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.renderer.gpu.GlesRenderBackend
import com.artflow.studio.data.renderer.gpu.IRenderBackend
import com.artflow.studio.data.renderer.gpu.RenderBackendType
import com.artflow.studio.data.renderer.gpu.VirtualTileCanvas

/**
 * Runtime GPU capabilities probed from EGL/GLES context before enabling Float16 FBO rendering.
 */
data class Gles3Capabilities(
    val glMajorVersion: Int = 2,
    val glMinorVersion: Int = 0,
    val supportsHalfFloatColorBuffer: Boolean = false,
    val maxTextureSize: Int = 2048,
) {
    val supportsGles3Float16Pipeline: Boolean
        get() = glMajorVersion >= 3 && supportsHalfFloatColorBuffer && maxTextureSize >= 2048
}

/**
 * Hybrid GLES 3.0+ Float16 render backend with automatic fallback to [GlesRenderBackend]
 * (GLES 2.0 + deterministic Kotlin pipeline) when hardware capabilities are unavailable.
 */
class Gles3RenderBackend(
    val capabilities: Gles3Capabilities = Gles3Capabilities(),
    private val fallback: GlesRenderBackend = GlesRenderBackend(),
) : IRenderBackend {
    override val backendType: RenderBackendType = RenderBackendType.GLES2
    override var isContextInitialized: Boolean = false
        private set

    val isUsingFloat16Pipeline: Boolean
        get() = isContextInitialized && capabilities.supportsGles3Float16Pipeline

    private var canvasWidth: Int = 0
    private var canvasHeight: Int = 0
    private var scratchTile: Float16AccumulationTile? = null
    private val uploadedTiles = HashSet<Int>()

    override fun initialize(
        width: Int,
        height: Int,
    ) {
        require(width > 0 && height > 0) { "Dimensions must be positive (got ${width}x$height)" }
        canvasWidth = width
        canvasHeight = height
        fallback.initialize(width, height)
        if (capabilities.supportsGles3Float16Pipeline) {
            scratchTile = Float16AccumulationTile(width, height)
        }
        isContextInitialized = true
    }

    override fun resize(
        width: Int,
        height: Int,
    ) {
        require(width > 0 && height > 0) { "Dimensions must be positive (got ${width}x$height)" }
        canvasWidth = width
        canvasHeight = height
        fallback.resize(width, height)
        if (capabilities.supportsGles3Float16Pipeline) {
            scratchTile = Float16AccumulationTile(width, height)
        }
    }

    override fun uploadDirtyTiles(
        canvas: VirtualTileCanvas,
        tileIndices: List<Int>,
    ) {
        if (!isContextInitialized || tileIndices.isEmpty()) return
        uploadedTiles.addAll(tileIndices)
        fallback.uploadDirtyTiles(canvas, tileIndices)
    }

    override fun compositeLayers(
        layers: List<VirtualTileCanvas>,
        target: PixelBuffer,
        bounds: IntBounds?,
    ) {
        if (!isContextInitialized || layers.isEmpty()) return
        if (!isUsingFloat16Pipeline) {
            fallback.compositeLayers(layers, target, bounds)
            return
        }
        val tile =
            scratchTile?.takeIf { it.width == target.width && it.height == target.height }
                ?: Float16AccumulationTile(target.width, target.height).also { scratchTile = it }
        tile.clear()
        val temp = PixelBuffer(target.width, target.height)
        for (layer in layers) {
            temp.clear()
            layer.compositeToBuffer(temp, bounds)
            accumulateBuffer(tile, temp)
        }
        tile.resolveTo8Bit(target, dither = true)
    }

    private fun accumulateBuffer(
        tile: Float16AccumulationTile,
        source: PixelBuffer,
    ) {
        for (y in 0 until source.height) {
            val row = y * source.width
            for (x in 0 until source.width) {
                val p = source.pixels[row + x]
                val a = ((p ushr 24) and 0xFF) / 255f
                if (a <= 0f) continue
                tile.accumulatePixel(
                    x,
                    y,
                    ((p shr 16) and 0xFF) / 255f,
                    ((p shr 8) and 0xFF) / 255f,
                    (p and 0xFF) / 255f,
                    a,
                )
            }
        }
    }

    override fun present() {
        fallback.present()
    }

    override fun release() {
        uploadedTiles.clear()
        scratchTile = null
        fallback.release()
        isContextInitialized = false
    }
}
