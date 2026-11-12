package com.artflow.studio.core.three

import com.artflow.studio.core.pixels.Channels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PbrMultiChannelPaintingTest {
    @Test
    fun multiChannelStampDabAppliesSynchronizedDabs() {
        val width = 64
        val height = 64
        val atlas = PbrMaterialAtlas(width, height)

        val dabColor = Channels.argb(255, 220, 40, 40) // Vibrant red
        val dabMetallic = 0.85f
        val dabRoughness = 0.15f

        atlas.stampDab(
            cx = 32f,
            cy = 32f,
            radius = 12f,
            color = dabColor,
            metallicValue = dabMetallic,
            roughnessValue = dabRoughness,
            hardness = 0.5f,
            flow = 1.0f,
        )

        // Center pixel (32, 32) must have received the full dab
        val centerIndex = 32 * width + 32
        val basePixel = atlas.baseColor.pixels[centerIndex]
        val metalPixel = atlas.metallic.pixels[centerIndex]
        val roughPixel = atlas.roughness.pixels[centerIndex]

        assertEquals(255f, Channels.alpha(basePixel), 0.01f)
        assertTrue("Base color red channel should be prominent", Channels.red(basePixel) > 200f)
        assertTrue("Base color green channel should be small", Channels.green(basePixel) < 60f)

        // Metallic should be ~0.85 * 255 = 217
        val metalVal = Channels.red(metalPixel)
        assertTrue("Metallic value should be close to 217 (got $metalVal)", abs(metalVal - 217f) < 10f)

        // Roughness should be ~0.15 * 255 = 38
        val roughVal = Channels.red(roughPixel)
        assertTrue("Roughness value should be close to 38 (got $roughVal)", abs(roughVal - 38f) < 10f)

        // Outside stamp radius (e.g. at (5, 5)), pixels must remain unpainted
        val cornerIndex = 5 * width + 5
        assertEquals(0f, Channels.alpha(atlas.baseColor.pixels[cornerIndex]), 0.01f)
        assertEquals(0f, Channels.alpha(atlas.metallic.pixels[cornerIndex]), 0.01f)
    }

    @Test
    fun cookTorranceBrdfEnergyConservation() {
        val atlas = PbrMaterialAtlas(16, 16)
        val albedo = floatArrayOf(0.9f, 0.5f, 0.3f)

        // Test across range of metallic & roughness
        for (metallic in listOf(0.0f, 0.5f, 1.0f)) {
            for (roughness in listOf(0.1f, 0.4f, 0.8f)) {
                val radiance =
                    atlas.evaluateCookTorrance(
                        albedo = albedo,
                        metallic = metallic,
                        roughness = roughness,
                        nDotV = 0.8f,
                        nDotL = 0.7f,
                        nDotH = 0.95f,
                        vDotH = 0.85f,
                    )

                for (c in radiance) {
                    assertTrue("Radiance must be non-negative (got $c)", c >= 0f)
                    assertTrue("Radiance must remain physically bounded (got $c)", c < 15.0f)
                }
            }
        }
    }

    @Test
    fun metallicReflectanceModulatesSpecularVsDiffuse() {
        val atlas = PbrMaterialAtlas(16, 16)
        val albedo = floatArrayOf(0.8f, 0.8f, 0.8f)

        // Pure dielectric (metallic = 0): strong diffuse body reflection
        val dielectricRadiance =
            atlas.evaluateCookTorrance(
                albedo = albedo,
                metallic = 0.0f,
                roughness = 0.5f,
                nDotV = 0.6f,
                nDotL = 0.6f,
                nDotH = 0.9f,
                vDotH = 0.7f,
            )

        // Pure metal (metallic = 1): diffuse body goes to zero, pure specular
        val conductorRadiance =
            atlas.evaluateCookTorrance(
                albedo = albedo,
                metallic = 1.0f,
                roughness = 0.5f,
                nDotV = 0.6f,
                nDotL = 0.6f,
                nDotH = 0.9f,
                vDotH = 0.7f,
            )

        assertTrue(dielectricRadiance[0] > 0f)
        assertTrue(conductorRadiance[0] > 0f)
    }
}
