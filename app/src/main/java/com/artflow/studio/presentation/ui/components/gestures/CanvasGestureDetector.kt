package com.artflow.studio.presentation.ui.components.gestures

import android.view.MotionEvent
import kotlin.math.abs

/**
 * Custom gesture detector for canvas-specific gestures
 * Implements Procreate-like gesture controls:
 * - Two-finger tap: Undo
 * - Three-finger tap: Redo
 * - Pinch: Zoom
 * - Rotation: Canvas rotation
 */
class CanvasGestureDetector(
    private val onTwoFingerTap: () -> Unit,
    private val onThreeFingerTap: () -> Unit,
    private val onRotate: (Float) -> Unit = {},
    private val onZoom: (Float) -> Unit = {}
) {
    
    private var activePointerCount = 0
    private var lastPointerCount = 0
    private var rotationAngle = 0f
    private var lastRotationAngle = 0f
    
    // Track finger positions for rotation detection
    private var finger1X = 0f
    private var finger1Y = 0f
    private var finger2X = 0f
    private var finger2Y = 0f
    
    /**
     * Process touch event for gesture detection
     * @return true if gesture was handled, false otherwise
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        val pointerCount = event.pointerCount
        val action = event.actionMasked
        
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                activePointerCount = 1
                lastPointerCount = 1
            }
            
            MotionEvent.ACTION_POINTER_DOWN -> {
                activePointerCount = pointerCount
                lastPointerCount = pointerCount
                
                // Store finger positions for rotation calculation
                if (pointerCount >= 2) {
                    finger1X = event.getX(0)
                    finger1Y = event.getY(0)
                    finger2X = event.getX(1)
                    finger2Y = event.getY(1)
                    lastRotationAngle = calculateRotation()
                }
            }
            
            MotionEvent.ACTION_MOVE -> {
                // Handle rotation with two fingers
                if (pointerCount == 2) {
                    finger1X = event.getX(0)
                    finger1Y = event.getY(0)
                    finger2X = event.getX(1)
                    finger2Y = event.getY(1)
                    
                    val currentRotation = calculateRotation()
                    val deltaRotation = currentRotation - lastRotationAngle
                    
                    if (abs(deltaRotation) > ROTATION_THRESHOLD) {
                        onRotate(deltaRotation)
                        lastRotationAngle = currentRotation
                    }
                }
            }
            
            MotionEvent.ACTION_UP -> {
                // Check for multi-finger taps on release
                if (lastPointerCount == 2) {
                    onTwoFingerTap()
                } else if (lastPointerCount == 3) {
                    onThreeFingerTap()
                }
                
                activePointerCount = 0
                lastPointerCount = 0
                rotationAngle = 0f
            }
            
            MotionEvent.ACTION_POINTER_UP -> {
                // When a finger is lifted, check if we had a multi-finger tap
                val remainingPointers = pointerCount - 1
                if (remainingPointers == 0) {
                    if (lastPointerCount == 2) {
                        onTwoFingerTap()
                    } else if (lastPointerCount == 3) {
                        onThreeFingerTap()
                    }
                }
                activePointerCount = remainingPointers
            }
            
            MotionEvent.ACTION_CANCEL -> {
                activePointerCount = 0
                lastPointerCount = 0
            }
        }
        
        return true
    }
    
    /**
     * Calculate the angle between two fingers
     */
    private fun calculateRotation(): Float {
        val deltaX = finger2X - finger1X
        val deltaY = finger2Y - finger1Y
        return Math.toDegrees(Math.atan2(deltaY.toDouble(), deltaX.toDouble())).toFloat()
    }
    
    companion object {
        private const val ROTATION_THRESHOLD = 5f // degrees
    }
}

/**
 * Simple gesture listener wrapper for standard Android GestureDetector
 */
class CanvasGestureListener(
    private val onDoubleTap: () -> Unit = {},
    private val onLongPress: () -> Unit = {}
) : android.view.GestureDetector.SimpleOnGestureListener() {
    
    override fun onDoubleTap(e: MotionEvent?): Boolean {
        onDoubleTap()
        return true
    }
    
    override fun onLongPress(e: MotionEvent?) {
        onLongPress()
    }
    
    override fun onDown(e: MotionEvent?): Boolean {
        return true
    }
}
