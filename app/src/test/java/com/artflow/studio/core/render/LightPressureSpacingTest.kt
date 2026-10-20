package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Test

class LightPressureSpacingTest {
    private val black = 0xFF000000.toInt()

    /** A straight stroke across a 60 x 20 canvas at one pressure, with the brush's pressure-to-size response. */
    private fun strokeAt(pressure: Float): PixelBuffer {
        val points = (0..10).map { StrokePoint(5f + it * 5f, 10f, pressure = pressure, timestamp = it * 10L) }
        val params = BrushParams(size = 12f, spacing = 0.25f, pressureToSize = 1f, pressureToOpacity = 0f)
        val target = PixelBuffer(60, 20)
        StrokeRasterizer().draw(target, Stroke(points = points, brushParams = params, layerId = 1, color = black))
        return target
    }

    @Test
    fun aLightPressureStrokeHasNoGapsOnItsCentreline() {
        // At this pressure the dab is about 1.8 px across. Spacing from the nominal 12 px size would leave
        // unpainted pixels between dabs; spacing from the dab's own diameter does not.
        val target = strokeAt(pressure = 0.15f)
        val unpainted = (8..42).filter { x -> (target.pixels[10 * 60 + x] ushr 24) == 0 }
        assertEquals("unpainted centre pixels", emptyList<Int>(), unpainted)
    }
}
