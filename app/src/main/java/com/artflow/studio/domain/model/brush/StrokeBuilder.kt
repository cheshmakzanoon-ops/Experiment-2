package com.artflow.studio.domain.model.brush

import android.graphics.Color
import android.graphics.Path
import android.graphics.PathMeasure

/**
 * Enhanced stroke builder with smoothing and interpolation algorithms
 * Implements Phase 7: Brush Engine Foundation - Stroke Smoothing
 */
class StrokeBuilder(
    private val brushParams: BrushParams,
    private val layerId: Long,
    private val color: Int = Color.BLACK
) {
    private val rawPoints = mutableListOf<StrokePoint>()
    private var strokeId: Long = 0
    private var isStarted = false
    
    /**
     * Start a new stroke with the initial point
     */
    fun start(x: Float, y: Float, pressure: Float = 1.0f): Long {
        strokeId = System.nanoTime()
        rawPoints.clear()
        
        val startPoint = StrokePoint(
            x = x,
            y = y,
            pressure = pressure
        )
        rawPoints.add(startPoint)
        isStarted = true
        
        return strokeId
    }
    
    /**
     * Add a point to the current stroke
     * Applies spacing filter to avoid too many points
     */
    fun addPoint(x: Float, y: Float, pressure: Float = 1.0f, tiltX: Float = 0f, tiltY: Float = 0f): Boolean {
        if (!isStarted) return false
        
        val lastPoint = rawPoints.lastOrNull() ?: run {
            // If no last point, this is actually the start
            rawPoints.add(StrokePoint(x, y, pressure, tiltX, tiltY))
            return true
        }
        
        // Apply minimum spacing filter to reduce point density
        val distance = lastPoint.distanceTo(StrokePoint(x, y))
        val minSpacing = when {
            brushParams.size < 10 -> 2f
            brushParams.size < 30 -> 4f
            else -> 6f
        }
        
        if (distance < minSpacing) {
            return false // Skip this point, too close to last
        }
        
        rawPoints.add(StrokePoint(x, y, pressure, tiltX, tiltY))
        return true
    }
    
    /**
     * End the stroke and return the smoothed result
     */
    fun end(): Stroke? {
        if (!isStarted || rawPoints.isEmpty()) return null
        
        isStarted = false
        
        // Apply smoothing algorithm
        val smoothedPoints = applySmoothing(rawPoints)
        
        // Apply tapering if enabled
        val taperedPoints = applyTapering(smoothedPoints)
        
        return Stroke(
            id = strokeId,
            points = taperedPoints,
            brushParams = brushParams,
            layerId = layerId,
            color = color
        )
    }
    
    /**
     * Apply linear interpolation smoothing to stroke points
     * This creates a smoother path by averaging adjacent points
     */
    private fun applySmoothing(points: List<StrokePoint>): List<StrokePoint> {
        if (points.size <= 2) return points
        
        val smoothingAmount = brushParams.smoothing.coerceIn(0f, 0.9f)
        if (smoothingAmount <= 0.01f) return points
        
        val smoothedPoints = mutableListOf<StrokePoint>()
        
        // Keep first point unchanged
        smoothedPoints.add(points[0])
        
        // Smooth middle points using weighted average
        for (i in 1 until points.size - 1) {
            val prevPoint = points[i - 1]
            val currPoint = points[i]
            val nextPoint = points[i + 1]
            
            // Calculate weighted average position
            val smoothFactor = smoothingAmount / 2f
            
            val smoothedX = currPoint.x * (1 - smoothingAmount) + 
                           (prevPoint.x + nextPoint.x) * smoothFactor
            
            val smoothedY = currPoint.y * (1 - smoothingAmount) + 
                           (prevPoint.y + nextPoint.y) * smoothFactor
            
            // Average pressure and tilt values
            val smoothedPressure = (prevPoint.pressure + currPoint.pressure + nextPoint.pressure) / 3f
            val smoothedTiltX = (prevPoint.tiltX + currPoint.tiltX + nextPoint.tiltX) / 3f
            val smoothedTiltY = (prevPoint.tiltY + currPoint.tiltY + nextPoint.tiltY) / 3f
            
            smoothedPoints.add(
                StrokePoint(
                    x = smoothedX,
                    y = smoothedY,
                    pressure = smoothedPressure.coerceIn(0f, 1f),
                    tiltX = smoothedTiltX,
                    tiltY = smoothedTiltY,
                    timestamp = currPoint.timestamp,
                    color = currPoint.color
                )
            )
        }
        
        // Keep last point unchanged
        smoothedPoints.add(points.last())
        
        return smoothedPoints
    }
    
    /**
     * Apply tapering to stroke ends for natural-looking line endings
     */
    private fun applyTapering(points: List<StrokePoint>): List<StrokePoint> {
        if (points.size < 4) return points
        
        val startTaper = brushParams.startTaper.coerceIn(0f, 1f)
        val endTaper = brushParams.endTaper.coerceIn(0f, 1f)
        
        if (startTaper <= 0f && endTaper <= 0f) return points
        
        val taperedPoints = points.toMutableList()
        val taperLength = (points.size * 0.15f).toInt().coerceAtLeast(2)
        
        // Apply start taper - gradually increase pressure from 0
        if (startTaper > 0f) {
            for (i in 0 until taperLength.coerceAtMost(taperedPoints.size / 2)) {
                val t = i.toFloat() / taperLength
                val taperedPressure = t * startTaper + (1 - t) * 0.1f
                val originalPoint = taperedPoints[i]
                taperedPoints[i] = originalPoint.copy(
                    pressure = (originalPoint.pressure * taperedPressure).coerceIn(0.1f, 1f)
                )
            }
        }
        
        // Apply end taper - gradually decrease pressure to 0
        if (endTaper > 0f) {
            val startIndex = taperedPoints.size - taperLength
            for (i in startIndex until taperedPoints.size) {
                val t = (taperedPoints.size - i).toFloat() / taperLength
                val taperedPressure = t * endTaper + (1 - t) * 0.1f
                val originalPoint = taperedPoints[i]
                taperedPoints[i] = originalPoint.copy(
                    pressure = (originalPoint.pressure * taperedPressure).coerceIn(0.1f, 1f)
                )
            }
        }
        
        return taperedPoints
    }
    
    /**
     * Get current raw points (for preview during stroke)
     */
    fun getCurrentPoints(): List<StrokePoint> = rawPoints.toList()
    
    /**
     * Check if stroke is in progress
     */
    fun isInProgress(): Boolean = isStarted
    
    /**
     * Get stroke ID
     */
    fun getStrokeId(): Long = strokeId
}

/**
 * Stroke cache for quick undo/redo operations
 * Stores rendered stroke data for fast restoration
 */
data class StrokeCache(
    val strokeId: Long,
    val layerId: Long,
    val strokeData: ByteArray,  // Serialized stroke points
    val thumbnailData: ByteArray?, // Optional thumbnail preview
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        private const val MAX_CACHE_SIZE = 100
        private val cacheMap = mutableMapOf<Long, StrokeCache>()
        
        /**
         * Add stroke to cache
         */
        fun store(stroke: Stroke, thumbnailData: ByteArray? = null) {
            if (cacheMap.size >= MAX_CACHE_SIZE) {
                // Remove oldest entry
                val oldestKey = cacheMap.minByOrNull { it.value.timestamp }?.key
                oldestKey?.let { cacheMap.remove(it) }
            }
            
            val serializedData = serializeStroke(stroke)
            cacheMap[stroke.id] = StrokeCache(
                strokeId = stroke.id,
                layerId = stroke.layerId,
                strokeData = serializedData,
                thumbnailData = thumbnailData
            )
        }
        
        /**
         * Retrieve stroke from cache
         */
        fun retrieve(strokeId: Long): Stroke? {
            val cached = cacheMap[strokeId] ?: return null
            return deserializeStroke(cached.strokeData, cached.layerId)
        }
        
        /**
         * Remove specific stroke from cache
         */
        fun remove(strokeId: Long) {
            cacheMap.remove(strokeId)
        }
        
        /**
         * Clear entire cache
         */
        fun clear() {
            cacheMap.clear()
        }
        
        /**
         * Serialize stroke to byte array
         */
        private fun serializeStroke(stroke: Stroke): ByteArray {
            // Simple serialization format:
            // [point_count][x1][y1][pressure1]...[xN][yN][pressureN][brush_params][color]
            val buffer = java.nio.ByteBuffer.allocate(
                4 + // point count
                stroke.points.size * 16 + // x,y,pressure per point (4 bytes each)
                32 + // brush params
                4 // color
            )
            
            buffer.putInt(stroke.points.size)
            stroke.points.forEach { point ->
                buffer.putFloat(point.x)
                buffer.putFloat(point.y)
                buffer.putFloat(point.pressure)
                buffer.putFloat(point.tiltX)
            }
            
            // Store key brush parameters
            buffer.putFloat(stroke.brushParams.size)
            buffer.putFloat(stroke.brushParams.opacity)
            buffer.putFloat(stroke.brushParams.smoothing)
            
            buffer.putInt(stroke.color)
            
            return buffer.array()
        }
        
        /**
         * Deserialize stroke from byte array
         */
        private fun deserializeStroke(data: ByteArray, layerId: Long): Stroke? {
            try {
                val buffer = java.nio.ByteBuffer.wrap(data)
                
                val pointCount = buffer.int
                if (pointCount <= 0) return null
                
                val points = mutableListOf<StrokePoint>()
                repeat(pointCount) {
                    val x = buffer.float
                    val y = buffer.float
                    val pressure = buffer.float
                    val tiltX = buffer.float
                    
                    points.add(StrokePoint(x, y, pressure, tiltX, 0f))
                }
                
                val size = buffer.float
                val opacity = buffer.float
                val smoothing = buffer.float
                
                val color = buffer.int
                
                val brushParams = BrushParams(
                    size = size,
                    opacity = opacity,
                    smoothing = smoothing
                )
                
                return Stroke(
                    id = System.nanoTime(),
                    points = points,
                    brushParams = brushParams,
                    layerId = layerId,
                    color = color
                )
            } catch (e: Exception) {
                return null
            }
        }
    }
}

/**
 * Helper extension functions for stroke calculations
 */
fun List<StrokePoint>.calculatePath(): Path {
    val path = Path()
    if (isEmpty()) return path
    
    path.moveTo(first().x, first().y)
    
    for (i in 1 until size) {
        val prevPoint = this[i - 1]
        val currPoint = this[i]
        
        // Use quadratic bezier for smoother connections
        val midX = (prevPoint.x + currPoint.x) / 2f
        val midY = (prevPoint.y + currPoint.y) / 2f
        
        path.quadTo(prevPoint.x, prevPoint.y, midX, midY)
    }
    
    // Connect to last point
    if (size > 1) {
        val lastPoint = last()
        val secondLastPoint = this[size - 2]
        val midX = (secondLastPoint.x + lastPoint.x) / 2f
        val midY = (secondLastPoint.y + lastPoint.y) / 2f
        path.quadTo(midX, midY, lastPoint.x, lastPoint.y)
    }
    
    return path
}

/**
 * Calculate total length of stroke path
 */
fun List<StrokePoint>.calculatePathLength(): Float {
    if (size < 2) return 0f
    
    var totalLength = 0f
    for (i in 1 until size) {
        totalLength += this[i].distanceTo(this[i - 1])
    }
    
    return totalLength
}

/**
 * Resample points to have uniform spacing
 * Useful for consistent brush rendering
 */
fun List<StrokePoint>.resampleUniformly(targetSpacing: Float): List<StrokePoint> {
    if (size < 2 || targetSpacing <= 0) return this
    
    val totalLength = calculatePathLength()
    if (totalLength <= 0) return this
    
    val resampledPoints = mutableListOf<StrokePoint>()
    resampledPoints.add(first())
    
    var accumulatedDistance = 0f
    var nextPointIndex = 1
    
    while (nextPointIndex < size) {
        val currentPoint = resampledPoints.last()
        val targetPoint = this[nextPointIndex]
        
        val distanceToTarget = currentPoint.distanceTo(targetPoint)
        accumulatedDistance += distanceToTarget
        
        if (accumulatedDistance >= targetSpacing) {
            // Interpolate point at exact spacing
            val ratio = targetSpacing / accumulatedDistance
            val interpolatedX = currentPoint.x + (targetPoint.x - currentPoint.x) * ratio
            val interpolatedY = currentPoint.y + (targetPoint.y - currentPoint.y) * ratio
            val interpolatedPressure = currentPoint.pressure + (targetPoint.pressure - currentPoint.pressure) * ratio
            
            resampledPoints.add(
                StrokePoint(interpolatedX, interpolatedY, interpolatedPressure)
            )
            
            accumulatedDistance = 0f
        } else {
            nextPointIndex++
        }
    }
    
    // Always include the last point
    if (resampledPoints.last().distanceTo(last()) > 1f) {
        resampledPoints.add(last())
    }
    
    return resampledPoints
}
