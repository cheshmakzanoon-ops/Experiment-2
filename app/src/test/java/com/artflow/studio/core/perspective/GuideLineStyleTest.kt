package com.artflow.studio.core.perspective

import com.artflow.studio.core.symmetry.SymmetryEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideLineStyleTest {
    @Test fun defaultsReproduceTheLegacyPerspectiveRendering() {
        val style = GuideLineStyle()
        // Colour 88CC66 at 60% opacity: alpha 0x99, as the overlay drew it before this setting.
        assertEquals(0x9988CC66.toInt(), style.argb())
        // A 1.2 screen-pixel hairline at zoom 2 is 0.6 canvas pixels.
        assertEquals(0.6f, style.widthInCanvasPixels(2f), 1e-6f)
    }

    @Test fun symmetryDefaultReproducesTheLegacyBlueAxis() {
        assertEquals(0xCC2D9CDB.toInt(), GuideLineStyle.SYMMETRY_DEFAULT.argb())
    }

    @Test fun thicknessAndOpacityAreClampedToTheirRanges() {
        assertEquals(0.5f, GuideLineStyle(thickness = 0f).sanitize().thickness, 0f)
        assertEquals(6f, GuideLineStyle(thickness = 40f).sanitize().thickness, 0f)
        assertEquals(0.05f, GuideLineStyle(opacity = 0f).sanitize().opacity, 0f)
        assertEquals(1f, GuideLineStyle(opacity = 3f).sanitize().opacity, 0f)
    }

    @Test fun nonFiniteValuesFallBackToDefaults() {
        val style = GuideLineStyle(thickness = Float.NaN, opacity = Float.POSITIVE_INFINITY).sanitize()
        assertEquals(GuideLineStyle.DEFAULT_THICKNESS, style.thickness, 0f)
        assertEquals(GuideLineStyle.DEFAULT_OPACITY, style.opacity, 0f)
    }

    @Test fun sanitizeIsIdempotentAndKeepsOnlyRgbBits() {
        val once = GuideLineStyle(thickness = 9f, opacity = 0.2f, colour = 0xFF112233.toInt()).sanitize()
        assertEquals(0x112233, once.colour)
        assertEquals(once, once.sanitize())
    }

    @Test fun opacitySetsTheAlphaChannelWithRounding() {
        assertEquals(0xFF2D9CDB.toInt(), GuideLineStyle(colour = 0x2D9CDB, opacity = 1f).argb())
        // 0.05 * 255 = 12.75, which rounds to 13 (0x0D).
        assertEquals(0x0D2D9CDB, GuideLineStyle(colour = 0x2D9CDB, opacity = 0.05f).argb())
    }

    @Test fun widthFollowsThicknessAndIsFlooredWhenZoomedOut() {
        assertEquals(3f, GuideLineStyle(thickness = 3f).widthInCanvasPixels(1f), 1e-6f)
        // 3 screen pixels at zoom 100 would be 0.03 canvas pixels, below the floor.
        assertEquals(GuideLineStyle.MIN_CANVAS_WIDTH, GuideLineStyle(thickness = 3f).widthInCanvasPixels(100f), 1e-6f)
    }

    @Test fun guideSettingsSanitizeTheLineStyleAndGridSize() {
        val settings =
            PerspectiveGuide.sanitize(
                PerspectiveGuide.Settings(
                    type = PerspectiveGuide.GuideType.GRID,
                    gridSpacing = 4000,
                    lineStyle = GuideLineStyle(thickness = 99f, opacity = 0f),
                ),
            )
        assertEquals(512, settings.gridSpacing)
        assertEquals(6f, settings.lineStyle.thickness, 0f)
        assertEquals(0.05f, settings.lineStyle.opacity, 0f)
    }

    @Test fun symmetrySettingsSanitizeTheLineStyle() {
        val settings = SymmetryEngine.sanitize(SymmetryEngine.Settings(lineStyle = GuideLineStyle.SYMMETRY_DEFAULT.copy(opacity = 0f)))
        assertEquals(0.05f, settings.lineStyle.opacity, 0f)
        assertEquals(GuideLineStyle.SYMMETRY_DEFAULT.colour, settings.lineStyle.colour)
    }

    @Test fun colourChoicesAreRgbOnlyAndDistinct() {
        val choices = GuideLineStyle.COLOUR_CHOICES
        assertEquals(choices.size, choices.toSet().size)
        assertTrue(choices.all { it in 0..0xFFFFFF })
        assertTrue(GuideLineStyle.DEFAULT_COLOUR in choices)
    }
}
