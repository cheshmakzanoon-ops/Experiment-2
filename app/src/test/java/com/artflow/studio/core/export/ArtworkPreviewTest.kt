package com.artflow.studio.core.export

import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.color.ColorProfiles
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.layer.Layer
import com.artflow.studio.domain.model.layer.LayerEffects
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

class ArtworkPreviewTest {
    @Test
    fun outputIsBoundedAndKeepsArtworkAspectRatioAndPixels() {
        val ink = 0xCC3762A3.toInt()
        val source = PixelBuffer.filled(1200, 600, ink)
        val image = ImageIO.read(ByteArrayInputStream(ArtworkPreview.encode(source)))
        assertEquals(512, image.width)
        assertEquals(256, image.height)
        assertEquals(ink, image.getRGB(256, 128))
        assertTrue(source.pixels.all { it == ink })
    }

    @Test
    fun displayP3PreviewConvertsOnlyTheDisplayCopy() {
        val color = 0xFFB35D91.toInt()
        val source = PixelBuffer.filled(2, 2, color)
        val before = source.pixels.copyOf()
        val image = ImageIO.read(ByteArrayInputStream(ArtworkPreview.encode(source, ColorProfile.DISPLAY_P3)))
        assertEquals(ColorProfiles.convert(color, ColorProfile.DISPLAY_P3, ColorProfile.SRGB), image.getRGB(0, 0))
        assertArrayEquals(before, source.pixels)
    }

    @Test
    fun admissionRefusesLargeDimensionsWithoutOverflowOrAnyRasterAllocation() {
        assertFalse(ArtworkPreview.canRender(Int.MAX_VALUE, Int.MAX_VALUE, emptyList(), Long.MAX_VALUE))
        assertFalse(ArtworkPreview.canRender(8192, 8192, emptyList(), Long.MAX_VALUE))
        assertFalse(ArtworkPreview.canRender(512, 512, emptyList(), 0))
        assertTrue(ArtworkPreview.canRender(512, 512, emptyList(), 16L * 1024 * 1024))
    }

    @Test
    fun nestedGroupsAndEffectsUseAdditionalAdmissionHeadroom() {
        val budget = 32L * 1024 * 1024
        assertTrue(ArtworkPreview.canRender(1024, 1024, emptyList(), budget))
        val groups = List(8) { Layer(it.toLong(), "Group", it, isGroup = true) }
        assertFalse(ArtworkPreview.canRender(1024, 1024, groups, budget))
    }

    @Test
    fun shadowHaloIsChargedForEachLayerEvenOnAThinCanvas() {
        val sharp = Layer(1, "Shadow", 0, effects = LayerEffects(shadow = LayerEffects.Shadow(blur = 0f)))
        val soft = sharp.copy(effects = LayerEffects(shadow = LayerEffects.Shadow(blur = LayerEffects.MAX_SHADOW_BLUR)))
        assertTrue(ArtworkPreview.canRender(8192, 1, listOf(sharp), 8L * 1024 * 1024))
        assertFalse(ArtworkPreview.canRender(8192, 1, listOf(soft), 8L * 1024 * 1024))
        assertTrue(ArtworkPreview.canRender(8192, 1, listOf(soft), 12L * 1024 * 1024))
        assertFalse(ArtworkPreview.canRender(8192, 1, listOf(soft, soft.copy(id = 2)), 12L * 1024 * 1024))
        assertFalse(ArtworkPreview.canRender(Int.MAX_VALUE, Int.MAX_VALUE, listOf(soft), Long.MAX_VALUE))
    }
}
