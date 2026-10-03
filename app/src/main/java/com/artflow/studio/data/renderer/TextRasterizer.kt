package com.artflow.studio.data.renderer

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.text.TextLayerContent
import com.artflow.studio.core.text.TextLayout
import com.artflow.studio.data.local.FontLibrary

/** Draws a text layer's glyphs with the platform fonts, wrapped and aligned by [TextLayout]. */
object TextRasterizer {
    fun render(
        width: Int,
        height: Int,
        content: TextLayerContent,
    ): PixelBuffer {
        val style = content.style
        val buffer = PixelBuffer(width, height)
        val bitmap = BitmapPixelBridge.toBitmap(buffer)
        val canvas = Canvas(bitmap)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = content.color
                textSize = style.fontSize
                val weight =
                    when {
                        style.bold && style.italic -> Typeface.BOLD_ITALIC
                        style.bold -> Typeface.BOLD
                        style.italic -> Typeface.ITALIC
                        else -> Typeface.NORMAL
                    }
                // An imported font is used when its file is still there; otherwise a system family.
                typeface =
                    FontLibrary.typeface(style.fontFamily)?.let { Typeface.create(it, weight) }
                        ?: Typeface.create(TextLayout.fallbackFamily(style.fontFamily), weight)
                textAlign = Paint.Align.LEFT
                if (style.letterSpacing != 0f && style.fontSize > 0f) letterSpacing = style.letterSpacing / style.fontSize
                if (style.underline) isUnderlineText = true
                if (style.strikeThrough) isStrikeThruText = true
                if (style.outline) {
                    this.style = Paint.Style.STROKE
                    strokeWidth = (style.fontSize / OUTLINE_DIVISOR).coerceAtLeast(1f)
                }
            }
        val text = TextLayout.displayText(content.text, style)
        if (style.vertical) {
            drawVertical(canvas, paint, text, content)
            val result = BitmapPixelBridge.fromBitmap(bitmap)
            bitmap.recycle()
            return result
        }
        val maxWidth = style.maxWidth ?: (width - content.x - 8f)
        val lines = mutableListOf<String>()
        text.split('\n').forEach { paragraph ->
            lines += TextLayout.wrap(paragraph, maxWidth, style) { candidate -> paint.measureText(candidate) }
        }
        var baseline = content.y + TextLayout.estimatedAscent(style)
        lines.forEach { line ->
            val measured = paint.measureText(line)
            val startX =
                when (style.alignment) {
                    TextLayout.Alignment.CENTER -> content.x + (maxWidth - measured) / 2f
                    TextLayout.Alignment.RIGHT -> content.x + maxWidth - measured
                    else -> content.x
                }
            canvas.drawText(line, startX, baseline, paint)
            baseline += style.lineHeightPx
        }
        val result = BitmapPixelBridge.fromBitmap(bitmap)
        bitmap.recycle()
        return result
    }

    /** Vertical text: each paragraph is a column of centred letters, columns running left to right. */
    private fun drawVertical(
        canvas: Canvas,
        paint: Paint,
        text: String,
        content: TextLayerContent,
    ) {
        val style = content.style
        var columnX = content.x
        text.split('\n').forEach { paragraph ->
            var baseline = content.y + TextLayout.estimatedAscent(style)
            TextLayout.glyphs(paragraph).forEach { glyph ->
                canvas.drawText(glyph, columnX + (style.fontSize - paint.measureText(glyph)) / 2f, baseline, paint)
                baseline += style.lineHeightPx
            }
            columnX += style.lineHeightPx
        }
    }

    private const val OUTLINE_DIVISOR = 18f
}
