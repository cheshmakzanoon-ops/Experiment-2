package com.artflow.studio.core.text

import org.junit.Assert.assertEquals
import org.junit.Test

class TextStyleOptionsTest {
    @Test fun allCapsAndVerticalGlyphs() {
        val style = TextLayout.TextStyle(allCaps = true)
        assertEquals("HELLO 1", TextLayout.displayText("Hello 1", style))
        assertEquals("Hello", TextLayout.displayText("Hello", TextLayout.TextStyle()))
        assertEquals(listOf("a", "🎨", "b"), TextLayout.glyphs("a🎨b"))
    }
}
