package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import kotlin.math.sin

/** A small, deterministic real-engine preview, not a decorative approximation of the controls. */
object BrushPreview {
    fun render(params: BrushParams): PixelBuffer {
        val preview = PixelBuffer(320, 96)
        val points =
            (0..40).map { index ->
                val progress = index / 40f
                StrokePoint(
                    x = 25f + progress * 270f,
                    y = 48f + sin(progress * Math.PI * 2).toFloat() * 12f,
                    pressure = 0.1f + sin(progress * Math.PI).toFloat() * 0.9f,
                    timestamp = index * 12L,
                )
            }
        val renderer = StrokeRasterizer()
        try {
            renderer.draw(
                preview,
                Stroke(
                    id = 1729L,
                    points = points,
                    brushParams = params.copy(size = params.size.coerceIn(1f, 48f)),
                    layerId = 0L,
                    color = 0xFFFF6B5C.toInt(),
                    timestamp = 0L,
                ),
            )
        } finally {
            renderer.release()
        }
        return preview
    }
}
