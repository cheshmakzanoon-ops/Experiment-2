package com.artflow.studio.core.three

import com.artflow.studio.core.pixels.Channels
import com.artflow.studio.core.pixels.PixelBuffer
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Physically Based Rendering (Cook-Torrance GGX BRDF), IBL environment presets,
 * tangent-space normal map encoding, and simultaneous multi-channel material painting.
 */
object PbrLighting {
    enum class IblEnvironment(
        val displayName: String,
        val skyColor: Vec3,
        val groundColor: Vec3,
        val keyDir: Vec3,
        val keyColor: Vec3,
    ) {
        STUDIO_SOFTBOX(
            "Studio Softbox",
            Vec3(0.85f, 0.88f, 0.92f),
            Vec3(0.28f, 0.28f, 0.30f),
            Vec3(0.55f, 0.72f, 0.42f).normalised(),
            Vec3(1.15f, 1.12f, 1.08f),
        ),
        OUTDOOR_SKY(
            "Outdoor Sky",
            Vec3(0.58f, 0.76f, 0.98f),
            Vec3(0.32f, 0.28f, 0.22f),
            Vec3(0.42f, 0.82f, 0.38f).normalised(),
            Vec3(1.35f, 1.24f, 1.05f),
        ),
        DRAMATIC_RIM(
            "Dramatic Rim",
            Vec3(0.22f, 0.26f, 0.38f),
            Vec3(0.10f, 0.08f, 0.12f),
            Vec3(-0.65f, 0.45f, -0.60f).normalised(),
            Vec3(1.65f, 1.30f, 0.95f),
        ),
        WARM_GALLERY(
            "Warm Gallery",
            Vec3(0.95f, 0.82f, 0.68f),
            Vec3(0.26f, 0.20f, 0.16f),
            Vec3(0.30f, 0.78f, 0.55f).normalised(),
            Vec3(1.22f, 1.05f, 0.86f),
        ),
    }

    /**
     * Evaluates the Cook-Torrance microfacet PBR BRDF + hemisphere IBL irradiance for a single surface point.
     * Returns tone-mapped sRGB ARGB.
     */
    fun shadePoint(
        albedoArgb: Int,
        metallic: Float,
        roughness: Float,
        normal: Vec3,
        viewDir: Vec3,
        environment: IblEnvironment = IblEnvironment.STUDIO_SOFTBOX,
        exposure: Float = 1.0f,
    ): Int {
        val alpha = (albedoArgb ushr 24) and 0xFF
        val n = normal.normalised()
        val v = viewDir.normalised()
        val l = environment.keyDir
        val h = Vec3(v.x + l.x, v.y + l.y, v.z + l.z).normalised()

        val rLin = srgbToLinear(((albedoArgb shr 16) and 0xFF) / 255f)
        val gLin = srgbToLinear(((albedoArgb shr 8) and 0xFF) / 255f)
        val bLin = srgbToLinear((albedoArgb and 0xFF) / 255f)

        val met = metallic.coerceIn(0f, 1f)
        val rough = roughness.coerceIn(0.04f, 1f)
        val a2 = (rough * rough).let { it * it }

        val ndotv = max(n.dot(v), 0.001f)
        val ndotl = max(n.dot(l), 0f)
        val ndoth = max(n.dot(h), 0f)
        val hdotv = max(h.dot(v), 0f)

        // Base reflectance F0: 0.04 for dielectrics, albedo for conductors.
        val f0r = 0.04f * (1f - met) + rLin * met
        val f0g = 0.04f * (1f - met) + gLin * met
        val f0b = 0.04f * (1f - met) + bLin * met

        // GGX Normal Distribution (D)
        val denomD = (ndoth * ndoth * (a2 - 1f) + 1f)
        val dist = a2 / (PI.toFloat() * denomD * denomD).coerceAtLeast(1e-5f)

        // Schlick-GGX Geometry (G)
        val k = ((rough + 1f) * (rough + 1f)) / 8f
        val g1v = ndotv / (ndotv * (1f - k) + k)
        val g1l = ndotl / (ndotl * (1f - k) + k)
        val geom = g1v * g1l

        // Schlick Fresnel (F)
        val fresnelTerm = (1f - hdotv).pow(5f)
        val fr = f0r + (1f - f0r) * fresnelTerm
        val fg = f0g + (1f - f0g) * fresnelTerm
        val fb = f0b + (1f - f0b) * fresnelTerm

        val specDenom = (4f * ndotv * ndotl).coerceAtLeast(0.001f)
        val specScale = (dist * geom) / specDenom
        val kdR = (1f - fr) * (1f - met)
        val kdG = (1f - fg) * (1f - met)
        val kdB = (1f - fb) * (1f - met)

        // Direct radiance from key light
        val directR = (kdR * rLin / PI.toFloat() + fr * specScale) * environment.keyColor.x * ndotl
        val directG = (kdG * gLin / PI.toFloat() + fg * specScale) * environment.keyColor.y * ndotl
        val directB = (kdB * bLin / PI.toFloat() + fb * specScale) * environment.keyColor.z * ndotl

        // Hemisphere IBL diffuse + specular ambient fill
        val skyFactor = (n.y * 0.5f + 0.5f).coerceIn(0f, 1f)
        val envR = environment.groundColor.x * (1f - skyFactor) + environment.skyColor.x * skyFactor
        val envG = environment.groundColor.y * (1f - skyFactor) + environment.skyColor.y * skyFactor
        val envB = environment.groundColor.z * (1f - skyFactor) + environment.skyColor.z * skyFactor
        val iblSpec = (1f - rough) * 0.35f

        val totalR = (directR + envR * (rLin * (1f - met) * 0.38f + f0r * iblSpec)) * exposure
        val totalG = (directG + envG * (gLin * (1f - met) * 0.38f + f0g * iblSpec)) * exposure
        val totalB = (directB + envB * (bLin * (1f - met) * 0.38f + f0b * iblSpec)) * exposure

        // ACES-like filmic Reinhard tone mapping -> sRGB
        return Channels.argb(
            alpha,
            (linearToSrgb(totalR / (1f + totalR)) * 255f).roundToInt().coerceIn(0, 255),
            (linearToSrgb(totalG / (1f + totalG)) * 255f).roundToInt().coerceIn(0, 255),
            (linearToSrgb(totalB / (1f + totalB)) * 255f).roundToInt().coerceIn(0, 255),
        )
    }

    /** Encodes a tangent-space unit normal into standard RGB normal-map ARGB (neutral flat = #8080FF). */
    fun encodeNormal(normal: Vec3): Int {
        val n = normal.normalised()
        val r = ((n.x * 0.5f + 0.5f) * 255f).roundToInt().coerceIn(0, 255)
        val g = ((n.y * 0.5f + 0.5f) * 255f).roundToInt().coerceIn(0, 255)
        val b = ((n.z * 0.5f + 0.5f) * 255f).roundToInt().coerceIn(0, 255)
        return Channels.argb(255, r, g, b)
    }

    /** Decodes a tangent-space normal-map ARGB pixel into a unit [Vec3]. */
    fun decodeNormal(argb: Int): Vec3 {
        val x = (((argb shr 16) and 0xFF) / 255f) * 2f - 1f
        val y = (((argb shr 8) and 0xFF) / 255f) * 2f - 1f
        val z = ((argb and 0xFF) / 255f) * 2f - 1f
        val len = sqrt(x * x + y * y + z * z)
        return if (len > 1e-4f) Vec3(x / len, y / len, z / len) else Vec3(0f, 0f, 1f)
    }

    /**
     * Simultaneous multi-channel PBR material dab: paints Color, Metallic, Roughness, and optional
     * convex/concave Normal bump in one atomic pass.
     */
    data class MultiChannelDab(
        val colorArgb: Int,
        val metallic: Float?,
        val roughness: Float?,
        val normalBumpStrength: Float = 0f,
    )

    fun stampMultiChannel(
        colorAtlas: PixelBuffer,
        metallicAtlas: PixelBuffer?,
        roughnessAtlas: PixelBuffer?,
        normalAtlas: PixelBuffer?,
        centerX: Float,
        centerY: Float,
        radius: Float,
        dab: MultiChannelDab,
    ) {
        val r = radius.coerceAtLeast(1f)
        val minX = max(0, floor(centerX - r).toInt())
        val maxX = min(colorAtlas.width - 1, ceil(centerX + r).toInt())
        val minY = max(0, floor(centerY - r).toInt())
        val maxY = min(colorAtlas.height - 1, ceil(centerY + r).toInt())
        val metPixel = dab.metallic?.let { MaterialChannel.gray(it) }
        val roughPixel = dab.roughness?.let { MaterialChannel.gray(it) }

        for (y in minY..maxY) {
            val row = y * colorAtlas.width
            for (x in minX..maxX) {
                val dx = (x + 0.5f - centerX) / r
                val dy = (y + 0.5f - centerY) / r
                val dist = hypot(dx, dy)
                if (dist > 1f) continue
                val falloff = 1f - dist * dist
                val idx = row + x

                colorAtlas.pixels[idx] = lerpColor(colorAtlas.pixels[idx], dab.colorArgb, falloff)
                if (metallicAtlas != null && metPixel != null) {
                    metallicAtlas.pixels[idx] = lerpColor(metallicAtlas.pixels[idx], metPixel, falloff)
                }
                if (roughnessAtlas != null && roughPixel != null) {
                    roughnessAtlas.pixels[idx] = lerpColor(roughnessAtlas.pixels[idx], roughPixel, falloff)
                }
                if (normalAtlas != null && dab.normalBumpStrength != 0f) {
                    val bump = dab.normalBumpStrength.coerceIn(-1f, 1f) * falloff
                    val n = Vec3(dx * bump, dy * bump, 1f).normalised()
                    normalAtlas.pixels[idx] = encodeNormal(n)
                }
            }
        }
    }

    private fun lerpColor(
        dst: Int,
        src: Int,
        t: Float,
    ): Int {
        val f = t.coerceIn(0f, 1f)
        val inv = 1f - f
        val da = (dst ushr 24) and 0xFF
        val sa = (src ushr 24) and 0xFF
        val a = (da * inv + sa * f).roundToInt().coerceIn(0, 255)
        val r = (((dst shr 16) and 0xFF) * inv + ((src shr 16) and 0xFF) * f).roundToInt().coerceIn(0, 255)
        val g = (((dst shr 8) and 0xFF) * inv + ((src shr 8) and 0xFF) * f).roundToInt().coerceIn(0, 255)
        val b = ((dst and 0xFF) * inv + (src and 0xFF) * f).roundToInt().coerceIn(0, 255)
        return Channels.argb(a, r, g, b)
    }

    private fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    private fun linearToSrgb(c: Float): Float =
        if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f
}
