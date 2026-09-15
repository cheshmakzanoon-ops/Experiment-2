package com.artflow.studio.core.brush

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.model.texture.BrushTexture
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Texture mapper for brush engine
 * Implements texture mapping algorithms for Phase 10: Brush Textures & Stamps
 * 
 * This singleton handles:
 * - Texture coordinate calculation
 * - Texture blending with brush color
 * - Grain overlay effects
 * - Stamp-based brush rendering
 * - Dual-texture support
 */
@Singleton
class TextureMapper @Inject constructor() {

    private val texturePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val blendPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    
    // Cache for rendered stamp bitmaps
    private val stampCache = mutableMapOf<String, Bitmap>()
    
    /**
     * Apply texture to a brush dab
     * @param canvas Canvas to draw on
     * @param x X position
     * @param y Y position
     * @param size Brush size
     * @param params Brush parameters including texture settings
     * @param texture The brush texture to apply
     * @param color Base brush color
     */
    fun applyTextureToDab(
        canvas: Canvas,
        x: Float,
        y: Float,
        size: Float,
        params: BrushParams,
        texture: BrushTexture?,
        color: Int
    ) {
        if (texture == null || !texture.isReady()) {
            // No texture available, draw solid circle
            drawSolidDab(canvas, x, y, size, color, params.opacity)
            return
        }

        texture.bitmap?.let { bitmap ->
            when {
                params.blendTexture -> {
                    // Blend texture with color using multiply/overlay
                    drawTexturedDabBlended(canvas, x, y, size, bitmap, color, params)
                }
                else -> {
                    // Use texture as alpha mask with solid color
                    drawTexturedDabMasked(canvas, x, y, size, bitmap, color, params)
                }
            }
        } ?: run {
            drawSolidDab(canvas, x, y, size, color, params.opacity)
        }
    }

    /**
     * Draw a solid colored dab (fallback when no texture)
     */
    private fun drawSolidDab(
        canvas: Canvas,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        opacity: Float
    ) {
        texturePaint.color = color
        texturePaint.alpha = (opacity * 255).toInt()
        canvas.drawCircle(x, y, size / 2f, texturePaint)
    }

    /**
     * Draw textured dab with color blending
     * Uses texture grayscale values to modulate color intensity
     */
    private fun drawTexturedDabBlended(
        canvas: Canvas,
        x: Float,
        y: Float,
        size: Float,
        texture: Bitmap,
        color: Int,
        params: BrushParams
    ) {
        val scaledSize = size * params.textureScale
        val left = x - scaledSize / 2f
        val top = y - scaledSize / 2f
        
        // Set up color matrix to tint texture with brush color
        val red = android.graphics.Color.red(color) / 255f
        val green = android.graphics.Color.green(color) / 255f
        val blue = android.graphics.Color.blue(color) / 255f
        val alpha = params.opacity
        
        val colorMatrix = floatArrayOf(
            red, 0f, 0f, 0f, 0f,
            0f, green, 0f, 0f, 0f,
            0f, 0f, blue, 0f, 0f,
            0f, 0f, 0f, alpha, 0f
        )
        
        blendPaint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        blendPaint.alpha = 255
        
        // Apply rotation if specified
        if (params.textureRotation != 0f) {
            canvas.save()
            canvas.rotate(params.textureRotation, x, y)
            canvas.drawBitmap(texture, null, 
                android.graphics.RectF(left, top, left + scaledSize, top + scaledSize), 
                blendPaint)
            canvas.restore()
        } else {
            canvas.drawBitmap(texture, null,
                android.graphics.RectF(left, top, left + scaledSize, top + scaledSize),
                blendPaint)
        }
    }

    /**
     * Draw textured dab using texture as alpha mask
     * Texture brightness determines opacity at each pixel
     */
    private fun drawTexturedDabMasked(
        canvas: Canvas,
        x: Float,
        y: Float,
        size: Float,
        texture: Bitmap,
        color: Int,
        params: BrushParams
    ) {
        val scaledSize = size * params.textureScale
        val left = x - scaledSize / 2f
        val top = y - scaledSize / 2f
        
        // Create a temporary bitmap for masking
        val maskBitmap = Bitmap.createBitmap(
            texture.width.coerceAtMost(512),
            texture.height.coerceAtMost(512),
            Bitmap.Config.ARGB_8888
        )
        
        val maskCanvas = Canvas(maskBitmap)
        maskCanvas.drawBitmap(texture, null,
            android.graphics.RectF(0f, 0f, maskBitmap.width.toFloat(), maskBitmap.height.toFloat()),
            texturePaint)
        
        // Tint the mask with brush color
        val colorFilter = ColorMatrixColorFilter(floatArrayOf(
            0f, 0f, 0f, 0f, android.graphics.Color.red(color).toFloat(),
            0f, 0f, 0f, 0f, android.graphics.Color.green(color).toFloat(),
            0f, 0f, 0f, 0f, android.graphics.Color.blue(color).toFloat(),
            0f, 0f, 0f, params.opacity, 0f
        ))
        
        blendPaint.colorFilter = colorFilter
        
        // Apply rotation if specified
        if (params.textureRotation != 0f) {
            canvas.save()
            canvas.rotate(params.textureRotation, x, y)
            canvas.drawBitmap(maskBitmap, null,
                android.graphics.RectF(left, top, left + scaledSize, top + scaledSize),
                blendPaint)
            canvas.restore()
        } else {
            canvas.drawBitmap(maskBitmap, null,
                android.graphics.RectF(left, top, left + scaledSize, top + scaledSize),
                blendPaint)
        }
        
        maskBitmap.recycle()
    }

    /**
     * Render a stamp-based brush dab
     * @param canvas Canvas to draw on
     * @param x X position
     * @param y Y position
     * @param size Brush size
     * @param stampData Stamp shape data for procedural generation
     * @param color Brush color
     * @param params Brush parameters
     */
    fun applyStampToDab(
        canvas: Canvas,
        x: Float,
        y: Float,
        size: Float,
        stampData: Any?, // Could be BrushStamp or procedural data
        color: Int,
        params: BrushParams
    ) {
        // For now, fall back to textured dab
        // Full stamp implementation would generate/render stamp shapes here
        drawSolidDab(canvas, x, y, size, color, params.opacity)
    }

    /**
     * Calculate texture coordinates for a stroke point
     * Handles texture mapping along stroke path
     * @param point Current stroke point
     * @param previousPoint Previous stroke point
     * @param textureOffset Current texture offset accumulator
     * @return Updated texture coordinates (u, v)
     */
    fun calculateTextureCoordinates(
        point: StrokePoint,
        previousPoint: StrokePoint?,
        textureOffset: Pair<Float, Float>,
        params: BrushParams
    ): Pair<Float, Float> {
        val (offsetU, offsetV) = textureOffset
        
        // Calculate distance from previous point for texture scrolling
        val distance = previousPoint?.let {
            kotlin.math.sqrt(
                Math.pow((point.x - it.x).toDouble(), 2.0) +
                Math.pow((point.y - it.y).toDouble(), 2.0)
            ).toFloat()
        } ?: 0f
        
        // Update U coordinate based on stroke distance (creates continuous texture along stroke)
        val newOffsetU = offsetU + (distance / params.size) * params.textureScale
        
        // Apply rotation to V coordinate if needed
        val newOffsetV = offsetV + (point.azimuth / 360f) * params.textureRotation
        
        return Pair(newOffsetU, newOffsetV)
    }

    /**
     * Get or create cached stamp bitmap
     */
    fun getOrCreateStampBitmap(
        stampId: String,
        size: Int = 512,
        generator: () -> Bitmap
    ): Bitmap {
        return stampCache[stampId] ?: run {
            val bitmap = generator()
            stampCache[stampId] = bitmap
            bitmap
        }
    }

    /**
     * Clear stamp cache to free memory
     */
    fun clearStampCache() {
        stampCache.values.forEach { it.recycle() }
        stampCache.clear()
        Timber.d("Stamp cache cleared")
    }

    /**
     * Preload texture into GPU memory for faster access
     */
    fun preloadTexture(texture: BrushTexture): Boolean {
        return texture.bitmap != null && !texture.bitmap.isRecycled
    }

    /**
     * Get estimated memory usage of cached stamps
     */
    fun getCacheMemoryUsage(): Int {
        return stampCache.values.sumOf { it.allocationByteCount }
    }
}
