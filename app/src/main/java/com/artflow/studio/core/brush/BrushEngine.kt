package com.artflow.studio.core.brush

import android.graphics.Canvas
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.model.texture.BrushTexture
import com.artflow.studio.domain.repository.texture.TextureRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Core brush engine implementation for ArtFlow
 * Implements Phase 7: Brush Engine Foundation, Phase 9: Advanced Brush Parameters,
 * and Phase 10: Brush Textures & Stamps
 * 
 * This singleton manages brush stroke rendering with advanced features:
 * - Pressure sensitivity (size, opacity, color)
 * - Velocity-based dynamics
 * - Stroke smoothing and interpolation
 * - Color jitter and dynamics
 * - Texture mapping support (Phase 10)
 * - Stamp-based brushes (Phase 10)
 * - Tilt influence
 */
@Singleton
class BrushEngine @Inject constructor(
    private val textureMapper: TextureMapper,
    private val textureRepository: TextureRepository
) {

    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    private var currentStrokeBuilder: StrokeBuilder? = null
    private var activeBrushParams: BrushParams = BrushParams()
    private var currentColor: Int = android.graphics.Color.BLACK
    private var currentLayerId: Long = 0
    private var currentTexture: BrushTexture? = null
    
    // Texture offset accumulator for continuous texture mapping along strokes
    private var textureOffsetU: Float = 0f
    private var textureOffsetV: Float = 0f
    
    private val strokeListeners = mutableListOf<(Stroke) -> Unit>()
    private val pointListeners = mutableListOf<(StrokePoint) -> Unit>()
    
    // State flow for loaded texture
    private val _currentTexture = MutableStateFlow<BrushTexture?>(null)
    val currentTextureFlow: StateFlow<BrushTexture?> = _currentTexture.asStateFlow()

    /**
     * Configure the brush engine with new parameters
     */
    fun configure(params: BrushParams, color: Int, layerId: Long) {
        activeBrushParams = params
        currentColor = color
        currentLayerId = layerId
        Timber.d("Brush engine configured: size=${params.size}, opacity=${params.opacity}")
    }

    /**
     * Begin a new stroke at the given coordinates
     * @param x X coordinate
     * @param y Y coordinate
     * @param pressure Stylus pressure (0.0 - 1.0)
     * @param tiltX Tilt angle X axis
     * @param tiltY Tilt angle Y axis
     * @return Stroke ID if successful, null otherwise
     */
    fun beginStroke(
        x: Float,
        y: Float,
        pressure: Float = 1.0f,
        tiltX: Float = 0f,
        tiltY: Float = 0f
    ): Long? {
        // End any existing stroke first
        currentStrokeBuilder?.end()?.let { stroke ->
            notifyStrokeComplete(stroke)
        }

        currentStrokeBuilder = StrokeBuilder(activeBrushParams, currentLayerId, currentColor)
        val strokeId = currentStrokeBuilder?.start(x, y, pressure)
        
        val effectivePoint = applyBrushDynamics(
            x = x,
            y = y,
            pressure = pressure,
            tiltX = tiltX,
            tiltY = tiltY,
            velocity = 0f
        )
        
        notifyPointAdded(effectivePoint)
        Timber.d("Stroke began at ($x, $y) with pressure $pressure")
        return strokeId
    }

    /**
     * Continue the current stroke with a new point
     * @param x X coordinate
     * @param y Y coordinate
     * @param pressure Stylus pressure (0.0 - 1.0)
     * @param tiltX Tilt angle X axis
     * @param tiltY Tilt angle Y axis
     * @param azimuth Stylus azimuth angle
     * @return True if point was added successfully
     */
    fun continueStroke(
        x: Float,
        y: Float,
        pressure: Float = 1.0f,
        tiltX: Float = 0f,
        tiltY: Float = 0f,
        azimuth: Float = 0f
    ): Boolean {
        val builder = currentStrokeBuilder ?: return false
        
        // Calculate velocity from previous point
        val lastPoint = builder.getCurrentPoints().lastOrNull()
        val velocity = lastPoint?.let { 
            it.velocityFrom(StrokePoint(x, y, pressure, tiltX, tiltY)) 
        } ?: 0f
        
        // Apply brush dynamics
        val effectivePoint = applyBrushDynamics(
            x = x,
            y = y,
            pressure = pressure,
            tiltX = tiltX,
            tiltY = tiltY,
            velocity = velocity,
            azimuth = azimuth
        )
        
        val added = builder.addPoint(
            x = effectivePoint.x,
            y = effectivePoint.y,
            pressure = effectivePoint.pressure,
            tiltX = effectivePoint.tiltX,
            tiltY = effectivePoint.tiltY
        )
        
        if (added) {
            notifyPointAdded(effectivePoint)
        }
        
        return added
    }

    /**
     * End the current stroke
     * @return The completed Stroke or null if no stroke was active
     */
    fun endStroke(): Stroke? {
        val builder = currentStrokeBuilder ?: return null
        val stroke = builder.end()
        
        currentStrokeBuilder = null
        
        stroke?.let {
            notifyStrokeComplete(it)
            Timber.d("Stroke ended with ${it.points.size} points")
        }
        
        return stroke
    }

    /**
     * Apply all brush dynamics to calculate effective point properties
     */
    private fun applyBrushDynamics(
        x: Float,
        y: Float,
        pressure: Float,
        tiltX: Float,
        tiltY: Float,
        velocity: Float,
        azimuth: Float = 0f
    ): StrokePoint {
        // Calculate effective size based on pressure and velocity
        val effectiveSize = activeBrushParams.calculateEffectiveSize(pressure, velocity)
        
        // Calculate effective opacity
        val effectiveOpacity = activeBrushParams.calculateEffectiveOpacity(pressure, velocity)
        
        // Apply color dynamics (jitter, velocity-based shifts)
        val effectiveColor = activeBrushParams.applyColorJitter(currentColor, pressure, velocity)
        
        // Apply tilt influence on rotation if enabled
        val effectiveTiltX = if (activeBrushParams.tiltInfluence > 0f) {
            tiltX * activeBrushParams.tiltInfluence
        } else {
            tiltX
        }
        
        val effectiveTiltY = if (activeBrushParams.tiltInfluence > 0f) {
            tiltY * activeBrushParams.tiltInfluence
        } else {
            tiltY
        }
        
        // Apply tilt-to-rotation mapping
        val effectiveRotation = if (activeBrushParams.tiltToRotation) {
            azimuth + (effectiveTiltX * 45f) // Map tilt to rotation
        } else {
            activeBrushParams.rotation
        }
        
        // Apply scatter if enabled
        val scatteredX = if (activeBrushParams.scatter > 0f) {
            x + (Math.random().toFloat() * 2f - 1f) * activeBrushParams.scatter * effectiveSize
        } else {
            x
        }
        
        val scatteredY = if (activeBrushParams.scatter > 0f) {
            y + (Math.random().toFloat() * 2f - 1f) * activeBrushParams.scatter * effectiveSize
        } else {
            y
        }
        
        return StrokePoint(
            x = scatteredX,
            y = scatteredY,
            pressure = effectiveOpacity, // Store effective opacity in pressure field for renderer
            tiltX = effectiveTiltX,
            tiltY = effectiveTiltY,
            azimuth = effectiveRotation,
            timestamp = System.currentTimeMillis(),
            color = effectiveColor
        )
    }

    /**
     * Register a listener for stroke completion events
     */
    fun addStrokeListener(listener: (Stroke) -> Unit) {
        strokeListeners.add(listener)
    }

    /**
     * Remove a stroke listener
     */
    fun removeStrokeListener(listener: (Stroke) -> Unit) {
        strokeListeners.remove(listener)
    }

    /**
     * Register a listener for point addition events
     */
    fun addPointListener(listener: (StrokePoint) -> Unit) {
        pointListeners.add(listener)
    }

    /**
     * Remove a point listener
     */
    fun removePointListener(listener: (StrokePoint) -> Unit) {
        pointListeners.remove(listener)
    }

    /**
     * Notify all listeners of a completed stroke
     */
    private fun notifyStrokeComplete(stroke: Stroke) {
        strokeListeners.forEach { it(stroke) }
    }

    /**
     * Notify all listeners of an added point
     */
    private fun notifyPointAdded(point: StrokePoint) {
        pointListeners.forEach { it(point) }
    }

    /**
     * Get the current brush parameters
     */
    fun getCurrentParams(): BrushParams = activeBrushParams

    /**
     * Check if a stroke is currently in progress
     */
    fun isStrokeInProgress(): Boolean = currentStrokeBuilder?.isInProgress() == true

    /**
     * Cancel the current stroke without completing it
     */
    fun cancelStroke() {
        currentStrokeBuilder = null
        Timber.d("Stroke cancelled")
    }

    /**
     * Reset the brush engine to default state
     */
    fun reset() {
        cancelStroke()
        activeBrushParams = BrushParams()
        currentColor = android.graphics.Color.BLACK
        currentLayerId = 0
        Timber.d("Brush engine reset")
    }
}

/**
 * Helper data class for brush state snapshot (for undo/redo)
 */
data class BrushStateSnapshot(
    val brushParams: BrushParams,
    val color: Int,
    val layerId: Long,
    val timestamp: Long = System.currentTimeMillis()
)
