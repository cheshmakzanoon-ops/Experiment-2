package com.artflow.studio.core.three

import kotlin.math.cos
import kotlin.math.sin

/**
 * How the 3D view lights a model: one key light fixed in the scene (so turning the model shows
 * it from new sides), a soft ambient fill, a highlight and an overall exposure.
 *
 * [azimuth] turns the light around the model and [elevation] raises it, both in degrees.
 * [warmth] tints the light from cool (-1) through white (0) to warm (1).
 */
data class ModelLighting(
    val azimuth: Float = 35f,
    val elevation: Float = 40f,
    val intensity: Float = 1f,
    val ambient: Float = 0.3f,
    val shine: Float = 0.25f,
    val exposure: Float = 1f,
    val warmth: Float = 0f,
) {
    /** Unit vector from the model toward the light. */
    fun direction(): Vec3 {
        val azimuthRadians = Math.toRadians(azimuth.toDouble())
        val elevationRadians = Math.toRadians(elevation.coerceIn(-MAX_ELEVATION, MAX_ELEVATION).toDouble())
        val flat = cos(elevationRadians)
        return Vec3(
            (flat * sin(azimuthRadians)).toFloat(),
            sin(elevationRadians).toFloat(),
            (flat * cos(azimuthRadians)).toFloat(),
        ).normalised()
    }

    /** Light colour scaled by [intensity], as linear RGB multipliers. */
    fun colour(): Vec3 {
        val tint = warmth.coerceIn(-1f, 1f)
        val red = 1f + WARM_SHIFT * tint
        val blue = 1f - WARM_SHIFT * tint
        val green = 1f + WARM_SHIFT * 0.3f * tint.coerceAtLeast(0f) - WARM_SHIFT * 0.1f * (-tint).coerceAtLeast(0f)
        val strength = intensity.coerceIn(0f, MAX_INTENSITY)
        return Vec3(red * strength, green * strength, blue * strength)
    }

    /** Brightness reaching a surface facing [normal]: ambient fill plus the key light. */
    fun shade(normal: Vec3): Float = ambient + intensity * maxOf(0f, normal.normalised().dot(direction()))

    data class Preset(
        val name: String,
        val lighting: ModelLighting,
    )

    companion object {
        const val MAX_ELEVATION = 89f
        const val MAX_INTENSITY = 2f
        private const val WARM_SHIFT = 0.25f

        val presets =
            listOf(
                Preset("Studio", ModelLighting()),
                Preset("Soft", ModelLighting(azimuth = 10f, elevation = 60f, intensity = 0.7f, ambient = 0.55f, shine = 0.1f)),
                Preset("Sunset", ModelLighting(azimuth = 80f, elevation = 12f, intensity = 1.2f, ambient = 0.2f, warmth = 0.8f)),
                Preset("Rim", ModelLighting(azimuth = 160f, elevation = 25f, intensity = 1.4f, ambient = 0.25f, shine = 0.5f)),
                Preset("Moonlight", ModelLighting(azimuth = -50f, elevation = 50f, intensity = 0.8f, ambient = 0.15f, warmth = -0.8f)),
                Preset("Flat", ModelLighting(intensity = 0f, ambient = 1f, shine = 0f)),
            )
    }
}
