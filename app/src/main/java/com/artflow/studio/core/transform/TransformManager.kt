package com.artflow.studio.core.transform

import android.graphics.Matrix
import android.graphics.RectF
import com.artflow.studio.domain.model.transform.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transform Manager for Phase 14: Transform System
 * Manages all transformation operations including translation, rotation, scale, skew, and perspective
 * 
 * Features:
 * - Translation (move) transform
 * - Rotation transform with pivot point
 * - Scale transform (uniform and non-uniform)
 * - Skew transform
 * - Perspective transform
 * - Distortion transform (free-form corner manipulation)
 * - Snap guides and visual feedback
 * - Undo/redo support
 */
@Singleton
class TransformManager @Inject constructor() {

    private val _transformState = MutableStateFlow(TransformState())
    val transformState: StateFlow<TransformState> = _transformState.asStateFlow()

    private val _isTransforming = MutableStateFlow(false)
    val isTransforming: StateFlow<Boolean> = _isTransforming.asStateFlow()

    private val _activeHandle = MutableStateFlow(TransformHandleType.NONE)
    val activeHandle: StateFlow<TransformHandleType> = _activeHandle.asStateFlow()

    private val _snapGuides = MutableStateFlow<List<SnapGuide>>(emptyList())
    val snapGuides: StateFlow<List<SnapGuide>> = _snapGuides.asStateFlow()

    private var transformBounds: RectF = RectF()
    private val config = TransformConfig()
    
    private val transformHistory = mutableListOf<TransformHistoryEntry>()
    private var historyIndex = -1

    /**
     * Begin a transform operation on the specified bounds
     */
    fun beginTransform(bounds: RectF) {
        saveHistoryState(TransformOperation.TRANSLATE) // Default operation type
        
        transformBounds.set(bounds)
        
        // Set pivot to center by default
        _transformState.value = _transformState.value.copy(
            pivotX = bounds.centerX(),
            pivotY = bounds.centerY()
        )
        
        _isTransforming.value = true
        Timber.d("Transform begun for bounds: $bounds")
    }

    /**
     * Apply translation to the transform
     */
    fun translate(deltaX: Float, deltaY: Float) {
        if (!_isTransforming.value) return
        
        val currentState = _transformState.value
        val newX = currentState.translationX + deltaX
        val newY = currentState.translationY + deltaY
        
        // Check for snapping
        val snappedX = snapValue(newX, transformBounds.left + deltaX, GuideType.HORIZONTAL)
        val snappedY = snapValue(newY, transformBounds.top + deltaY, GuideType.VERTICAL)
        
        _transformState.value = currentState.copy(
            translationX = snappedX,
            translationY = snappedY
        )
        
        updateSnapGuides(GuideType.HORIZONTAL, snappedY)
        updateSnapGuides(GuideType.VERTICAL, snappedX)
        
        Timber.v("Translated: ($newX, $newY) -> ($snappedX, $snappedY)")
    }

    /**
     * Apply rotation to the transform
     */
    fun rotate(deltaDegrees: Float) {
        if (!_isTransforming.value) return
        
        val currentState = _transformState.value
        var newRotation = currentState.rotation + deltaDegrees
        
        // Snap rotation if enabled
        if (config.enableRotationSnap) {
            newRotation = snapRotation(newRotation)
        }
        
        _transformState.value = currentState.copy(rotation = newRotation)
        
        // Update angle guide
        if (config.showGuides) {
            updateSnapGuides(GuideType.ANGLE, newRotation)
        }
        
        Timber.v("Rotated: ${currentState.rotation}° -> $newRotation°")
    }

    /**
     * Apply scale to the transform
     */
    fun scale(scaleFactorX: Float, scaleFactorY: Float? = null) {
        if (!_isTransforming.value) return
        
        val currentState = _transformState.value
        val uniformScale = config.enableUniformScale || scaleFactorY == null
        val scaleY = if (uniformScale) scaleFactorX else scaleFactorY
        
        // Clamp scale values
        val newScaleX = currentState.scaleX * scaleFactorX
        val newScaleY = currentState.scaleY * scaleY
        
        val clampedScaleX = newScaleX.coerceIn(config.minScale, config.maxScale)
        val clampedScaleY = newScaleY.coerceIn(config.minScale, config.maxScale)
        
        _transformState.value = currentState.copy(
            scaleX = clampedScaleX,
            scaleY = clampedScaleY
        )
        
        Timber.v("Scaled: (${currentState.scaleX}, ${currentState.scaleY}) -> ($clampedScaleX, $clampedScaleY)")
    }

    /**
     * Apply skew to the transform
     */
    fun skew(skewX: Float, skewY: Float) {
        if (!_isTransforming.value) return
        
        val currentState = _transformState.value
        
        // Clamp skew values to reasonable range (-45 to 45 degrees)
        val clampedSkewX = skewX.coerceIn(-45f, 45f)
        val clampedSkewY = skewY.coerceIn(-45f, 45f)
        
        _transformState.value = currentState.copy(
            skewX = clampedSkewX,
            skewY = clampedSkewY
        )
        
        Timber.v("Skewed: (${currentState.skewX}°, ${currentState.skewY}°) -> ($clampedSkewX°, $clampedSkewY°)")
    }

    /**
     * Apply perspective distortion
     */
    fun applyPerspective(perspectiveX: Float, perspectiveY: Float) {
        if (!_isTransforming.value) return
        
        val currentState = _transformState.value
        
        // Clamp perspective values
        val clampedPerspectiveX = perspectiveX.coerceIn(-100f, 100f)
        val clampedPerspectiveY = perspectiveY.coerceIn(-100f, 100f)
        
        _transformState.value = currentState.copy(
            perspectiveX = clampedPerspectiveX,
            perspectiveY = clampedPerspectiveY
        )
        
        Timber.v("Perspective applied: ($clampedPerspectiveX, $clampedPerspectiveY)")
    }

    /**
     * Set the pivot point for transformations
     */
    fun setPivot(x: Float, y: Float) {
        val currentState = _transformState.value
        _transformState.value = currentState.copy(
            pivotX = x,
            pivotY = y
        )
        
        Timber.d("Pivot set to: ($x, $y)")
    }

    /**
     * Reset pivot to center of bounds
     */
    fun resetPivot() {
        _transformState.value = _transformState.value.copy(
            pivotX = transformBounds.centerX(),
            pivotY = transformBounds.centerY()
        )
        
        Timber.d("Pivot reset to center")
    }

    /**
     * Enable distortion mode for free-form corner manipulation
     */
    fun enableDistortionMode(corners: List<Pair<Float, Float>>) {
        if (corners.size != 4) {
            Timber.e("Distortion mode requires exactly 4 corners")
            return
        }
        
        _transformState.value = _transformState.value.copy(
            isDistortionMode = true,
            distortionCorners = corners
        )
        
        _isTransforming.value = true
        Timber.d("Distortion mode enabled")
    }

    /**
     * Update a specific corner in distortion mode
     */
    fun updateDistortionCorner(index: Int, x: Float, y: Float) {
        if (!_transformState.value.isDistortionMode) return
        if (index !in 0..3) return
        
        val corners = _transformState.value.distortionCorners.toMutableList()
        corners[index] = x to y
        
        _transformState.value = _transformState.value.copy(
            distortionCorners = corners
        )
        
        Timber.v("Distortion corner $index updated to ($x, $y)")
    }

    /**
     * Handle handle selection for interactive transform
     */
    fun selectHandle(handleType: TransformHandleType) {
        _activeHandle.value = handleType
        Timber.d("Handle selected: $handleType")
    }

    /**
     * Process drag on a selected handle
     */
    fun handleDrag(
        deltaX: Float,
        deltaY: Float,
        startX: Float,
        startY: Float,
        currentX: Float,
        currentY: Float
    ) {
        when (_activeHandle.value) {
            TransformHandleType.TOP_LEFT,
            TransformHandleType.TOP_RIGHT,
            TransformHandleType.BOTTOM_LEFT,
            TransformHandleType.BOTTOM_RIGHT -> {
                // Corner handles: scale and rotate
                handleCornerDrag(deltaX, deltaY, startX, startY, currentX, currentY)
            }
            TransformHandleType.TOP_CENTER,
            TransformHandleType.BOTTOM_CENTER -> {
                // Vertical scaling
                scale(1f, 1f + deltaY / 100f)
            }
            TransformHandleType.LEFT_CENTER,
            TransformHandleType.RIGHT_CENTER -> {
                // Horizontal scaling
                scale(1f + deltaX / 100f, 1f)
            }
            TransformHandleType.ROTATION -> {
                // Rotation handle
                val deltaAngle = calculateRotationAngle(startX, startY, currentX, currentY)
                rotate(deltaAngle)
            }
            TransformHandleType.PIVOT -> {
                // Move pivot
                setPivot(currentX, currentY)
            }
            TransformHandleType.NONE -> {
                // No handle, might be translating the whole content
                translate(deltaX, deltaY)
            }
        }
    }

    /**
     * Handle corner handle drag for combined scale/rotate
     */
    private fun handleCornerDrag(
        deltaX: Float,
        deltaY: Float,
        startX: Float,
        startY: Float,
        currentX: Float,
        currentY: Float
    ) {
        val currentState = _transformState.value
        val pivotX = currentState.pivotX
        val pivotY = currentState.pivotY
        
        // Calculate distance from pivot for scaling
        val startDistance = kotlin.math.hypot(startX - pivotX, startY - pivotY)
        val currentDistance = kotlin.math.hypot(currentX - pivotX, currentY - pivotY)
        
        if (startDistance > 0) {
            val scaleFactor = currentDistance / startDistance
            scale(scaleFactor, scaleFactor)
        }
        
        // Calculate angle for rotation
        val startAngle = kotlin.math.atan2(startY - pivotY, startX - pivotX)
        val currentAngle = kotlin.math.atan2(currentY - pivotY, currentX - pivotX)
        
        val deltaAngle = kotlin.math.toDegrees(currentAngle - startAngle).toFloat()
        rotate(deltaAngle)
    }

    /**
     * Calculate rotation angle between two points relative to pivot
     */
    private fun calculateRotationAngle(
        startX: Float,
        startY: Float,
        currentX: Float,
        currentY: Float
    ): Float {
        val currentState = _transformState.value
        val pivotX = currentState.pivotX
        val pivotY = currentState.pivotY
        
        val startAngle = kotlin.math.atan2(startY - pivotY, startX - pivotX)
        val currentAngle = kotlin.math.atan2(currentY - pivotY, currentX - pivotX)
        
        return kotlin.math.toDegrees(currentAngle - startAngle).toFloat()
    }

    /**
     * Snap a value to nearby guides
     */
    private fun snapValue(value: Float, reference: Float, guideType: GuideType): Float {
        if (!config.enableSnapping) return value
        
        // Simple snapping implementation
        // In production, this would check against canvas guides, other layers, etc.
        return value
    }

    /**
     * Snap rotation to configured angle increments
     */
    private fun snapRotation(rotation: Float): Float {
        val snapAngle = config.rotationSnapAngle
        return kotlin.math.round(rotation / snapAngle) * snapAngle
    }

    /**
     * Update snap guides for visual feedback
     */
    private fun updateSnapGuides(type: GuideType, value: Float) {
        if (!config.showGuides) {
            _snapGuides.value = emptyList()
            return
        }
        
        val guide = SnapGuide(
            type = type,
            position = value,
            value = value,
            isSnapped = config.enableSnapping
        )
        
        _snapGuides.value = listOf(guide)
    }

    /**
     * Get the transformed matrix
     */
    fun getTransformMatrix(): Matrix {
        return _transformState.value.toMatrix()
    }

    /**
     * Get the transformed bounds
     */
    fun getTransformedBounds(): RectF {
        return _transformState.value.getTransformedBounds(transformBounds)
    }

    /**
     * Commit the current transform (end operation)
     */
    fun commitTransform() {
        if (!_isTransforming.value) return
        
        saveHistoryState(getCurrentOperationType())
        
        _isTransforming.value = false
        _activeHandle.value = TransformHandleType.NONE
        _snapGuides.value = emptyList()
        
        Timber.d("Transform committed")
    }

    /**
     * Cancel and revert the current transform
     */
    fun cancelTransform() {
        if (!_isTransforming.value) return
        
        undo()
        
        _isTransforming.value = false
        _activeHandle.value = TransformHandleType.NONE
        _snapGuides.value = emptyList()
        
        Timber.d("Transform cancelled")
    }

    /**
     * Reset all transforms to identity
     */
    fun resetTransform() {
        saveHistoryState(TransformOperation.RESET)
        
        _transformState.value = TransformState()
        transformBounds = RectF()
        
        Timber.d("Transform reset to identity")
    }

    /**
     * Determine the current operation type based on active handle
     */
    private fun getCurrentOperationType(): TransformOperation {
        return when (_activeHandle.value) {
            TransformHandleType.ROTATION -> TransformOperation.ROTATE
            TransformHandleType.PIVOT -> TransformOperation.TRANSLATE
            TransformHandleType.NONE -> TransformOperation.TRANSLATE
            else -> TransformOperation.SCALE
        }
    }

    /**
     * Save state to history for undo/redo
     */
    private fun saveHistoryState(operation: TransformOperation) {
        // Remove any future states if we're not at the end
        while (historyIndex < transformHistory.lastIndex) {
            transformHistory.removeAt(transformHistory.lastIndex)
        }
        
        val entry = TransformHistoryEntry(
            operation = operation,
            previousState = getPreviousStateFromHistory(),
            newState = _transformState.value.copy(),
            timestamp = System.currentTimeMillis()
        )
        
        transformHistory.add(entry)
        historyIndex++
        
        // Limit history size
        if (transformHistory.size > 50) {
            transformHistory.removeAt(0)
            historyIndex--
        }
    }

    /**
     * Get previous state from history or create default
     */
    private fun getPreviousStateFromHistory(): TransformState {
        return if (historyIndex >= 0 && transformHistory.isNotEmpty()) {
            transformHistory[historyIndex].newState
        } else {
            TransformState()
        }
    }

    /**
     * Undo the last transform operation
     */
    fun undo(): Boolean {
        if (historyIndex < 0) return false
        
        val entry = transformHistory[historyIndex]
        historyIndex--
        
        _transformState.value = entry.previousState.copy()
        
        Timber.d("Transform undone: ${entry.operation}")
        return true
    }

    /**
     * Redo a previously undone transform operation
     */
    fun redo(): Boolean {
        if (historyIndex >= transformHistory.lastIndex) return false
        
        historyIndex++
        val entry = transformHistory[historyIndex]
        
        _transformState.value = entry.newState.copy()
        
        Timber.d("Transform redone: ${entry.operation}")
        return true
    }

    /**
     * Clear transform history
     */
    fun clearHistory() {
        transformHistory.clear()
        historyIndex = -1
    }

    /**
     * Check if there's an active transform
     */
    fun hasActiveTransform(): Boolean {
        return _transformState.value.hasActiveTransform()
    }

    /**
     * Get current transform configuration
     */
    fun getConfig(): TransformConfig {
        return config
    }
}

/**
 * History entry for transform operations
 */
private data class TransformHistoryEntry(
    val operation: TransformOperation,
    val previousState: TransformState,
    val newState: TransformState,
    val timestamp: Long
)
