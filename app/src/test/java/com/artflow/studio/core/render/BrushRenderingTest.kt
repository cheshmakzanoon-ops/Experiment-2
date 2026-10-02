package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.brush.BrushParams
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
}
