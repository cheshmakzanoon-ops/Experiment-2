package com.artflow.studio.core

import com.artflow.studio.core.color.IccColorManager
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.three.GltfPbrExport
import com.artflow.studio.core.three.PbrLighting
import com.artflow.studio.core.three.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Milestone5ColorAndPbrTest {
    @Test
    fun `icc color manager performs real cmyk separation with tac ink limits and gamut warning`() {
        val richBlack = 0xFF000000.toInt()
        val swopBlack = IccColorManager.srgbToCmyk(richBlack, IccColorManager.WorkingSpace.CMYK_SWOP_V2)
        assertTrue("SWOP v2 total ink must stay <= 300%", swopBlack.totalInk <= 3.01f)

        val brightCyan = 0xFF00FFFF.toInt()
        val cmykCyan = IccColorManager.srgbToCmyk(brightCyan, IccColorManager.WorkingSpace.CMYK_SWOP_V2)
        assertTrue("Pure cyan should have high C channel (${cmykCyan.c})", cmykCyan.c > 0.8f)

        val buf = PixelBuffer(8, 8).apply { fill(brightCyan) }
        val converted =
            IccColorManager.convertPixels(
                buf,
                IccColorManager.WorkingSpace.SRGB,
                IccColorManager.WorkingSpace.CMYK_SWOP_V2,
            )
        assertNotEquals("CMYK conversion must actually alter out-of-gamut pixels", brightCyan, converted.getSafe(4, 4))

        val warning =
            IccColorManager.gamutWarningMask(
                buf,
                IccColorManager.WorkingSpace.SRGB,
                IccColorManager.WorkingSpace.CMYK_SWOP_V2,
                threshold = 10,
            )
        assertNotEquals("Out-of-gamut neon cyan should trigger gamut warning", 0, warning.getSafe(4, 4) ushr 24)
    }

    @Test
    fun `cook torrance pbr shading, normal encoding, and multi-channel material brush work end-to-end`() {
        val normal = Vec3(0.2f, -0.3f, 0.93f).normalised()
        val decoded = PbrLighting.decodeNormal(PbrLighting.encodeNormal(normal))
        assertEquals(normal.x, decoded.x, 0.02f)
        assertEquals(normal.y, decoded.y, 0.02f)
        assertEquals(normal.z, decoded.z, 0.02f)

        val view = Vec3(0f, 0f, 1f)
        val matte =
            PbrLighting.shadePoint(
                albedoArgb = 0xFFD0A030.toInt(),
                metallic = 0f,
                roughness = 0.9f,
                normal = Vec3(0f, 0f, 1f),
                viewDir = view,
                environment = PbrLighting.IblEnvironment.STUDIO_SOFTBOX,
            )
        val glossyMetal =
            PbrLighting.shadePoint(
                albedoArgb = 0xFFD0A030.toInt(),
                metallic = 1f,
                roughness = 0.1f,
                normal = Vec3(0f, 0f, 1f),
                viewDir = view,
                environment = PbrLighting.IblEnvironment.STUDIO_SOFTBOX,
            )
        assertNotEquals("Glossy metal and matte dielectric must shade distinctly", matte, glossyMetal)

        val color = PixelBuffer(32, 32)
        val metallic = PixelBuffer(32, 32)
        val roughness = PixelBuffer(32, 32)
        val normalMap = PixelBuffer(32, 32).apply { fill(PbrLighting.encodeNormal(Vec3(0f, 0f, 1f))) }
        PbrLighting.stampMultiChannel(
            colorAtlas = color,
            metallicAtlas = metallic,
            roughnessAtlas = roughness,
            normalAtlas = normalMap,
            centerX = 16f,
            centerY = 16f,
            radius = 8f,
            dab = PbrLighting.MultiChannelDab(0xFFCC8822.toInt(), metallic = 0.9f, roughness = 0.2f, normalBumpStrength = 0.8f),
        )
        assertNotEquals(0, color.getSafe(16, 16) ushr 24)
        assertTrue((metallic.getSafe(16, 16) and 0xFF) > 180)
        assertTrue((roughness.getSafe(16, 16) and 0xFF) < 90)
        assertNotEquals(PbrLighting.encodeNormal(Vec3(0f, 0f, 1f)), normalMap.getSafe(20, 16))
    }

    @Test
    fun `gltf pbr exporter produces valid glb binary with embedded basecolor, metallic-roughness, and normal maps`() {
        val base = PixelBuffer(16, 16).apply { fill(0xFFCC4422.toInt()) }
        val metal = PixelBuffer(16, 16).apply { fill(0xFFE0E0E0.toInt()) }
        val rough = PixelBuffer(16, 16).apply { fill(0xFF404040.toInt()) }
        val normal = PixelBuffer(16, 16).apply { fill(0xFF8080FF.toInt()) }

        val glb = GltfPbrExport.exportGlb("GoldArmor", base, metal, rough, normal)
        val header = ByteBuffer.wrap(glb, 0, 20).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546C67, header.int) // "glTF"
        assertEquals(2, header.int) // version 2
        assertEquals(glb.size, header.int) // total length
        val jsonLen = header.int
        assertEquals(0x4E4F534A, header.int) // "JSON"
        val jsonText = String(glb, 20, jsonLen, Charsets.UTF_8)
        assertTrue("Expected pbrMetallicRoughness in GLB JSON", "\"pbrMetallicRoughness\"" in jsonText)
        assertTrue("Expected normalTexture in GLB JSON", "\"normalTexture\"" in jsonText)
    }
}
