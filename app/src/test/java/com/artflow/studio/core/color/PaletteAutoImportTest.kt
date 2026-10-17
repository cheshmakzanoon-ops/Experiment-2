package com.artflow.studio.core.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PaletteAutoImportTest {
    @Test
    fun aGplBodyPastedWithoutItsHeaderIsReadAsColoursNotAsHexCodes() {
        // Read as hex, "255" is a three-digit code and "128" another, so the list would import as garbage.
        val palette = PaletteCodec.importAuto("0 0 0\tBlack\n255 128 0\tOrange\n255 255 255\tWhite")
        assertNotNull(palette)
        assertEquals(listOf(0xFF000000.toInt(), 0xFFFF8000.toInt(), 0xFFFFFFFF.toInt()), palette?.colors)
    }

    @Test
    fun aSingleRgbLineIsAPaletteOfOneColour() {
        assertEquals(listOf(0xFF102030.toInt()), PaletteCodec.importAuto("16 32 48")?.colors)
    }

    @Test
    fun hexListsStillImportAsHex() {
        val palette = PaletteCodec.importAuto("#FF0000, 00ff00\n#abc")
        assertEquals(listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFFAABBCC.toInt()), palette?.colors)
    }
}
