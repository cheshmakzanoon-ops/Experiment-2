package com.artflow.studio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.text.TextLayerContent
import com.artflow.studio.core.text.TextLayout
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.data.renderer.TextRasterizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises deterministic grapheme boundaries and platform text spacing, including on API 26. */
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
    fun pinnedBoundariesHandleRegionalPairingJoinerContextAndIndicConjunctsOnTheDeviceRuntime() {
        val clusters = listOf("🇦🇧", "🇨🇩", "🇪", "a\u200D", "b", "👩🏽‍💻", "\u0915\u094D\u0937")
        val text = clusters.joinToString("")
        assertEquals(clusters, TextLayout.glyphs(text))
        assertEquals(clusters, TextLayout.wrap(text, 1f, TextLayout.TextStyle()) { TextLayout.glyphs(it).size.toFloat() })
        assertEquals(listOf("a\u200D", "💻"), TextLayout.glyphs("a\u200D💻"))
        assertEquals(listOf("👩\u200D", "a"), TextLayout.glyphs("👩\u200Da"))
        assertEquals(listOf("\u0915\u094D\u200C", "\u0937"), TextLayout.glyphs("\u0915\u094D\u200C\u0937"))
    }

    @Test
    fun controlsRecentMarksAndMalformedUtf16KeepTheirPinnedBoundariesOnTheDeviceRuntime() {
        val recentMark = String(Character.toChars(0x1E4EC))
        val supplementarySelector = String(Character.toChars(0xE0100))
        val clusters = listOf("a$recentMark", "❤\uFE0F", "x$supplementarySelector", "\r\n", "\u0301", "\t", "\uD800", "x", "\uDC00")
        val text = clusters.joinToString("")
        assertEquals(clusters, TextLayout.glyphs(text))
        assertEquals(text, TextLayout.glyphs(text).joinToString(""))
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

    @Test
    fun renderedWordSpacingParticipatesInSoftWrappingForBothSettings() {
        val width = textPaint().measureText("aa bb") + 10f
        listOf(24f to 0f, 0f to 24f, 6f to 18f).forEach { (styleSpacing, layerSpacing) ->
            val style = TextLayout.TextStyle(fontFamily = "monospace", fontSize = 32f, maxWidth = width, wordSpacing = styleSpacing)
            val content = TextLayerContent("aa bb", style, Color.BLACK, 20f, 20f, wordSpacing = layerSpacing)
            assertArrayEquals(render(content.copy(text = "aa\nbb")), render(content))
        }
    }

    @Test
    fun styleAndLayerSpacingAreAddedOnceForAlignedPlatformDrawing() {
        val paint = textPaint().apply { letterSpacing = 6f / textSize }
        val advance = paint.measureText("AA ") + 20f
        val total = advance + paint.measureText("BB")
        listOf(TextLayout.Alignment.LEFT, TextLayout.Alignment.CENTER, TextLayout.Alignment.RIGHT).forEach { alignment ->
            val style =
                TextLayout.TextStyle(
                    fontFamily = "monospace",
                    fontSize = 32f,
                    letterSpacing = 3f,
                    wordSpacing = 7f,
                    maxWidth = 300f,
                    alignment = alignment,
                )
            val content = TextLayerContent("AA BB", style, Color.BLACK, 20f, 20f, tracking = 2f, kerning = 1f, wordSpacing = 13f)
            val start =
                when (alignment) {
                    TextLayout.Alignment.CENTER -> content.x + (300f - total) / 2f
                    TextLayout.Alignment.RIGHT -> content.x + 300f - total
                    else -> content.x
                }
            val expected =
                drawReference { canvas ->
                    val baseline = content.y + TextLayout.estimatedAscent(style)
                    canvas.drawText("AA ", start, baseline, paint)
                    canvas.drawText("BB", start + advance, baseline, paint)
                }
            assertArrayEquals(expected, render(content))
        }
    }

    @Test
    fun justifiedPlatformDrawingPreservesExpandedIndentationAndRepeatedGaps() {
        val paint = textPaint()
        val gap = paint.measureText(" ") + 10f
        val firstAdvance = paint.measureText("aa ") + 10f
        val secondAdvance = paint.measureText("bb ") + 10f
        val natural = 2f * gap + firstAdvance + 2f * gap + secondAdvance + paint.measureText("cc")
        val style =
            TextLayout.TextStyle(
                fontFamily = "monospace",
                fontSize = 32f,
                wordSpacing = 4f,
                maxWidth = natural + 40f,
                alignment = TextLayout.Alignment.JUSTIFY,
            )
        val content = TextLayerContent("  aa   bb cc ddddddddd", style, Color.BLACK, 20f, 20f, wordSpacing = 6f)
        val expected =
            drawReference { canvas ->
                val baseline = content.y + TextLayout.estimatedAscent(style)
                val firstX = content.x + 2f * gap
                canvas.drawText("aa", firstX, baseline, paint)
                canvas.drawText("bb", firstX + firstAdvance + 2f * gap + 20f, baseline, paint)
                canvas.drawText("cc", firstX + firstAdvance + 2f * gap + secondAdvance + 40f, baseline, paint)
                canvas.drawText("ddddddddd", content.x, baseline + style.lineHeightPx, paint)
            }
        assertArrayEquals(expected, render(content))
    }

    @Test
    fun aSingleWordJustifiedLineRetainsItsExpandedIndentation() {
        val style =
            TextLayout.TextStyle(
                fontFamily = "monospace",
                fontSize = 32f,
                wordSpacing = 4f,
                maxWidth = textPaint().measureText("  aa") + 21f,
                alignment = TextLayout.Alignment.JUSTIFY,
            )
        val content = TextLayerContent("  aa bb", style, Color.BLACK, 20f, 20f, wordSpacing = 6f)
        val expected = content.copy(text = "  aa\nbb", style = style.copy(alignment = TextLayout.Alignment.LEFT))
        assertArrayEquals(render(expected), render(content))
    }

    @Test
    fun curvedPlatformDrawingUsesTheSameCombinedSpacing() {
        val style = TextLayout.TextStyle(fontFamily = "monospace", fontSize = 32f, maxWidth = 300f, wordSpacing = 7f)
        val content = TextLayerContent("AA BB", style, Color.BLACK, 20f, 60f, curveAmount = 0.4f, wordSpacing = 13f)
        val combined = render(content)
        assertArrayEquals(combined, render(content.copy(style = style.copy(wordSpacing = 20f), wordSpacing = 0f)))
        assertArrayEquals(combined, render(content.copy(style = style.copy(wordSpacing = 0f), wordSpacing = 20f)))
        assertFalse(combined.contentEquals(render(content.copy(style = style.copy(wordSpacing = 0f), wordSpacing = 0f))))
    }

    private fun textPaint(): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 32f
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        }

    private fun render(content: TextLayerContent): IntArray = TextRasterizer.render(500, 200, content).pixels

    private fun drawReference(draw: (Canvas) -> Unit): IntArray {
        val bitmap = Bitmap.createBitmap(500, 200, Bitmap.Config.ARGB_8888)
        return try {
            draw(Canvas(bitmap))
            BitmapPixelBridge.fromBitmap(bitmap).pixels
        } finally {
            bitmap.recycle()
        }
    }
}
