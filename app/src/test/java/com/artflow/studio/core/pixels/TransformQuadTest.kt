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
        interpolation: TransformQuad.Interpolation = TransformQuad.Interpolation.NEAREST,
        quad: (Quad) -> Quad,
    ): PixelBuffer {
        val bounds = LayerTransform.floatingBounds(source, null)!!
        return PixelBuffer(source.width, source.height).also {
            TransformQuad.render(source, it, bounds, quad(Quad.fromBounds(bounds)), null, interpolation)
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

    @Test fun magneticsLockMovesAndRotationSteps() {
        val box = Quad(10f, 10f, 30f, 10f, 30f, 30f, 10f, 30f)
        val assist = TransformQuad.Assist(magnetics = true)
        val area = TransformQuad.SnapArea(100, 100, 0f)
        val moved = TransformQuad.drag(box, Target.Body, TransformQuad.Mode.FREEFORM, 0f to 0f, 20f to 2f, assist, area)
        assertEquals("A nearly horizontal move stays level", 10f, moved.y0, 1e-3f)
        assertEquals(30f, moved.x0, 1e-3f)
        // Dragging the knob a little under 15° rounds to exactly 15°.
        val turned = TransformQuad.drag(box, Target.Rotate, TransformQuad.Mode.FREEFORM, 40f to 20f, 40f to 25f, assist, area)
        val angle = Math.toDegrees(kotlin.math.atan2((turned.y1 - turned.y0).toDouble(), (turned.x1 - turned.x0).toDouble()))
        assertEquals(15.0, angle, 1e-2)
        // A Freeform corner keeps the square's proportions.
        val scaled = TransformQuad.drag(box, Target.Corner(2), TransformQuad.Mode.FREEFORM, 30f to 30f, 40f to 34f, assist, area)
        assertEquals(scaled.x1 - scaled.x0, scaled.y3 - scaled.y0, 1e-3f)
    }

    @Test fun snappingAlignsTheBoxWithCanvasLines() {
        val box = Quad(10f, 10f, 30f, 10f, 30f, 30f, 10f, 30f)
        val assist = TransformQuad.Assist(snapping = true)
        val area = TransformQuad.SnapArea(100, 80, 4f)
        // Centre ends at (48, 61): x snaps to the canvas centre, y is too far from any line.
        val moved = TransformQuad.drag(box, Target.Body, TransformQuad.Mode.FREEFORM, 0f to 0f, 28f to 41f, assist, area)
        assertEquals(50f, moved.centerX, 1e-3f)
        assertEquals(61f, moved.centerY, 1e-3f)
        val (columns, rows) = TransformQuad.alignedGuides(moved, 100, 80)
        assertEquals(listOf(50f), columns)
        assertTrue(rows.isEmpty())
    }

    @Test fun aLargerSnappingDistanceReachesFartherLines() {
        val box = Quad(10f, 10f, 30f, 10f, 30f, 30f, 10f, 30f)
        val assist = TransformQuad.Assist(snapping = true, snapDistance = 10f)
        // The bottom edge ends at 71, 9 pixels above the canvas edge: within 10, so it snaps there.
        val area = TransformQuad.SnapArea(100, 80, assist.snapDistance)
        val moved = TransformQuad.drag(box, Target.Body, TransformQuad.Mode.FREEFORM, 0f to 0f, 28f to 41f, assist, area)
        assertEquals(70f, moved.centerY, 1e-3f)
    }

    @Test
    fun aTapOutsideTheBoxNudgesItOnePixelTowardTheTap() {
        val box = Quad.fromBounds(IntBounds(10, 10, 29, 29))
        assertEquals(null, TransformQuad.nudgeToward(box, 20f, 20f))
        assertEquals(1f to 0f, TransformQuad.nudgeToward(box, 60f, 22f))
        assertEquals(0f to -1f, TransformQuad.nudgeToward(box, 18f, -40f))
        assertEquals(-1f to 0f, TransformQuad.nudgeToward(box, 0f, 15f))
    }

    @Test fun bicubicIdentityKeepsExactPixels() {
        val source = block(16, 4, 4, 4)
        val out = render(source, TransformQuad.Interpolation.BICUBIC) { it }
        assertTrue(source.pixels.contentEquals(out.pixels))
    }

    @Test fun bicubicHalfPixelShiftStaysWithinTheSourceColour() {
        val out = render(block(16, 4, 4, 4), TransformQuad.Interpolation.BICUBIC) { TransformQuad.translate(it, 0.5f, 0f) }
        for (pixel in out.pixels) {
            if (pixel != 0) assertEquals(0xFF0000, pixel and 0xFFFFFF)
        }
        assertEquals(0xFF, out.pixels[5 * 16 + 6] ushr 24)
    }
}
