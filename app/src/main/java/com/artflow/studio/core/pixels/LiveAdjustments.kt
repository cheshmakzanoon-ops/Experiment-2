package com.artflow.studio.core.pixels

import com.artflow.studio.domain.model.Color
import com.artflow.studio.domain.model.layer.AdjustmentType
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Procreate-style Adjustments: destructive, previewed live and applied to the active layer (or
 * only its selected pixels). Single-value effects take an [amount] from 0 to 1 that the artist
 * sets by sliding across the canvas; colour adjustments use [AdjustmentType] parameters.
 */
object LiveAdjustments {
    enum class Kind(
        val displayName: String,
        /** True when the effect is a single amount set by sliding across the canvas. */
        val slidesAmount: Boolean,
        val adjustmentType: AdjustmentType? = null,
        /** True when a touch on the canvas places the effect's target point (Recolor's crosshair). */
        val usesPoint: Boolean = false,
    ) {
        HUE_SATURATION_BRIGHTNESS("Hue, Saturation, Brightness", false, AdjustmentType.HUE_SATURATION),
        COLOR_BALANCE("Color Balance", false, AdjustmentType.COLOR_BALANCE),
        CURVES("Curves", false, AdjustmentType.CURVES),
        GRADIENT_MAP("Gradient Map", false, AdjustmentType.GRADIENT_MAP),
        GAUSSIAN_BLUR("Gaussian Blur", true),
        MOTION_BLUR("Motion Blur", true),
        NOISE("Noise", true),
        SHARPEN("Sharpen", true),
        BLOOM("Bloom", true),
        GLITCH("Glitch", true),
        HALFTONE("Halftone", true),
        CHROMATIC_ABERRATION("Chromatic Aberration", true),
        RECOLOR("Recolor", false, usesPoint = true),
        PERSPECTIVE_BLUR("Perspective Blur", false, usesPoint = true),
    }

    /** Settings for one preview or commit. [angleDegrees] orients Motion Blur. */
    data class Settings(
        val amount: Float,
        val parameters: Map<String, Float> = emptyMap(),
        val angleDegrees: Float = 0f,
    )

    fun apply(
        kind: Kind,
        source: PixelBuffer,
        settings: Settings,
        selection: SelectionMask? = null,
        alphaLocked: Boolean = false,
    ): PixelBuffer {
        val amount = settings.amount.coerceIn(0f, 1f)
        val type = kind.adjustmentType
        val filtered =
            when {
                type != null -> AdjustmentProcessor.apply(source, type, settings.parameters)
                kind == Kind.RECOLOR -> recolor(source, settings)
                kind == Kind.PERSPECTIVE_BLUR -> perspectiveBlur(source, settings)
                amount <= 0f -> source.copy()
                else -> filter(kind, source, amount, settings.angleDegrees)
            }
        if (alphaLocked) {
            // An alpha-locked layer keeps its transparency: an effect may change colour, never coverage.
            for (i in filtered.pixels.indices) {
                filtered.pixels[i] = Channels.withAlpha(filtered.pixels[i], source.pixels[i] ushr 24)
            }
        }
        val mask = selection?.takeIf { it.width == source.width && it.height == source.height } ?: return filtered
        // An empty selection selects nothing, so the layer keeps its pixels rather than taking the whole effect.
        if (!mask.isActive()) return source.copy()
        for (i in filtered.pixels.indices) {
            filtered.pixels[i] = ImageFilters.lerpArgb(source.pixels[i], filtered.pixels[i], mask.alphaAt(i))
        }
        return filtered
    }

    private fun filter(
        kind: Kind,
        source: PixelBuffer,
        amount: Float,
        angle: Float,
    ): PixelBuffer =
        when (kind) {
            Kind.GAUSSIAN_BLUR -> ImageFilters.gaussianBlur(source, amount * MAX_BLUR_RADIUS)
            Kind.MOTION_BLUR -> ImageFilters.motionBlur(source, amount * MAX_MOTION_DISTANCE, angle)
            Kind.NOISE -> ImageFilters.addNoise(source, amount, monochrome = true)
            Kind.SHARPEN -> ImageFilters.sharpen(source, amount * 2f)
            Kind.CHROMATIC_ABERRATION ->
                ImageFilters.chromaticAberration(source, amount * MAX_ABERRATION, source.width / 2f, source.height / 2f)
            Kind.BLOOM -> bloom(source, amount)
            Kind.GLITCH -> glitch(source, amount)
            Kind.HALFTONE -> halftone(source, amount)
            else -> source.copy()
        }

    /**
     * Procreate's Recolor: the area around the crosshair (`x`, `y` parameters) whose colour is within
     * the flood threshold [Settings.amount] takes the new colour (`rgb`), keeping its light and shade.
     */
    fun recolor(
        source: PixelBuffer,
        settings: Settings,
    ): PixelBuffer {
        val out = source.copy()
        val x = settings.parameters[RECOLOR_X]?.toInt() ?: return out
        val y = settings.parameters[RECOLOR_Y]?.toInt() ?: return out
        if (x !in 0 until source.width || y !in 0 until source.height) return out
        val rgb = settings.parameters[RECOLOR_RGB]?.toInt() ?: return out
        val seed = source.pixels[y * source.width + x]
        if ((seed ushr 24) == 0) return out
        // Full flood reaches about half of the colour space, so even 100% keeps clearly different colours.
        val tolerance = (settings.amount.coerceIn(0f, 1f) * RECOLOR_MAX_TOLERANCE).roundToInt()
        val area = SelectionMask.magicWand(source, x, y, tolerance = tolerance)
        val target = Color.rgbToHsv(0xFF000000.toInt() or rgb)
        val seedValue = Color.rgbToHsv(seed)[2]
        for (i in out.pixels.indices) {
            val coverage = area.alphaAt(i)
            if (coverage <= 0f) continue
            val pixel = source.pixels[i]
            val value = (target[2] + Color.rgbToHsv(pixel)[2] - seedValue).coerceIn(0f, 1f)
            val replaced = (pixel and 0xFF000000.toInt()) or (Color.hsvToRgb(target[0], target[1], value) and 0x00FFFFFF)
            out.pixels[i] = ImageFilters.lerpArgb(pixel, replaced, coverage)
        }
        return out
    }

    /**
     * Procreate's positional Perspective Blur: everything streaks toward the focus point (`x`, `y`
     * parameters, the centre by default), more strongly the farther it is from it.
     */
    fun perspectiveBlur(
        source: PixelBuffer,
        settings: Settings,
    ): PixelBuffer {
        val amount = settings.amount.coerceIn(0f, 1f)
        if (amount <= 0f) return source.copy()
        val cx = settings.parameters[RECOLOR_X] ?: (source.width / 2f)
        val cy = settings.parameters[RECOLOR_Y] ?: (source.height / 2f)
        val reach = amount * MAX_PERSPECTIVE_PULL
        val out = PixelBuffer(source.width, source.height)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                out.pixels[y * source.width + x] = streak(source, x + 0.5f, y + 0.5f, (cx - x - 0.5f) * reach, (cy - y - 0.5f) * reach)
            }
        }
        return out
    }

    /** Average of samples along a line from (px, py) by (dx, dy), weighted by alpha so edges stay clean. */
    private fun streak(
        source: PixelBuffer,
        px: Float,
        py: Float,
        dx: Float,
        dy: Float,
    ): Int {
        // Short streaks near the focus need only a few taps; long ones far away use the most.
        val count = ((sqrt(dx * dx + dy * dy) / PERSPECTIVE_TAP_SPACING).toInt() + 1).coerceAtMost(PERSPECTIVE_SAMPLES)
        var a = 0f
        var r = 0f
        var g = 0f
        var b = 0f
        for (k in 0 until count) {
            val t = k / count.toFloat()
            val sample = source.sampleNearest(px + dx * t, py + dy * t)
            val alpha = (sample ushr 24) / 255f
            a += alpha
            r += ((sample shr 16) and 0xFF) * alpha
            g += ((sample shr 8) and 0xFF) * alpha
            b += (sample and 0xFF) * alpha
        }
        if (a <= 0f) return 0
        return Channels.argb(
            (a / count * 255f).roundToInt().coerceIn(0, 255),
            (r / a).roundToInt().coerceIn(0, 255),
            (g / a).roundToInt().coerceIn(0, 255),
            (b / a).roundToInt().coerceIn(0, 255),
        )
    }

    const val RECOLOR_X = "x"
    const val RECOLOR_Y = "y"
    const val RECOLOR_RGB = "rgb"

    /** Bright areas glow: a blurred bright pass is screened back over the image. */
    fun bloom(
        source: PixelBuffer,
        amount: Float,
    ): PixelBuffer {
        val bright = PixelBuffer(source.width, source.height)
        for (i in source.pixels.indices) {
            val p = source.pixels[i]
            val excess = ((Channels.luminance(p) - BLOOM_THRESHOLD) / (1f - BLOOM_THRESHOLD)).coerceIn(0f, 1f)
            bright.pixels[i] = Channels.scaleAlpha(p, excess)
        }
        val glow = ImageFilters.gaussianBlur(bright, 4f + amount * MAX_BLOOM_RADIUS)
        val out = source.copy()
        for (i in out.pixels.indices) {
            val g = glow.pixels[i]
            val strength = Channels.alpha(g) / 255f * amount * 1.5f
            if (strength > 0f) out.pixels[i] = screen(out.pixels[i], g, strength.coerceAtMost(1f))
        }
        return out
    }

    private fun screen(
        base: Int,
        light: Int,
        strength: Float,
    ): Int {
        fun channel(shift: Int): Int {
            val b = (base shr shift) and 0xFF
            val l = (light shr shift) and 0xFF
            val screened = 255 - (255 - b) * (255 - l) / 255
            return (b + (screened - b) * strength).roundToInt().coerceIn(0, 255)
        }
        val alpha = max((base ushr 24) and 0xFF, ((light ushr 24) * strength).roundToInt())
        return Channels.argb(alpha, channel(16), channel(8), channel(0))
    }

    /** Digital corruption: bands of rows slip sideways and the red channel separates. */
    fun glitch(
        source: PixelBuffer,
        amount: Float,
    ): PixelBuffer {
        val out = PixelBuffer(source.width, source.height)
        val maxShift = (source.width * 0.08f * amount).roundToInt()
        val split = (8f * amount).roundToInt()
        for (y in 0 until source.height) {
            val band = y / GLITCH_BAND
            val shift = if (hash(band) % 3 == 0) (hash(band * 7 + 1) % (2 * maxShift + 1)) - maxShift else 0
            for (x in 0 until source.width) {
                val p = source.getSafe(x - shift, y)
                val red = source.getSafe(x - shift - split, y)
                out.pixels[y * source.width + x] = (p and 0xFF00FFFF.toInt()) or (red and 0x00FF0000)
            }
        }
        return out
    }

    /** Newsprint dots: each cell becomes a dot whose size follows the cell's darkness. */
    fun halftone(
        source: PixelBuffer,
        amount: Float,
    ): PixelBuffer {
        val cell = (4 + amount * 16f).roundToInt().coerceAtLeast(2)
        val out = PixelBuffer(source.width, source.height)
        for (cy in 0 until source.height step cell) {
            for (cx in 0 until source.width step cell) {
                halftoneCell(source, out, cx, cy, cell)
            }
        }
        return out
    }

    private fun halftoneCell(
        source: PixelBuffer,
        out: PixelBuffer,
        cx: Int,
        cy: Int,
        cell: Int,
    ) {
        val x1 = min(source.width, cx + cell)
        val y1 = min(source.height, cy + cell)
        var a = 0L
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0
        for (y in cy until y1) {
            for (x in cx until x1) {
                val p = source.pixels[y * source.width + x]
                a += (p ushr 24) and 0xFF
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
                count++
            }
        }
        if (count == 0 || a == 0L) return
        val color = Channels.argb((a / count).toInt(), (r / count).toInt(), (g / count).toInt(), (b / count).toInt())
        val darkness = 1f - Channels.luminance(color)
        val radius = cell * 0.5f * sqrt(darkness.coerceIn(0.05f, 1f)) * 1.15f
        val centerX = cx + cell / 2f
        val centerY = cy + cell / 2f
        for (y in cy until y1) {
            for (x in cx until x1) {
                val dx = x + 0.5f - centerX
                val dy = y + 0.5f - centerY
                if (dx * dx + dy * dy <= radius * radius) out.pixels[y * source.width + x] = color
            }
        }
    }

    private fun hash(value: Int): Int {
        var h = value * 374761393 + 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return (h xor (h ushr 16)) and 0x7FFFFFFF
    }

    private const val MAX_BLUR_RADIUS = 60f
    private const val MAX_MOTION_DISTANCE = 120f
    private const val MAX_BLOOM_RADIUS = 40f
    private const val BLOOM_THRESHOLD = 0.6f
    private const val GLITCH_BAND = 6
    private const val MAX_ABERRATION = 0.05f
    private const val RECOLOR_MAX_TOLERANCE = 128f
    private const val MAX_PERSPECTIVE_PULL = 0.35f
    private const val PERSPECTIVE_SAMPLES = 16
    private const val PERSPECTIVE_TAP_SPACING = 3f
}
