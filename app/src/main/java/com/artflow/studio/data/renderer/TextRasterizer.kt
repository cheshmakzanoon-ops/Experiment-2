package com.artflow.studio.data.renderer

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.text.TextLayerContent
import com.artflow.studio.core.text.TextLayout

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
                typeface =
                    Typeface.create(
                        TextLayout.fallbackFamily(style.fontFamily),
                        when {
                            style.bold && style.italic -> Typeface.BOLD_ITALIC
                            style.bold -> Typeface.BOLD
                            style.italic -> Typeface.ITALIC
                            else -> Typeface.NORMAL
                        },
                    )
                textAlign = Paint.Align.LEFT
                if (style.letterSpacing != 0f && style.fontSize > 0f) letterSpacing = style.letterSpacing / style.fontSize
                if (style.underline) isUnderlineText = true
                if (style.strikeThrough) isStrikeThruText = true
            }
        val maxWidth = style.maxWidth ?: (width - content.x - 8f)
        val lines = mutableListOf<String>()
        content.text.split('\n').forEach { paragraph ->
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
}
