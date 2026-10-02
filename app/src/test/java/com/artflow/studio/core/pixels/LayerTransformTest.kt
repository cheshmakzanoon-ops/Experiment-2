package com.artflow.studio.core.pixels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerTransformTest {
    private val red = 0xFFFF0000.toInt()

    private fun dot(
        size: Int,
        x: Int,
        y: Int,
    ): PixelBuffer = PixelBuffer(size, size).also { it.pixels[y * size + x] = red }

    @Test fun identityKeepsPixels() {
        val src = dot(8, 2, 3)
        val out = PixelBuffer(8, 8)
        LayerTransform.render(src, out, LayerTransform.Params(4f, 4f))
        assertEquals(red, out.pixels[3 * 8 + 2])
        assertEquals(1, out.opaquePixelCount())
    }

    @Test fun integerTranslationMovesExactly() {
        val src = dot(8, 1, 1)
        val out = PixelBuffer(8, 8)
        LayerTransform.render(src, out, LayerTransform.Params(4f, 4f, translateX = 3f, translateY = 2f))
        assertEquals(red, out.pixels[3 * 8 + 4])
        assertEquals(1, out.opaquePixelCount())
    }

    @Test fun horizontalFlipMirrorsAboutPivot() {
        val src = dot(8, 1, 4)
        val out = PixelBuffer(8, 8)
        LayerTransform.render(src, out, LayerTransform.Params(4f, 4f, flipHorizontal = true), highQuality = false)
        assertEquals(red, out.pixels[4 * 8 + 6])
    }

    @Test fun quarterRotationAboutCentre() {
        val src = dot(8, 6, 3) // centre (6.5, 3.5) → rel (2.5, -0.5) → rotated 90° (0.5, 2.5)
        val out = PixelBuffer(8, 8)
        LayerTransform.render(src, out, LayerTransform.Params(4f, 4f, rotationDegrees = 90f), highQuality = false)
        assertEquals(red, out.pixels[6 * 8 + 4])
    }

    @Test fun uniformScaleDoublesCoverage() {
        val src = PixelBuffer(16, 16)
        for (y in 6 until 10) for (x in 6 until 10) src.pixels[y * 16 + x] = red
        val out = PixelBuffer(16, 16)
        LayerTransform.render(src, out, LayerTransform.Params(8f, 8f, scaleX = 2f, scaleY = 2f), highQuality = false)
        assertEquals(64, out.opaquePixelCount())
    }

    @Test fun selectionOnlyMovesSelectedPixels() {
        val src = PixelBuffer(8, 8)
        src.pixels[0] = red
        src.pixels[7] = red
        val mask = SelectionMask(8, 8).also { it.coverage[0] = 255.toByte() }
        val out = PixelBuffer(8, 8)
        LayerTransform.render(src, out, LayerTransform.Params(0f, 0f, translateY = 2f), mask)
        assertEquals(red, out.pixels[7])
        assertEquals(red, out.pixels[2 * 8])
        assertEquals(0, out.pixels[0])
    }

    @Test fun dragModesProduceExpectedParams() {
        val scale = LayerTransform.fromDrag(LayerTransform.Mode.UNIFORM, 0f, 0f, 10f, 0f, 20f, 0f)
        assertEquals(2f, scale.scaleX, 1e-4f)
        assertEquals(2f, scale.scaleY, 1e-4f)
        val rotate = LayerTransform.fromDrag(LayerTransform.Mode.ROTATE, 0f, 0f, 10f, 0f, 0f, 10f)
        assertEquals(90f, rotate.rotationDegrees, 1e-3f)
        val free = LayerTransform.fromDrag(LayerTransform.Mode.FREEFORM, 0f, 0f, 10f, 10f, 30f, 5f)
        assertEquals(3f, free.scaleX, 1e-4f)
        assertEquals(0.5f, free.scaleY, 1e-4f)
        assertTrue(LayerTransform.fromDrag(LayerTransform.Mode.MOVE, 0f, 0f, 1f, 1f, 1f, 1f).isIdentity)
    }

    @Test fun pivotIsCentreOfContent() {
        val src = PixelBuffer(10, 10)
        src.pixels[2 * 10 + 2] = red
        src.pixels[5 * 10 + 7] = red
        assertEquals(5f to 4f, LayerTransform.pivotOf(src, null))
    }
}
