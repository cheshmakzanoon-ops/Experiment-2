package com.artflow.studio.core

import com.artflow.studio.core.color.SpectralMixingPad
import com.artflow.studio.core.pixels.Channels
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.LayerStrokeRenderer
import com.artflow.studio.core.render.PhysicalPaintPlane
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.PhysicalPaintMode
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePhysicalStudioTest {
    @Test
    fun spectralMixingPadBlendsBlueAndYellowIntoGreen() {
        val pad = SpectralMixingPad(width = 64, height = 64)
        val blue = 0xFF0044DD.toInt()
        val yellow = 0xFFFFEE00.toInt()
        pad.deposit(32f, 32f, blue, radius = 16f, mixStrength = 1f)
        val mixed = pad.smear(31f, 32f, 33f, 32f, sourceColor = yellow, radius = 10f, pickupRate = 0.20f)
        val r = Channels.red(mixed)
        val g = Channels.green(mixed)
        val b = Channels.blue(mixed)
        assertTrue("Expected Kubelka-Munk blue+yellow mix to be green-dominant (r=$r g=$g b=$b)", g > r && g > b)
        pad.clear()
        assertEquals(SpectralMixingPad.PAD_BACKGROUND, pad.sampleAt(32f, 32f))
    }

    @Test
    fun physicalPaintPlaneTracksWetnessDryAndRewetLifecycle() {
        val buffer = PixelBuffer(64, 64)
        val plane = PhysicalPaintPlane(64, 64)
        assertFalse(plane.hasActiveWetness())

        val watercolor =
            BrushParams(
                size = 16f,
                opacity = 0.9f,
                physicalMode = PhysicalPaintMode.WATERCOLOR,
                waterAmount = 0.85f,
            )
        val stroke =
            Stroke(
                id = 1L,
                points = listOf(StrokePoint(20f, 32f, 0.8f), StrokePoint(44f, 32f, 0.8f)),
                brushParams = watercolor,
                layerId = 1L,
                color = 0xFF2060CC.toInt(),
            )
        val painted =
            LayerStrokeRenderer.renderWithPhysical(
                base = buffer,
                historicalStrokes = emptyList(),
                incomingStrokes = listOf(stroke),
                width = 64,
                height = 64,
                alphaLock = false,
                selection = null,
                physicalPlane = plane,
            )
        assertTrue("Watercolor stroke must deposit active wetness on PhysicalPaintPlane", plane.hasActiveWetness())

        plane.dryAll()
        assertFalse("dryAll must clear active wetness", plane.hasActiveWetness())

        plane.rewetPaintedPixels(painted, amount = 0.75f)
        assertTrue("rewetPaintedPixels must restore wetness on painted pixels", plane.hasActiveWetness())
    }
}
