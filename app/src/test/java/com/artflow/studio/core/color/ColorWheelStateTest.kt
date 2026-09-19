package com.artflow.studio.core.color

import org.junit.Assert.*
import org.junit.Test

class ColorWheelStateTest {
    private val geometry = ColorWheelGeometry(300f, 240f)
    private val blue = ColorWheelState.fromArgb(0x400000FF)

    @Test
    fun allSquareCornersBelongToTheSquareAndFitInsideTheRing() {
        for (dx in listOf(-1f, 1f)) {
            for (dy in listOf(-1f, 1f)) {
                assertEquals(
                    ColorWheelRegion.SQUARE,
                    geometry.hitTest(geometry.centerX + dx * geometry.inner, geometry.centerY + dy * geometry.inner),
                )
            }
        }
        assertTrue(geometry.inner * kotlin.math.sqrt(2f) < geometry.radius - geometry.ringStroke / 2f)
    }

    @Test
    fun squareCornersMapToFullSaturationAndBrightnessRange() {
        val white = blue.pick(geometry.centerX - geometry.inner, geometry.centerY - geometry.inner, geometry, ColorWheelRegion.SQUARE)
        val fullBlue = blue.pick(geometry.centerX + geometry.inner, geometry.centerY - geometry.inner, geometry, ColorWheelRegion.SQUARE)
        val black = blue.pick(geometry.centerX + geometry.inner, geometry.centerY + geometry.inner, geometry, ColorWheelRegion.SQUARE)
        assertEquals(0x40FFFFFF, white.argb)
        assertEquals(0x400000FF, fullBlue.argb)
        assertEquals(0x40000000, black.argb)
    }

    @Test
    fun squareDragClampsOutsideWithoutTurningIntoAHueDrag() {
        val moved = blue.pick(10_000f, -10_000f, geometry, ColorWheelRegion.SQUARE)
        assertEquals(blue.hue, moved.hue, 0f)
        assertEquals(1f, moved.saturation, 0f)
        assertEquals(1f, moved.value, 0f)
        assertEquals(blue.alpha, moved.alpha)
    }

    @Test
    fun hueDragCrossingTheSquareDoesNotChangeSaturationBrightnessOrAlpha() {
        val before = blue.copy(saturation = 0.3f, value = 0.4f)
        val moved = before.pick(geometry.centerX, geometry.centerY - 1, geometry, ColorWheelRegion.HUE)
        assertEquals(270f, moved.hue, 0f)
        assertEquals(before.saturation, moved.saturation, 0f)
        assertEquals(before.value, moved.value, 0f)
        assertEquals(before.alpha, moved.alpha)
    }

    @Test
    fun blackAndGreyDoNotEraseChosenHue() {
        val black = blue.withArgb(0x40000000)
        assertEquals(blue.hue, black.hue, 0f)
        assertEquals(blue.saturation, black.saturation, 0f)
        assertEquals(blue.argb, black.withValue(1f).argb)
        val grey = blue.withArgb(0x40808080)
        assertEquals(blue.hue, grey.hue, 0f)
        assertEquals(0f, grey.saturation, 0f)
        assertEquals(blue.argb, grey.copy(saturation = 1f, value = 1f).argb)
    }

    @Test
    fun changingHueWhileBlackTakesEffectWhenBrightnessReturns() {
        val black = blue.withArgb(0x40000000)
        val red = black.pick(geometry.centerX + geometry.radius, geometry.centerY, geometry, ColorWheelRegion.HUE)
        assertEquals(0x40000000, red.argb)
        assertEquals(0x40FF0000, red.withValue(1f).argb)
    }

    @Test
    fun externalChromaticSelectionReplacesTheRememberedHueAndAlpha() {
        val green = blue.withArgb(0xAA00FF00.toInt())
        assertEquals(120f, green.hue, 0f)
        assertEquals(0xAA, green.alpha)
        assertEquals(0xAA00FF00.toInt(), green.argb)
    }

    @Test
    fun blankSpaceAndInvalidCoordinatesAreNotColourTargets() {
        assertNull(geometry.hitTest(0f, 0f))
        assertNull(geometry.hitTest(geometry.centerX + geometry.radius * 0.75f, geometry.centerY))
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertNull(geometry.hitTest(bad, 0f))
            assertEquals(blue, blue.pick(bad, 0f, geometry, ColorWheelRegion.HUE))
            assertEquals(blue, blue.withValue(bad))
        }
        assertNull(ColorWheelGeometry(12f, 12f).hitTest(6f, 6f))
        assertNull(ColorWheelGeometry(Float.POSITIVE_INFINITY, 100f).hitTest(6f, 6f))
    }

    @Test
    fun ringStrokeFitsAtPhoneTabletAndHighDensitySizes() {
        for (size in listOf(100f, 240f, 480f, 960f)) {
            val bounds = ColorWheelGeometry(size, size * 1.5f)
            assertTrue(bounds.radius + bounds.ringStroke / 2f <= size / 2f)
        }
    }

    @Test
    fun hueRingHitTestingMatchesAllFourCardinalPoints() {
        val points = listOf(1f to 0f, 0f to 1f, -1f to 0f, 0f to -1f)
        points.forEachIndexed { index, (dx, dy) ->
            val x = geometry.centerX + dx * geometry.radius
            val y = geometry.centerY + dy * geometry.radius
            assertEquals(ColorWheelRegion.HUE, geometry.hitTest(x, y))
            assertEquals(index * 90f, blue.pick(x, y, geometry, ColorWheelRegion.HUE).hue, 0f)
        }
    }
}
