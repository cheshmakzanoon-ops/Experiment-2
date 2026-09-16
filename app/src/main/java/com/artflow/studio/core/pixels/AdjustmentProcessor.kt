package com.artflow.studio.core.pixels

import com.artflow.studio.domain.model.layer.AdjustmentType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Applies adjustment-layer maths to pixel data (Phase 25).
 *
 * This is the piece that turns [AdjustmentType] descriptions into actual pixels: the manager in
 * `core/layer` owns the *stack*, this object owns the *maths*. Keeping it free of
 * `android.graphics` makes the whole adjustment pipeline unit-testable.
 */
object AdjustmentProcessor {

    /**
     * Apply an adjustment to [source] and return a new buffer.
     *
     * @param parameters same keys as [AdjustmentType.defaultParameters], missing keys fall back
     *   to the type's defaults.
     * @param intensity 0..1 global mix, used for adjustment-layer opacity and live previews.
     * @param mask optional per-pixel coverage (`0..255`) restricting the effect, e.g. from a
     *   selection or the layer's own mask.
     */
    fun apply(
        source: PixelBuffer,
        type: AdjustmentType,
        parameters: Map<String, Float>,
        intensity: Float = 1f,
        mask: ByteArray? = null
    ): PixelBuffer {
        val mixed = intensity.coerceIn(0f, 1f)
        if (mixed <= 0f) return source.copy()

        val merged = type.defaultParameters + parameters
        val out = source.copy()

        when (type) {
            AdjustmentType.INVERT -> perPixel(source, out, mixed, mask) { _, p ->
                Channels.argb(
                    Channels.alpha(p).toInt(),
                    255 - Channels.red(p).toInt(),
                    255 - Channels.green(p).toInt(),
                    255 - Channels.blue(p).toInt()
                )
            }
            AdjustmentType.BRIGHTNESS_CONTRAST -> {
                val brightness = merged.float("brightness", 0f) * 2.55f
                val contrast = contrastFactor(merged.float("contrast", 0f))
                perPixel(source, out, mixed, mask) { _, p ->
                    Channels.argb(
                        Channels.alpha(p).toInt(),
                        applyContrast(Channels.red(p) + brightness, contrast),
                        applyContrast(Channels.green(p) + brightness, contrast),
                        applyContrast(Channels.blue(p) + brightness, contrast)
                    )
                }
            }
            AdjustmentType.HUE_SATURATION -> {
                val hueShift = merged.float("hue", 0f)
                val saturation = 1f + merged.float("saturation", 0f) / 100f
                val lightness = merged.float("lightness", 0f) / 100f
                perPixel(source, out, mixed, mask) { _, p ->
                    val hsv = com.artflow.studio.domain.model.Color.rgbToHsv(p)
                    var h = (hsv[0] + hueShift) % 360f
                    if (h < 0f) h += 360f
                    val s = (hsv[1] * saturation).coerceIn(0f, 1f)
                    var v = hsv[2]
                    v = if (lightness >= 0f) v + (1f - v) * lightness else v * (1f + lightness)
                    val rgb = com.artflow.studio.domain.model.Color.hsvToRgb(h, s, v.coerceIn(0f, 1f))
                    Channels.withAlpha(rgb, Channels.alpha(p).toInt())
                }
            }
            AdjustmentType.COLOR_BALANCE -> {
                val cyanRed = merged.float("cyan_red", 0f) / 100f * 255f
                val magentaGreen = merged.float("magenta_green", 0f) / 100f * 255f
                val yellowBlue = merged.float("yellow_blue", 0f) / 100f * 255f
                perPixel(source, out, mixed, mask) { _, p ->
                    Channels.argb(
                        Channels.alpha(p).toInt(),
                        clamp(Channels.red(p) + cyanRed),
                        clamp(Channels.green(p) + magentaGreen),
                        clamp(Channels.blue(p) + yellowBlue)
                    )
                }
            }
            AdjustmentType.CURVES -> {
                val lut = buildCurveLut(merged)
                perPixel(source, out, mixed, mask) { _, p ->
                    Channels.argb(
                        Channels.alpha(p).toInt(),
                        lut[Channels.red(p).toInt().coerceIn(0, 255)],
                        lut[Channels.green(p).toInt().coerceIn(0, 255)],
                        lut[Channels.blue(p).toInt().coerceIn(0, 255)]
                    )
                }
            }
            AdjustmentType.LEVELS -> {
                val inputBlack = merged.float("input_black", 0f)
                val inputWhite = merged.float("input_white", 255f)
                val gamma = merged.float("gamma", 1f).coerceIn(0.01f, 10f)
                val outputBlack = merged.float("output_black", 0f)
                val outputWhite = merged.float("output_white", 255f)
                val lut = buildLevelsLut(inputBlack, inputWhite, gamma, outputBlack, outputWhite)
                perPixel(source, out, mixed, mask) { _, p ->
                    Channels.argb(
                        Channels.alpha(p).toInt(),
                        lut[Channels.red(p).toInt().coerceIn(0, 255)],
                        lut[Channels.green(p).toInt().coerceIn(0, 255)],
                        lut[Channels.blue(p).toInt().coerceIn(0, 255)]
                    )
                }
            }
            AdjustmentType.POSTERIZE -> {
                val levels = merged.float("levels", 4f).roundToInt().coerceIn(2, 256)
                val step = 255f / (levels - 1)
                perPixel(source, out, mixed, mask) { _, p ->
                    Channels.argb(
                        Channels.alpha(p).toInt(),
                        posterizeChannel(Channels.red(p), step),
                        posterizeChannel(Channels.green(p), step),
                        posterizeChannel(Channels.blue(p), step)
                    )
                }
            }
            AdjustmentType.SELECTIVE_COLOR -> {
                perPixel(source, out, mixed, mask) { _, p ->
                    applySelectiveColor(p, merged)
                }
            }
            AdjustmentType.GRADIENT_MAP -> {
                val startHue = merged.float("gradient_start_hue", 0f)
                val endHue = merged.float("gradient_end_hue", 360f)
                perPixel(source, out, mixed, mask) { _, p ->
                    val luminance = Channels.luminance(p)
                    val hue = startHue + (endHue - startHue) * luminance
                    val mapped = com.artflow.studio.domain.model.Color.hsvToRgb(
                        hue,
                        if (luminance > 0.5f) 1f - (luminance - 0.5f) * 2f * 0.2f else 1f,
                        1f
                    )
                    Channels.withAlpha(mapped, Channels.alpha(p).toInt())
                }
            }
        }

        return out
    }

    /** Convenience wrapper for masked application (selection / layer mask). */
    fun applyWithMask(
        source: PixelBuffer,
        type: AdjustmentType,
        parameters: Map<String, Float>,
        intensity: Float,
        mask: SelectionMask?
    ): PixelBuffer = apply(source, type, parameters, intensity, mask?.coverage)

    /**
     * Blend an adjustment over the source at [intensity] (`0..1`) without copying the source first.
     * Used by the live preview so dragging a slider does not allocate on every frame.
     */
    fun applyInPlace(
        target: PixelBuffer,
        original: PixelBuffer,
        type: AdjustmentType,
        parameters: Map<String, Float>,
        intensity: Float
    ) {
        val adjusted = apply(original, type, parameters, 1f, null)
        for (i in target.pixels.indices) {
            target.pixels[i] = ImageFilters.lerpArgb(original.pixels[i], adjusted.pixels[i], intensity)
        }
    }

    /**
     * Build the 256-entry lookup table used by the Curves adjustment from up to five control
     * points (keys `point_N_x` / `point_N_y`). Uses monotone cubic interpolation so the curve
     * cannot overshoot into artifacts.
     */
    fun buildCurveLut(parameters: Map<String, Float>): IntArray {
        val points = (0..4).mapNotNull { index ->
            val x = parameters["point_${index}_x"] ?: return@mapNotNull null
            val y = parameters["point_${index}_y"] ?: return@mapNotNull null
            (x.coerceIn(0f, 255f) to y.coerceIn(0f, 255f))
        }.sortedBy { it.first }

        val lut = IntArray(256)
        if (points.size < 2) {
            for (i in 0..255) lut[i] = i
            return lut
        }

        // Monotone cubic Hermite (Fritsch-Carlson) through the control points.
        val xs = FloatArray(points.size) { points[it].first }
        val ys = FloatArray(points.size) { points[it].second }
        val n = points.size
        val slopes = FloatArray(n - 1) { i ->
            val dx = xs[i + 1] - xs[i]
            if (abs(dx) < 1e-4f) 0f else (ys[i + 1] - ys[i]) / dx
        }
        val tangents = FloatArray(n)
        tangents[0] = slopes[0]
        tangents[n - 1] = slopes[n - 2]
        for (i in 1 until n - 1) {
            tangents[i] = if (slopes[i - 1] * slopes[i] <= 0f) 0f else (slopes[i - 1] + slopes[i]) / 2f
        }
        for (i in 0 until n - 1) {
            if (abs(slopes[i]) < 1e-5f) {
                tangents[i] = 0f
                tangents[i + 1] = 0f
            } else {
                val a = tangents[i] / slopes[i]
                val b = tangents[i + 1] / slopes[i]
                val h = a * a + b * b
                if (h > 9f) {
                    val t = 3f / kotlin.math.sqrt(h)
                    tangents[i] = t * a * slopes[i]
                    tangents[i + 1] = t * b * slopes[i]
                }
            }
        }

        var segment = 0
        for (x in 0..255) {
            val xf = x.toFloat()
            while (segment < n - 2 && xf > xs[segment + 1]) segment++
            val h = (xs[segment + 1] - xs[segment]).coerceAtLeast(1e-4f)
            val t = ((xf - xs[segment]) / h).coerceIn(0f, 1f)
            val t2 = t * t
            val t3 = t2 * t
            val h00 = 2f * t3 - 3f * t2 + 1f
            val h10 = t3 - 2f * t2 + t
            val h01 = -2f * t3 + 3f * t2
            val h11 = t3 - t2
            val value = h00 * ys[segment] + h10 * h * tangents[segment] +
                h01 * ys[segment + 1] + h11 * h * tangents[segment + 1]
            lut[x] = value.roundToInt().coerceIn(0, 255)
        }
        return lut
    }

    /** 256-entry LUT for the Levels adjustment. */
    fun buildLevelsLut(
        inputBlack: Float,
        inputWhite: Float,
        gamma: Float,
        outputBlack: Float,
        outputWhite: Float
    ): IntArray {
        val inBlack = inputBlack.coerceIn(0f, 255f)
        val inWhite = inputWhite.coerceIn(inBlack + 1f, 255f)
        val safeGamma = gamma.coerceIn(0.01f, 10f)
        val outBlack = outputBlack.coerceIn(0f, 255f)
        val outWhite = outputWhite.coerceIn(outBlack + 1f, 255f)

        val lut = IntArray(256)
        for (i in 0..255) {
            val normalized = ((i - inBlack) / (inWhite - inBlack)).coerceIn(0f, 1f)
            val corrected = normalized.pow(1f / safeGamma)
            lut[i] = (outBlack + corrected * (outWhite - outBlack)).roundToInt().coerceIn(0, 255)
        }
        return lut
    }

    private fun applySelectiveColor(pixel: Int, parameters: Map<String, Float>): Int {
        val hsv = com.artflow.studio.domain.model.Color.rgbToHsv(pixel)
        val hue = hsv[0]
        val saturation = hsv[1]
        val value = hsv[2]

        val ranges = SELECTIVE_RANGES
        var cyan = 0f
        var magenta = 0f
        var yellow = 0f
        var black = 0f
        var totalWeight = 0f

        ranges.forEach { range ->
            val weight = range.weight(hue, saturation, value)
            if (weight <= 0f) return@forEach
            totalWeight += weight
            cyan += (parameters["${range.key}_cyan"] ?: 0f) * weight
            magenta += (parameters["${range.key}_magenta"] ?: 0f) * weight
            yellow += (parameters["${range.key}_yellow"] ?: 0f) * weight
            black += (parameters["${range.key}_black"] ?: 0f) * weight
        }
        if (totalWeight <= 0f) return pixel

        val normalizedCyan = cyan / totalWeight / 100f
        val normalizedMagenta = magenta / totalWeight / 100f
        val normalizedYellow = yellow / totalWeight / 100f
        val normalizedBlack = black / totalWeight / 100f

        var r = Channels.red(pixel) / 255f
        var g = Channels.green(pixel) / 255f
        var b = Channels.blue(pixel) / 255f

        // CMYK-style shifts: increasing cyan removes red, and so on.
        r = (r - normalizedCyan + normalizedBlack * 0.5f).coerceIn(0f, 1f)
        g = (g - normalizedMagenta + normalizedBlack * 0.5f).coerceIn(0f, 1f)
        b = (b - normalizedYellow + normalizedBlack * 0.5f).coerceIn(0f, 1f)

        return Channels.argb(
            Channels.alpha(pixel).toInt(),
            (r * 255f).roundToInt(),
            (g * 255f).roundToInt(),
            (b * 255f).roundToInt()
        )
    }

    private inline fun perPixel(
        source: PixelBuffer,
        target: PixelBuffer,
        intensity: Float,
        mask: ByteArray?,
        transform: (Int, Int) -> Int
    ) {
        for (i in source.pixels.indices) {
            val original = source.pixels[i]
            if ((original ushr 24) == 0) continue
            val adjusted = transform(i, original)
            val coverage = if (mask != null) (mask[i].toInt() and 0xFF) / 255f else 1f
            val effective = intensity * coverage
            target.pixels[i] = if (effective >= 1f) {
                adjusted
            } else if (effective <= 0f) {
                original
            } else {
                ImageFilters.lerpArgb(original, adjusted, effective)
            }
        }
    }

    /** Quantises one channel into [step]-sized bands (Posterize). */
    private fun posterizeChannel(value: Float, step: Float): Int =
        ((value / step).roundToInt() * step).roundToInt().coerceIn(0, 255)

    private fun contrastFactor(contrast: Float): Float =
        ((100f + contrast.coerceIn(-100f, 100f)) / 100f).let { it * it }

    private fun applyContrast(value: Float, factor: Float): Int =
        ((value / 255f - 0.5f) * factor + 0.5f).let { (it * 255f) }.roundToInt().coerceIn(0, 255)

    private fun clamp(value: Float): Int = value.roundToInt().coerceIn(0, 255)

    private fun Map<String, Float>.float(key: String, default: Float): Float = this[key] ?: default

    /** Hue windows used by Selective Color. */
    private data class SelectiveRange(val key: String, val center: Float, val width: Float) {
        fun weight(hue: Float, saturation: Float, value: Float): Float {
            if (key == "neutrals") {
                val lowSat = (1f - saturation * 4f).coerceIn(0f, 1f)
                val midValue = (1f - abs(value - 0.5f) * 2f).coerceIn(0f, 1f)
                return lowSat * (1f - midValue * 0.5f)
            }
            if (key == "whites") {
                return if (value > 0.7f) (value - 0.7f) / 0.3f * (1f - saturation * 0.5f) else 0f
            }
            if (key == "blacks") {
                return if (value < 0.3f) (0.3f - value) / 0.3f else 0f
            }
            val delta = angularDistance(hue, center)
            val falloff = (1f - delta / width).coerceIn(0f, 1f)
            return falloff * (0.35f + 0.65f * saturation)
        }

        private fun angularDistance(a: Float, b: Float): Float {
            val diff = abs(a - b) % 360f
            return min(diff, 360f - diff)
        }
    }

    private val SELECTIVE_RANGES = listOf(
        SelectiveRange("reds", 0f, 30f),
        SelectiveRange("yellows", 60f, 30f),
        SelectiveRange("greens", 120f, 45f),
        SelectiveRange("cyans", 180f, 45f),
        SelectiveRange("blues", 240f, 45f),
        SelectiveRange("magentas", 300f, 30f),
        SelectiveRange("whites", 0f, 0f),
        SelectiveRange("neutrals", 0f, 0f),
        SelectiveRange("blacks", 0f, 0f)
    )

    /** Adjustment types that only make sense on the whole composite (histogram-based). */
    fun types(): List<AdjustmentType> = AdjustmentType.entries

    /** Sensible default preview value for a single-parameter slider. */
    fun defaultValue(type: AdjustmentType, key: String): Float =
        type.defaultParameters[key] ?: 0f

    /** Largest change a parameter is allowed to make, used by the slider ranges in the UI. */
    fun rangeFor(type: AdjustmentType, key: String): ClosedFloatingPointRange<Float> =
        type.parameterRanges[key] ?: -100f..100f

    /** Clamp so the UI can never send an out-of-range value. */
    fun clampParameter(type: AdjustmentType, key: String, value: Float): Float =
        type.validateParameter(key, max(min(value, 1e6f), -1e6f))
}
