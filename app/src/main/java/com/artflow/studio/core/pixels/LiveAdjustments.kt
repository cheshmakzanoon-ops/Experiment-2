package com.artflow.studio.core.pixels

import com.artflow.studio.domain.model.Color
import com.artflow.studio.domain.model.layer.AdjustmentType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
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

        /** Fades the layer: sliding further makes it more transparent. */
        OPACITY("Opacity", true),
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
    ): PixelBuffer {
        val amount = settings.amount.coerceIn(0f, 1f)
        val type = kind.adjustmentType
        val filtered =
            when {
                type != null -> AdjustmentProcessor.apply(source, type, settings.parameters)
                kind == Kind.RECOLOR -> recolor(source, settings)
                kind == Kind.PERSPECTIVE_BLUR -> perspectiveBlur(source, settings)
                amount <= 0f -> source.copy()
                kind == Kind.NOISE -> noise(source, amount, settings.parameters)
                else -> filter(kind, source, amount, settings.angleDegrees, settings.parameters)
            }
        val mask = selection?.takeIf { it.width == source.width && it.height == source.height && it.isActive() } ?: return filtered
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
        parameters: Map<String, Float>,
    ): PixelBuffer =
        when (kind) {
            Kind.OPACITY -> faded(source, 1f - amount)
            Kind.GAUSSIAN_BLUR -> ImageFilters.gaussianBlur(source, amount * MAX_BLUR_RADIUS)
            Kind.MOTION_BLUR -> ImageFilters.motionBlur(source, amount * MAX_MOTION_DISTANCE, angle)
            Kind.SHARPEN -> ImageFilters.sharpen(source, amount * 2f)
            Kind.CHROMATIC_ABERRATION ->
                ImageFilters.chromaticAberration(source, amount * MAX_ABERRATION, source.width / 2f, source.height / 2f)
            Kind.BLOOM ->
                bloom(
                    source,
                    amount,
                    transition = parameters[BLOOM_TRANSITION] ?: DEFAULT_BLOOM_TRANSITION,
                    size = parameters[BLOOM_SIZE] ?: 1f,
                    burn = parameters[BLOOM_BURN] ?: 0f,
                )
            Kind.GLITCH -> glitch(source, amount, glitchStyle(parameters))
            Kind.HALFTONE -> halftone(source, amount, halftoneStyle(parameters))
            else -> source.copy()
        }

    /** Noise of the type and size in [parameters] ([NOISE_TYPE], [NOISE_SCALE]); plain grain by default. */
    private fun noise(
        source: PixelBuffer,
        amount: Float,
        parameters: Map<String, Float>,
    ): PixelBuffer {
        val type = FractalNoise.Type.entries.getOrElse(parameters[NOISE_TYPE]?.toInt() ?: 0) { FractalNoise.Type.GRAIN }
        return FractalNoise.apply(source, type, amount, parameters[NOISE_SCALE] ?: DEFAULT_NOISE_SCALE)
    }

    private fun faded(
        source: PixelBuffer,
        keep: Float,
    ): PixelBuffer = PixelBuffer(source.width, source.height, IntArray(source.pixels.size) { Channels.scaleAlpha(source.pixels[it], keep) })

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
     * Procreate's Perspective Blur. Positional: everything streaks toward the focus point (`x`, `y`
     * parameters, the centre by default), more strongly the farther it is from it. Directional
     * ([PERSPECTIVE_DIRECTIONAL] set): only what lies ahead of the point along [PERSPECTIVE_ANGLE]
     * streaks, straight back along that direction, so the picture seems to rush one way.
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
        val directional = (settings.parameters[PERSPECTIVE_DIRECTIONAL] ?: 0f) >= 0.5f
        val radians = Math.toRadians((settings.parameters[PERSPECTIVE_ANGLE] ?: 0f).toDouble())
        val ux = cos(radians).toFloat()
        val uy = sin(radians).toFloat()
        val out = PixelBuffer(source.width, source.height)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val px = x + 0.5f
                val py = y + 0.5f
                val ahead = if (directional) max(0f, (px - cx) * ux + (py - cy) * uy) * reach else 0f
                out.pixels[y * source.width + x] =
                    when {
                        !directional -> streak(source, px, py, (cx - px) * reach, (cy - py) * reach)
                        ahead <= 0f -> source.pixels[y * source.width + x]
                        else -> streak(source, px, py, -ux * ahead, -uy * ahead)
                    }
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

    /** Noise: which [FractalNoise.Type] (by ordinal) and its feature size in pixels. */
    const val NOISE_TYPE = "noise_type"
    const val NOISE_SCALE = "noise_scale"
    const val DEFAULT_NOISE_SCALE = 48f

    /** Halftone: which [HalftoneStyle] (by ordinal). */
    const val HALFTONE_STYLE = "halftone_style"

    /** Bloom: the brightness where glow begins (0 to 1), the glow's size and its burn toward white (0 to 1). */
    const val BLOOM_TRANSITION = "bloom_transition"
    const val BLOOM_SIZE = "bloom_size"
    const val BLOOM_BURN = "bloom_burn"
    const val DEFAULT_BLOOM_TRANSITION = 0.6f

    /** Perspective Blur: 1 for Directional, 0 (the default) for Positional. */
    const val PERSPECTIVE_DIRECTIONAL = "perspective_directional"

    /** Perspective Blur: the Directional mode's direction in degrees (0 points right, 90 down). */
    const val PERSPECTIVE_ANGLE = "perspective_angle"

    /** Glitch: which [GlitchStyle] (by ordinal). */
    const val GLITCH_STYLE = "glitch_style"

    /** Dots grow a little past the cell's inscribed circle so fully inked cells join up. */
    private const val DOT_REACH = 1.15f

    /**
     * Bright areas glow: a blurred bright pass is screened back over the image. Procreate's options:
     * [transition] is the brightness where the glow begins, [size] scales its spread and [burn]
     * pushes the glow toward white.
     */
    fun bloom(
        source: PixelBuffer,
        amount: Float,
        transition: Float = DEFAULT_BLOOM_TRANSITION,
        size: Float = 1f,
        burn: Float = 0f,
    ): PixelBuffer {
        val threshold = transition.coerceIn(0f, MAX_BLOOM_TRANSITION)
        val white = burn.coerceIn(0f, 1f)
        val bright = PixelBuffer(source.width, source.height)
        for (i in source.pixels.indices) {
            val p = source.pixels[i]
            val excess = ((Channels.luminance(p) - threshold) / (1f - threshold)).coerceIn(0f, 1f)
            val lit = if (white > 0f) ImageFilters.lerpArgb(p, p or 0x00FFFFFF, white) else p
            bright.pixels[i] = Channels.scaleAlpha(lit, excess)
        }
        val glow = ImageFilters.gaussianBlur(bright, 4f + amount * size.coerceIn(0f, 1f) * MAX_BLOOM_RADIUS)
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

    /** Procreate's Glitch styles. */
    enum class GlitchStyle(
        val displayName: String,
    ) {
        /** Bands of rows slip sideways and the red channel separates. */
        SIGNAL("Signal"),

        /** Square blocks are replaced by pixels from nearby, like a damaged compressed image. */
        ARTIFACT("Artifact"),

        /** Rows ripple sideways along a wave. */
        WAVE("Wave"),

        /** Red and blue pull apart to either side. */
        DIVERGE("Diverge"),
    }

    fun glitchStyle(parameters: Map<String, Float>): GlitchStyle =
        GlitchStyle.entries.getOrElse(parameters[GLITCH_STYLE]?.toInt() ?: 0) { GlitchStyle.SIGNAL }

    /** Digital corruption in one of the [GlitchStyle]s, stronger with [amount]. */
    fun glitch(
        source: PixelBuffer,
        amount: Float,
        style: GlitchStyle = GlitchStyle.SIGNAL,
    ): PixelBuffer =
        when (style) {
            GlitchStyle.SIGNAL -> signalGlitch(source, amount)
            GlitchStyle.ARTIFACT -> artifactGlitch(source, amount)
            GlitchStyle.WAVE -> waveGlitch(source, amount)
            GlitchStyle.DIVERGE -> divergeGlitch(source, amount)
        }

    private fun artifactGlitch(
        source: PixelBuffer,
        amount: Float,
    ): PixelBuffer {
        val out = source.copy()
        val block = (ARTIFACT_MIN_BLOCK + amount * ARTIFACT_BLOCK_RANGE).roundToInt()
        val damaged = (amount * ARTIFACT_MAX_SHARE * HASH_BUCKETS).roundToInt()
        for (top in 0 until source.height step block) {
            for (left in 0 until source.width step block) {
                val cell = (top / block) * BLOCK_ROW_STRIDE + left / block
                if (hash(cell) % HASH_BUCKETS < damaged) {
                    val dx = hash(cell * 3 + 1) % (4 * block + 1) - 2 * block
                    val dy = hash(cell * 5 + 2) % (2 * block + 1) - block
                    copyBlock(source, out, left, top, block, dx, dy)
                }
            }
        }
        return out
    }

    /** Fills the [size] square at ([left], [top]) of [out] with [source]'s pixels offset by ([dx], [dy]). */
    private fun copyBlock(
        source: PixelBuffer,
        out: PixelBuffer,
        left: Int,
        top: Int,
        size: Int,
        dx: Int,
        dy: Int,
    ) {
        for (y in top until minOf(top + size, source.height)) {
            for (x in left until minOf(left + size, source.width)) out.pixels[y * source.width + x] = source.getSafe(x + dx, y + dy)
        }
    }

    private fun waveGlitch(
        source: PixelBuffer,
        amount: Float,
    ): PixelBuffer {
        val out = PixelBuffer(source.width, source.height)
        val reach = source.width * WAVE_REACH * amount
        for (y in 0 until source.height) {
            val shift = (sin(y * 2.0 * PI / WAVE_PERIOD) * reach).roundToInt()
            for (x in 0 until source.width) out.pixels[y * source.width + x] = source.getSafe(x - shift, y)
        }
        return out
    }

    private fun divergeGlitch(
        source: PixelBuffer,
        amount: Float,
    ): PixelBuffer {
        val out = PixelBuffer(source.width, source.height)
        val split = (source.width * DIVERGE_REACH * amount).roundToInt()
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val p = source.getSafe(x, y)
                val red = source.getSafe(x + split, y)
                val blue = source.getSafe(x - split, y)
                val alpha = maxOf(p ushr 24, red ushr 24, blue ushr 24)
                out.pixels[y * source.width + x] =
                    (alpha shl 24) or (red and 0x00FF0000) or (p and 0x0000FF00) or (blue and 0x000000FF)
            }
        }
        return out
    }

    private fun signalGlitch(
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

    /** Procreate's Halftone styles: dots in each area's colour, CMY screen print, or black newspaper dots. */
    enum class HalftoneStyle(
        val displayName: String,
    ) {
        FULL_COLOUR("Full colour"),
        SCREEN_PRINT("Screen print"),
        NEWSPAPER("Newspaper"),
    }

    fun halftoneStyle(parameters: Map<String, Float>): HalftoneStyle =
        HalftoneStyle.entries.getOrElse(parameters[HALFTONE_STYLE]?.toInt() ?: 0) { HalftoneStyle.FULL_COLOUR }

    /** Halftone dots: each cell becomes a dot whose size follows the cell's darkness. */
    fun halftone(
        source: PixelBuffer,
        amount: Float,
        style: HalftoneStyle = HalftoneStyle.FULL_COLOUR,
    ): PixelBuffer {
        val cell = (4 + amount * 16f).roundToInt().coerceAtLeast(2)
        if (style != HalftoneStyle.FULL_COLOUR) return printedHalftone(source, cell, style)
        val out = PixelBuffer(source.width, source.height)
        for (cy in 0 until source.height step cell) {
            for (cx in 0 until source.width step cell) {
                halftoneCell(source, out, cx, cy, cell)
            }
        }
        return out
    }

    /**
     * Ink on paper: white wherever the layer has paint, then one screen of dots per ink, each
     * multiplied over the paper. Screen print uses cyan, magenta and yellow screens offset from
     * one another, as separations are; Newspaper prints black dots sized by darkness.
     */
    private fun printedHalftone(
        source: PixelBuffer,
        cell: Int,
        style: HalftoneStyle,
    ): PixelBuffer {
        val out = PixelBuffer(source.width, source.height)
        for (i in source.pixels.indices) {
            val alpha = source.pixels[i] ushr 24
            if (alpha != 0) out.pixels[i] = (alpha shl 24) or 0xFFFFFF
        }
        val inks =
            if (style == HalftoneStyle.NEWSPAPER) {
                listOf(Ink(0x000000, 0f) { 1f - Channels.luminance(it) })
            } else {
                listOf(
                    Ink(0x00FFFF, 0f) { 1f - Channels.red(it) / 255f },
                    Ink(0xFF00FF, 1f / 3f) { 1f - Channels.green(it) / 255f },
                    Ink(0xFFFF00, 2f / 3f) { 1f - Channels.blue(it) / 255f },
                )
            }
        for (ink in inks) {
            val offset = (cell * ink.offset).roundToInt()
            for (cy in -offset until source.height step cell) {
                for (cx in -offset until source.width step cell) {
                    inkCell(source, out, cx, cy, cell, ink)
                }
            }
        }
        return out
    }

    /** One ink of a printed halftone: its colour, how far its screen is shifted, and its coverage of a colour. */
    private class Ink(
        val rgb: Int,
        val offset: Float,
        val coverage: (Int) -> Float,
    )

    private fun inkCell(
        source: PixelBuffer,
        out: PixelBuffer,
        cx: Int,
        cy: Int,
        cell: Int,
        ink: Ink,
    ) {
        val x0 = max(0, cx)
        val y0 = max(0, cy)
        val x1 = min(source.width, cx + cell)
        val y1 = min(source.height, cy + cell)
        if (x1 <= x0 || y1 <= y0) return
        var total = 0f
        var count = 0
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val p = source.pixels[y * source.width + x]
                if ((p ushr 24) == 0) continue
                total += ink.coverage(p)
                count++
            }
        }
        if (count == 0) return
        val radius = cell * 0.5f * sqrt((total / count).coerceIn(0f, 1f)) * DOT_REACH
        val centerX = cx + cell / 2f
        val centerY = cy + cell / 2f
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val i = y * source.width + x
                val paper = out.pixels[i]
                val dx = x + 0.5f - centerX
                val dy = y + 0.5f - centerY
                if ((paper ushr 24) == 0 || dx * dx + dy * dy > radius * radius) continue
                // Inks multiply over the paper and over each other.
                val r = ((paper shr 16) and 0xFF) * ((ink.rgb shr 16) and 0xFF) / 255
                val g = ((paper shr 8) and 0xFF) * ((ink.rgb shr 8) and 0xFF) / 255
                val b = (paper and 0xFF) * (ink.rgb and 0xFF) / 255
                out.pixels[i] = (paper and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
            }
        }
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
    private const val MAX_BLOOM_TRANSITION = 0.95f
    private const val ARTIFACT_MIN_BLOCK = 8f
    private const val ARTIFACT_BLOCK_RANGE = 24f
    private const val ARTIFACT_MAX_SHARE = 0.5f
    private const val HASH_BUCKETS = 1000
    private const val BLOCK_ROW_STRIDE = 4099
    private const val WAVE_REACH = 0.05f
    private const val WAVE_PERIOD = 48.0
    private const val DIVERGE_REACH = 0.02f
    private const val GLITCH_BAND = 6
    private const val MAX_ABERRATION = 0.05f
    private const val RECOLOR_MAX_TOLERANCE = 128f
    private const val MAX_PERSPECTIVE_PULL = 0.35f
    private const val PERSPECTIVE_SAMPLES = 16
    private const val PERSPECTIVE_TAP_SPACING = 3f
}
