package com.artflow.studio.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextJustifyTest {
    /** Every character is 10 px wide, spaces included. */
    private val measure: (String) -> Float = { it.length * 10f }

    @Test
    fun wordsSpreadSoTheLineFillsTheBox() {
        val runs = TextLayout.justify("aa bb cc", measure, 100f, 5f)
        assertEquals(listOf("aa", "bb", "cc"), runs.map { it.text })
        assertEquals(5f, runs.first().x, 1e-4f)
        val last = runs.last()
        assertEquals(105f, last.x + last.width, 1e-4f)
        // The two gaps are equal.
        assertEquals(runs[1].x - (runs[0].x + runs[0].width), runs[2].x - (runs[1].x + runs[1].width), 1e-4f)
    }

    @Test
    fun aLineThatAlreadyFillsOrOverflowsTheBoxKeepsItsNaturalSpacing() {
        val runs = TextLayout.justify("aaaa bbbb", measure, 60f, 0f)
        // 9 characters measure 90 px, so the natural one-space gap of 10 px stays.
        assertEquals(0f, runs[0].x, 1e-4f)
        assertEquals(50f, runs[1].x, 1e-4f)
    }

    @Test
    fun aSingleWordIsNotStretched() {
        val runs = TextLayout.justify("alone", measure, 200f, 7f)
        assertEquals(1, runs.size)
        assertEquals(7f, runs[0].x, 1e-4f)
        assertTrue(runs[0].width <= 50f)
    }

    @Test
    fun repeatedSpacesBetweenWordsDoNotCreateEmptyRuns() {
        val runs = TextLayout.justify("aa   bb", measure, 80f, 0f)
        assertEquals(listOf("aa", "bb"), runs.map { it.text })
        assertEquals(80f, runs.last().x + runs.last().width, 1e-4f)
    }
}
