package com.artflow.studio.data.renderer.gpu

import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer

/**
 * Interface contract for ArtFlow GPU rendering backends.
 * Provides unified lifecycle, tile management, dab dispatch, and presentation across Vulkan,
 * GLES 3.x, and Software pipelines.
 */
interface IRenderBackend {
    /** The active hardware or software backend type. */
    val backendType: RenderBackendType

    /** Whether this backend context is initialized and ready to render. */
    val isContextInitialized: Boolean

    /** Initializes GPU resources for a canvas of the specified width and height. */
    fun initialize(
        width: Int,
        height: Int,
    )

    /** Handles viewport or canvas resize events. */
    fun resize(
        width: Int,
        height: Int,
    )

    /** Uploads modified virtual tiles to GPU memory/textures. */
    fun uploadDirtyTiles(
        canvas: VirtualTileCanvas,
        tileIndices: List<Int>,
    )

    /**
     * Composites layer tiles using GPU shaders or optimized SIMD paths into the target buffer.
     */
    fun compositeLayers(
        layers: List<VirtualTileCanvas>,
        target: PixelBuffer,
        bounds: IntBounds? = null,
    )

    /** Presents the composited frame to the display surface. */
    fun present()

    /** Releases all allocated GPU pipelines, textures, and buffers. */
    fun release()
}
