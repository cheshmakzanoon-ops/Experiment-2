package com.artflow.studio.core.color

import com.artflow.studio.core.pixels.Channels
import com.artflow.studio.core.pixels.PixelBuffer
import kotlin.math.abs
import kotlin.math.pow

/**
 * Standard ICC color profiles for print and screen display.
 */
enum class IccProfileType(
    val profileName: String,
    val isCmyk: Boolean,
    val totalAreaCoverage: Float, // Total Area Coverage (e.g. 3.0f = 300%)
    val blackLimit: Float = 1.0f,
    val description: String = "",
) {
    FOGRA39(
        profileName = "Coated FOGRA39 (ISO 12647-2:2004)",
        isCmyk = true,
        totalAreaCoverage = 3.30f,
        blackLimit = 0.95f,
        description = "European standard offset lithography on coated paper",
    ),
    SWOP_2006(
        profileName = "U.S. Web Coated (SWOP) v2",
        isCmyk = true,
        totalAreaCoverage = 3.00f,
        blackLimit = 0.90f,
        description = "Specifications for Web Offset Publications (US standard)",
    ),
    JAPAN_COLOR_2001(
        profileName = "Japan Color 2001 Coated",
        isCmyk = true,
        totalAreaCoverage = 3.50f,
        blackLimit = 0.98f,
        description = "Japanese standard commercial sheetfed printing",
    ),
    GRACOL_2006(
        profileName = "GRACoL 2006 Coated #1",
        isCmyk = true,
        totalAreaCoverage = 3.20f,
        blackLimit = 0.95f,
        description = "General Requirements for Applications in Commercial Offset Lithography",
    ),
    DISPLAY_P3(
        profileName = "Display P3",
        isCmyk = false,
        totalAreaCoverage = 0.0f,
        blackLimit = 1.0f,
        description = "Apple wide gamut D65 RGB display space",
    ),
    SRGB(
        profileName = "sRGB IEC61966-2.1",
        isCmyk = false,
        totalAreaCoverage = 0.0f,
        blackLimit = 1.0f,
        description = "Standard web and sRGB display space",
    ),
    ;

    companion object {
        fun fromName(name: String?): IccProfileType =
            entries.firstOrNull { it.profileName.equals(name, ignoreCase = true) || it.name.equals(name, ignoreCase = true) }
                ?: FOGRA39
    }
}

/**
 * Standard ICC rendering intents.
 */
enum class RenderingIntent {
    PERCEPTUAL,
    RELATIVE_COLORIMETRIC,
    SATURATION,
    ABSOLUTE_COLORIMETRIC,
}

/**
 * Profile-referenced ICC color conversion and CMYK soft-proofing engine.
 *
 * Implements analytical UCR/GCR (Under Color Removal / Gray Component Replacement),
 * Total Area Coverage (TAC) clipping, and multi-profile soft-proofing with out-of-gamut
 * zebra pattern diagnostics. Note: This is an analytical ink-absorption model rather than
 * a native C LittleCMS library integration.
 */
object IccColorEngine {
    /** Default gamut tolerance threshold for out-of-gamut detection. */
    const val DEFAULT_GAMUT_TOLERANCE = 0.08f

    // Profile ink colorant absorption vectors (Cyan, Magenta, Yellow, Black) in linear RGB space
    private data class InkProfile(
        val cyan: FloatArray,
        val magenta: FloatArray,
        val yellow: FloatArray,
        val black: FloatArray,
        val paperWhite: FloatArray,
        val blackStart: Float,
        val gcrExponent: Float,
        val ucrFactor: Float,
    )

    private val FOGRA39_INKS =
        InkProfile(
            cyan = floatArrayOf(0.04f, 0.61f, 0.88f),
            magenta = floatArrayOf(0.89f, 0.08f, 0.49f),
            yellow = floatArrayOf(0.96f, 0.92f, 0.05f),
            black = floatArrayOf(0.08f, 0.07f, 0.08f),
            paperWhite = floatArrayOf(0.98f, 0.98f, 0.96f),
            blackStart = 0.10f,
            gcrExponent = 1.10f,
            ucrFactor = 0.78f,
        )

    private val SWOP_INKS =
        InkProfile(
            cyan = floatArrayOf(0.05f, 0.58f, 0.84f),
            magenta = floatArrayOf(0.86f, 0.09f, 0.46f),
            yellow = floatArrayOf(0.94f, 0.89f, 0.06f),
            black = floatArrayOf(0.12f, 0.11f, 0.11f),
            paperWhite = floatArrayOf(0.95f, 0.95f, 0.92f),
            blackStart = 0.18f,
            gcrExponent = 1.25f,
            ucrFactor = 0.70f,
        )

    private val JAPAN_INKS =
        InkProfile(
            cyan = floatArrayOf(0.03f, 0.63f, 0.90f),
            magenta = floatArrayOf(0.91f, 0.07f, 0.52f),
            yellow = floatArrayOf(0.98f, 0.94f, 0.04f),
            black = floatArrayOf(0.06f, 0.06f, 0.06f),
            paperWhite = floatArrayOf(0.99f, 0.99f, 0.98f),
            blackStart = 0.08f,
            gcrExponent = 1.05f,
            ucrFactor = 0.82f,
        )

    private val GRACOL_INKS =
        InkProfile(
            cyan = floatArrayOf(0.04f, 0.60f, 0.87f),
            magenta = floatArrayOf(0.88f, 0.08f, 0.48f),
            yellow = floatArrayOf(0.95f, 0.91f, 0.05f),
            black = floatArrayOf(0.09f, 0.08f, 0.09f),
            paperWhite = floatArrayOf(0.97f, 0.97f, 0.95f),
            blackStart = 0.12f,
            gcrExponent = 1.15f,
            ucrFactor = 0.75f,
        )

    private fun getInkProfile(profile: IccProfileType): InkProfile =
        when (profile) {
            IccProfileType.FOGRA39 -> FOGRA39_INKS
            IccProfileType.SWOP_2006 -> SWOP_INKS
            IccProfileType.JAPAN_COLOR_2001 -> JAPAN_INKS
            IccProfileType.GRACOL_2006 -> GRACOL_INKS
            else -> FOGRA39_INKS
        }

    /**
     * Converts an RGB color (in 0f..1f range) to 4-channel CMYK [c, m, y, k]
     * under the specified CMYK profile using calibrated GCR/UCR and TAC constraints.
     */
    fun rgbToCmyk(
        r: Float,
        g: Float,
        b: Float,
        profile: IccProfileType = IccProfileType.FOGRA39,
    ): FloatArray {
        val cr = r.coerceIn(0f, 1f)
        val cg = g.coerceIn(0f, 1f)
        val cb = b.coerceIn(0f, 1f)

        var c = 1f - cr
        var m = 1f - cg
        var y = 1f - cb

        val ink = getInkProfile(profile)
        val kMin = minOf(c, m, y)

        // Profile-specific Black Generation curve (GCR)
        val k =
            if (kMin > ink.blackStart) {
                val normalized = (kMin - ink.blackStart) / (1f - ink.blackStart)
                (normalized.pow(ink.gcrExponent) * profile.blackLimit).coerceIn(0f, profile.blackLimit)
            } else {
                0f
            }

        // Under Color Removal (UCR)
        if (k > 0f) {
            val ucr = k * ink.ucrFactor
            c = (c - ucr).coerceIn(0f, 1f)
            m = (m - ucr).coerceIn(0f, 1f)
            y = (y - ucr).coerceIn(0f, 1f)
        }

        // Total Area Coverage (TAC) limiting
        val total = c + m + y + k
        if (total > profile.totalAreaCoverage && total > 0.001f) {
            val maxCmy = profile.totalAreaCoverage - k
            val cmySum = c + m + y
            if (cmySum > 0.001f && maxCmy > 0f) {
                val scale = maxCmy / cmySum
                c *= scale
                m *= scale
                y *= scale
            }
        }

        return floatArrayOf(
            c.coerceIn(0f, 1f),
            m.coerceIn(0f, 1f),
            y.coerceIn(0f, 1f),
            k.coerceIn(0f, 1f),
        )
    }

    /**
     * Converts a 4-channel CMYK color (in 0f..1f range) to RGB [r, g, b]
     * using the profile's calibrated spectral ink absorption model.
     */
    fun cmykToRgb(
        c: Float,
        m: Float,
        y: Float,
        k: Float,
        profile: IccProfileType = IccProfileType.FOGRA39,
    ): FloatArray {
        val cc = c.coerceIn(0f, 1f)
        val cm = m.coerceIn(0f, 1f)
        val cy = y.coerceIn(0f, 1f)
        val ck = k.coerceIn(0f, 1f)

        val ink = getInkProfile(profile)

        // Multi-ink subtractive spectral interaction
        fun channel(ch: Int): Float {
            val paper = ink.paperWhite[ch]
            val cFactor = 1f - cc * (1f - ink.cyan[ch])
            val mFactor = 1f - cm * (1f - ink.magenta[ch])
            val yFactor = 1f - cy * (1f - ink.yellow[ch])
            val kFactor = 1f - ck * (1f - ink.black[ch])
            return (paper * cFactor * mFactor * yFactor * kFactor).coerceIn(0f, 1f)
        }

        return floatArrayOf(channel(0), channel(1), channel(2))
    }

    /**
     * Simulates on screen how an RGB color will appear when printed on the target CMYK profile.
     */
    fun softProof(
        rgb: FloatArray,
        @Suppress("UnusedParameter") sourceProfile: IccProfileType = IccProfileType.SRGB,
        targetProfile: IccProfileType = IccProfileType.FOGRA39,
        intent: RenderingIntent = RenderingIntent.PERCEPTUAL,
    ): FloatArray {
        require(rgb.size >= 3) { "RGB array must have at least 3 elements" }
        if (!targetProfile.isCmyk) return floatArrayOf(rgb[0], rgb[1], rgb[2])

        val cmyk = rgbToCmyk(rgb[0], rgb[1], rgb[2], targetProfile)
        val printedRgb = cmykToRgb(cmyk[0], cmyk[1], cmyk[2], cmyk[3], targetProfile)

        return when (intent) {
            RenderingIntent.ABSOLUTE_COLORIMETRIC -> {
                // Preserves paper tint
                printedRgb
            }
            RenderingIntent.RELATIVE_COLORIMETRIC, RenderingIntent.PERCEPTUAL, RenderingIntent.SATURATION -> {
                // Normalizes paper white to screen display white (1, 1, 1)
                val paper = getInkProfile(targetProfile).paperWhite
                floatArrayOf(
                    (printedRgb[0] / paper[0]).coerceIn(0f, 1f),
                    (printedRgb[1] / paper[1]).coerceIn(0f, 1f),
                    (printedRgb[2] / paper[2]).coerceIn(0f, 1f),
                )
            }
        }
    }

    /**
     * Determines whether an RGB color falls outside the printable gamut of [targetProfile].
     */
    fun isOutOfGamut(
        r: Float,
        g: Float,
        b: Float,
        targetProfile: IccProfileType = IccProfileType.FOGRA39,
        tolerance: Float = DEFAULT_GAMUT_TOLERANCE,
    ): Boolean {
        if (!targetProfile.isCmyk) return false
        // Pure grays map smoothly to black ink, so they are always in gamut
        if (abs(r - g) < 0.005f && abs(g - b) < 0.005f) return false

        val proofed = softProof(floatArrayOf(r, g, b), targetProfile = targetProfile)
        val maxDiff = maxOf(abs(proofed[0] - r), abs(proofed[1] - g), abs(proofed[2] - b))
        return maxDiff > tolerance
    }

    /**
     * Generates a diagnostic visual buffer overlaying a diagonal zebra hatching pattern
     * on all pixels that cannot be reproduced within the destination CMYK profile.
     */
    fun generateGamutZebra(
        source: PixelBuffer,
        targetProfile: IccProfileType = IccProfileType.FOGRA39,
        zebraColor: Int = 0xFFFF0055.toInt(), // Vivid fluorescent warning magenta
        stripeWidth: Int = 8,
    ): PixelBuffer {
        val width = source.width
        val height = source.height
        val total = width * height
        val result = PixelBuffer(width, height)
        for (index in 0 until total) {
            val pixel = source.pixels[index]
            val alpha = Channels.alpha(pixel)

            if (alpha <= 0.001f) {
                result.pixels[index] = 0
                continue
            }

            val r = Channels.red(pixel) / 255f
            val g = Channels.green(pixel) / 255f
            val b = Channels.blue(pixel) / 255f
            val x = index % width
            val y = index / width

            if (isOutOfGamut(r, g, b, targetProfile)) {
                val isStripe = ((x + y) / stripeWidth) % 2 == 0
                result.pixels[index] = if (isStripe) zebraColor else pixel
            } else {
                result.pixels[index] = pixel
            }
        }
        return result
    }
}
