package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.DualBrush
import com.artflow.studio.domain.model.brush.GrainBlend
import com.artflow.studio.domain.model.brush.PhysicalPaintMode
import com.artflow.studio.domain.model.brush.PressureMapping
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Physical material follows the same grain and secondary-brush cutouts as the incoming color. */
class PhysicalStrokeTextureCoverageTest {
    private val plain =
        BrushParams(
            size = 12f,
            pressureToSize = 0f,
            pressureToOpacity = 0f,
            physicalMode = PhysicalPaintMode.WATERCOLOR,
            waterAmount = 0.9f,
            impastoThickness = 0.8f,
        )
    private val point = StrokePoint(20.5f, 16.5f, pressure = 1f, timestamp = 0L)

    @Test
    fun grainThatRemovesTheEntireStampCannotAllocatePhysicalPaint() {
        for (blend in GrainBlend.entries) {
            val plane = plane()
            val pixels = paint(textured(0).copy(grainBlend = blend), plane)
            assertTrue("$blend removes the visible stamp", pixels.pixels.all { it ushr 24 == 0 })
            assertTrue("$blend must not leave invisible physical material", plane.isEmpty())
        }
    }

    @Test
    fun partialPrimaryGrainScalesEachMaterialChannelByItsBlendedCoverage() {
        val full = plane()
        paint(plain, full)
        val expected =
            mapOf(
                GrainBlend.MULTIPLY to 0.6f * 128f / 255f,
                GrainBlend.SUBTRACT to 26f / 255f,
                GrainBlend.HARD_MIX to 1f,
            )
        for ((blend, coverage) in expected) {
            val plane = plane()
            val pixels = paint(textured(128).copy(flow = 0.6f, grainBlend = blend), plane)
            // The center starts at 153/255. Multiply gives 76.8/255; subtract gives 26/255;
            // Hard Mix crosses its strict threshold and gives full coverage.
            val expectedAlpha =
                when (blend) {
                    GrainBlend.MULTIPLY -> 77
                    GrainBlend.SUBTRACT -> 26
                    GrainBlend.HARD_MIX -> 255
                }
            assertEquals("$blend visible center", expectedAlpha, pixels.getSafe(20, 16) ushr 24)
            assertCenterScaled(full, plane, coverage)
            assertNoMaterialUnderTransparent(pixels, plane)
        }
    }

    @Test
    fun multiplyWithAnEmptySecondaryGrainCannotAllocatePhysicalPaint() {
        val plane = plane()
        val pixels = paint(plain.copy(dual = DualBrush(textured(0))), plane)
        assertTrue(pixels.pixels.all { it ushr 24 == 0 })
        assertTrue("A fully masked dual brush must not leave water or relief", plane.isEmpty())
    }

    @Test
    fun aSecondarySubtractCutoutPreservesMaterialAlreadyAtTheCenter() {
        val plane = plane()
        plane.setCell(20, 16, wetness = 0.2f, impasto = 0.4f, pigment = 0.3f)
        val brush = plain.copy(dual = DualBrush(plain.copy(size = 6f), DualBrush.Mode.SUBTRACT))
        val pixels = paint(brush, plane)
        assertEquals(0, pixels.getSafe(20, 16) ushr 24)
        assertEquals(0.2f, plane.wetnessAt(20, 16), 0f)
        assertEquals(0.4f, plane.impastoAt(20, 16), 0f)
        assertEquals(0.3f, plane.pigmentAt(20, 16), 0f)
        assertTrue("The outer ring still paints", pixels.getSafe(24, 16) ushr 24 > 0)
        assertTrue(plane.wetnessAt(24, 16) > 0f)
        assertTrue(plane.impastoAt(24, 16) > 0f)
    }

    @Test
    fun secondaryGrainBlendAndOpacityScaleTheMaterialAfterDualQuantization() {
        val full = plane()
        paint(plain, full)
        val expected = mapOf(GrainBlend.MULTIPLY to 38, GrainBlend.SUBTRACT to 13, GrainBlend.HARD_MIX to 128)
        for ((blend, alpha) in expected) {
            val second = textured(128).copy(flow = 0.6f, opacity = 0.5f, grainBlend = blend)
            val plane = plane()
            val pixels = paint(plain.copy(dual = DualBrush(second)), plane)
            assertEquals("$blend visible center", alpha, pixels.getSafe(20, 16) ushr 24)
            assertCenterScaled(full, plane, alpha / 255f)
            assertNoMaterialUnderTransparent(pixels, plane)
        }
    }

    @Test
    fun texturedPhysicalPaintStillHonorsSelectionOpacityAndAlphaLockTogether() {
        val full = plane()
        paint(plain, full)
        val plane = plane()
        val base = PixelBuffer(WIDTH, HEIGHT).also { it.setSafe(20, 16, 0x80FFFFFF.toInt()) }
        val selection = SelectionMask(WIDTH, HEIGHT, ByteArray(WIDTH * HEIGHT) { 128.toByte() })
        val pixels =
            paint(textured(128).copy(opacity = 0.25f), plane, base = base, selection = selection, alphaLock = true)
        val half = 128f / 255f
        assertEquals(128, pixels.getSafe(20, 16) ushr 24)
        assertCenterScaled(full, plane, 0.25f * half * half * half)
        assertNoMaterialUnderTransparent(pixels, plane)
    }

    @Test
    fun pressureGrainJitterUsesTheSamePostDabRandomForMaterialAndVisiblePaint() {
        val curve = PressureMapping(listOf(PressureMapping.Point(0f, 0.1f), PressureMapping.Point(1f, 0.9f)))
        val brush =
            textured(64).copy(
                flow = 0.8f,
                sizeJitter = 0.25f,
                opacityJitter = 0.3f,
                hueJitter = 0.2f,
                tipRandomized = true,
                grainDepth = 0.8f,
                importedAuxiliaryCurves =
                    mapOf(
                        "dynamicsPressureGrainDepthCurve" to curve,
                        "dynamicsPressureGrainDepthJitterCurve" to curve,
                    ),
            )
        val stroke = stroke(brush, listOf(point.copy(pressure = 0.4f)))
        val expected = PixelBuffer(WIDTH, HEIGHT).also { StrokeRasterizer().draw(it, stroke) }
        val plane = plane()
        val actual = PixelBuffer(WIDTH, HEIGHT).also { StrokeRasterizer().draw(it, stroke, physicalPlane = plane) }
        assertArrayEquals(expected.pixels, actual.pixels)
        val alpha = (expected.getSafe(20, 16) ushr 24) / 255f
        assertTrue(alpha > 0f && alpha < 0.8f)
        // At the dab center the radial falloff is one. Color alone rounds to 8-bit alpha;
        // physical channels retain the continuous coverage, within half an alpha level.
        assertEquals(alpha * plain.waterAmount, plane.wetnessAt(20, 16), plain.waterAmount / 510f + 0.000001f)
        assertEquals(alpha, plane.pigmentAt(20, 16), 1f / 510f + 0.000001f)
        assertNoMaterialUnderTransparent(actual, plane)
    }

    @Test
    fun primaryAndSecondaryGrainKeepPhysicalCoverageAnchoredWhenRenderingACrop() {
        val tile = CustomGrains.Tile(4, byteArrayOf(0, 64, 127, -1, -64, 32, -128, 96, 1, -2, 80, -96, 110, 20, -16, 50))
        val id = CustomGrains.idFor(tile)
        CustomGrains.register(id, tile)
        val area = IntBounds(8, 4, 31, 27)
        for (moving in listOf(false, true)) {
            val grain = plain.copy(blendTexture = true, textureId = id, textureScale = 1.3f, textureRotation = 27f, grainMoving = moving)
            for (brush in listOf(grain, plain.copy(dual = DualBrush(grain.copy(size = 10f))))) {
                val fullPlane = plane()
                val full = paint(brush, fullPlane)
                val croppedPlane = PhysicalPaintPlane(area.width, area.height)
                val shifted = stroke(brush, listOf(point.copy(x = point.x - area.left, y = point.y - area.top)))
                val cropped = PixelBuffer(area.width, area.height)
                StrokeRasterizer(area.left, area.top).draw(cropped, shifted, physicalPlane = croppedPlane)
                assertArrayEquals(full.crop(area).pixels, cropped.pixels)
                assertPlaneRegion(fullPlane, croppedPlane, area.left, area.top)
                assertNoMaterialUnderTransparent(full, fullPlane)
            }
        }
    }

    @Test
    fun neutralGrainKeepsThePhysicalDabPathWhenThePlainBrushCouldUseACapsule() {
        val points = listOf(point.copy(x = 12.25f), point.copy(x = 28.25f, timestamp = 20L))
        val brush = plain.copy(spacing = 0f)
        val expectedPlane = plane()
        val expected = paint(brush, expectedPlane, points = points)
        val actualPlane = plane()
        val actual = paint(brush.copy(blendTexture = true, textureId = textured(128).textureId, grainDepth = 0f), actualPlane, points)
        assertArrayEquals(expected.pixels, actual.pixels)
        assertPlaneRegion(expectedPlane, actualPlane)
    }

    private fun assertCenterScaled(
        full: PhysicalPaintPlane,
        actual: PhysicalPaintPlane,
        factor: Float,
    ) {
        assertEquals(full.wetnessAt(20, 16) * factor, actual.wetnessAt(20, 16), 0.000001f)
        assertEquals(full.impastoAt(20, 16) * factor, actual.impastoAt(20, 16), 0.000001f)
        assertEquals(full.pigmentAt(20, 16) * factor, actual.pigmentAt(20, 16), 0.000001f)
    }

    private fun assertNoMaterialUnderTransparent(
        pixels: PixelBuffer,
        plane: PhysicalPaintPlane,
    ) {
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                if (pixels.getSafe(x, y) ushr 24 == 0) {
                    assertEquals("Wetness at transparent $x,$y", 0f, plane.wetnessAt(x, y), 0f)
                    assertEquals("Relief at transparent $x,$y", 0f, plane.impastoAt(x, y), 0f)
                    assertEquals("Pigment at transparent $x,$y", 0f, plane.pigmentAt(x, y), 0f)
                }
            }
        }
    }

    private fun assertPlaneRegion(
        full: PhysicalPaintPlane,
        actual: PhysicalPaintPlane,
        left: Int = 0,
        top: Int = 0,
    ) {
        for (y in 0 until actual.height) {
            for (x in 0 until actual.width) {
                assertEquals(full.wetnessAt(x + left, y + top), actual.wetnessAt(x, y), 0f)
                assertEquals(full.impastoAt(x + left, y + top), actual.impastoAt(x, y), 0f)
                assertEquals(full.pigmentAt(x + left, y + top), actual.pigmentAt(x, y), 0f)
            }
        }
    }

    private fun textured(value: Int): BrushParams {
        val tile = CustomGrains.Tile(4, ByteArray(16) { value.toByte() })
        val id = CustomGrains.idFor(tile)
        CustomGrains.register(id, tile)
        return plain.copy(textureId = id, blendTexture = true)
    }

    private fun plane(): PhysicalPaintPlane = PhysicalPaintPlane(WIDTH, HEIGHT)

    private fun stroke(
        brush: BrushParams,
        points: List<StrokePoint>,
    ): Stroke = Stroke(id = 1709L, points = points, brushParams = brush, layerId = 1L, color = 0xFFE04020.toInt(), timestamp = 0L)

    private fun paint(
        brush: BrushParams,
        plane: PhysicalPaintPlane,
        points: List<StrokePoint> = listOf(point),
        base: PixelBuffer? = null,
        selection: SelectionMask? = null,
        alphaLock: Boolean = false,
    ): PixelBuffer =
        LayerStrokeRenderer.renderWithPhysical(
            base = base,
            historicalStrokes = emptyList(),
            incomingStrokes = listOf(stroke(brush, points)),
            width = WIDTH,
            height = HEIGHT,
            alphaLock = alphaLock,
            selection = selection,
            physicalPlane = plane,
        )

    private companion object {
        const val WIDTH = 40
        const val HEIGHT = 32
    }
}
