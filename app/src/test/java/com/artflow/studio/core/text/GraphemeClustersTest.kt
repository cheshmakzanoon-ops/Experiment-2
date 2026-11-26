package com.artflow.studio.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphemeClustersTest {
    @Test
    fun allPinnedUnicode17ConformanceCasesMatch() {
        val resource = requireNotNull(javaClass.getResourceAsStream("/unicode/GraphemeBreakTest-17.0.0.txt"))
        var cases = 0
        resource.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEachIndexed { index, line ->
                val specification = line.substringBefore('#').trim()
                if (specification.isNotEmpty()) {
                    val expected = parseClusters(specification)
                    assertEquals("Unicode 17.0.0 line ${index + 1}: $specification", expected, TextLayout.glyphs(expected.joinToString("")))
                    cases++
                }
            }
        }
        assertEquals("The complete pinned conformance fixture must run", 766, cases)
    }

    @Test
    fun regionalIndicatorsPairFromTheStartOfEachUninterruptedRun() {
        assertEquals(listOf("🇦🇧", "🇨🇩", "🇪"), TextLayout.glyphs("🇦🇧🇨🇩🇪"))
        assertEquals(listOf("🇦\u0301", "🇧🇨"), TextLayout.glyphs("🇦\u0301🇧🇨"))
        assertEquals(listOf("🇦🇧", "x", "🇨🇩", "🇪"), TextLayout.glyphs("🇦🇧x🇨🇩🇪"))
    }

    @Test
    fun modifiersVariationSelectorsKeycapsAndTagSequencesStayAttached() {
        val supplementarySelector = codePoints(0x20000, 0xE0100)
        val emojiTagSequence = codePoints(0x1F3F4, 0xE0067, 0xE0062, 0xE007F)
        val recentCombiningMark = "a" + codePoints(0x1E4EC)
        val clusters = listOf("e\u0301", "❤\uFE0E", "❤\uFE0F", "👍🏽", "1\uFE0F\u20E3", supplementarySelector)
        assertEquals(clusters, TextLayout.glyphs(clusters.joinToString("")))
        assertEquals(listOf(emojiTagSequence, recentCombiningMark), TextLayout.glyphs(emojiTagSequence + recentCombiningMark))
    }

    @Test
    fun emojiZwjRuleRequiresPictographsOnBothSidesAndOnlyExtendBeforeTheJoiner() {
        assertEquals(listOf("a\u200D", "b"), TextLayout.glyphs("a\u200Db"))
        assertEquals(listOf("a\u200D", "💻"), TextLayout.glyphs("a\u200D💻"))
        assertEquals(listOf("👩\u200D", "a"), TextLayout.glyphs("👩\u200Da"))
        assertEquals(listOf("👩\u200D\u200D", "💻"), TextLayout.glyphs("👩\u200D\u200D💻"))
        assertEquals(listOf("👩\u0903\u200D", "💻"), TextLayout.glyphs("👩\u0903\u200D💻"))
        assertEquals(listOf("👩\u200D\u0301", "💻"), TextLayout.glyphs("👩\u200D\u0301💻"))
        val clusters = listOf("👩🏽\u0301\u200D💻", "👨‍👩‍👧‍👦")
        assertEquals(clusters, TextLayout.glyphs(clusters.joinToString("")))
    }

    @Test
    fun controlsBreakBeforeExtendersWhileCrLfRemainsOneCluster() {
        val clusters = listOf("a\u0301", "\r\n", "\u0301", "\t", "\u200D", "\u0000", "b", "\u2028", "\uFE0F")
        assertEquals(clusters, TextLayout.glyphs(clusters.joinToString("")))
        assertEquals(listOf("\u0600", "\n"), TextLayout.glyphs("\u0600\n"))
    }

    @Test
    fun indicConjunctsRequireAConsonantAndLinkerWithoutAnInterruptingNonJoiner() {
        val conjuncts = listOf("\u0915\u094D\u0937", "\u0915\u093C\u094D\u200D\u0937")
        conjuncts.forEach { assertEquals(listOf(it), TextLayout.glyphs(it)) }
        assertEquals(listOf("\u0915\u093C", "\u0937"), TextLayout.glyphs("\u0915\u093C\u0937"))
        assertEquals(listOf("a\u094D", "\u0915"), TextLayout.glyphs("a\u094D\u0915"))
        assertEquals(listOf("\u0915\u094D\u200C", "\u0937"), TextLayout.glyphs("\u0915\u094D\u200C\u0937"))
    }

    @Test
    fun hangulAndPrependRulesKeepTheirClustersWithoutChangingWhitespaceClassification() {
        val clusters = listOf("\u1100\u1161\u11A8", "\uAC00\u11A8", "\u0600 ", "a", " \u0301", "b")
        assertEquals(clusters, TextLayout.glyphs(clusters.joinToString("")))
        val text = "\u0600 a \u0301b c"
        val style = TextLayout.TextStyle(wordSpacing = 2f)
        val measure: (String) -> Float = { TextLayout.glyphs(it).size.toFloat() }
        val runs = TextLayout.wordSpacedRuns(text, style.wordSpacing, measure)
        assertEquals(listOf("\u0600 a \u0301b ", "c"), runs.map { it.text })
        assertEquals(text, runs.joinToString("") { it.text })
        assertEquals(listOf("\u0600 a \u0301b", "c"), TextLayout.wrap(text, 5f, style, measure))
    }

    @Test
    fun malformedUtf16IsPreservedWithoutSplittingValidSurrogatePairs() {
        val clusters = listOf("\uD83D", "x", "\uDC00", "😀", "\uD800\u0301", "\uDC00")
        val text = clusters.joinToString("")
        assertEquals(clusters, TextLayout.glyphs(text))
        assertEquals(text, TextLayout.glyphs(text).joinToString(""))
        assertEquals(clusters, TextLayout.wrap(text, 1f, TextLayout.TextStyle()) { TextLayout.glyphs(it).size.toFloat() })
        assertEquals(emptyList<String>(), TextLayout.glyphs(""))
    }

    @Test
    fun longExtenderAndRegionalIndicatorRunsKeepTheSameBoundaries() {
        val marks = "\u0301".repeat(4096)
        val emoji = "👩$marks\u200D💻"
        val ordinary = "a$marks\u200D"
        val conjunct = "\u0915$marks\u094D$marks\u0937"
        val clusters = listOf(emoji, ordinary, "b", conjunct)
        assertEquals(clusters, TextLayout.glyphs(clusters.joinToString("")))
        val flags = TextLayout.glyphs("🇦".repeat(8193))
        assertEquals(4097, flags.size)
        assertTrue(flags.dropLast(1).all { it == "🇦🇦" })
        assertEquals("🇦", flags.last())
    }

    private fun codePoints(vararg values: Int): String = buildString { values.forEach { appendCodePoint(it) } }

    private fun parseClusters(specification: String): List<String> {
        val result = mutableListOf<String>()
        val cluster = StringBuilder()
        specification.split(Regex("\\s+")).forEach { token ->
            when (token) {
                "÷" -> {
                    if (cluster.isNotEmpty()) {
                        result += cluster.toString()
                        cluster.setLength(0)
                    }
                }
                "×" -> Unit
                else -> cluster.appendCodePoint(token.toInt(16))
            }
        }
        assertEquals("The fixture must end at a grapheme boundary", 0, cluster.length)
        return result
    }
}
