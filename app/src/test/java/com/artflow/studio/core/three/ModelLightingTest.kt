package com.artflow.studio.core.three

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLightingTest {
    @Test
    fun directionFollowsAngleAndHeight() {
        val front = ModelLighting(azimuth = 0f, elevation = 0f).direction()
        assertEquals(0f, front.x, EPSILON)
        assertEquals(1f, front.z, EPSILON)
        val side = ModelLighting(azimuth = 90f, elevation = 0f).direction()
        assertEquals(1f, side.x, EPSILON)
        val above = ModelLighting(azimuth = 0f, elevation = 89f).direction()
        assertTrue(above.y > 0.99f)
        assertEquals(1f, ModelLighting(azimuth = 123f, elevation = 33f).direction().length(), EPSILON)
    }

    @Test
    fun surfacesFacingTheLightAreBrighter() {
        val light = ModelLighting(azimuth = 0f, elevation = 0f, intensity = 1f, ambient = 0.2f)
        assertEquals(1.2f, light.shade(Vec3(0f, 0f, 1f)), EPSILON)
        assertEquals(0.2f, light.shade(Vec3(0f, 0f, -1f)), EPSILON)
        assertTrue(light.shade(Vec3(1f, 0f, 1f)) in 0.2f..1.2f)
    }

    @Test
    fun warmthTintsAndFlatPresetIgnoresTheKeyLight() {
        val warm = ModelLighting(warmth = 1f).colour()
        assertTrue(warm.x > warm.z)
        val cool = ModelLighting(warmth = -1f).colour()
        assertTrue(cool.z > cool.x)
        val neutral = ModelLighting(warmth = 0f, intensity = 1f).colour()
        assertEquals(neutral.x, neutral.z, EPSILON)
        val flat = ModelLighting.presets.first { it.name == "Flat" }.lighting
        assertEquals(flat.shade(Vec3(0f, 1f, 0f)), flat.shade(Vec3(0f, -1f, 0f)), EPSILON)
        assertEquals(
            ModelLighting.presets.size,
            ModelLighting.presets
                .map { it.name }
                .distinct()
                .size,
        )
    }

    @Test
    fun smoothMetalHasSharperStrongerHighlightsThanRoughPaint() {
        val metal = ModelLighting(metallic = 1f, roughness = 0.1f)
        val paint = ModelLighting(metallic = 0f, roughness = 0.9f)
        assertTrue(metal.highlightPower() > paint.highlightPower())
        assertTrue(metal.highlightStrength() > paint.highlightStrength())
        assertTrue(paint.highlightStrength() > 0f)
        val sunset = ModelLighting.presets.first { it.name == "Sunset" }
        val applied = sunset.appliedTo(metal)
        assertEquals(1f, applied.metallic, EPSILON)
        assertEquals(sunset.lighting.azimuth, applied.azimuth, EPSILON)
        assertTrue(sunset.isApplied(applied))
    }

    private companion object {
        const val EPSILON = 1e-4f
    }

    @Test
    fun aSecondLightBrightensTheSideItFacesAndPresetsKeepIt() {
        val back = Vec3(0f, 0f, -1f)
        val one = ModelLighting(azimuth = 0f, elevation = 0f, ambient = 0.2f)
        val two = one.copy(secondAzimuth = 180f, secondElevation = 0f, secondIntensity = 0.5f)
        assertEquals(one.shade(back) + 0.5f, two.shade(back), EPSILON)
        assertEquals(0.5f, ModelLighting.presets.first().appliedTo(two).secondIntensity, EPSILON)
    }
}
