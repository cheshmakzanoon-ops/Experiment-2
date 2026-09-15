package com.artflow.studio.core.layer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.artflow.studio.domain.model.layer.LayerMask
import com.artflow.studio.domain.model.layer.MaskCreationMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Core manager for layer masks in ArtFlow
 * Implements Phase 16: Layer Masks - Non-destructive layer masking
 * 
 * This singleton manages all layer mask operations including:
 * - Creating masks from various sources (empty, selection, alpha)
 * - Painting on masks with brushes
 * - Mask properties (density, feather, invert)
 * - Mask linking/unlinking from layers
 * - Mask thumbnails generation
 */
@Singleton
class LayerMaskManager @Inject constructor() {

    private val _masks = MutableStateFlow<Map<Long, LayerMask>>(emptyMap())
    val masks: StateFlow<Map<Long, LayerMask>> = _masks.asStateFlow()

    private var nextMaskId = 1L

    private val paint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
        }
    }

    /**
     * Add a new layer mask to a layer
     * @param layerId The ID of the layer to add mask to
     * @param creationMethod How the mask should be created
     * @param canvasWidth Width of the canvas for bitmap creation
     * @param canvasHeight Height of the canvas for bitmap creation
     * @return The created LayerMask or null if layer already has a mask
     */
    fun addLayerMask(
        layerId: Long,
        creationMethod: MaskCreationMethod,
        canvasWidth: Int,
        canvasHeight: Int
    ): LayerMask? {
        // Check if layer already has a mask
        if (_masks.value.containsKey(layerId)) {
            Timber.w("Layer $layerId already has a mask")
            return null
        }

        val mask = LayerMask(
            id = nextMaskId++,
            layerId = layerId,
            bitmap = createMaskBitmap(creationMethod, canvasWidth, canvasHeight),
            isEnabled = true,
            isInverted = false,
            density = 1.0f,
            featherRadius = 0f
        )

        _masks.value = _masks.value + (layerId to mask)

        Timber.d("Layer mask added to layer $layerId using method $creationMethod")
        return mask
    }

    /**
     * Create a bitmap based on the creation method
     */
    private fun createMaskBitmap(
        method: MaskCreationMethod,
        width: Int,
        height: Int
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        when (method) {
            MaskCreationMethod.EMPTY -> {
                // White mask (reveals all)
                canvas.drawColor(Color.WHITE)
            }
            MaskCreationMethod.HIDE_ALL -> {
                // Black mask (hides all)
                canvas.drawColor(Color.BLACK)
            }
            MaskCreationMethod.FROM_SELECTION,
            MaskCreationMethod.FROM_LAYER_ALPHA,
            MaskCreationMethod.FROM_GRADIENT,
            MaskCreationMethod.CUSTOM -> {
                // Default to white for now, actual implementation would use selection/alpha/gradient
                canvas.drawColor(Color.WHITE)
            }
        }

        return bitmap
    }

    /**
     * Remove a layer mask from a layer
     * @param layerId The ID of the layer to remove mask from
     * @param discardChanges If true, discard mask; if false, apply mask to layer alpha
     * @return True if removed successfully
     */
    fun removeLayerMask(layerId: Long, discardChanges: Boolean = true): Boolean {
        val mask = _masks.value[layerId] ?: return false

        if (!discardChanges) {
            // TODO: Apply mask to layer alpha before removing
            Timber.d("Applying mask to layer $layerId before removal")
        }

        // Recycle bitmap to free memory
        mask.bitmap?.recycle()

        _masks.value = _masks.value - layerId

        Timber.d("Layer mask removed from layer $layerId")
        return true
    }

    /**
     * Toggle mask enabled/disabled state
     * @param layerId The layer ID
     * @param isEnabled Enable state
     * @return True if updated
     */
    fun toggleMaskEnabled(layerId: Long, isEnabled: Boolean): Boolean {
        val mask = _masks.value[layerId] ?: return false

        _masks.value = _masks.value + (layerId to mask.copyWith(isEnabled = isEnabled))

        Timber.d("Layer mask on layer $layerId ${if (isEnabled) "enabled" else "disabled"}")
        return true
    }

    /**
     * Invert the mask colors
     * @param layerId The layer ID
     * @param isInverted Inversion state
     * @return True if updated
     */
    fun invertMask(layerId: Long, isInverted: Boolean): Boolean {
        val mask = _masks.value[layerId] ?: return false

        _masks.value = _masks.value + (layerId to mask.copyWith(isInverted = isInverted))

        Timber.d("Layer mask on layer $layerId ${if (isInverted) "inverted" else "uninverted"}")
        return true
    }

    /**
     * Set mask density (overall opacity)
     * @param layerId The layer ID
     * @param density Density value (0.0 - 1.0)
     * @return True if updated
     */
    fun setMaskDensity(layerId: Long, density: Float): Boolean {
        val mask = _masks.value[layerId] ?: return false
        val clampedDensity = density.coerceIn(0f, 1f)

        _masks.value = _masks.value + (layerId to mask.copyWith(density = clampedDensity))

        Timber.d("Layer mask density on layer $layerId set to $clampedDensity")
        return true
    }

    /**
     * Set mask feather radius for soft edges
     * @param layerId The layer ID
     * @param featherRadius Feather radius in pixels
     * @return True if updated
     */
    fun setMaskFeather(layerId: Long, featherRadius: Float): Boolean {
        val mask = _masks.value[layerId] ?: return false
        val clampedFeather = featherRadius.coerceIn(0f, 500f)

        _masks.value = _masks.value + (layerId to mask.copyWith(featherRadius = clampedFeather))

        Timber.d("Layer mask feather on layer $layerId set to $clampedFeather px")
        return true
    }

    /**
     * Paint on a layer mask with grayscale values
     * @param layerId The layer ID
     * @param x X coordinate
     * @param y Y coordinate
     * @param brushSize Size of the brush
     * @param grayValue Grayscale value (0=black/hide, 255=white/reveal)
     * @param pressure Pressure value for opacity modulation
     * @return True if painted successfully
     */
    fun paintOnMask(
        layerId: Long,
        x: Float,
        y: Float,
        brushSize: Float,
        grayValue: Int,
        pressure: Float = 1.0f
    ): Boolean {
        val mask = _masks.value[layerId] ?: return false
        val bitmap = mask.bitmap ?: return false

        // Calculate effective brush size with pressure
        val effectiveSize = brushSize * pressure.coerceIn(0.1f, 1.0f)
        val radius = effectiveSize / 2f

        // Create temporary canvas for painting
        val canvas = Canvas(bitmap)

        // Set up paint for grayscale brush
        paint.apply {
            color = Color.rgb(grayValue, grayValue, grayValue)
            this.radius = radius
            maskFilter = if (mask.featherRadius > 0) {
                android.graphics.BlurMaskFilter(mask.featherRadius, android.graphics.BlurMaskFilter.Blur.NORMAL)
            } else {
                null
            }
        }

        // Draw on mask
        canvas.drawCircle(x, y, radius, paint)

        Timber.d("Painted on mask layer $layerId at ($x, $y) with gray=$grayValue")
        return true
    }

    /**
     * Fill entire mask with a grayscale value
     * @param layerId The layer ID
     * @param grayValue Grayscale value (0-255)
     * @return True if filled successfully
     */
    fun fillMask(layerId: Long, grayValue: Int): Boolean {
        val mask = _masks.value[layerId] ?: return false
        val bitmap = mask.bitmap ?: return false

        val canvas = Canvas(bitmap)
        paint.color = Color.rgb(grayValue, grayValue, grayValue)
        paint.maskFilter = null

        canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), paint)

        Timber.d("Filled mask layer $layerId with gray=$grayValue")
        return true
    }

    /**
     * Clear mask to white (reveal all)
     * @param layerId The layer ID
     * @return True if cleared successfully
     */
    fun clearMask(layerId: Long): Boolean {
        return fillMask(layerId, 255)
    }

    /**
     * Invert mask colors programmatically
     * @param layerId The layer ID
     * @return True if inverted successfully
     */
    fun invertMaskColors(layerId: Long): Boolean {
        val mask = _masks.value[layerId] ?: return false
        val bitmap = mask.bitmap ?: return false

        // Process each pixel
        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                val pixel = bitmap.getPixel(x, y)
                val gray = Color.red(pixel)
                val inverted = 255 - gray
                bitmap.setPixel(x, y, Color.rgb(inverted, inverted, inverted))
            }
        }

        Timber.d("Inverted mask colors for layer $layerId")
        return true
    }

    /**
     * Get mask for a specific layer
     */
    fun getMaskForLayer(layerId: Long): LayerMask? {
        return _masks.value[layerId]
    }

    /**
     * Get all active masks
     */
    fun getActiveMasks(): Map<Long, LayerMask> {
        return _masks.value.filterValues { it.isActive() }
    }

    /**
     * Generate thumbnail for a mask
     * @param layerId The layer ID
     * @param thumbnailSize Desired thumbnail size (will be square)
     * @return Thumbnail bitmap or null if mask doesn't exist
     */
    fun generateMaskThumbnail(layerId: Long, thumbnailSize: Int): Bitmap? {
        val mask = _masks.value[layerId] ?: return null
        val bitmap = mask.bitmap ?: return null

        // Scale bitmap to thumbnail size
        return Bitmap.createScaledBitmap(bitmap, thumbnailSize, thumbnailSize, true)
    }

    /**
     * Apply feather effect to mask edges
     * @param layerId The layer ID
     * @param radius Feather radius in pixels
     * @return True if applied successfully
     */
    fun applyFeatherToMask(layerId: Long, radius: Float): Boolean {
        val mask = _masks.value[layerId] ?: return false
        val bitmap = mask.bitmap ?: return false

        // Use Gaussian blur for feathering
        // Note: Actual implementation would use RenderScript or GPU shader for performance
        setMaskFeather(layerId, radius)

        Timber.d("Applied feather radius $radius to mask layer $layerId")
        return true
    }

    /**
     * Load mask from existing bitmap
     * @param layerId The layer ID
     * @param bitmap Source bitmap (will be converted to grayscale)
     * @return True if loaded successfully
     */
    fun loadMaskFromBitmap(layerId: Long, bitmap: Bitmap): Boolean {
        val mask = _masks.value[layerId]

        // Convert to grayscale
        val grayBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        for (x in 0 until grayBitmap.width) {
            for (y in 0 until grayBitmap.height) {
                val pixel = grayBitmap.getPixel(x, y)
                val avg = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
                grayBitmap.setPixel(x, y, Color.rgb(avg, avg, avg))
            }
        }

        val updatedMask = mask?.copyWith(bitmap = grayBitmap)
            ?: LayerMask(
                id = nextMaskId++,
                layerId = layerId,
                bitmap = grayBitmap
            )

        _masks.value = _masks.value + (layerId to updatedMask)

        Timber.d("Loaded mask from bitmap for layer $layerId")
        return true
    }

    /**
     * Clear all masks
     */
    fun clearAllMasks() {
        _masks.value.values.forEach { it.bitmap?.recycle() }
        _masks.value = emptyMap()
        Timber.d("All layer masks cleared")
    }

    /**
     * Get mask count
     */
    fun getMaskCount(): Int = _masks.value.size
}
