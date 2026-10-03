package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.DualBrush
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.model.layer.BlendMode
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushRenderingTest {
    private fun stroke(
        params: BrushParams,
        color: Int,
    ) = Stroke(
        id = 3,
        points = listOf(StrokePoint(4f, 10f, 1f, timestamp = 0L), StrokePoint(36f, 10f, 1f, timestamp = 16L)),
        brushParams = params,
        layerId = 1,
        color = color,
    )

    private fun paint(
        params: BrushParams,
        color: Int,
        base: Int,
    ): PixelBuffer =
        PixelBuffer(40, 20).also { canvas ->
            canvas.fill(base)
            StrokeRasterizer().draw(canvas, stroke(params, color))
        }

    @Test fun multiplyBrushDarkensInsteadOfCovering() {
        val yellow = 0xFFFFFF00.toInt()
        val cyan = 0xFF00FFFF.toInt()
        val out = paint(BrushParams(size = 8f, spacing = 0.05f, blendMode = BlendMode.MULTIPLY), cyan, yellow)
        // Cyan multiplied onto yellow is green.
        val middle = out.pixels[10 * 40 + 20]
        assertTrue(((middle shr 16) and 0xFF) < 30)
        assertTrue(((middle shr 8) and 0xFF) > 220)
        assertTrue((middle and 0xFF) < 30)
    }

    @Test fun wetMixPicksUpPaintUnderTheBrush() {
        val white = -1
        val blue = 0xFF0000FF.toInt()
        val dry = paint(BrushParams(size = 8f, spacing = 0.05f), blue, white)
        val wet = paint(BrushParams(size = 8f, spacing = 0.05f, wetMix = 1f), blue, white)
        val index = 10 * 40 + 20
        assertTrue(((dry.pixels[index] shr 16) and 0xFF) < 30)
        assertTrue("Wet paint mixes with the white underneath", ((wet.pixels[index] shr 16) and 0xFF) > 60)
    }

    @Test fun wetEdgesThinTheStrokeMiddle() {
        val clear = 0
        val black = 0xFF000000.toInt()
        val normal = paint(BrushParams(size = 12f, spacing = 0.05f), black, clear)
        val wet = paint(BrushParams(size = 12f, spacing = 0.05f, wetEdges = 1f), black, clear)
        val centre = 10 * 40 + 20
        assertTrue((wet.pixels[centre] ushr 24) < (normal.pixels[centre] ushr 24))
    }

    @Test fun dualBrushCombinesWithTheSecondBrush() {
        val main = BrushParams(size = 12f, spacing = 0.05f)
        val thin = BrushParams(size = 4f, spacing = 0.05f)
        val black = 0xFF000000.toInt()
        val multiply = paint(main.copy(dual = DualBrush(thin, DualBrush.Mode.MULTIPLY)), black, 0)
        val subtract = paint(main.copy(dual = DualBrush(thin, DualBrush.Mode.SUBTRACT)), black, 0)
        val line = 10 * 40 + 20
        // Four pixels off the line: inside the 12 px main brush, outside the 4 px second brush.
        val side = 14 * 40 + 20
        assertTrue("Multiply keeps only the overlap", (multiply.pixels[side] ushr 24) == 0)
        assertTrue((multiply.pixels[line] ushr 24) > 200)
        assertTrue("Subtract cuts the second brush out", (subtract.pixels[line] ushr 24) < 30)
        assertTrue((subtract.pixels[side] ushr 24) > 200)
    }

    @Test fun tiltedPenPaintsWiderAndLighter() {
        val params = BrushParams(size = 6f, spacing = 0.05f, tiltInfluence = 1f)

        fun draw(tilt: Float): PixelBuffer =
            PixelBuffer(40, 20).also { canvas ->
                val points =
                    listOf(
                        StrokePoint(4f, 10f, 1f, tiltX = tilt, timestamp = 0L),
                        StrokePoint(36f, 10f, 1f, tiltX = tilt, timestamp = 16L),
                    )
                StrokeRasterizer().draw(canvas, Stroke(id = 5, points = points, brushParams = params, layerId = 1, color = -0x1000000))
            }
        val upright = draw(0f)
        val flat = draw((Math.PI / 2).toFloat())
        val edge = 10 * 40 + 20 + 40 * 5
        assertTrue("A flat pen reaches farther from the line", (flat.pixels[edge] ushr 24) > (upright.pixels[edge] ushr 24))
        assertTrue("A flat pen is lighter at the centre", (flat.pixels[10 * 40 + 20] ushr 24) < (upright.pixels[10 * 40 + 20] ushr 24))
    }
}
