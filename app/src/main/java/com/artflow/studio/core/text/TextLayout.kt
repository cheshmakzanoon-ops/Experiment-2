package com.artflow.studio.core.text

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Text layout for the text tool (Phase 23).
 *
 * Line breaking, alignment, kerning and leading are all pure maths over a caller-supplied
 * measurement function. That keeps the layout engine testable on the JVM while the actual glyph
 * rasterisation happens in `data/renderer/TextLayerRenderer` on the device.
 */
object TextLayout {

    enum class Alignment(val displayName: String) {
        LEFT("Left"),
        CENTER("Center"),
        RIGHT("Right"),
        JUSTIFY("Justify")
    }

    /**
     * Text styling. Font metrics (ascent/descent) come from the platform font, but everything the
     * layout engine needs is expressed here so the engine stays platform independent.
     */
    data class TextStyle(
        val fontFamily: String = "sans-serif",
        val fontSize: Float = 64f,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strikeThrough: Boolean = false,
        /** Extra spacing between letters, in pixels. */
        val letterSpacing: Float = 0f,
        /** Extra spacing between words, in pixels. */
        val wordSpacing: Float = 0f,
        /** Line height as a multiple of the font size. */
        val lineHeight: Float = 1.2f,
        val alignment: Alignment = Alignment.LEFT,
        /** Maximum line width; `null` means "never wrap". */
        val maxWidth: Float? = null,
        /** Distance from one line's baseline to the next, in pixels. */
        val curveRadius: Float? = null
    ) {
        val lineHeightPx: Float get() = fontSize * lineHeight.coerceIn(0.5f, 4f)
    }

    /** A laid-out glyph run: where it starts and how wide it is. */
    data class PositionedText(
        val text: String,
        val x: Float,
        val y: Float,
        val width: Float
    )

    /** A single glyph placed on a curve, with the tangent rotation the renderer must apply. */
    data class CurvedGlyph(
        val text: String,
        val x: Float,
        val y: Float,
        val width: Float,
        /** Rotation in degrees around ([x], [y]) so the glyph follows the arc. */
        val rotationDegrees: Float
    )

    /** One laid-out line. */
    data class LaidOutLine(
        val text: String,
        val x: Float,
        val baselineY: Float,
        val width: Float,
        val runs: List<PositionedText>
    )

    /** The complete result of laying out a string. */
    data class LayoutResult(
        val lines: List<LaidOutLine>,
        val width: Float,
        val height: Float,
        val lineCount: Int
    ) {
        val isEmpty: Boolean get() = lines.isEmpty() || lines.all { it.text.isEmpty() }
    }

    /**
     * Lays out [text] inside a box anchored at ([originX], [originY]).
     *
     * @param measureWidth measures the rendered width of a string in pixels (the platform font).
     * @param measureAscent distance from the baseline to the top of the tallest glyph.
     * @param measureDescent distance from the baseline to the bottom of the lowest glyph.
     */
    fun layout(
        text: String,
        originX: Float,
        originY: Float,
        style: TextStyle,
        measureWidth: (String) -> Float,
        measureAscent: () -> Float,
        measureDescent: () -> Float
    ): LayoutResult {
        if (text.isEmpty()) return LayoutResult(emptyList(), 0f, 0f, 0)

        val paragraphLines = text.split('\n')
        val maxWidth = style.maxWidth
        val wrapped = mutableListOf<String>()
        paragraphLines.forEach { paragraph ->
            if (maxWidth == null) {
                wrapped += paragraph
            } else {
                wrapped += wrap(paragraph, maxWidth, style, measureWidth)
            }
        }

        val ascent = measureAscent()
        val descent = measureDescent()
        val lineHeight = style.lineHeightPx
        val widest = wrapped.maxOfOrNull { measureWidth(it) } ?: 0f

        val lines = wrapped.mapIndexed { index, lineText ->
            val lineWidth = measureWidth(lineText)
            val baselineY = originY + ascent + index * lineHeight
            val startX = when (style.alignment) {
                Alignment.LEFT, Alignment.JUSTIFY -> originX
                Alignment.CENTER -> originX + ((maxWidth ?: widest) - lineWidth) / 2f
                Alignment.RIGHT -> originX + (maxWidth ?: widest) - lineWidth
            }
            val runs = if (style.alignment == Alignment.JUSTIFY && index < wrapped.size - 1) {
                justify(lineText, measureWidth, maxWidth ?: lineWidth, startX)
            } else {
                listOf(PositionedText(lineText, startX, baselineY, lineWidth))
            }
            LaidOutLine(
                text = lineText,
                x = startX,
                baselineY = baselineY,
                width = lineWidth,
                runs = runs
            )
        }

        val height = ascent + descent + (lines.size - 1).coerceAtLeast(0) * lineHeight
        return LayoutResult(lines, widest, height, lines.size)
    }

    /**
     * Greedy word wrap. Words longer than the line box are broken at the character level so a
     * single long token can never overflow the box.
     */
    fun wrap(
        paragraph: String,
        maxWidth: Float,
        style: TextStyle,
        measureWidth: (String) -> Float
    ): List<String> {
        if (paragraph.isEmpty()) return listOf("")
        if (maxWidth <= 0f) return listOf(paragraph)

        val words = paragraph.split(' ').filter { it.isNotEmpty() }.toMutableList()
        if (words.isEmpty()) return listOf("")

        val lines = mutableListOf<String>()
        var current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) {
                lines += current.toString()
                current = StringBuilder()
            }
        }

        words.forEach { word ->
            val candidate = if (current.isEmpty()) word else "${current} $word"
            if (measureWidth(candidate) <= maxWidth) {
                current = StringBuilder(candidate)
                return@forEach
            }

            // The word does not fit: close the current line, then break the word if needed.
            flush()
            if (measureWidth(word) <= maxWidth) {
                current = StringBuilder(word)
                return@forEach
            }

            var fragment = StringBuilder()
            word.forEach { character ->
                val attempt = StringBuilder(fragment).append(character).toString()
                if (measureWidth(attempt) > maxWidth && fragment.isNotEmpty()) {
                    lines += fragment.toString()
                    fragment = StringBuilder().append(character)
                } else {
                    fragment = StringBuilder(attempt)
                }
            }
            current = fragment
        }
        flush()
        return lines.ifEmpty { listOf("") }
    }

    /** Distributes leftover width across the gaps of an already-wrapped line. */
    private fun justify(
        lineText: String,
        measureWidth: (String) -> Float,
        targetWidth: Float,
        startX: Float
    ): List<PositionedText> {
        val words = lineText.split(' ').filter { it.isNotEmpty() }
        if (words.size < 2) return listOf(PositionedText(lineText, startX, 0f, measureWidth(lineText)))

        val wordsWidth = words.sumOf { measureWidth(it).toDouble() }.toFloat()
        val gap = (targetWidth - wordsWidth) / (words.size - 1)
        var cursor = startX
        return words.map { word ->
            val width = measureWidth(word)
            val positioned = PositionedText(word, cursor, 0f, width)
            cursor += width + gap
            positioned
        }
    }

    /**
     * Positions [text] along a circular arc (Phase 23: text on path).
     *
     * @param radius positive values curve the text downwards (like a smile); negative values
     *   curve it upwards (like an arch).
     * @param centerX arc centre
     * @param centerY arc centre
     */
    fun layoutOnCurve(
        text: String,
        centerX: Float,
        centerY: Float,
        radius: Float,
        style: TextStyle,
        measureWidth: (String) -> Float
    ): List<CurvedGlyph> {
        if (text.isEmpty() || radius == 0f) return emptyList()
        val arcRadius = abs(radius)
        val totalWidth = measureWidth(text)
        // A glyph at angle theta sits at (cx + r sin(theta), cy + r cos(theta)) with the sign of
        // `radius` deciding whether the arc curves up (arch) or down (smile).
        val totalAngle = totalWidth / arcRadius
        var angle = -totalAngle / 2f
        val direction = if (radius < 0f) -1f else 1f

        return text.map { character ->
            val glyph = character.toString()
            val glyphWidth = measureWidth(glyph)
            val halfAngle = (glyphWidth / 2f) / arcRadius
            angle += halfAngle
            val x = centerX + direction * arcRadius * sin(angle)
            val y = centerY + radius * cos(angle)
            // For x = r sin(theta), y = r cos(theta) the tangent direction is atan2(-sin, cos) = -theta.
            val rotation = Math.toDegrees((-angle * direction).toDouble()).toFloat()
            angle += halfAngle
            CurvedGlyph(glyph, x, y, glyphWidth, rotation)
        }
    }

    /** Bounding box of a layout, useful for the transform handles around a text layer. */
    fun bounds(result: LayoutResult): FloatArray {
        if (result.lines.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f)
        val top = result.lines.first().baselineY
        val bottom = result.lines.last().baselineY
        val left = result.lines.minOf { it.x }
        val right = result.lines.maxOf { it.x + it.width }
        return floatArrayOf(left, top, right, bottom)
    }

    /** Length of the baseline path for "text on path" (used by the preview overlay). */
    fun pathLength(points: List<Pair<Float, Float>>): Float {
        if (points.size < 2) return 0f
        var total = 0f
        for (i in 1 until points.size) {
            val dx = points[i].first - points[i - 1].first
            val dy = points[i].second - points[i - 1].second
            total += sqrt(dx * dx + dy * dy)
        }
        return total
    }

    /** Angle of the path at [index], in degrees, for glyph rotation on an arbitrary path. */
    fun pathAngleAt(points: List<Pair<Float, Float>>, index: Int): Float {
        if (points.size < 2) return 0f
        val clamped = index.coerceIn(0, points.size - 2)
        val dx = points[clamped + 1].first - points[clamped].first
        val dy = points[clamped + 1].second - points[clamped].second
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    /** Font families offered by the picker (Android's built-in families, so no assets are needed). */
    val FONT_FAMILIES: List<String> = listOf(
        "sans-serif",
        "sans-serif-condensed",
        "sans-serif-medium",
        "serif",
        "serif-monospace",
        "monospace",
        "casual",
        "cursive",
        "sans-serif-smallcaps"
    )

    /** Sizes offered by the quick text size stepper. */
    val FONT_SIZE_STEPS: List<Float> = listOf(12f, 18f, 24f, 32f, 48f, 64f, 96f, 128f, 192f, 256f)

    /** Natural width of a wrapped layout for the "fit to text" action. */
    fun naturalWidth(result: LayoutResult): Float = max(result.width, 1f)

    /** True when the text needs at least one line break. */
    fun needsWrap(text: String, style: TextStyle, measureWidth: (String) -> Float): Boolean =
        style.maxWidth?.let { measureWidth(text) > it } ?: false

    /** Word count, shown in the text panel. */
    fun wordCount(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    /** Character count excluding line breaks. */
    fun characterCount(text: String): Int = text.replace("\n", "").length

    /** Resolves a font family name to a safe fallback if the platform does not know it. */
    fun fallbackFamily(fontFamily: String): String =
        if (fontFamily in FONT_FAMILIES) fontFamily else "sans-serif"

    /** Letter-spacing helper applied by the renderer: returns the per-glyph advance. */
    fun advanceFor(glyphWidth: Float, style: TextStyle, isSpace: Boolean): Float =
        glyphWidth + style.letterSpacing + if (isSpace) style.wordSpacing else 0f

    /** Compute the ascent used by the layout when the platform font has not been loaded yet. */
    fun estimatedAscent(style: TextStyle): Float = style.fontSize * 0.78f

    /** Compute the descent used by the layout when the platform font has not been loaded yet. */
    fun estimatedDescent(style: TextStyle): Float = style.fontSize * 0.22f
}
