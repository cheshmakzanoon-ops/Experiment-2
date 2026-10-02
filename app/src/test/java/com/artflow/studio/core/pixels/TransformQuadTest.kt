package com.artflow.studio.core.pixels

import com.artflow.studio.core.pixels.TransformQuad.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransformQuadTest {
    private val red = 0xFFFF0000.toInt()

    private fun block(
        size: Int,
        left: Int,
        top: Int,
        side: Int,
    ): PixelBuffer =
        PixelBuffer(size, size).also { buffer ->
            for (y in top until top + side) for (x in left until left + side) buffer.pixels[y * size + x] = red
        }

    private fun render(
        source: PixelBuffer,
        quad: (Quad) -> Quad,
    ): PixelBuffer {
        val bounds = LayerTransform.floatingBounds(source, null)!!
        return PixelBuffer(source.width, source.height).also {
            TransformQuad.render(source, it, bounds, quad(Quad.fromBounds(bounds)), null, highQuality = false)
        }
    }

    @Test fun identityQuadReproducesPixels() {
        val source = block(16, 4, 4, 4)
        assertTrue(source.pixels.contentEquals(render(source) { it }.pixels))
    }

    @Test fun translationMovesBlock() {
        val out = render(block(16, 2, 2, 4)) { TransformQuad.translate(it, 6f, 3f) }
        assertEquals(red, out.pixels[5 * 16 + 8])
        assertEquals(16, out.opaquePixelCount())
    }

    @Test fun horizontalFlipMirrorsInsideTheBox() {
        val source = PixelBuffer(8, 8)
        source.pixels[0] = red
        source.pixels[3] = 0xFF0000FF.toInt()
        val out = render(source) { TransformQuad.flip(it, horizontal = true) }
        assertEquals(0xFF0000FF.toInt(), out.pixels[0])
        assertEquals(red, out.pixels[3])
    }

    @Test fun quarterTurnKeepsArea() {
        val out = render(block(20, 6, 8, 4)) { TransformQuad.rotate(it, 90f) }
        assertEquals(16, out.opaquePixelCount())
    }

    @Test fun distortedCornerOnlyMovesThatCorner() {
        val quad = Quad.fromBounds(IntBounds(0, 0, 9, 9))
        val moved = TransformQuad.drag(quad, Target.Corner(2), TransformQuad.Mode.DISTORT, 10f, 10f, 14f, 12f)
        assertEquals(14f, moved.x2, 1e-4f)
        assertEquals(12f, moved.y2, 1e-4f)
        assertEquals(quad.x0, moved.x0, 1e-4f)
        assertEquals(quad.x1, moved.x1, 1e-4f)
    }

    @Test fun uniformCornerDragScalesAboutOppositeCorner() {
        val quad = Quad.fromBounds(IntBounds(0, 0, 9, 9))
        val moved = TransformQuad.drag(quad, Target.Corner(2), TransformQuad.Mode.UNIFORM, 10f, 10f, 20f, 20f)
        assertEquals(0f, moved.x0, 1e-3f)
        assertEquals(20f, moved.x2, 1e-3f)
        assertEquals(20f, moved.y2, 1e-3f)
    }

    @Test fun hitTestingFindsHandles() {
        val quad = Quad.fromBounds(IntBounds(0, 0, 99, 99))
        assertEquals(Target.Corner(0), TransformQuad.hit(quad, 2f, 2f, 10f, 30f))
        assertEquals(Target.Edge(1), TransformQuad.hit(quad, 99f, 50f, 10f, 30f))
        assertEquals(Target.Rotate, TransformQuad.hit(quad, 50f, -30f, 10f, 30f))
        assertEquals(Target.Body, TransformQuad.hit(quad, 50f, 50f, 10f, 30f))
    }

    @Test fun fitToCanvasCentresAndFills() {
        val fitted = TransformQuad.fitTo(Quad.fromBounds(IntBounds(0, 0, 9, 4)), 100, 100)
        assertEquals(0f, fitted.x0, 1e-3f)
        assertEquals(100f, fitted.x1, 1e-3f)
        assertEquals(25f, fitted.y0, 1e-3f)
    }
}
