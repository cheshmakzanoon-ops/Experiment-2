package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke

/** A bounded, isolated test surface. No document repository or history is involved. */
object BrushPractice {
    const val WIDTH = 320
    const val HEIGHT = 180
    const val MAX_STROKES = 8
    const val MAX_POINTS = 128
    const val PAPER = 0xFFF8F5EF.toInt()
    const val INK = 0xFF974F43.toInt()

    fun render(
        parameters: BrushParams,
        strokes: List<Stroke>,
    ): PixelBuffer {
        require(strokes.size <= MAX_STROKES)
        require(strokes.all { it.points.size <= MAX_POINTS })
        val result = PixelBuffer.filled(WIDTH, HEIGHT, PAPER)
        val renderer = StrokeRasterizer()
        try {
            for (stroke in strokes) {
                renderer.draw(
                    result,
                    stroke.copy(brushParams = parameters.copy(size = parameters.size.coerceIn(1f, 48f))),
                    enableWetMix = true,
                )
            }
        } finally {
            renderer.release()
        }
        return result
    }
}
