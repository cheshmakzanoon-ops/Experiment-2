package com.artflow.studio.core.color

import com.artflow.studio.domain.model.Color
import org.junit.Assert.*
import org.junit.Test

class ColorConversionTest {
    @Test
    fun allEightBitRgbColorsSurviveAnHsvRoundTripExactly() {
        for (rgb in 0..0xFFFFFF) {
            val expected = rgb or 0xFF000000.toInt()
            val hsv = Color.rgbToHsv(expected)
            val actual = Color.hsvToRgb(hsv[0], hsv[1], hsv[2])
            assertEquals(expected, actual)
        }
    }

    @Test
    fun smallCoordinateRoundingDoesNotDarkenFullBrightness() {
        val brightness = Math.nextDown(1f)
        assertEquals(0xFF0000FF.toInt(), Color.hsvToRgb(240f, 1f, brightness))
        assertEquals(0x400000FF, ColorWheelState(240f, Math.nextDown(1f), brightness, 64).argb)
    }

    @Test
    fun repeatedPickerReflectionsDoNotAccumulateColorLoss() {
        val expected = 0x401237BD
        var wheel = ColorWheelState.fromArgb(expected)
        repeat(1000) { wheel = wheel.withArgb(wheel.argb) }
        assertEquals(expected, wheel.argb)
        assertEquals(expected, ColorHarmony.fromHsv(wheel.hue, wheel.saturation, wheel.value, wheel.alpha))
    }

    @Test
    fun hueWrapsAndFiniteOutOfRangeChannelsClamp() {
        val red = 0xFFFF0000.toInt()
        assertEquals(red, Color.hsvToRgb(-360f, 2f, 2f))
        assertEquals(red, Color.hsvToRgb(720f, 1f, 1f))
        assertEquals(0xFFFFFFFF.toInt(), Color.hsvToRgb(123f, -1f, 1f))
        assertEquals(0xFF000000.toInt(), Color.hsvToRgb(123f, 1f, -1f))
    }

    @Test
    fun nonFiniteHsvComponentsAreRejectedRatherThanInventingAColor() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertTrue(runCatching { Color.hsvToRgb(bad, 1f, 1f) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { Color.hsvToRgb(0f, bad, 1f) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { Color.hsvToRgb(0f, 1f, bad) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
}
