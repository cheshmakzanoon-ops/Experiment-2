package com.artflow.studio.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextWrappingIntegrityTest {
    private val style = TextLayout.TextStyle()
    private val unitWidth: (String) -> Float = { it.length.toFloat() }

    @Test
    fun authoredIndentationRepeatedSpacesTabsAndTrailingSpacesSurviveWhenTheyFit() {
        val text = "  one  two\tthree  "
        assertEquals(listOf(text), TextLayout.wrap(text, 100f, style, unitWidth))
    }

    @Test
    fun wrappingConsumesOnlyTheSeparatorAtTheSoftLineBreak() {
        assertEquals(listOf("one  two", "three"), TextLayout.wrap("one  two three", 8f, style, unitWidth))
        assertEquals(listOf("  one", "two"), TextLayout.wrap("  one  two", 7f, style, unitWidth))
    }

    @Test
    fun wrappingKeepsACombiningMarkAttachedToItsSpaceBase() {
        val text = "a \u0301b"
        val lines = TextLayout.wrap(text, 3f, style, unitWidth)
        assertEquals(listOf("a \u0301", "b"), lines)
        assertEquals(text, lines.joinToString(""))
    }

    @Test
    fun softWrappingConsumesOnlyWholeSeparatorClusters() {
        assertEquals(listOf("a", " \u0301b"), TextLayout.wrap("a \t \u0301b", 3f, style, unitWidth))
    }

    @Test
    fun emptyAndWhitespaceOnlyParagraphsKeepTheirAuthoredContent() {
        assertEquals(listOf(""), TextLayout.wrap("", 10f, style, unitWidth))
        assertEquals(listOf(" \t  "), TextLayout.wrap(" \t  ", 10f, style, unitWidth))
    }

    @Test
    fun verticalGlyphsKeepCombiningAccentsEmojiModifiersJoinersAndFlagsTogether() {
        val glyphs = listOf("e\u0301", "\u2764\uFE0F", "👍🏽", "👩‍💻", "🇨🇦")
        assertEquals(glyphs, TextLayout.glyphs(glyphs.joinToString("")))
    }

    @Test
    fun emergencyWrappingNeverMeasuresOrEmitsABrokenGrapheme() {
        val clusters = listOf("e\u0301", "👩‍💻", "👍🏽", "🇨🇦")
        val measured = mutableListOf<String>()
        val expectedBoundaries = mutableSetOf(0)
        var offset = 0
        clusters.forEach {
            offset += it.length
            expectedBoundaries += offset
        }
        val source = clusters.joinToString("")
        val lines =
            TextLayout.wrap(source, 1f, style) {
                measured += it
                TextLayout.glyphs(it).size.toFloat()
            }
        assertEquals(clusters, lines)
        assertTrue(
            measured.all { fragment ->
                val start = source.indexOf(fragment)
                start in expectedBoundaries && start + fragment.length in expectedBoundaries
            },
        )
    }

    @Test
    fun oneGraphemeWiderThanTheBoxRemainsWhole() {
        val family = "👨‍👩‍👧‍👦"
        assertEquals(listOf(family), TextLayout.wrap(family, 1f, style) { 10f })
    }

    @Test
    fun justifiedTextPreservesIndentationAndUnequalAuthoredGaps() {
        val runs = TextLayout.justify("  aa   bb cc ", unitWidth, 16f, 4f)
        assertEquals(listOf("aa", "bb", "cc"), runs.map { it.text })
        assertEquals(listOf(6f, 12.5f, 17f), runs.map { it.x })
        assertEquals(19f, runs.last().x + runs.last().width, 0f)
    }

    @Test
    fun justifiedRunsKeepCombiningMarksWithTheirSpaceBase() {
        val text = "a \u0301b"
        val singleWord = TextLayout.justify(text, unitWidth, 8f, 4f)
        assertEquals(listOf(text), singleWord.map { it.text })
        assertEquals(listOf(4f), singleWord.map { it.x })

        val runs = TextLayout.justify("a \t \u0301b c", unitWidth, 12f, 4f)
        assertEquals(listOf("a", " \u0301b", "c"), runs.map { it.text })
        assertEquals(listOf(4f, 9f, 15f), runs.map { it.x })
        assertEquals(16f, runs.last().x + runs.last().width, 0f)
    }

    @Test
    fun curvedGlyphHelperUsesTheSameWholeClustersAsVerticalText() {
        val clusters = listOf("e\u0301", "👩‍💻", "🇨🇦")
        val curved = TextLayout.layoutOnCurve(clusters.joinToString(""), 0f, 0f, 100f, style) { 10f }
        assertEquals(clusters, curved.map { it.text })
    }
}
