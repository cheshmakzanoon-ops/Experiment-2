package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.BlendModes
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.Layer
import com.artflow.studio.domain.model.layer.LayerBlendContract
import kotlin.math.min
import kotlin.math.roundToInt

/** Layer-only compatibility contracts; brush blending and standard layer modes are unchanged. */
internal object LayerBlending {
    fun composite(
        destination: PixelBuffer,
        content: PixelBuffer,
        layer: Layer,
        clipped: Boolean,
    ) {
        when {
            // Keep ArtFlow's existing clipping rule: blend against an opaque temporary base,
            // then restore its alpha. This does not assert native host clipping/group routing.
            clipped -> BlendModes.compositeClipped(destination, content, layer.blendMode, layer.opacity)
            layer.blendContract == LayerBlendContract.PROCREATE_DARKEN_19 && layer.blendMode == BlendMode.DARKEN ->
                legacyDarken(destination, content, layer.opacity)
            else -> BlendModes.composite(destination, content, layer.blendMode, layer.opacity)
        }
    }

    private fun legacyDarken(
        destination: PixelBuffer,
        content: PixelBuffer,
        opacity: Float,
    ) {
        require(destination.width == content.width && destination.height == content.height) { "Blend requires matching buffer sizes" }
        val amount = opacity.coerceIn(0f, 1f).toDouble()
        if (amount <= 0.0) return
        for (index in destination.pixels.indices) {
            destination.pixels[index] = legacyDarkenPixel(destination.pixels[index], content.pixels[index], amount)
        }
    }

    /**
     * The recovered ordinary source19/GPU35 equation is min(B, S + (1-S.a)*B)
     * on premultiplied RGBA. Its real-arithmetic alpha is the original destination alpha.
     * This CPU path uses the app's 8-bit pixels; it does not emulate native half arithmetic.
     * Masks already scale source alpha in renderLayerContent. For this minimum, finite
     * coverage commutes with the native post-blend mix in real arithmetic; existing 8-bit
     * mask quantization is retained.
     */
    private fun legacyDarkenPixel(
        backdrop: Int,
        source: Int,
        opacity: Double,
    ): Int {
        val sourceAlpha = (source ushr 24) / 255.0 * opacity
        if (sourceAlpha <= 0.0) return backdrop
        val alpha = backdrop ushr 24
        if (alpha == 0) return 0
        val baseAlpha = alpha / 255.0
        val inverse = 1.0 - sourceAlpha
        var output = alpha shl 24
        for (shift in 0..16 step 8) {
            val base = ((backdrop ushr shift) and 255) * baseAlpha
            val top = ((source ushr shift) and 255) * sourceAlpha
            val channel = (min(base, top + inverse * base) / baseAlpha).roundToInt().coerceIn(0, 255)
            output = output or (channel shl shift)
        }
        return output
    }
}
