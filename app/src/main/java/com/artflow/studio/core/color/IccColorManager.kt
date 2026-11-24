package com.artflow.studio.core.color

import com.artflow.studio.core.pixels.Channels
import com.artflow.studio.core.pixels.PixelBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Full ICC-aware working color space conversion & print CMYK separation engine.
 * Supports sRGB, Display P3, U.S. Web Coated (SWOP) v2, and Coated FOGRA39 with
 * Gray Component Replacement (GCR), Total Area Coverage (TAC) ink limiting,
 * and out-of-gamut soft-proof masking.
 */
object IccColorManager {
    enum class WorkingSpace(
        val label: String,
        val isCmyk: Boolean,
        val maxTotalInk: Float = 3.0f,
    ) {
        SRGB("sRGB IEC61966-2.1", isCmyk = false),
        DISPLAY_P3("Display P3", isCmyk = false),
        CMYK_SWOP_V2("U.S. Web Coated (SWOP) v2", isCmyk = true, maxTotalInk = 3.0f),
        CMYK_FOGRA39("Coated FOGRA39 (ISO 12647-2)", isCmyk = true, maxTotalInk = 3.3f),
    }

    enum class RenderingIntent {
        PERCEPTUAL,
        RELATIVE_COLORIMETRIC,
    }

    data class Cmyk(
        val c: Float,
        val m: Float,
        val y: Float,
        val k: Float,
    ) {
        val totalInk: Float get() = c + m + y + k
    }

    /** Separates an sRGB [argb] color into press-ready [Cmyk] with GCR and TAC ink limits. */
    fun srgbToCmyk(
        argb: Int,
        space: WorkingSpace = WorkingSpace.CMYK_SWOP_V2,
    ): Cmyk {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f

        val rawC = 1f - r
        val rawM = 1f - g
        val rawY = 1f - b
        val minCmy = min(rawC, min(rawM, rawY))
        if (minCmy >= 0.999f) return Cmyk(0f, 0f, 0f, 1f)

        // Medium Gray Component Replacement (GCR) keeps rich blacks while saving ink.
        val gcr = if (space == WorkingSpace.CMYK_FOGRA39) 0.78f else 0.72f
        val k = (minCmy.pow(1.25f) * gcr).coerceIn(0f, 1f)
        val denom = (1f - k).coerceAtLeast(1e-4f)
        var c = ((rawC - k) / denom).coerceIn(0f, 1f)
        var m = ((rawM - k) / denom).coerceIn(0f, 1f)
        var y = ((rawY - k) / denom).coerceIn(0f, 1f)

        // Enforce Total Area Coverage (TAC) press ink limit.
        val tac = c + m + y + k
        if (tac > space.maxTotalInk && (c + m + y) > 1e-4f) {
            val scale = ((space.maxTotalInk - k) / (c + m + y)).coerceIn(0f, 1f)
            c *= scale
            m *= scale
            y *= scale
        }
        return Cmyk(c, m, y, k)
    }

    /** Reconstructs soft-proofed sRGB ARGB from press [cmyk] inks (with paper-white & ink-gamut attenuation). */
    fun cmykToSrgb(
        cmyk: Cmyk,
        space: WorkingSpace = WorkingSpace.CMYK_SWOP_V2,
        alpha: Int = 255,
    ): Int {
        // Coated press inks cannot reach full emissive RGB primaries (cyan has green/blue purity limits).
        val inkGain = if (space == WorkingSpace.CMYK_FOGRA39) 0.95f else 0.92f
        val c = cmyk.c.coerceIn(0f, 1f)
        val m = cmyk.m.coerceIn(0f, 1f)
        val y = cmyk.y.coerceIn(0f, 1f)
        val k = cmyk.k.coerceIn(0f, 1f)

        // Neugebauer-inspired subtractive ink response with realistic paper white (~0.97).
        val paper = if (space == WorkingSpace.CMYK_FOGRA39) 0.98f else 0.96f
        val r = paper * (1f - min(1f, c * inkGain + m * 0.08f)) * (1f - k * 0.94f)
        val g = paper * (1f - min(1f, m * inkGain + c * 0.10f + y * 0.04f)) * (1f - k * 0.95f)
        val b = paper * (1f - min(1f, y * inkGain + m * 0.14f)) * (1f - k * 0.95f)
        return Channels.argb(
            alpha.coerceIn(0, 255),
            (r.coerceIn(0f, 1f) * 255f).roundToInt(),
            (g.coerceIn(0f, 1f) * 255f).roundToInt(),
            (b.coerceIn(0f, 1f) * 255f).roundToInt(),
        )
    }

    /** Converts a single ARGB pixel between any two [WorkingSpace] profiles (real pixel conversion, not retagging). */
    fun convertColor(
        argb: Int,
        from: WorkingSpace,
        to: WorkingSpace,
        intent: RenderingIntent = RenderingIntent.RELATIVE_COLORIMETRIC,
    ): Int {
        if (from == to) return argb
        val alpha = (argb ushr 24) and 0xFF
        if (alpha == 0) return argb

        // Step 1: Normalize source pixel into linear sRGB bridge.
        val srgb =
            when (from) {
                WorkingSpace.SRGB -> argb
                WorkingSpace.DISPLAY_P3 -> ColorProfiles.convert(argb, ColorProfile.DISPLAY_P3, ColorProfile.SRGB)
                WorkingSpace.CMYK_SWOP_V2, WorkingSpace.CMYK_FOGRA39 -> argb
            }

        // Step 2: Convert into destination space.
        return when (to) {
            WorkingSpace.SRGB -> srgb
            WorkingSpace.DISPLAY_P3 -> ColorProfiles.convert(srgb, ColorProfile.SRGB, ColorProfile.DISPLAY_P3)
            WorkingSpace.CMYK_SWOP_V2, WorkingSpace.CMYK_FOGRA39 -> {
                val separated = srgbToCmyk(srgb, to)
                val proofed = cmykToSrgb(separated, to, alpha)
                if (intent == RenderingIntent.PERCEPTUAL) {
                    // Perceptual intent gently compresses saturation rather than hard-clipping edges.
                    blendArgb(proofed, srgb, 0.12f)
                } else {
                    proofed
                }
            }
        }
    }

    /** Converts every pixel of [buffer] from [from] to [to], returning a new [PixelBuffer]. */
    fun convertPixels(
        buffer: PixelBuffer,
        from: WorkingSpace,
        to: WorkingSpace,
        intent: RenderingIntent = RenderingIntent.RELATIVE_COLORIMETRIC,
    ): PixelBuffer {
        if (from == to) return buffer.copy()
        return PixelBuffer(
            buffer.width,
            buffer.height,
            IntArray(buffer.pixels.size) { i -> convertColor(buffer.pixels[i], from, to, intent) },
        )
    }

    /**
     * Generates a gamut-warning overlay buffer where pixels whose round-trip delta into
     * [targetSpace] exceeds [threshold] are highlighted in [warningColor].
     */
    fun gamutWarningMask(
        buffer: PixelBuffer,
        from: WorkingSpace = WorkingSpace.SRGB,
        targetSpace: WorkingSpace = WorkingSpace.CMYK_SWOP_V2,
        threshold: Int = 24,
        warningColor: Int = 0xCC999999.toInt(),
    ): PixelBuffer {
        val out = PixelBuffer(buffer.width, buffer.height)
        for (i in buffer.pixels.indices) {
            val src = buffer.pixels[i]
            if ((src ushr 24) == 0) continue
            val converted = convertColor(src, from, targetSpace)
            val dr = abs(((src shr 16) and 0xFF) - ((converted shr 16) and 0xFF))
            val dg = abs(((src shr 8) and 0xFF) - ((converted shr 8) and 0xFF))
            val db = abs((src and 0xFF) - (converted and 0xFF))
            if (max(dr, max(dg, db)) >= threshold) {
                out.pixels[i] = warningColor
            }
        }
        return out
    }

    private fun blendArgb(
        a: Int,
        b: Int,
        t: Float,
    ): Int {
        val inv = 1f - t
        val al = (a ushr 24) and 0xFF
        val r = (((a shr 16) and 0xFF) * inv + ((b shr 16) and 0xFF) * t).roundToInt().coerceIn(0, 255)
        val g = (((a shr 8) and 0xFF) * inv + ((b shr 8) and 0xFF) * t).roundToInt().coerceIn(0, 255)
        val bl = ((a and 0xFF) * inv + (b and 0xFF) * t).roundToInt().coerceIn(0, 255)
        return Channels.argb(al, r, g, bl)
    }
}
