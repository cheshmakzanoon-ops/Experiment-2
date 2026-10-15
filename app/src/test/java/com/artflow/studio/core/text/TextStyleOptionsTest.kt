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

    @Test fun justifySpreadsTheWordsAcrossTheLine() {
        val runs = TextLayout.justify("aa bb cc", { it.length * 10f }, 100f, 0f)
        assertEquals(listOf("aa", "bb", "cc"), runs.map { it.text })
        assertEquals(listOf(0f, 40f, 80f), runs.map { it.x })
    }
}
