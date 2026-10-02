package com.artflow.studio.core.pixels

import com.artflow.studio.core.pixels.WarpMesh.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WarpMeshTest {
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
        change: (WarpMesh) -> WarpMesh,
    ): PixelBuffer {
        val bounds = LayerTransform.floatingBounds(source, null)!!
        return PixelBuffer(source.width, source.height).also {
            WarpMesh.render(source, it, bounds, change(WarpMesh.fromBounds(bounds)), null, highQuality = false)
        }
    }

    @Test fun unbentMeshReproducesPixels() {
        val source = block(24, 5, 6, 8)
        assertTrue(source.pixels.contentEquals(render(source) { it }.pixels))
    }

    @Test fun translatedMeshMovesBlockWithoutDoubleCoverage() {
        val out = render(block(24, 2, 2, 6)) { it.translate(7f, 4f) }
        assertEquals(red, out.pixels[6 * 24 + 9])
        assertEquals(36, out.opaquePixelCount())
    }

    @Test fun cornersFollowTheBox() {
        val mesh = WarpMesh.fromBounds(IntBounds(10, 20, 29, 59))
        assertEquals(Quad(10f, 20f, 30f, 20f, 30f, 60f, 10f, 60f), mesh.corners())
        val (cx, cy) = mesh.evaluate(0.5f, 0.5f)
        assertEquals(20f, cx, 1e-3f)
        assertEquals(40f, cy, 1e-3f)
    }

    @Test fun pullingTheSurfaceMovesThePointUnderTheFinger() {
        val mesh = WarpMesh.fromBounds(IntBounds(0, 0, 99, 99))
        val pulled = mesh.drag(Target.Surface(0.3f, 0.6f), 12f, -5f)
        val (bx, by) = mesh.evaluate(0.3f, 0.6f)
        val (ax, ay) = pulled.evaluate(0.3f, 0.6f)
        assertEquals(bx + 12f, ax, 1e-3f)
        assertEquals(by - 5f, ay, 1e-3f)
        // The far corner barely moves.
        assertEquals(mesh.x(3), pulled.x(3), 1f)
    }

    @Test fun hitFindsPointsSurfaceAndOutside() {
        val mesh = WarpMesh.fromBounds(IntBounds(0, 0, 89, 89))
        assertEquals(Target.Point(5), mesh.hit(31f, 29f, 6f))
        assertTrue(mesh.hit(45f, 50f, 4f) is Target.Surface)
        assertEquals(Target.Body, mesh.hit(300f, 300f, 6f))
    }

    @Test fun draggingAnEdgePointBulgesTheContent() {
        val source = block(32, 4, 6, 20)
        val out = render(source) { it.drag(Target.Point(1), 0f, -6f) }
        // The top edge bows upward between its corners; the corners stay put.
        assertEquals(red, out.pixels[4 * 32 + 9])
        assertEquals(0, out.pixels[4 * 32 + 4])
        assertTrue(out.opaquePixelCount() > source.opaquePixelCount())
    }

    @Test fun flipAndFitKeepTheShapeInsideTheCanvas() {
        val mesh = WarpMesh.fromBounds(IntBounds(0, 0, 19, 9))
        val flipped = mesh.flip(horizontal = true)
        assertEquals(mesh.x(3), flipped.x(0), 1e-4f)
        assertEquals(mesh.x(0), flipped.x(3), 1e-4f)
        val fitted = mesh.fitTo(100, 100).corners()
        assertEquals(0f, fitted.x0, 1e-3f)
        assertEquals(100f, fitted.x1, 1e-3f)
        assertEquals(25f, fitted.y0, 1e-3f)
    }
}
