package com.artflow.studio.core.pixels

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Reset must recover the original bytes instead of re-compositing two feathered fragments. */
class SelectionTransformIdentityTest {
    private val source =
        PixelBuffer(16, 12).apply {
            for (i in pixels.indices) pixels[i] = ((i * 17 % 256) shl 24) or (i * 7919 and 0xFFFFFF)
        }
    private val selection =
        SelectionMask(16, 12).apply {
            for (i in coverage.indices) coverage[i] = (i * 31 % 256).toByte()
        }
    private val bounds = IntBounds(0, 0, 15, 11)

    @Test
    fun affineIdentityKeepsAllAlphaAndColorBytesUnderSoftSelection() {
        val target = PixelBuffer(16, 12)
        LayerTransform.render(source, target, LayerTransform.Params(8f, 6f), selection)
        assertArrayEquals(source.pixels, target.pixels)
    }

    @Test
    fun quadResetRestoresTheOriginalAfterASelectedTransformAtEveryQuality() {
        TransformQuad.Interpolation.entries.forEach { quality ->
            val target = PixelBuffer(16, 12)
            TransformQuad.render(source, target, bounds, TransformQuad.translate(Quad.fromBounds(bounds), 3f, 2f), selection, quality)
            assertFalse(source.pixels.contentEquals(target.pixels))
            TransformQuad.render(source, target, bounds, Quad.fromBounds(bounds), selection, quality)
            assertArrayEquals(quality.name, source.pixels, target.pixels)
        }
    }

    @Test
    fun unbentStandardAndAdvancedMeshesKeepSoftSelectionPixelsExactly() {
        listOf(WarpMesh.SIDE, WarpMesh.ADVANCED_SIDE).forEach { side ->
            TransformQuad.Interpolation.entries.forEach { quality ->
                val target = PixelBuffer(16, 12)
                WarpMesh.render(source, target, bounds, WarpMesh.fromBounds(bounds, side), selection, quality)
                assertArrayEquals("$side / $quality", source.pixels, target.pixels)
            }
        }
    }

    @Test
    fun renderShapeResetRestoresBytesAfterAWarp() {
        val target = PixelBuffer(16, 12)
        val quad = Quad.fromBounds(bounds)
        val mesh = WarpMesh.fromBounds(bounds).drag(WarpMesh.Target.Point(5), 2f, -3f)
        TransformQuad.renderShape(source, target, bounds, quad, mesh, selection, TransformQuad.Interpolation.BILINEAR)
        assertFalse(source.pixels.contentEquals(target.pixels))
        TransformQuad.renderShape(source, target, bounds, quad, null, selection, TransformQuad.Interpolation.BILINEAR)
        assertArrayEquals(source.pixels, target.pixels)
    }
}
