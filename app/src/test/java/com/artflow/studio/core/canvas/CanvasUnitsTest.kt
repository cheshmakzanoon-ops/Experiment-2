package com.artflow.studio.core.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanvasUnitsTest {
    @Test
    fun physicalSizesBecomePixelsAtTheDpi() {
        // Procreate's A4 at 300 dpi is 2480 x 3508 whether it is typed in centimetres or millimetres.
        assertEquals(2480, CanvasUnit.CENTIMETERS.toPixels(21.0, 300))
        assertEquals(3508, CanvasUnit.CENTIMETERS.toPixels(29.7, 300))
        assertEquals(2480, CanvasUnit.MILLIMETERS.toPixels(210.0, 300))
        assertEquals(3508, CanvasUnit.MILLIMETERS.toPixels(297.0, 300))
        assertEquals(1200, CanvasUnit.INCHES.toPixels(4.0, 300))
        assertEquals(1800, CanvasUnit.INCHES.toPixels(6.0, 300))
    }

    @Test
    fun pixelsIgnoreTheDpi() {
        assertEquals(1920, CanvasUnit.PIXELS.toPixels(1920.4, 0))
        assertEquals(1920, CanvasUnit.PIXELS.toPixels(1920.4, 300))
        assertEquals(1080.0, CanvasUnit.PIXELS.fromPixels(1080, 0), 0.0)
    }

    @Test
    fun unusableLengthsAreZeroPixels() {
        assertEquals(0, CanvasUnit.INCHES.toPixels(4.0, 0))
        assertEquals(0, CanvasUnit.INCHES.toPixels(-1.0, 300))
        assertEquals(0, CanvasUnit.PIXELS.toPixels(Double.NaN, 300))
        assertEquals(0, CanvasUnit.CENTIMETERS.toPixels(Double.POSITIVE_INFINITY, 300))
        assertEquals(0.0, CanvasUnit.INCHES.fromPixels(100, 0), 0.0)
    }

    @Test
    fun hugeLengthsStayWithinInt() {
        assertEquals(Int.MAX_VALUE, CanvasUnit.INCHES.toPixels(1e30, 300))
    }

    @Test
    fun formatKeepsEditableTextShort() {
        assertEquals("1920", CanvasUnit.PIXELS.format(1920.0))
        assertEquals("8", CanvasUnit.INCHES.format(8.0))
        assertEquals("100", CanvasUnit.CENTIMETERS.format(100.0))
        assertEquals("10.5", CanvasUnit.CENTIMETERS.format(10.5))
        assertEquals("8.2667", CanvasUnit.INCHES.format(2480 / 300.0))
        assertEquals("0", CanvasUnit.MILLIMETERS.format(0.0))
    }

    @Test
    fun parseTakesBothDecimalMarksAndRejectsNonLengths() {
        assertEquals(21.5, CanvasUnit.parse("21,5")!!, 0.0)
        assertEquals(21.5, CanvasUnit.parse(" 21.5 ")!!, 0.0)
        assertEquals(8.0, CanvasUnit.parse("8")!!, 0.0)
        assertNull(CanvasUnit.parse(""))
        assertNull(CanvasUnit.parse("."))
        assertNull(CanvasUnit.parse("-3"))
        assertNull(CanvasUnit.parse("abc"))
        assertNull(CanvasUnit.parse("NaN"))
        assertNull(CanvasUnit.parse("Infinity"))
    }

    @Test
    fun otherScriptsDigitsAndDecimalMarksAreUnderstood() {
        // Persian digits with the Arabic decimal mark, as a Persian keyboard types them.
        assertEquals(21.5, CanvasUnit.parse("\u06F2\u06F1\u066B\u06F5")!!, 0.0)
        assertEquals(1920.0, CanvasUnit.parse("\u0661\u0669\u0662\u0660")!!, 0.0)
        assertEquals("21.5", CanvasUnit.CENTIMETERS.sanitize("\u06F2\u06F1\u066B\u06F5"))
    }

    @Test
    fun sanitizeKeepsOnlyWhatCanBeALength() {
        assertEquals("1920", CanvasUnit.PIXELS.sanitize("19a2-0"))
        assertEquals("1920", CanvasUnit.PIXELS.sanitize("19.20"))
        assertEquals("12345678", CanvasUnit.PIXELS.sanitize("123456789"))
        assertEquals("21.5", CanvasUnit.CENTIMETERS.sanitize("2x1,5"))
        // A second decimal mark is dropped; the digits after it stay.
        assertEquals("21.57", CanvasUnit.CENTIMETERS.sanitize("21.5.7"))
        assertEquals(8, CanvasUnit.INCHES.sanitize("1".repeat(20)).length)
        assertEquals("", CanvasUnit.MILLIMETERS.sanitize("abc"))
    }

    @Test
    fun switchingUnitsBackAndForthNeverMovesAPixel() {
        // The dialog turns the pixels into text in the new unit and reads them back, so the text has to be exact enough.
        for (dpi in intArrayOf(CanvasOperations.MIN_DPI, 72, 132, 300, 600, CanvasOperations.MAX_DPI)) {
            for (unit in CanvasUnit.entries) {
                for (pixels in 1..CanvasOperations.MAX_DIMENSION) {
                    val text = unit.format(unit.fromPixels(pixels, dpi))
                    assertEquals("$pixels px as $text ${unit.suffix} at $dpi dpi", pixels, unit.toPixels(CanvasUnit.parse(text)!!, dpi))
                }
            }
        }
    }
}
