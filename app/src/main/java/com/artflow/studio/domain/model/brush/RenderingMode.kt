package com.artflow.studio.domain.model.brush

import kotlinx.serialization.Serializable

/**
 * Single rendering-mode contract shared by the brush model, editor and rasterizer.
 * These are ArtFlow implementations of reference-style labels, not recovered
 * reference code or claims of identical rendered pixels.
 */
@Serializable
enum class RenderingMode(
    val displayName: String,
    val blends: Boolean,
    val strength: Float,
    val uniformEdges: Boolean,
) {
    GLAZE_LIGHT("Light glaze", blends = false, strength = 0.5f, uniformEdges = false),
    GLAZE_UNIFORM("Uniform glaze", blends = false, strength = 1f, uniformEdges = true),
    GLAZE_INTENSE("Intense glaze", blends = false, strength = 1.5f, uniformEdges = false),
    GLAZE_HEAVY("Heavy glaze", blends = false, strength = 2f, uniformEdges = true),
    BLEND_UNIFORM("Uniform blending", blends = true, strength = 1f, uniformEdges = false),
    BLEND_INTENSE("Intense blending", blends = true, strength = 1.5f, uniformEdges = false),
    BLEND_HEAVY("Heavy blending", blends = true, strength = 2f, uniformEdges = true),
    ;

    /** Names used in the merged Brush Studio and the rasterizer. */
    val blending: Boolean get() = blends
    val intense: Boolean get() = strength > 1f
}
