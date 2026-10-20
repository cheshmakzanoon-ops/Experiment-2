package com.artflow.studio.presentation.ui.screens.canvas

import com.artflow.studio.core.color.Palette
import com.artflow.studio.core.color.PaletteCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class PaletteShareTest {
    @Test
    fun fileNamesAreSafeAndEndInAse() {
        assertEquals("Sunset warm.ase", paletteFileName("Sunset: warm?"))
        assertEquals("Palette.ase", paletteFileName("../\\:*"))
    }

    @Test
    fun sharedSwatchesImportBackWithTheirColours() {
        val palette = Palette(name = "Shared", colors = listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt()))
        val back = PaletteCodec.decode(PaletteCodec.exportAse(palette), "Shared")
        assertEquals(palette.colors, back?.colors)
    }
}
