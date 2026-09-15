package com.artflow.studio.data.renderer.native

import android.opengl.GLES20
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kotlin wrapper for the native brush engine (C++ via JNI)
 * Provides high-performance brush rendering using native code
 */
@Singleton
class NativeBrushEngine @Inject constructor() {

    private var isInitialized = false

    init {
        // Load native library
        System.loadLibrary("artflow-brush")
    }

    /**
     * Initialize the native brush engine
     * Must be called before any other methods
     * @return true if initialization succeeded
     */
    fun initialize(): Boolean {
        if (!isInitialized) {
            isInitialized = nativeInitialize()
        }
        return isInitialized
    }

    /**
     * Render a stroke with given parameters
     * @param points Array of stroke points [x, y, pressure, tiltX, tiltY, color, timestamp]
     * @param pointCount Number of points in the array
     * @param size Brush size in pixels
     * @param opacity Brush opacity (0.0 - 1.0)
     * @param spacing Spacing between dabs (0.0 - 1.0)
     * @param pressureToSize How much pressure affects size
     * @param pressureToOpacity How much pressure affects opacity
     * @param textureId OpenGL texture ID to render to
     */
    fun renderStroke(
        points: FloatArray,
        pointCount: Int,
        size: Float,
        opacity: Float,
        spacing: Float,
        pressureToSize: Float,
        pressureToOpacity: Float,
        textureId: Int
    ) {
        check(isInitialized) { "NativeBrushEngine not initialized" }
        nativeRenderStroke(
            points, pointCount,
            size, opacity, spacing,
            pressureToSize, pressureToOpacity,
            textureId
        )
    }

    /**
     * Interpolate stroke points for smoother rendering
     * @param points Input stroke points
     * @param pointCount Number of input points
     * @param spacing Desired spacing between interpolated points
     * @return Interpolated points array
     */
    fun interpolatePoints(
        points: FloatArray,
        pointCount: Int,
        spacing: Float
    ): FloatArray? {
        check(isInitialized) { "NativeBrushEngine not initialized" }
        return nativeInterpolatePoints(points, pointCount, spacing)
    }

    /**
     * Calculate brush size based on pressure
     * @param baseSize Base brush size
     * @param pressure Current pressure value (0.0 - 1.0)
     * @param pressureToSize Pressure influence factor
     * @return Calculated brush size
     */
    fun calculateSize(baseSize: Float, pressure: Float, pressureToSize: Float): Float {
        check(isInitialized) { "NativeBrushEngine not initialized" }
        return nativeCalculateSize(baseSize, pressure, pressureToSize)
    }

    /**
     * Calculate brush opacity based on pressure
     * @param baseOpacity Base brush opacity
     * @param pressure Current pressure value (0.0 - 1.0)
     * @param pressureToOpacity Pressure influence factor
     * @return Calculated opacity
     */
    fun calculateOpacity(baseOpacity: Float, pressure: Float, pressureToOpacity: Float): Float {
        check(isInitialized) { "NativeBrushEngine not initialized" }
        return nativeCalculateOpacity(baseOpacity, pressure, pressureToOpacity)
    }

    /**
     * Dispose native resources
     * Call when engine is no longer needed
     */
    fun dispose() {
        if (isInitialized) {
            nativeDispose()
            isInitialized = false
        }
    }

    // Native method declarations
    private external fun nativeInitialize(): Boolean
    private external fun nativeRenderStroke(
        points: FloatArray,
        pointCount: Int,
        size: Float,
        opacity: Float,
        spacing: Float,
        pressureToSize: Float,
        pressureToOpacity: Float,
        textureId: Int
    )
    private external fun nativeInterpolatePoints(
        points: FloatArray,
        pointCount: Int,
        spacing: Float
    ): FloatArray?
    private external fun nativeCalculateSize(
        baseSize: Float,
        pressure: Float,
        pressureToSize: Float
    ): Float
    private external fun nativeCalculateOpacity(
        baseOpacity: Float,
        pressure: Float,
        pressureToOpacity: Float
    ): Float
    private external fun nativeDispose()
}
