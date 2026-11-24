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
        val hasMatrixTransform =
            content.rotationDegrees != 0f ||
                content.scaleX != 1f ||
                content.scaleY != 1f ||
                content.baselineShift != 0f
        if (hasMatrixTransform) {
            canvas.save()
            canvas.translate(content.x, content.y + content.baselineShift)
            if (content.rotationDegrees != 0f) canvas.rotate(content.rotationDegrees)
            if (content.scaleX != 1f || content.scaleY != 1f) canvas.scale(content.scaleX, content.scaleY)
            canvas.translate(-content.x, -content.y)
        }
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
                val totalLetterSpacing = style.letterSpacing + content.tracking + content.kerning
                if (totalLetterSpacing != 0f && style.fontSize > 0f) letterSpacing = totalLetterSpacing / style.fontSize
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
            if (hasMatrixTransform) canvas.restore()
            val result = BitmapPixelBridge.fromBitmap(bitmap)
            bitmap.recycle()
            return result
        }
        val maxWidth = style.maxWidth ?: (width - content.x - 8f)
        // Justified text stretches each wrapped line except a paragraph's last one, which keeps its natural width.
        val lines = mutableListOf<Pair<String, Boolean>>()
        text.split('\n').forEach { paragraph ->
            val wrapped = TextLayout.wrap(paragraph, maxWidth, style) { candidate -> paint.measureText(candidate) }
            wrapped.forEachIndexed { index, line -> lines += line to (index < wrapped.lastIndex) }
        }
        var baseline = content.y + TextLayout.estimatedAscent(style)
        lines.forEach { (line, stretch) ->
            drawLine(
                canvas,
                paint,
                line,
                Placement(
                    content.x,
                    maxWidth,
                    baseline,
                    style.alignment,
                    stretch && style.alignment == TextLayout.Alignment.JUSTIFY && style.maxWidth != null,
                    content.curveAmount,
                    content.wordSpacing,
                ),
            )
            baseline += style.lineHeightPx
        }
        if (hasMatrixTransform) canvas.restore()
        val result = BitmapPixelBridge.fromBitmap(bitmap)
        bitmap.recycle()
        return result
    }

    private class Placement(
        val x: Float,
        val boxWidth: Float,
        val baseline: Float,
        val alignment: TextLayout.Alignment,
        val justified: Boolean,
        val curveAmount: Float = 0f,
        val wordSpacing: Float = 0f,
    )

    private fun drawLine(
        canvas: Canvas,
        paint: Paint,
        line: String,
        at: Placement,
    ) {
        if (kotlin.math.abs(at.curveAmount) > 0.001f && line.isNotEmpty()) {
            drawCurvedLine(canvas, paint, line, at)
            return
        }
        if (at.justified) {
            TextLayout.justify(line, { paint.measureText(it) }, at.boxWidth, at.x).forEach {
                canvas.drawText(it.text, it.x, at.baseline, paint)
            }
            return
        }
        val words = if (at.wordSpacing != 0f) line.split(' ') else null
        if (words != null && words.size > 1) {
            val spaceWidth = paint.measureText(" ") + at.wordSpacing
            val totalWidth = words.sumOf { paint.measureText(it).toDouble() }.toFloat() + spaceWidth * (words.size - 1)
            var cursor =
                when (at.alignment) {
                    TextLayout.Alignment.CENTER -> at.x + (at.boxWidth - totalWidth) / 2f
                    TextLayout.Alignment.RIGHT -> at.x + at.boxWidth - totalWidth
                    else -> at.x
                }
            words.forEach { word ->
                canvas.drawText(word, cursor, at.baseline, paint)
                cursor += paint.measureText(word) + spaceWidth
            }
            return
        }
        val measured = paint.measureText(line)
        val startX =
            when (at.alignment) {
                TextLayout.Alignment.CENTER -> at.x + (at.boxWidth - measured) / 2f
                TextLayout.Alignment.RIGHT -> at.x + at.boxWidth - measured
                else -> at.x
            }
        canvas.drawText(line, startX, at.baseline, paint)
    }

    /** Renders [line] along a smooth circular arc determined by [Placement.curveAmount] (-1..1). */
    private fun drawCurvedLine(
        canvas: Canvas,
        paint: Paint,
        line: String,
        at: Placement,
    ) {
        val measured = paint.measureText(line).coerceAtLeast(1f)
        val startX =
            when (at.alignment) {
                TextLayout.Alignment.CENTER -> at.x + (at.boxWidth - measured) / 2f
                TextLayout.Alignment.RIGHT -> at.x + at.boxWidth - measured
                else -> at.x
            }
        val clamped = at.curveAmount.coerceIn(-1f, 1f)
        val sweepDegrees = clamped * 180f
        val sweepRadians = Math.toRadians(kotlin.math.abs(sweepDegrees).toDouble()).toFloat().coerceAtLeast(0.01f)
        val radius = (measured / sweepRadians).coerceAtLeast(paint.textSize)
        val midX = startX + measured / 2f
        val path = android.graphics.Path()
        if (clamped > 0f) {
            val cy = at.baseline + radius
            val rect = android.graphics.RectF(midX - radius, cy - radius, midX + radius, cy + radius)
            path.addArc(rect, -90f - sweepDegrees / 2f, sweepDegrees)
        } else {
            val cy = at.baseline - radius
            val rect = android.graphics.RectF(midX - radius, cy - radius, midX + radius, cy + radius)
            path.addArc(rect, 90f - sweepDegrees / 2f, sweepDegrees)
        }
        canvas.drawTextOnPath(line, path, 0f, 0f, paint)
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
