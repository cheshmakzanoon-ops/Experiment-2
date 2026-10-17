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
    ): Int = blendPixel(backdrop, source, mode, opacity, FloatArray(3))

    /** [blend] with [scratch] (three floats) holding the blended channels, so a pixel loop reuses it. */
    private fun blendPixel(
        backdrop: Int,
        source: Int,
        mode: BlendMode,
        opacity: Float,
        scratch: FloatArray,
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

        blendInto(scratch, cbR, cbG, cbB, csR, csG, csB, mode)
        val blendR = scratch[0]
        val blendG = scratch[1]
        val blendB = scratch[2]

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

        val scratch = FloatArray(3)
        for (i in dst.pixels.indices) {
            dst.pixels[i] = blendPixel(dst.pixels[i], src.pixels[i], mode, clamped, scratch)
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
        val scratch = FloatArray(3)
        for (i in dst.pixels.indices) {
            val alpha = (dst.pixels[i] ushr 24) and 0xFF
            if (alpha == 0) continue
            val opaque = Channels.withAlpha(dst.pixels[i], 255)
            val mixed =
                if (mode == BlendMode.NORMAL || mode == BlendMode.PASS_THROUGH) {
                    sourceOver(opaque, Channels.scaleAlpha(src.pixels[i], amount))
                } else {
                    blendPixel(opaque, src.pixels[i], mode, amount, scratch)
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
    ): Triple<Float, Float, Float> {
        val out = FloatArray(3)
        blendInto(out, cbR, cbG, cbB, csR, csG, csB, mode)
        return Triple(out[0], out[1], out[2])
    }

    /**
     * [blendChannels] with the result written to [out] (red, green, blue). A pixel loop reuses one array this way,
     * instead of boxing three floats and a Triple for every pixel.
     */
    private fun blendInto(
        out: FloatArray,
        cbR: Float,
        cbG: Float,
        cbB: Float,
        csR: Float,
        csG: Float,
        csB: Float,
        mode: BlendMode,
    ) {
        when (mode) {
            BlendMode.NORMAL, BlendMode.PASS_THROUGH -> store(out, csR, csG, csB)
            BlendMode.MULTIPLY -> store(out, cbR * csR, cbG * csG, cbB * csB)
            BlendMode.SCREEN -> store(out, screen(cbR, csR), screen(cbG, csG), screen(cbB, csB))
            BlendMode.OVERLAY -> store(out, hardLight(csR, cbR), hardLight(csG, cbG), hardLight(csB, cbB))
            BlendMode.DARKEN -> store(out, min(cbR, csR), min(cbG, csG), min(cbB, csB))
            BlendMode.LIGHTEN -> store(out, max(cbR, csR), max(cbG, csG), max(cbB, csB))
            BlendMode.COLOR_DODGE -> store(out, dodge(cbR, csR), dodge(cbG, csG), dodge(cbB, csB))
            BlendMode.COLOR_BURN -> store(out, burn(cbR, csR), burn(cbG, csG), burn(cbB, csB))
            BlendMode.HARD_LIGHT -> store(out, hardLight(cbR, csR), hardLight(cbG, csG), hardLight(cbB, csB))
            BlendMode.SOFT_LIGHT -> store(out, softLight(cbR, csR), softLight(cbG, csG), softLight(cbB, csB))
            BlendMode.DIFFERENCE -> store(out, abs(cbR - csR), abs(cbG - csG), abs(cbB - csB))
            BlendMode.EXCLUSION -> store(out, exclusion(cbR, csR), exclusion(cbG, csG), exclusion(cbB, csB))
            BlendMode.HUE -> {
                setSaturation(csR, csG, csB, saturation(cbR, cbG, cbB), out)
                setLuminosity(out[0], out[1], out[2], luminosity(cbR, cbG, cbB), out)
            }
            BlendMode.SATURATION -> {
                setSaturation(cbR, cbG, cbB, saturation(csR, csG, csB), out)
                setLuminosity(out[0], out[1], out[2], luminosity(cbR, cbG, cbB), out)
            }
            BlendMode.COLOR -> setLuminosity(csR, csG, csB, luminosity(cbR, cbG, cbB), out)
            BlendMode.LUMINOSITY -> setLuminosity(cbR, cbG, cbB, luminosity(csR, csG, csB), out)
            BlendMode.DARKER_COLOR ->
                if (luminosity(csR, csG, csB) < luminosity(cbR, cbG, cbB)) store(out, csR, csG, csB) else store(out, cbR, cbG, cbB)
            BlendMode.LIGHTER_COLOR ->
                if (luminosity(csR, csG, csB) > luminosity(cbR, cbG, cbB)) store(out, csR, csG, csB) else store(out, cbR, cbG, cbB)
            else -> store(out, extended(cbR, csR, mode), extended(cbG, csG, mode), extended(cbB, csB, mode))
        }
    }

    private fun store(
        out: FloatArray,
        r: Float,
        g: Float,
        b: Float,
    ) {
        out[0] = r
        out[1] = g
        out[2] = b
    }

    /** The separable modes beyond the W3C set, as image editors define them. */
    private fun extended(
        base: Float,
        source: Float,
        mode: BlendMode,
    ): Float =
        when (mode) {
            BlendMode.LINEAR_BURN -> max(0f, base + source - 1f)
            BlendMode.ADD -> min(1f, base + source)
            BlendMode.VIVID_LIGHT -> if (source <= 0.5f) burn(base, 2f * source) else dodge(base, 2f * source - 1f)
            BlendMode.LINEAR_LIGHT -> (base + 2f * source - 1f).coerceIn(0f, 1f)
            BlendMode.PIN_LIGHT -> if (source <= 0.5f) min(base, 2f * source) else max(base, 2f * source - 1f)
            BlendMode.HARD_MIX -> if (base + source >= 1f) 1f else 0f
            BlendMode.SUBTRACT -> max(0f, base - source)
            BlendMode.DIVIDE -> divide(base, source)
            else -> source
        }

    private fun divide(
        base: Float,
        source: Float,
    ): Float =
        when {
            base <= 0f -> 0f
            source <= 0f -> 1f
            else -> min(1f, base / source)
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
        out: FloatArray,
    ) {
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
        store(out, cr.coerceIn(0f, 1f), cg.coerceIn(0f, 1f), cb.coerceIn(0f, 1f))
    }

    private fun setLuminosity(
        r: Float,
        g: Float,
        b: Float,
        target: Float,
        out: FloatArray,
    ) {
        val d = target - luminosity(r, g, b)
        clipColor(r + d, g + d, b + d, out)
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
        out: FloatArray,
    ) {
        val maxChannel = max(r, max(g, b))
        val minChannel = min(r, min(g, b))
        if (maxChannel <= minChannel) {
            store(out, 0f, 0f, 0f)
            return
        }

        val range = maxChannel - minChannel
        // Each channel's place in ascending order, with ties going to the lower channel index as a stable sort would.
        // The places map min -> 0, mid -> scaled, max -> target. Working them out here avoids a list per pixel.
        val placeR = before(g, r) + before(b, r)
        val placeG = beforeOrTied(r, g) + before(b, g)
        val placeB = beforeOrTied(r, b) + beforeOrTied(g, b)
        store(
            out,
            saturatedChannel(r, placeR, minChannel, range, target),
            saturatedChannel(g, placeG, minChannel, range, target),
            saturatedChannel(b, placeB, minChannel, range, target),
        )
    }

    /** 1 when [first] sorts strictly before [second], else 0. */
    private fun before(
        first: Float,
        second: Float,
    ): Int = if (first.compareTo(second) < 0) 1 else 0

    /** 1 when [first] sorts before [second] or ties with it, else 0. Use it only where [first] has the lower index. */
    private fun beforeOrTied(
        first: Float,
        second: Float,
    ): Int = if (first.compareTo(second) <= 0) 1 else 0

    private fun saturatedChannel(
        value: Float,
        place: Int,
        minChannel: Float,
        range: Float,
        target: Float,
    ): Float =
        when (place) {
            0 -> 0f
            1 -> ((value - minChannel) * target) / range
            else -> target
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
            BlendMode.LINEAR_BURN -> "Darkens by adding the layers and subtracting white"
            BlendMode.DARKER_COLOR -> "Keeps whichever whole colour is darker"
            BlendMode.ADD -> "Brightens by adding the layers (linear dodge)"
            BlendMode.LIGHTER_COLOR -> "Keeps whichever whole colour is lighter"
            BlendMode.VIVID_LIGHT -> "Burns or dodges by the source, for strong contrast"
            BlendMode.LINEAR_LIGHT -> "Darkens or brightens linearly by the source"
            BlendMode.PIN_LIGHT -> "Replaces the backdrop where the source is darker or lighter"
            BlendMode.HARD_MIX -> "Posterises each channel to full on or off"
            BlendMode.SUBTRACT -> "Subtracts the source from the backdrop"
            BlendMode.DIVIDE -> "Divides the backdrop by the source"
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
