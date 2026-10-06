package com.artflow.studio.domain.model.brush

import com.artflow.studio.domain.model.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorDynamicsTest {
    private val red = 0xFFFF0000.toInt()

    @Test
    fun firmUprightStrokesKeepTheChosenColour() {
        val brush = BrushParams(colorDynamics = ColorDynamics(1f, 1f, 1f, 1f, 1f, 1f))
        assertEquals(red, brush.applyColorJitter(red, pressure = 1f, tilt = 0f))
        assertEquals(red, BrushParams().applyColorJitter(red, pressure = 0f, tilt = 1f))
    }

    @Test
    fun lightPressesShiftHueAndDarken() {
        val brush = BrushParams(colorDynamics = ColorDynamics(pressureHue = 0.5f, pressureBrightness = 0.5f))
        val light = Color.rgbToHsv(brush.applyColorJitter(red, pressure = 0f))
        assertEquals(90f, light[0], 1f)
        assertEquals(0.5f, light[2], 0.01f)
    }

    @Test
    fun tiltWashesOutSaturation() {
        val brush = BrushParams(colorDynamics = ColorDynamics(tiltSaturation = 1f))
        val flat = Color.rgbToHsv(brush.applyColorJitter(red, pressure = 1f, tilt = 1f))
        assertTrue(flat[1] < 0.01f)
        assertEquals(0xFF, brush.applyColorJitter(red, pressure = 1f, tilt = 1f) ushr 24)
    }
}
