package com.artflow.studio.core.pixels

import com.artflow.studio.domain.model.layer.BlendMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full implementation of the layer blend modes from the
 * [W3C Compositing and Blending Level 1](https://www.w3.org/TR/compositing-1/) specification.
 *
 * `android.graphics.PorterDuff` only exposes a handful of these modes, cannot express the
 * non-separable ones (Hue/Saturation/Color/Luminosity), and applies blending per draw call
 * rather than per layer. Doing the maths here means the live canvas, the saved document and the
 * exported file all produce identical pixels.
 *
 * Everything works on `0.0..1.0` channel values; alpha stays `0.0..1.0` as well.
 */
object BlendModes {
    /**
     * Composite [source] over [backdrop] using [mode].
     *
     * @param source unpremultiplied source pixel (ARGB)
     * @param backdrop unpremultiplied backdrop pixel (ARGB)
     * @param opacity additional source opacity in `0..1`
     */
    fun blend(
        backdrop: Int,
        source: Int,
        mode: BlendMode,
        opacity: Float = 1f,
    ): Int {
        val asource = (Channels.alpha(source) / 255f) * opacity.coerceIn(0f, 1f)
        if (asource <= 0f) return backdrop

        val abackdrop = Channels.alpha(backdrop) / 255f
        val outAlpha = asource + abackdrop * (1f - asource)
        if (outAlpha <= 1e-6f) return 0

        val cbR = Channels.red(backdrop) / 255f
        val cbG = Channels.green(backdrop) / 255f
        val cbB = Channels.blue(backdrop) / 255f
        val csR = Channels.red(source) / 255f
        val csG = Channels.green(source) / 255f
        val csB = Channels.blue(source) / 255f

        val (blendR, blendG, blendB) = blendChannels(cbR, cbG, cbB, csR, csG, csB, mode)

        // Co = As * (1 - Ab) * Cs + As * Ab * B(Cb, Cs) + (1 - As) * Ab * Cb
        val weightSourceOnly = asource * (1f - abackdrop)
        val weightBlended = asource * abackdrop
        val weightBackdrop = (1f - asource) * abackdrop

        val outR = (weightSourceOnly * csR + weightBlended * blendR + weightBackdrop * cbR) / outAlpha
        val outG = (weightSourceOnly * csG + weightBlended * blendG + weightBackdrop * cbG) / outAlpha
        val outB = (weightSourceOnly * csB + weightBlended * blendB + weightBackdrop * cbB) / outAlpha

        return Channels.fromFloats(
            a = outAlpha * 255f,
            r = outR * 255f,
            g = outG * 255f,
            b = outB * 255f,
        )
    }

    /**
     * Composite a whole layer buffer onto a destination buffer.
     *
     * Callers pass the backdrop and layer buffers at the same size; this is the single code path
     * used by the rasterizer, the export pipeline and the thumbnail generator.
     */
    fun composite(
        dst: PixelBuffer,
        src: PixelBuffer,
        mode: BlendMode,
        opacity: Float,
    ) {
        require(dst.width == src.width && dst.height == src.height) {
            "Blend requires matching buffer sizes"
        }
        val clamped = opacity.coerceIn(0f, 1f)
        if (clamped <= 0f) return

        // Normal source-over is by far the most common case and does not need the blend maths.
        if (mode == BlendMode.NORMAL || mode == BlendMode.PASS_THROUGH) {
            if (clamped >= 1f) {
                for (i in dst.pixels.indices) {
                    dst.pixels[i] = sourceOver(dst.pixels[i], src.pixels[i])
                }
            } else {
                for (i in dst.pixels.indices) {
                    dst.pixels[i] = sourceOver(dst.pixels[i], Channels.scaleAlpha(src.pixels[i], clamped))
                }
            }
            return
        }

        for (i in dst.pixels.indices) {
            dst.pixels[i] = blend(dst.pixels[i], src.pixels[i], mode, clamped)
        }
    }

    /** Source-atop painting: change colour without increasing or decreasing existing coverage. */
    fun sourceAtop(
        backdrop: Int,
        source: Int,
    ): Int {
        val alpha = (backdrop ushr 24) and 0xFF
        if (alpha == 0) return backdrop
        return Channels.withAlpha(sourceOver(Channels.withAlpha(backdrop, 255), source), alpha)
    }

    /** Blend a clipping layer into its isolated base, preserving that base's exact alpha. */
    fun compositeClipped(
        dst: PixelBuffer,
        src: PixelBuffer,
        mode: BlendMode,
        opacity: Float,
    ) {
        require(dst.width == src.width && dst.height == src.height) { "Clipping requires matching buffer sizes" }
        val amount = opacity.coerceIn(0f, 1f)
        if (amount <= 0f) return
        for (i in dst.pixels.indices) {
            val alpha = (dst.pixels[i] ushr 24) and 0xFF
            if (alpha == 0) continue
            val opaque = Channels.withAlpha(dst.pixels[i], 255)
            val mixed =
                if (mode == BlendMode.NORMAL || mode == BlendMode.PASS_THROUGH) {
                    sourceOver(opaque, Channels.scaleAlpha(src.pixels[i], amount))
                } else {
                    blend(opaque, src.pixels[i], mode, amount)
                }
            dst.pixels[i] = Channels.withAlpha(mixed, alpha)
        }
    }

    /** Plain source-over (a.k.a. normal) compositing of unpremultiplied ARGB pixels. */
    fun sourceOver(
        backdrop: Int,
        source: Int,
    ): Int {
        val asource = Channels.alpha(source) / 255f
        if (asource >= 1f) return source
        if (asource <= 0f) return backdrop

        val abackdrop = Channels.alpha(backdrop) / 255f
        val outAlpha = asource + abackdrop * (1f - asource)
        if (outAlpha <= 1e-6f) return 0

        val invOutAlpha = 1f / outAlpha
        val r = (Channels.red(source) * asource + Channels.red(backdrop) * abackdrop * (1f - asource)) * invOutAlpha
        val g = (Channels.green(source) * asource + Channels.green(backdrop) * abackdrop * (1f - asource)) * invOutAlpha
        val b = (Channels.blue(source) * asource + Channels.blue(backdrop) * abackdrop * (1f - asource)) * invOutAlpha
        return Channels.fromFloats(outAlpha * 255f, r, g, b)
    }

    /**
     * Separable + non-separable blend functions, evaluated on `0..1` channels.
     * Returns `Triple(r, g, b)`.
     */
    fun blendChannels(
        cbR: Float,
        cbG: Float,
        cbB: Float,
        csR: Float,
        csG: Float,
        csB: Float,
        mode: BlendMode,
    ): Triple<Float, Float, Float> =
        when (mode) {
            BlendMode.NORMAL, BlendMode.PASS_THROUGH -> Triple(csR, csG, csB)
            BlendMode.MULTIPLY -> Triple(cbR * csR, cbG * csG, cbB * csB)
            BlendMode.SCREEN ->
                Triple(
                    screen(cbR, csR),
                    screen(cbG, csG),
                    screen(cbB, csB),
                )
            BlendMode.OVERLAY ->
                Triple(
                    hardLight(csR, cbR),
                    hardLight(csG, cbG),
                    hardLight(csB, cbB),
                )
            BlendMode.DARKEN -> Triple(min(cbR, csR), min(cbG, csG), min(cbB, csB))
            BlendMode.LIGHTEN -> Triple(max(cbR, csR), max(cbG, csG), max(cbB, csB))
            BlendMode.COLOR_DODGE -> Triple(dodge(cbR, csR), dodge(cbG, csG), dodge(cbB, csB))
            BlendMode.COLOR_BURN -> Triple(burn(cbR, csR), burn(cbG, csG), burn(cbB, csB))
            BlendMode.HARD_LIGHT ->
                Triple(
                    hardLight(cbR, csR),
                    hardLight(cbG, csG),
                    hardLight(cbB, csB),
                )
            BlendMode.SOFT_LIGHT ->
                Triple(
                    softLight(cbR, csR),
                    softLight(cbG, csG),
                    softLight(cbB, csB),
                )
            BlendMode.DIFFERENCE -> Triple(abs(cbR - csR), abs(cbG - csG), abs(cbB - csB))
            BlendMode.EXCLUSION ->
                Triple(
                    exclusion(cbR, csR),
                    exclusion(cbG, csG),
                    exclusion(cbB, csB),
                )
            BlendMode.HUE -> {
                val saturated = setSaturation(csR, csG, csB, saturation(cbR, cbG, cbB))
                setLuminosity(saturated.first, saturated.second, saturated.third, luminosity(cbR, cbG, cbB))
            }
            BlendMode.SATURATION -> {
                val saturated = setSaturation(cbR, cbG, cbB, saturation(csR, csG, csB))
                setLuminosity(saturated.first, saturated.second, saturated.third, luminosity(cbR, cbG, cbB))
            }
            BlendMode.COLOR -> setLuminosity(csR, csG, csB, luminosity(cbR, cbG, cbB))
            BlendMode.LUMINOSITY -> setLuminosity(cbR, cbG, cbB, luminosity(csR, csG, csB))
        }

    private fun screen(
        base: Float,
        source: Float,
    ): Float = base + source - base * source

    private fun dodge(
        base: Float,
        source: Float,
    ): Float =
        when {
            base <= 0f -> 0f
            source >= 1f -> 1f
            else -> min(1f, base / (1f - source))
        }

    private fun burn(
        base: Float,
        source: Float,
    ): Float =
        when {
            base >= 1f -> 1f
            source <= 0f -> 0f
            else -> 1f - min(1f, (1f - base) / source)
        }

    private fun hardLight(
        base: Float,
        source: Float,
    ): Float =
        when {
            source <= 0.5f -> base * (2f * source)
            else -> screen(base, 2f * source - 1f)
        }

    private fun softLight(
        base: Float,
        source: Float,
    ): Float {
        val d = if (base <= 0.25f) ((16f * base - 12f) * base + 4f) * base else kotlin.math.sqrt(base)
        return when {
            source <= 0.5f -> base - (1f - 2f * source) * base * (1f - base)
            else -> base + (2f * source - 1f) * (d - base)
        }
    }

    private fun exclusion(
        base: Float,
        source: Float,
    ): Float = base + source - 2f * base * source

    // --- Non-separable helpers (W3C section 10.3) ---------------------------------------------

    fun luminosity(
        r: Float,
        g: Float,
        b: Float,
    ): Float = 0.3f * r + 0.59f * g + 0.11f * b

    fun saturation(
        r: Float,
        g: Float,
        b: Float,
    ): Float = max(r, max(g, b)) - min(r, min(g, b))

    private fun clipColor(
        r: Float,
        g: Float,
        b: Float,
    ): Triple<Float, Float, Float> {
        val lum = luminosity(r, g, b)
        val minChannel = min(r, min(g, b))
        val maxChannel = max(r, max(g, b))
        var cr = r
        var cg = g
        var cb = b
        if (minChannel < 0f) {
            val denominator = lum - minChannel
            if (denominator != 0f) {
                cr = lum + ((cr - lum) * lum) / denominator
                cg = lum + ((cg - lum) * lum) / denominator
                cb = lum + ((cb - lum) * lum) / denominator
            } else {
                cr = lum
                cg = lum
                cb = lum
            }
        }
        if (maxChannel > 1f) {
            val denominator = maxChannel - lum
            if (denominator != 0f) {
                cr = lum + ((cr - lum) * (1f - lum)) / denominator
                cg = lum + ((cg - lum) * (1f - lum)) / denominator
                cb = lum + ((cb - lum) * (1f - lum)) / denominator
            } else {
                cr = lum
                cg = lum
                cb = lum
            }
        }
        return Triple(cr.coerceIn(0f, 1f), cg.coerceIn(0f, 1f), cb.coerceIn(0f, 1f))
    }

    private fun setLuminosity(
        r: Float,
        g: Float,
        b: Float,
        target: Float,
    ): Triple<Float, Float, Float> {
        val d = target - luminosity(r, g, b)
        return clipColor(r + d, g + d, b + d)
    }

    /**
     * W3C `SetSat`: rescales the channel spread to [target] while preserving which channel was
     * the minimum, middle and maximum value.
     */
    private fun setSaturation(
        r: Float,
        g: Float,
        b: Float,
        target: Float,
    ): Triple<Float, Float, Float> {
        val maxChannel = max(r, max(g, b))
        val minChannel = min(r, min(g, b))
        if (maxChannel <= minChannel) return Triple(0f, 0f, 0f)

        val range = maxChannel - minChannel
        // Sort the channels so we can map min -> 0, mid -> scaled, max -> target.
        val order = listOf(0 to r, 1 to g, 2 to b).sortedBy { it.second }
        val result = FloatArray(3)
        result[order[0].first] = 0f
        result[order[1].first] = ((order[1].second - minChannel) * target) / range
        result[order[2].first] = target
        return Triple(result[0], result[1], result[2])
    }

    /**
     * Blend modes available to the user. `PASS_THROUGH` is a group-only mode and behaves as
     * normal compositing for pixel data.
     */
    fun selectableModes(): List<BlendMode> = BlendMode.getLayerBlendModes()

    /** Human-readable description for the blend mode picker. */
    fun describe(mode: BlendMode): String =
        when (mode) {
            BlendMode.NORMAL -> "Normal source-over compositing"
            BlendMode.MULTIPLY -> "Darkens where the layers overlap"
            BlendMode.SCREEN -> "Lightens where the layers overlap"
            BlendMode.OVERLAY -> "Multiply darks, screen lights"
            BlendMode.DARKEN -> "Keeps the darker of the two pixels"
            BlendMode.LIGHTEN -> "Keeps the lighter of the two pixels"
            BlendMode.COLOR_DODGE -> "Brightens the backdrop to reflect the source"
            BlendMode.COLOR_BURN -> "Darkens the backdrop to reflect the source"
            BlendMode.HARD_LIGHT -> "Strong contrast, like a harsh spotlight"
            BlendMode.SOFT_LIGHT -> "Gentle contrast, like a soft spotlight"
            BlendMode.DIFFERENCE -> "Absolute difference of the two layers"
            BlendMode.EXCLUSION -> "Like Difference but lower contrast"
            BlendMode.HUE -> "Source hue over backdrop saturation and luminosity"
            BlendMode.SATURATION -> "Source saturation over backdrop hue and luminosity"
            BlendMode.COLOR -> "Source hue and saturation over backdrop luminosity"
            BlendMode.LUMINOSITY -> "Source luminosity over backdrop colour"
            BlendMode.PASS_THROUGH -> "Group mode: blends with the layers below the group"
        }

    /** Convenience for previews: blend a single colour over another. */
    fun previewBlend(
        backdrop: Int,
        source: Int,
        mode: BlendMode,
    ): Int = blend(backdrop, source, mode, 1f)

    /** Alpha-composite a solid colour at [alpha] over an existing pixel. */
    fun tint(
        backdrop: Int,
        argb: Int,
        alpha: Float,
    ): Int = sourceOver(backdrop, Channels.scaleAlpha(argb, alpha.coerceIn(0f, 1f)))

    internal fun floatToByte(value: Float): Int = (value * 255f).roundToInt().coerceIn(0, 255)
}
