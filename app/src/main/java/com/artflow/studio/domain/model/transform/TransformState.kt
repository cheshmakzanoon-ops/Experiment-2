package com.artflow.studio.domain.model.transform

import android.graphics.Matrix
import android.graphics.RectF

/**
 * Transform model for Phase 14: Transform System
 * Represents transformation state and operations for selected content
 */
data class TransformState(
    val translationX: Float = 0f,
    val translationY: Float = 0f,
    val rotation: Float = 0f,           // Rotation in degrees
    val scaleX: Float = 1f,             // Scale factor X (1.0 = 100%)
    val scaleY: Float = 1f,             // Scale factor Y (1.0 = 100%)
    val skewX: Float = 0f,              // Skew X in degrees
    val skewY: Float = 0f,              // Skew Y in degrees
    val pivotX: Float = 0f,             // Pivot point X
    val pivotY: Float = 0f,             // Pivot point Y
    val perspectiveX: Float = 0f,       // Perspective distortion X
    val perspectiveY: Float = 0f,       // Perspective distortion Y
    val isDistortionMode: Boolean = false,  // Free distortion mode
    val distortionCorners: List<Pair<Float, Float>> = emptyList()  // 4 corners for distortion
) {
    /**
     * Check if any transform is active (not identity)
     */
    fun hasActiveTransform(): Boolean {
        return translationX != 0f || translationY != 0f ||
                rotation != 0f ||
                scaleX != 1f || scaleY != 1f ||
                skewX != 0f || skewY != 0f ||
                perspectiveX != 0f || perspectiveY != 0f ||
                isDistortionMode
    }

    /**
     * Reset all transforms to identity
     */
    fun reset(): TransformState {
        return copy(
            translationX = 0f,
            translationY = 0f,
            rotation = 0f,
            scaleX = 1f,
            scaleY = 1f,
            skewX = 0f,
            skewY = 0f,
            perspectiveX = 0f,
            perspectiveY = 0f,
            isDistortionMode = false,
            distortionCorners = emptyList()
        )
    }

    /**
     * Create an Android Matrix from this transform state
     */
    fun toMatrix(): Matrix {
        val matrix = Matrix()

        // Apply transformations in order: translate -> rotate -> scale -> skew -> perspective
        matrix.preTranslate(translationX, translationY)
        matrix.preRotate(rotation, pivotX, pivotY)
        matrix.preScale(scaleX, scaleY, pivotX, pivotY)
        matrix.preSkew(skewX, skewY, pivotX, pivotY)

        // Apply perspective if needed
        if (perspectiveX != 0f || perspectiveY != 0f) {
            applyPerspective(matrix)
        }

        return matrix
    }

    /**
     * Apply perspective distortion to matrix
     */
    private fun applyPerspective(matrix: Matrix) {
        val perspectiveMatrix = Matrix()
        perspectiveMatrix.setValues(floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            perspectiveX / 1000f, perspectiveY / 1000f, 1f
        ))
        matrix.postConcat(perspectiveMatrix)
    }

    /**
     * Get the bounding box after transformation
     */
    fun getTransformedBounds(originalBounds: RectF): RectF {
        val matrix = toMatrix()
        val transformedBounds = RectF(originalBounds)
        matrix.mapRect(transformedBounds)
        return transformedBounds
    }
}

/**
 * Transform handle types for interactive manipulation
 */
enum class TransformHandleType {
    TOP_LEFT,         // Top-left corner (scale/rotate)
    TOP_RIGHT,        // Top-right corner (scale/rotate)
    BOTTOM_LEFT,      // Bottom-left corner (scale/rotate)
    BOTTOM_RIGHT,     // Bottom-right corner (scale/rotate)
    TOP_CENTER,       // Top edge (scale vertical)
    BOTTOM_CENTER,    // Bottom edge (scale vertical)
    LEFT_CENTER,      // Left edge (scale horizontal)
    RIGHT_CENTER,     // Right edge (scale horizontal)
    ROTATION,         // Rotation handle (above top-center)
    PIVOT,            // Pivot point (center by default)
    NONE              // No handle selected
}

/**
 * Transform operation types for undo/redo
 */
enum class TransformOperation {
    TRANSLATE,
    ROTATE,
    SCALE,
    SKEW,
    PERSPECTIVE,
    DISTORT,
    RESET
}

/**
 * Configuration for transform behavior
 */
data class TransformConfig(
    val enableSnapping: Boolean = true,       // Snap to guides
    val snapThreshold: Float = 5f,            // Pixels threshold for snapping
    val enableRotationSnap: Boolean = true,   // Snap rotation to 15° increments
    val rotationSnapAngle: Float = 15f,       // Rotation snap increment
    val enableUniformScale: Boolean = false,  // Lock aspect ratio
    val minScale: Float = 0.01f,              // Minimum scale (1%)
    val maxScale: Float = 10f,                // Maximum scale (1000%)
    val showGuides: Boolean = true,           // Show transform guides
    val handleSize: Float = 20f               // Handle touch target size
) {
    init {
        require(snapThreshold > 0) { "Snap threshold must be positive" }
        require(rotationSnapAngle > 0) { "Rotation snap angle must be positive" }
        require(minScale > 0) { "Minimum scale must be positive" }
        require(maxScale > minScale) { "Max scale must be greater than min scale" }
    }
}

/**
 * Snap guide information for visual feedback
 */
data class SnapGuide(
    val type: GuideType,
    val position: Float,
    val value: Float,  // The snapped value (angle, scale, etc.)
    val isSnapped: Boolean = false
)

enum class GuideType {
    HORIZONTAL,     // Horizontal guide line
    VERTICAL,       // Vertical guide line
    ANGLE,          // Angle guide (for rotation)
    CENTER,         // Center alignment guide
    EDGE            // Edge alignment guide
}
