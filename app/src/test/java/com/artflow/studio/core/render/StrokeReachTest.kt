package com.artflow.studio.core.render

import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.model.brush.StudioBrushes
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.cos

/** The reach must contain every pixel a stroke's dabs touch, or previews and commits would clip paint. */
class StrokeReachTest {
    private val width = 900
    private val height = 700

    private fun stroke(
        params: BrushParams,
        seed: Long,
    ): Stroke {
        val random = Random(seed)
        return Stroke(
            id = seed,
            points =
                (0 until 14).map { i ->
                    StrokePoint(
                        x = 300f + i * 21f,
                        y = 350f + cos(i / 2f) * 60f,
                        pressure = random.nextFloat(),
                        tiltX = random.nextFloat() * 1.6f,
                        tiltY = random.nextFloat() * 3f,
                        timestamp = i * (1L + random.nextInt(20)),
                    )
                },
            brushParams = params,
            layerId = 0L,
            color = 0xFF204060.toInt(),
            timestamp = 0L,
        )
    }

    private fun assertInside(
        params: BrushParams,
        seed: Long,
        label: String,
    ) {
        val stroke = stroke(params.copy(taperStart = 0f, taperEnd = 0f, dual = null, spacing = params.spacing.coerceAtLeast(0.02f)), seed)
        val rasterizer = StrokeRasterizer()
        val live = rasterizer.startLive(stroke, width, height)
        rasterizer.drawLive(
            com.artflow.studio.core.pixels
                .PixelBuffer(width, height),
            live,
            stroke,
            null,
        )
        val bounds = StrokeReach.bounds(stroke.points, stroke.brushParams, width, height)
        val coverage = live.coverage
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (coverage.pixels[y * width + x] ushr 24 == 0) continue
                assertTrue("$label paints ($x, $y) outside $bounds", bounds.contains(x, y))
            }
        }
    }

    @Test
    fun everyPresetStaysInsideItsReach() {
        StudioBrushes.presets.forEach { preset -> assertInside(preset.parameters, preset.id.hashCode().toLong(), preset.id) }
    }

    @Test
    fun extremeDynamicsStayInsideTheirReach() {
        val random = Random(11)
        repeat(60) { index ->
            val params =
                BrushParams(
                    size = 2f + random.nextFloat() * 160f,
                    spacing = 0.02f + random.nextFloat() * 0.6f,
                    scatter = if (index % 2 == 0) random.nextFloat() * 4f else 0f,
                    count = 1 + index % 5,
                    sizeJitter = random.nextFloat(),
                    tiltInfluence = if (index % 3 == 0) random.nextFloat() else 0f,
                    velocityToSize = if (index % 4 == 0) -random.nextFloat() else random.nextFloat(),
                    pressureToSize = random.nextFloat(),
                    roundness = if (index % 5 == 0) 0.1f + random.nextFloat() * 0.8f else 1f,
                    rotation = random.nextFloat() * 360f,
                    tipRandomized = index % 6 == 0,
                )
            assertInside(params, index.toLong(), "random brush $index")
        }
    }
}
