package com.artflow.studio.core.three

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.Compositor
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.Layer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialChannelTest {
    @Test fun scalarMapsStaySeparateWhileGroupOpacityAndMasksApplyToEachChannel() {
        val inputs =
            listOf(
                Compositor.LayerInput(Layer(1, "Color", 0, parentGroupId = 4), PixelBuffer.filled(2, 1, 0xFFCC3311.toInt())),
                Compositor.LayerInput(
                    Layer(2, "Metal", 1, parentGroupId = 4, materialChannel = MaterialChannel.METALLIC),
                    PixelBuffer.filled(2, 1, 0xFFFFFFFF.toInt()),
                ),
                Compositor.LayerInput(
                    Layer(3, "Rough", 2, parentGroupId = 4, materialChannel = MaterialChannel.ROUGHNESS),
                    PixelBuffer.filled(2, 1, 0xFF404040.toInt()),
                ),
                Compositor.LayerInput(
                    Layer(4, "Group", 3, isGroup = true, opacity = 0.5f, blendMode = BlendMode.NORMAL),
                    mask = PixelBuffer(2, 1, intArrayOf(-1, 0xFF000000.toInt())),
                ),
            )
        val renderer = Compositor()
        try {
            for ((channel, rgb) in listOf(
                MaterialChannel.COLOR to 0xCC3311,
                MaterialChannel.METALLIC to 0xFFFFFF,
                MaterialChannel.ROUGHNESS to 0x404040,
            )) {
                val result = renderer.composite(inputs, 2, 1, options = Compositor.Options(materialChannel = channel))
                assertEquals(rgb, result.pixels[0] and 0xFFFFFF)
                assertEquals(128, result.pixels[0] ushr 24)
                assertEquals(0, result.pixels[1])
            }
            val hidden = inputs.map { if (it.layer.id == 4L) it.copy(layer = it.layer.copy(isVisible = false)) else it }
            MaterialChannel.entries.forEach { channel ->
                assertArrayEquals(
                    IntArray(2),
                    renderer.composite(hidden, 2, 1, options = Compositor.Options(materialChannel = channel)).pixels,
                )
            }
        } finally {
            renderer.release()
        }
    }

    @Test fun resolvingMapCoverageUsesScalarDefaultsAndNeverMutatesSource() {
        val pixels = intArrayOf(0, 0x80FFFFFF.toInt(), 0xFF000000.toInt(), -1)
        val source = PixelBuffer(4, 1, pixels.copyOf())
        val roughness = MaterialChannel.resolved(source, MaterialChannel.ROUGHNESS)
        val metallic = MaterialChannel.resolved(source, MaterialChannel.METALLIC)
        assertArrayEquals(intArrayOf(153, 204, 0, 255), roughness.pixels.map { it and 255 }.toIntArray())
        assertArrayEquals(intArrayOf(0, 128, 0, 255), metallic.pixels.map { it and 255 }.toIntArray())
        assertTrue(roughness.pixels.all { it ushr 24 == 255 })
        assertArrayEquals(pixels, source.pixels)
        assertEquals(0xFF808080.toInt(), MaterialChannel.gray(0.5f))
    }

    @Test fun objMaterialUsesIndependentPbrMapsAndExplicitUnpaintedDefaults() {
        val plain = ObjExport.material()
        assertTrue(plain.contains("Pm 0.0\nPr 0.6\n"))
        val maps = ObjExport.material(setOf(MaterialChannel.METALLIC, MaterialChannel.ROUGHNESS))
        assertTrue(maps.contains("Pm 1.0\nPr 1.0\n"))
        assertTrue(maps.contains("map_Pm metallic.png\n"))
        assertTrue(maps.contains("map_Pr roughness.png\n"))
        assertTrue(maps.contains("map_Kd texture.png\n"))
    }
}
