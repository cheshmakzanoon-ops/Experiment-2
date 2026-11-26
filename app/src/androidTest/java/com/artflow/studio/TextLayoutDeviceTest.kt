package com.artflow.studio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.text.TextLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the platform grapheme matcher on every supported emulator, including API 26. */
@RunWith(AndroidJUnit4::class)
class TextLayoutDeviceTest {
    @Test
    fun wrappedAndVerticalTextKeepUnicodeClustersOnTheDeviceRuntime() {
        val clusters = listOf("e\u0301", "\u2764\uFE0F", "👍🏽", "👩‍💻", "🇨🇦")
        val text = clusters.joinToString("")
        assertEquals(clusters, TextLayout.glyphs(text))
        assertEquals(clusters, TextLayout.wrap(text, 1f, TextLayout.TextStyle()) { TextLayout.glyphs(it).size.toFloat() })
        assertEquals(listOf("  one  two\t"), TextLayout.wrap("  one  two\t", 50f, TextLayout.TextStyle()) { it.length.toFloat() })
    }

    @Test
    fun spaceBasedClustersSurviveWrappingAndJustificationOnTheDeviceRuntime() {
        val text = "a \u0301b"
        val style = TextLayout.TextStyle()
        val measure: (String) -> Float = { it.length.toFloat() }
        assertEquals(listOf("a", " \u0301", "b"), TextLayout.glyphs(text))
        assertEquals(listOf("a \u0301", "b"), TextLayout.wrap(text, 3f, style, measure))
        assertEquals(listOf("a", " \u0301b"), TextLayout.wrap("a \t \u0301b", 3f, style, measure))
        assertEquals(listOf(text), TextLayout.justify(text, measure, 8f, 4f).map { it.text })

        val runs = TextLayout.justify("a \t \u0301b c", measure, 12f, 4f)
        assertEquals(listOf("a", " \u0301b", "c"), runs.map { it.text })
        assertEquals(listOf(4f, 9f, 15f), runs.map { it.x })
    }
}
