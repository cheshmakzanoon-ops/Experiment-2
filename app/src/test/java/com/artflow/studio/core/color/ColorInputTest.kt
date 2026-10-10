package com.artflow.studio.core.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorInputTest {
    @Test
    fun hexFieldAcceptsShorthandLongFormAndAlpha() {
        assertEquals(0xFFAABBCC.toInt(), ColorHarmony.parseHex("#abc"))
        assertEquals(0xFFFF8000.toInt(), ColorHarmony.parseHex("#ff8000"))
        assertEquals(0x80FF0080.toInt(), ColorHarmony.parseHex("80ff0080"))
    }

    @Test
    fun hexFieldRejectsWordsAndBadDigitsWithoutThrowing() {
        // "red" is a valid three-letter word but not hex; it used to throw on the UI thread.
        assertNull(ColorHarmony.parseHex("red"))
        assertNull(ColorHarmony.parseHex("#zz0000"))
        assertNull(ColorHarmony.parseHex("12345"))
    }

    @Test
    fun textColourPicksTheHigherContrastBlackOrWhite() {
        // Mid-light grey (relative luminance about 0.30): black reads at about 6.9:1, white at about 3.0:1.
        assertEquals(0xFF000000.toInt(), ColorHarmony.bestTextColor(0xFF949494.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), ColorHarmony.bestTextColor(0xFF202020.toInt()))
    }
}
