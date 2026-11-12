package com.artflow.studio.core.three

import com.artflow.studio.core.pixels.Channels
import com.artflow.studio.core.pixels.PixelBuffer
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Multi-channel PBR material atlas managing synchronized BaseColor, Metallic, and Roughness planes.
 * Allows simultaneous stamping across all 3 PBR channels in a single brush dab as in Procreate 5.2+.
 */
class PbrMaterialAtlas(
    val width: Int,
    val height: Int,
    val baseColor: PixelBuffer = PixelBuffer(width, height),
    val metallic: PixelBuffer = PixelBuffer(width, height),
    val roughness: PixelBuffer = PixelBuffer(width, height),
) {
    init {
        require(width > 0 && height > 0) { "Atlas dimensions must be positive ($width x $height)" }
        require(baseColor.width == width && baseColor.height == height) { "BaseColor buffer dimensions mismatch" }
        require(metallic.width == width && metallic.height == height) { "Metallic buffer dimensions mismatch" }
        require(roughness.width == width && roughness.height == height) { "Roughness buffer dimensions mismatch" }
    }

    /**
     * Stamps a radial brush dab onto all three PBR material channels simultaneously.
     *
     * @param cx Center X in pixel coordinates
     * @param cy Center Y in pixel coordinates
     * @param radius Dab radius in pixels
     * @param color ARGB color for baseColor channel
     * @param metallicValue Metallic scalar (0f..1f)
     * @param roughnessValue Roughness scalar (0f..1f)
     * @param hardness Falloff hardness (0f = soft gaussian/smoothstep, 1f = hard edge)
     * @param flow Opacity flow multiplier (0f..1f)
     */
    fun stampDab(
        cx: Float,
        cy: Float,
        radius: Float,
        color: Int,
        metallicValue: Float,
        roughnessValue: Float,
        hardness: Float = 0.5f,
        flow: Float = 1.0f,
    ) {
        if (radius <= 0f || flow <= 0f) return

        val minX = max(0, (cx - radius).toInt())
        val maxX = min(width - 1, (cx + radius).toInt() + 1)
        val minY = max(0, (cy - radius).toInt())
        val maxY = min(height - 1, (cy + radius).toInt() + 1)

        val brushA = (Channels.alpha(color) / 255f) * flow.coerceIn(0f, 1f)
        val brushR = Channels.red(color)
        val brushG = Channels.green(color)
        val brushB = Channels.blue(color)

        val metalInt = (metallicValue.coerceIn(0f, 1f) * 255f).roundToInt()
        val roughInt = (roughnessValue.coerceIn(0f, 1f) * 255f).roundToInt()

        val clampedHardness = hardness.coerceIn(0.01f, 0.99f)

        for (y in minY..maxY) {
            val dy = y - cy
            val rowOffset = y * width
            for (x in minX..maxX) {
                val dx = x - cx
                val dabAlpha = computeDabAlpha(hypot(dx, dy), radius, clampedHardness, brushA)
                if (dabAlpha <= 0.001f) continue

                val index = rowOffset + x

                // 1. BaseColor blending (standard premultiplied alpha blend)
                val dstColor = baseColor.pixels[index]
                val dstA = Channels.alpha(dstColor) / 255f
                val outA = dabAlpha + dstA * (1f - dabAlpha)
                if (outA > 0f) {
                    val outR = (brushR * dabAlpha + Channels.red(dstColor) * dstA * (1f - dabAlpha)) / outA
                    val outG = (brushG * dabAlpha + Channels.green(dstColor) * dstA * (1f - dabAlpha)) / outA
                    val outB = (brushB * dabAlpha + Channels.blue(dstColor) * dstA * (1f - dabAlpha)) / outA
                    baseColor.pixels[index] =
                        Channels.argb(
                            (outA * 255f).roundToInt().coerceIn(0, 255),
                            outR.roundToInt().coerceIn(0, 255),
                            outG.roundToInt().coerceIn(0, 255),
                            outB.roundToInt().coerceIn(0, 255),
                        )
                }

                // 2. Metallic blending
                val dstMetal = Channels.red(metallic.pixels[index]).toInt()
                val outMetal = (metalInt * dabAlpha + dstMetal * (1f - dabAlpha)).roundToInt().coerceIn(0, 255)
                metallic.pixels[index] = Channels.argb(255, outMetal, outMetal, outMetal)

                // 3. Roughness blending
                val dstRough = Channels.red(roughness.pixels[index]).toInt()
                val outRough = (roughInt * dabAlpha + dstRough * (1f - dabAlpha)).roundToInt().coerceIn(0, 255)
                roughness.pixels[index] = Channels.argb(255, outRough, outRough, outRough)
            }
        }
    }

    private fun computeDabAlpha(
        dist: Float,
        radius: Float,
        clampedHardness: Float,
        brushA: Float,
    ): Float {
        if (dist > radius) return 0f
        val normalizedDist = dist / radius
        val edgeAlpha =
            if (normalizedDist <= clampedHardness) {
                1.0f
            } else {
                val t = (normalizedDist - clampedHardness) / (1f - clampedHardness)
                (1f - t * t * (3f - 2f * t)).coerceIn(0f, 1f)
            }
        return (brushA * edgeAlpha).coerceIn(0f, 1f)
    }

    /**
     * Evaluates Cook-Torrance microfacet specular BRDF and energy conservation in CPU simulation.
     */
    fun evaluateCookTorrance(
        albedo: FloatArray, // [r, g, b] in 0..1
        metallic: Float,
        roughness: Float,
        nDotV: Float,
        nDotL: Float,
        nDotH: Float,
        vDotH: Float,
    ): FloatArray {
        val nv = nDotV.coerceIn(0.001f, 1.0f)
        val nl = nDotL.coerceIn(0.001f, 1.0f)
        val nh = nDotH.coerceIn(0.001f, 1.0f)
        val vh = vDotH.coerceIn(0.001f, 1.0f)

        // 1. D: GGX / Trowbridge-Reitz normal distribution
        val alpha = roughness * roughness
        val alpha2 = alpha * alpha
        val nh2 = nh * nh
        val denomD = nh2 * (alpha2 - 1.0f) + 1.0f
        val d = alpha2 / (Math.PI.toFloat() * denomD * denomD)

        // 2. F: Schlick Fresnel
        val f0Dielectric = 0.04f
        val f0 =
            FloatArray(3) { i ->
                f0Dielectric * (1f - metallic) + albedo[i] * metallic
            }
        val f =
            FloatArray(3) { i ->
                f0[i] + (1f - f0[i]) * Math.pow((1.0 - vh).toDouble(), 5.0).toFloat()
            }

        // 3. G: Smith model with Schlick-GGX
        val k = ((roughness + 1f) * (roughness + 1f)) / 8f
        val g1V = nv / (nv * (1f - k) + k)
        val g1L = nl / (nl * (1f - k) + k)
        val g = g1V * g1L

        // Specular BRDF term
        val denomBrdf = 4f * nv * nl
        val specular =
            FloatArray(3) { i ->
                (d * f[i] * g) / denomBrdf
            }

        // Energy conservation: kD + kS = 1.0
        val kd =
            FloatArray(3) { i ->
                (1f - f[i]) * (1f - metallic)
            }
        val diffuse =
            FloatArray(3) { i ->
                kd[i] * albedo[i] / Math.PI.toFloat()
            }

        return FloatArray(3) { i ->
            (diffuse[i] + specular[i]) * nl
        }
    }
}
