package com.artflow.studio.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextWordSpacingTest {
    private val unitWidth: (String) -> Float = { it.length.toFloat() }

    @Test
    fun positiveAndNegativeWordSpacingParticipateInWrapping() {
        val expanded = TextLayout.TextStyle(wordSpacing = 2f, maxWidth = 6f)
        assertEquals(listOf("aa", "bb"), TextLayout.wrap("aa bb", 6f, expanded, unitWidth))
        assertTrue(TextLayout.needsWrap("aa bb", expanded, unitWidth))
        assertEquals(listOf("aa bb"), TextLayout.wrap("aa bb", 6f, expanded.copy(wordSpacing = 0f), unitWidth))

        val condensed = expanded.copy(wordSpacing = -1f, maxWidth = 4f)
        assertEquals(listOf("aa bb"), TextLayout.wrap("aa bb", 4f, condensed, unitWidth))
        assertFalse(TextLayout.needsWrap("aa bb", condensed, unitWidth))
    }

    @Test
    fun alignmentAndReportedWidthUseTheSameAdvancesAsPositionedRuns() {
        listOf(TextLayout.Alignment.CENTER to 12f, TextLayout.Alignment.RIGHT to 14f).forEach { (alignment, x) ->
            val style = TextLayout.TextStyle(wordSpacing = 2f, maxWidth = 9f, alignment = alignment)
            val result = TextLayout.layout("a b", 10f, 3f, style, unitWidth, { 8f }, { 2f })
            val line = result.lines.single()
            assertEquals(5f, result.width, 0f)
            assertEquals(5f, line.width, 0f)
            assertEquals(x, line.x, 0f)
            assertEquals(listOf(x, x + 4f), line.runs.map { it.x })
            assertEquals(x + line.width, line.runs.last().x + line.runs.last().width, 0f)
            assertTrue(line.runs.all { it.y == line.baselineY })
        }
    }

    @Test
    fun justificationKeepsSpacingOnIndentationAndUnequalAuthoredGaps() {
        val style = TextLayout.TextStyle(wordSpacing = 1f, maxWidth = 22f, alignment = TextLayout.Alignment.JUSTIFY)
        val result = TextLayout.layout("  aa   bb cc ddddddddd", 4f, 0f, style, unitWidth, { 8f }, { 2f })
        assertEquals(listOf("  aa   bb cc", "ddddddddd"), result.lines.map { it.text })
        val runs = result.lines.first().runs
        assertEquals(listOf("aa", "bb", "cc"), runs.map { it.text })
        assertEquals(listOf(8f, 18f, 24f), runs.map { it.x })
        assertEquals(26f, runs.last().x + runs.last().width, 0f)
    }

    @Test
    fun aSingleWordJustifiedLineKeepsItsExpandedIndentation() {
        val style = TextLayout.TextStyle(wordSpacing = 1f, maxWidth = 7f, alignment = TextLayout.Alignment.JUSTIFY)
        val result = TextLayout.layout("  aa bb", 0f, 0f, style, unitWidth, { 8f }, { 2f })
        assertEquals(listOf("  aa", "bb"), result.lines.map { it.text })
        val runs = result.lines.first().runs
        assertEquals(listOf(" ", " ", "aa"), runs.map { it.text })
        assertEquals(listOf(0f, 2f, 4f), runs.map { it.x })
    }

    @Test
    fun spacingPreservesMarkedSpacesTabsJoinedEmojiAndTrailingGaps() {
        val text = "  a \u0301b\t👩‍💻  e\u0301 "
        val style = TextLayout.TextStyle(wordSpacing = 3f)
        val measure: (String) -> Float = { TextLayout.glyphs(it).size.toFloat() }
        val line = TextLayout.layout(text, 0f, 0f, style, measure, { 8f }, { 2f }).lines.single()
        assertEquals(text, line.runs.joinToString("") { it.text })
        assertEquals(TextLayout.glyphs(text), line.runs.flatMap { TextLayout.glyphs(it.text) })
        assertEquals(26f, line.width, 0f)
        assertEquals(listOf(0f, 4f, 8f, 17f, 21f), line.runs.map { it.x })
    }

    @Test
    fun wrappingMeasuresTheDrawnRunsWhenPlatformShapingIsNotAdditive() {
        val measure: (String) -> Float = {
            when (it) {
                "fi a" -> 90f
                "fi " -> 7f
                "fi" -> 5f
                "a" -> 3f
                else -> it.length.toFloat()
            }
        }
        val style = TextLayout.TextStyle(wordSpacing = 2f, maxWidth = 12f)
        val result = TextLayout.layout("fi a", 0f, 0f, style, measure, { 8f }, { 2f })
        assertEquals(listOf("fi a"), result.lines.map { it.text })
        assertEquals(12f, result.width, 0f)
        val runs = result.lines.single().runs
        assertEquals(listOf(9f, 3f), runs.map { it.width })
    }

    @Test
    fun zeroWordSpacingKeepsWholeRunPlatformShaping() {
        val measure: (String) -> Float = { if (it == "fi a") 7f else it.length * 10f }
        val result = TextLayout.layout("fi a", 0f, 0f, TextLayout.TextStyle(), measure, { 8f }, { 2f })
        assertEquals(7f, result.width, 0f)
        val runs = result.lines.single().runs
        assertEquals(listOf("fi a"), runs.map { it.text })
    }

    @Test
    fun curvedGlyphAdvancesIncludeSpacingWithoutBreakingMarkedSpaces() {
        val glyphs = TextLayout.layoutOnCurve("a \u0301 b", 0f, 0f, 100f, TextLayout.TextStyle(wordSpacing = 2f), unitWidth)
        assertEquals(listOf("a", " \u0301", " ", "b"), glyphs.map { it.text })
        assertEquals(listOf(1f, 2f, 3f, 1f), glyphs.map { it.width })
    }
}
