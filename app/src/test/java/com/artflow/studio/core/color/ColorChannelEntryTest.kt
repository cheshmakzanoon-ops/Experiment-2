package com.artflow.studio.core.color

import com.artflow.studio.core.color.ColorHarmony.ColorMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorChannelEntryTest {
    @Test
    fun hueReadsInDegreesAndTheOtherChannelsInPercent() {
        assertEquals("210°", ColorHarmony.formatChannelValue(ColorMode.HSV, 0, 210f))
        assertEquals("87%", ColorHarmony.formatChannelValue(ColorMode.HSV, 1, 0.87f))
        assertEquals("100%", ColorHarmony.formatChannelValue(ColorMode.HSV, 2, 1f))
        assertEquals("128", ColorHarmony.formatChannelValue(ColorMode.RGB, 0, 128f))
        assertEquals("35%", ColorHarmony.formatChannelValue(ColorMode.CMYK, 3, 0.35f))
    }

    @Test
    fun saturationAndBrightnessAreNoLongerRoundedToZeroOrOne() {
        // The old readout printed value.roundToInt(), so every 0..1 channel showed 0 or 1.
        assertEquals("50%", ColorHarmony.formatChannelValue(ColorMode.HSV, 1, 0.5f))
        assertEquals("1%", ColorHarmony.formatChannelValue(ColorMode.CMYK, 0, 0.01f))
    }

    @Test
    fun typedValuesComeBackInTheSlidersUnits() {
        assertEquals(0.4f, ColorHarmony.parseChannelValue(ColorMode.HSV, 2, "40")!!, 1e-6f)
        assertEquals(0.4f, ColorHarmony.parseChannelValue(ColorMode.HSV, 2, " 40 % ")!!, 1e-6f)
        assertEquals(180f, ColorHarmony.parseChannelValue(ColorMode.HSV, 0, "180°")!!, 1e-6f)
        assertEquals(255f, ColorHarmony.parseChannelValue(ColorMode.RGB, 1, "255")!!, 1e-6f)
        assertEquals(0.125f, ColorHarmony.parseChannelValue(ColorMode.CMYK, 2, "12,5")!!, 1e-6f)
    }

    @Test
    fun valuesOutsideTheRangeOrNotNumbersAreRejected() {
        assertNull(ColorHarmony.parseChannelValue(ColorMode.HSV, 1, "101"))
        assertNull(ColorHarmony.parseChannelValue(ColorMode.HSV, 0, "361"))
        assertNull(ColorHarmony.parseChannelValue(ColorMode.RGB, 0, "-1"))
        assertNull(ColorHarmony.parseChannelValue(ColorMode.RGB, 0, "256"))
        assertNull(ColorHarmony.parseChannelValue(ColorMode.RGB, 0, ""))
        assertNull(ColorHarmony.parseChannelValue(ColorMode.RGB, 0, "red"))
        assertNull(ColorHarmony.parseChannelValue(ColorMode.RGB, 0, "NaN"))
        assertNull(ColorHarmony.parseChannelValue(ColorMode.RGB, 0, "Infinity"))
    }
}
