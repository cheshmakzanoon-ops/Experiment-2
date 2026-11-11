package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.Channels
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.DualBrush
import com.artflow.studio.domain.model.brush.PressureMapping
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ValkyrieWetMixTest {
    private val random = Random(42)

    @Test
    fun dilutionCurveThinsPaint() {
        val baseBrush = BrushParams(
            size = 20f,
            opacity = 1f,
            wetDilution = 0.5f,
        )
        val color = -0x10000 // Opaque Red

        val sampleNoCurve = WetBrushPickup.Sample(
            x = 10f, y = 10f, directionX = 1f, directionY = 0f, travelled = 5f, pressure = 0.5f,
        )
        val resultNoCurve = WetBrushPickup.color(baseBrush, null, color, sampleNoCurve, random)
        val alphaNoCurve = Channels.alpha(resultNoCurve)

        // Add a dilution curve that increases dilution to 1.0 at pressure 0.5
        val mappedBrush = baseBrush.copy(
            importedAuxiliaryCurves = mapOf(
                "dynamicsPressureDilutionCurve" to PressureMapping(
                    listOf(
                        PressureMapping.Point(0f, 0.2f),
                        PressureMapping.Point(1f, 1f),
                    ),
                ),
            ),
        )
        val resultWithCurve = WetBrushPickup.color(mappedBrush, null, color, sampleNoCurve, random)
        val alphaWithCurve = Channels.alpha(resultWithCurve)

        assertNotEquals(alphaNoCurve, alphaWithCurve)
        assertTrue(alphaWithCurve > alphaNoCurve)
    }

    @Test
    fun chargeDepletionAlongStroke() {
        val canvas = PixelBuffer(100, 100)
        canvas.fill(-0xff0100) // Green background

        val brush = BrushParams(
            size = 10f,
            wetCharge = 0.5f,
            wetMix = 0.5f,
        )
        val paintColor = -0x10000 // Red

        // Sample near touchdown (travelled = 2px)
        val touchSample = WetBrushPickup.Sample(
            x = 50f, y = 50f, directionX = 1f, directionY = 0f, travelled = 2f, pressure = 0.5f,
        )
        val touchColor = WetBrushPickup.color(brush, canvas, paintColor, touchSample, random)

        // Sample far along stroke (travelled = 500px)
        val farSample = WetBrushPickup.Sample(
            x = 50f, y = 50f, directionX = 1f, directionY = 0f, travelled = 500f, pressure = 0.5f,
        )
        val farColor = WetBrushPickup.color(brush, canvas, paintColor, farSample, random)

        // Far color has depleted charge, meaning canvas pickup / mixing has evolved
        assertNotEquals(touchColor, farColor)
    }

    @Test
    fun dualBrushNewCombinationModes() {
        val rasterizer = StrokeRasterizer()
        val baseBrush = BrushParams(size = 20f, spacing = 0.1f)
        val secondBrush = BrushParams(size = 15f, spacing = 0.1f)

        for (mode in listOf(DualBrush.Mode.COMBINE, DualBrush.Mode.INTENSE_BLEND, DualBrush.Mode.DISPLACE)) {
            val dual = DualBrush(secondBrush, mode)
            val compoundBrush = baseBrush.copy(dual = dual)

            val stroke = Stroke(
                id = 101L,
                layerId = 1L,
                points = listOf(
                    StrokePoint(10f, 10f, pressure = 0.8f),
                    StrokePoint(30f, 30f, pressure = 0.8f),
                ),
                color = -0x10000,
                brushParams = compoundBrush,
            )

            val target = PixelBuffer(60, 60)
            rasterizer.draw(target, stroke)

            // Ensure marks were stamped
            var stampedPixels = 0
            for (i in target.pixels.indices) {
                if ((target.pixels[i] ushr 24) > 0) stampedPixels++
            }
            assertTrue("Mode $mode should deposit pixels", stampedPixels > 0)
        }
    }

    @Test
    fun auxiliaryCurvesModulateScatterAndCount() {
        val baseBrush = BrushParams(
            size = 20f,
            sizeJitter = 0.5f,
            opacityJitter = 0.5f,
        )

        // Test size jitter curve
        val sizeNoCurve = baseBrush.calculateEffectiveSize(pressure = 0.5f, random = Random(1))
        val withSizeCurve = baseBrush.copy(
            importedAuxiliaryCurves = mapOf(
                "dynamicsPressureSizeJitterCurve" to PressureMapping(
                    listOf(
                        PressureMapping.Point(0f, 0f),
                        PressureMapping.Point(1f, 1f),
                    ),
                ),
            ),
        )
        val sizeWithCurve = withSizeCurve.calculateEffectiveSize(pressure = 0.5f, random = Random(1))
        assertNotEquals(sizeNoCurve, sizeWithCurve)

        // Test opacity jitter curve
        val opNoCurve = baseBrush.calculateEffectiveOpacity(pressure = 0.5f, random = Random(1))
        val withOpCurve = baseBrush.copy(
            importedAuxiliaryCurves = mapOf(
                "dynamicsPressureOpacityJitterCurve" to PressureMapping(
                    listOf(
                        PressureMapping.Point(0f, 0f),
                        PressureMapping.Point(1f, 1f),
                    ),
                ),
            ),
        )
        val opWithCurve = withOpCurve.calculateEffectiveOpacity(pressure = 0.5f, random = Random(1))
        assertNotEquals(opNoCurve, opWithCurve)
    }
}
