package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.brush.BrushLibraryCodec
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.RenderingMode
import com.artflow.studio.domain.model.brush.SavedBrush
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rendering modes decide how overlapping dabs inside one stroke combine, and old brushes keep their look. */
class RenderingModeTest {
    private val black = 0xFF000000.toInt()
    private val centre = 10 * 40 + 20
    private val glazeModes =
        listOf(RenderingMode.GLAZE_LIGHT, RenderingMode.GLAZE_UNIFORM, RenderingMode.GLAZE_INTENSE, RenderingMode.GLAZE_HEAVY)

    /** A horizontal stroke along y = 10 over a transparent 40 x 20 layer; [spacing] sets how many dabs overlap. */
    private fun paint(
        params: BrushParams,
        spacing: Float = 0.05f,
    ): PixelBuffer {
        val stroke =
            Stroke(
                id = 3L,
                points = listOf(StrokePoint(4.5f, 10f, 1f, timestamp = 0L), StrokePoint(36.5f, 10f, 1f, timestamp = 16L)),
                brushParams = params.copy(spacing = spacing),
                layerId = 1,
                color = black,
            )
        return PixelBuffer(40, 20).also { StrokeRasterizer().draw(it, stroke) }
    }

    private fun alphaAt(
        mode: RenderingMode,
        spacing: Float = 0.05f,
        flow: Float = 0.2f,
    ): Int = paint(BrushParams(size = 8f, flow = flow, renderingMode = mode), spacing).pixels[centre] ushr 24

    @Test
    fun glazeModesDoNotBuildUpAcrossOverlappingDabs() {
        // Dabs every pixel versus every four pixels: many overlaps against few. Glazing keeps one dab's density.
        for (mode in glazeModes) {
            assertEquals("$mode", alphaAt(mode, spacing = 0.5f), alphaAt(mode, spacing = 0.05f))
        }
    }

    @Test
    fun uniformGlazeHoldsASingleDabsDensityWhereDabsOverlap() {
        // Flow 0.2 gives each dab 20% coverage; many overlapping dabs still leave the pixel at that density.
        val single = alphaAt(RenderingMode.GLAZE_UNIFORM, spacing = 0.05f)
        assertTrue("A dab at 20% flow lays about 51 of 255: $single", single in 50..52)
    }

    @Test
    fun blendModesBuildUpWhereTheyOverlap() {
        val sparse = alphaAt(RenderingMode.BLEND_UNIFORM, spacing = 0.5f)
        val dense = alphaAt(RenderingMode.BLEND_UNIFORM, spacing = 0.05f)
        assertTrue("Overlapping blended dabs layer up: $sparse -> $dense", dense > sparse + 60)
    }

    @Test
    fun glazeStrengthsOrderLightUniformIntenseHeavy() {
        val light = alphaAt(RenderingMode.GLAZE_LIGHT)
        val uniform = alphaAt(RenderingMode.GLAZE_UNIFORM)
        val intense = alphaAt(RenderingMode.GLAZE_INTENSE)
        val heavy = alphaAt(RenderingMode.GLAZE_HEAVY)
        assertTrue("$light < $uniform < $intense < $heavy", light < uniform && uniform < intense && intense < heavy)
    }

    @Test
    fun blendStrengthsOrderUniformIntenseHeavyUntilFull() {
        // Low flow keeps the build-up below full coverage, so the ordering is visible.
        val uniform = alphaAt(RenderingMode.BLEND_UNIFORM, flow = 0.05f)
        val intense = alphaAt(RenderingMode.BLEND_INTENSE, flow = 0.05f)
        val heavy = alphaAt(RenderingMode.BLEND_HEAVY, flow = 0.05f)
        assertTrue("$uniform < $intense < $heavy", uniform < intense && intense < heavy)
        assertTrue("Blending stays below full coverage at this flow: $heavy", heavy < 255)
    }

    @Test
    fun blendingOutbuildsEveryGlazeMode() {
        val blended = alphaAt(RenderingMode.BLEND_UNIFORM)
        for (glaze in glazeModes) {
            assertTrue("$blended should exceed $glaze", blended > alphaAt(glaze))
        }
    }

    @Test
    fun aBuildUpBrushWithoutAModeRendersAsBlendingUniform() {
        val legacy = BrushParams(size = 8f, flow = 0.2f, buildUp = true)
        assertEquals(RenderingMode.BLEND_UNIFORM, legacy.effectiveRenderingMode)
        val explicit = BrushParams(size = 8f, flow = 0.2f, renderingMode = RenderingMode.BLEND_UNIFORM)
        assertArrayEquals(paint(legacy).pixels, paint(explicit).pixels)
    }

    @Test
    fun aGlazeBrushWithoutAModeRendersAsGlazeUniform() {
        val legacy = BrushParams(size = 8f, flow = 0.2f)
        assertEquals(RenderingMode.GLAZE_UNIFORM, legacy.effectiveRenderingMode)
        val explicit = BrushParams(size = 8f, flow = 0.2f, renderingMode = RenderingMode.GLAZE_UNIFORM)
        assertArrayEquals(paint(legacy).pixels, paint(explicit).pixels)
    }

    @Test
    fun anExplicitModeWinsOverTheBuildUpFlag() {
        val explicit = BrushParams(buildUp = true, renderingMode = RenderingMode.GLAZE_HEAVY)
        assertEquals(RenderingMode.GLAZE_HEAVY, explicit.effectiveRenderingMode)
    }

    @Test
    fun renderingModeRoundTripsThroughTheLibraryAndOldLibrariesStillLoad() {
        val blended = BrushParams(size = 12f, buildUp = true, renderingMode = RenderingMode.BLEND_HEAVY)
        val encoded = BrushLibraryCodec.encode(listOf(SavedBrush("mode-1", "Mode", blended)))
        assertEquals(blended, BrushLibraryCodec.decode(encoded).single().parameters)

        // A library written before the field existed has no renderingMode key: it derives the mode from buildUp.
        val old = BrushLibraryCodec.encode(listOf(SavedBrush("old-1", "Old", BrushParams(size = 12f, buildUp = true))))
        val withoutMode = old.replace(Regex(""",\s*"renderingMode"\s*:\s*(null|"[A-Z_]+")"""), "")
        val decoded = BrushLibraryCodec.decode(withoutMode).single().parameters
        assertEquals(null, decoded.renderingMode)
        assertEquals(RenderingMode.BLEND_UNIFORM, decoded.effectiveRenderingMode)
    }
}
