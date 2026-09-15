package com.artflow.studio.data.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.Layer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renders the vector stroke model into raster [Bitmap]s.
 *
 * The live canvas is drawn by [com.artflow.studio.data.renderer.opengl.OpenGLCanvasRenderer];
 * this rasterizer is the offline counterpart used for exporting, thumbnails and persistence.
 * Both consume the same [Stroke] data, so what is saved and exported always matches what the
 * artist drew.
 */
@Singleton
class CanvasRasterizer @Inject constructor() {

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val compositePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }

    /**
     * Flatten a layer stack into a single bitmap.
     *
     * Layers are composited bottom-up (lowest [Layer.index] first) honouring per-layer
     * opacity and the blend modes that Android's [PorterDuff] modes can express.
     *
     * @param includeHidden when true, hidden layers are still composited (used by "export all").
     */
    fun rasterizeLayers(
        layers: List<Layer>,
        width: Int,
        height: Int,
        backgroundColor: Int = Color.WHITE,
        includeHidden: Boolean = false
    ): Bitmap {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)

        val output = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        // A fully transparent background colour means "leave the canvas transparent".
        if (Color.alpha(backgroundColor) != 0) {
            canvas.drawColor(backgroundColor)
        }

        val scratch = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)

        layers
            .filter { includeHidden || it.isVisible }
            .sortedBy { it.index }
            .forEach { layer ->
                val layerBitmap = rasterizeLayer(layer, safeWidth, safeHeight, scratch)
                compositeLayer(canvas, layerBitmap, layer, safeWidth, safeHeight)
                if (layerBitmap !== scratch) layerBitmap.recycle()
            }

        scratch.recycle()
        return output
    }

    /**
     * Render a single layer's strokes into a transparent bitmap.
     *
     * @param scratch a reusable canvas-sized bitmap used to keep a stroke's internal
     *   segments from stacking their own alpha. Callers must not retain it.
     */
    fun rasterizeLayer(
        layer: Layer,
        width: Int,
        height: Int,
        scratch: Bitmap? = null
    ): Bitmap {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)

        if (layer.strokes.isEmpty()) return bitmap

        val reusable = scratch
            ?.takeIf { it.width == safeWidth && it.height == safeHeight && !it.isRecycled }
        val canvas = Canvas(bitmap)
        layer.strokes.forEach { drawStroke(canvas, it, reusable) }
        return bitmap
    }

    /**
     * Paint a single stroke. Width varies per segment (pressure / taper) while the overall
     * stroke opacity is applied once, so a semi-transparent stroke does not darken itself
     * where segments overlap.
     */
    fun drawStroke(canvas: Canvas, stroke: Stroke, scratch: Bitmap? = null) {
        val points = stroke.points
        if (points.isEmpty()) return

        val alpha = strokeAlpha(stroke)
        if (alpha <= 0) return

        if (alpha >= 255 || scratch == null) {
            drawStrokeSegments(canvas, points, stroke, alpha)
        } else {
            scratch.eraseColor(Color.TRANSPARENT)
            val scratchCanvas = Canvas(scratch)
            val opaque = (stroke.color and 0x00FFFFFF) or (0xFF shl 24)
            drawStrokeSegments(scratchCanvas, points, stroke, 255, opaque)
            compositePaint.alpha = alpha
            canvas.drawBitmap(scratch, 0f, 0f, compositePaint)
        }
    }

    private fun drawStrokeSegments(
        canvas: Canvas,
        points: List<StrokePoint>,
        stroke: Stroke,
        alpha: Int,
        overrideColor: Int? = null
    ) {
        val params = stroke.brushParams
        strokePaint.color = overrideColor ?: stroke.color
        strokePaint.alpha = alpha

        if (points.size == 1) {
            val point = points.first()
            val size = params.calculateEffectiveSize(point.pressure).coerceAtLeast(1f)
            fillPaint.color = strokePaint.color
            fillPaint.alpha = alpha
            canvas.drawCircle(point.x, point.y, size / 2f, fillPaint)
            return
        }

        strokePaint.style = Paint.Style.STROKE
        for (i in 1 until points.size) {
            val previous = points[i - 1]
            val current = points[i]
            val sizeA = params.calculateEffectiveSize(previous.pressure).coerceAtLeast(1f)
            val sizeB = params.calculateEffectiveSize(current.pressure).coerceAtLeast(1f)
            strokePaint.strokeWidth = (sizeA + sizeB) / 2f

            // Skip zero-length segments (duplicate touch samples) - round caps make them
            // visible as a dab, so draw a dot instead of a degenerate line.
            if (previous.x == current.x && previous.y == current.y) {
                strokePaint.style = Paint.Style.FILL
                canvas.drawCircle(current.x, current.y, sizeB / 2f, strokePaint)
                strokePaint.style = Paint.Style.STROKE
                continue
            }

            canvas.drawLine(previous.x, previous.y, current.x, current.y, strokePaint)
        }
    }

    private fun compositeLayer(
        canvas: Canvas,
        layerBitmap: Bitmap,
        layer: Layer,
        width: Int,
        height: Int
    ) {
        val opacity = layer.opacity.coerceIn(0f, 1f)
        if (opacity <= 0f) return

        compositePaint.reset()
        compositePaint.isAntiAlias = true
        compositePaint.isFilterBitmap = true
        compositePaint.alpha = (opacity * 255).toInt().coerceIn(0, 255)
        compositePaint.xfermode = xfermodeFor(layer.blendMode)

        // saveLayer is required so the xfermode applies to the layer as a whole rather than
        // to each individual drawing call.
        val restoreCount = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawBitmap(layerBitmap, 0f, 0f, compositePaint)
        canvas.restoreToCount(restoreCount)
        compositePaint.xfermode = null
    }

    /**
     * Map a domain [BlendMode] onto a [PorterDuff.Mode].
     *
     * Only the modes supported by every API level we target (26+) are mapped. The remaining
     * modes fall back to normal compositing - the UI marks them as approximated rather than
     * silently producing a different result on older devices.
     */
    private fun xfermodeFor(blendMode: BlendMode): PorterDuffXfermode? {
        val mode = when (blendMode) {
            BlendMode.MULTIPLY -> PorterDuff.Mode.MULTIPLY
            BlendMode.SCREEN -> PorterDuff.Mode.SCREEN
            BlendMode.OVERLAY -> PorterDuff.Mode.OVERLAY
            BlendMode.DARKEN -> PorterDuff.Mode.DARKEN
            BlendMode.LIGHTEN -> PorterDuff.Mode.LIGHTEN
            else -> null
        }
        return mode?.let { PorterDuffXfermode(it) }
    }

    /**
     * Blend modes this rasterizer cannot express with [PorterDuff] on API 26-28.
     * Used by the UI to flag approximated output.
     */
    fun isBlendModeApproximated(blendMode: BlendMode): Boolean = xfermodeFor(blendMode) == null &&
        blendMode != BlendMode.NORMAL && blendMode != BlendMode.PASS_THROUGH

    /**
     * Overall opacity of a stroke, derived from the brush dynamics applied to its points.
     */
    private fun strokeAlpha(stroke: Stroke): Int {
        val params = stroke.brushParams
        val points = stroke.points
        if (points.isEmpty()) return (params.opacity * 255).toInt().coerceIn(0, 255)

        val total = points.sumOf { params.calculateEffectiveOpacity(it.pressure).toDouble() }
        val average = total / points.size
        return (average * 255).toInt().coerceIn(0, 255)
    }

    /**
     * Produce a thumbnail that fits inside [maxWidth] x [maxHeight], preserving aspect ratio.
     */
    fun createThumbnail(source: Bitmap, maxWidth: Int = 512, maxHeight: Int = 512): Bitmap {
        val scale = minOf(
            maxWidth.toFloat() / source.width.coerceAtLeast(1),
            maxHeight.toFloat() / source.height.coerceAtLeast(1)
        ).coerceAtMost(1f)

        if (scale >= 1f) return source
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }
}
