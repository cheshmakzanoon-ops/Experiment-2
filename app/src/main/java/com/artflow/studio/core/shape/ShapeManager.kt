package com.artflow.studio.core.shape

import com.artflow.studio.domain.model.Color
import com.artflow.studio.domain.model.shape.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Core shape management system for ArtFlow
 * Implements Phase 24: Shape Tools
 * 
 * This singleton manages vector shapes, handling all shape operations including:
 * - Shape creation (rectangle, ellipse, polygon, line)
 * - Shape modification (resize, rotate, transform)
 * - Shape styling (fill, stroke, corner radius)
 * - Shape Boolean operations (union, intersect, subtract)
 */
@Singleton
class ShapeManager @Inject constructor() {

    private val _shapes = MutableStateFlow<List<VectorShape>>(emptyList())
    val shapes: StateFlow<List<VectorShape>> = _shapes.asStateFlow()

    private val _selectedShapeId = MutableStateFlow<Long?>(null)
    val selectedShapeId: StateFlow<Long?> = _selectedShapeId.asStateFlow()

    private var nextShapeId = 1L

    /**
     * Create a rectangle shape
     * @param x X position of top-left corner
     * @param y Y position of top-left corner
     * @param width Width of rectangle
     * @param height Height of rectangle
     * @param cornerRadius Optional corner radius for rounded rectangles
     * @param fillColor Fill color (null for no fill)
     * @param strokeColor Stroke color
     * @param strokeWidth Stroke width
     * @return The created RectangleShape
     */
    fun createRectangle(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        cornerRadius: Float = 0f,
        fillColor: Color? = null,
        strokeColor: Color = Color.BLACK,
        strokeWidth: Float = 2f
    ): RectangleShape {
        val shape = RectangleShape(
            id = nextShapeId++,
            name = "Rectangle ${nextShapeId - 1}",
            x = x,
            y = y,
            width = width,
            height = height,
            cornerRadius = cornerRadius,
            fillColor = fillColor,
            strokeColor = strokeColor,
            strokeWidth = strokeWidth
        )

        addShape(shape)
        Timber.d("Rectangle created: ${shape.name} at ($x, $y) ${width}x$height")
        return shape
    }

    /**
     * Create an ellipse/circle shape
     * @param centerX X position of center
     * @param centerY Y position of center
     * @param radiusX Horizontal radius
     * @param radiusY Vertical radius (same as radiusX for circle)
     * @param fillColor Fill color (null for no fill)
     * @param strokeColor Stroke color
     * @param strokeWidth Stroke width
     * @return The created EllipseShape
     */
    fun createEllipse(
        centerX: Float,
        centerY: Float,
        radiusX: Float,
        radiusY: Float = radiusX,
        fillColor: Color? = null,
        strokeColor: Color = Color.BLACK,
        strokeWidth: Float = 2f
    ): EllipseShape {
        val shape = EllipseShape(
            id = nextShapeId++,
            name = if (radiusX == radiusY) "Circle ${nextShapeId - 1}" else "Ellipse ${nextShapeId - 1}",
            centerX = centerX,
            centerY = centerY,
            radiusX = radiusX,
            radiusY = radiusY,
            fillColor = fillColor,
            strokeColor = strokeColor,
            strokeWidth = strokeWidth
        )

        addShape(shape)
        Timber.d("Ellipse created: ${shape.name} at ($centerX, $centerY) r=$radiusX")
        return shape
    }

    /**
     * Create a polygon shape
     * @param centerX X position of center
     * @param centerY Y position of center
     * @param radius Radius of the polygon
     * @param sides Number of sides (3 = triangle, 5 = pentagon, 6 = hexagon, etc.)
     * @param rotationDegrees Initial rotation in degrees
     * @param fillColor Fill color (null for no fill)
     * @param strokeColor Stroke color
     * @param strokeWidth Stroke width
     * @return The created PolygonShape
     */
    fun createPolygon(
        centerX: Float,
        centerY: Float,
        radius: Float,
        sides: Int = 3,
        rotationDegrees: Float = 0f,
        fillColor: Color? = null,
        strokeColor: Color = Color.BLACK,
        strokeWidth: Float = 2f
    ): PolygonShape {
        require(sides >= 3) { "Polygon must have at least 3 sides" }

        val shape = PolygonShape(
            id = nextShapeId++,
            name = "Polygon ${nextShapeId - 1} ($sides sides)",
            centerX = centerX,
            centerY = centerY,
            radius = radius,
            sides = sides,
            rotationDegrees = rotationDegrees,
            fillColor = fillColor,
            strokeColor = strokeColor,
            strokeWidth = strokeWidth
        )

        addShape(shape)
        Timber.d("Polygon created: ${shape.name} at ($centerX, $centerY) r=$radius sides=$sides")
        return shape
    }

    /**
     * Create a line shape
     * @param startX Start X position
     * @param startY Start Y position
     * @param endX End X position
     * @param endY End Y position
     * @param strokeColor Stroke color
     * @param strokeWidth Stroke width
     * @param lineCap Line cap style
     * @param isArrow Whether to draw arrow head at end
     * @return The created LineShape
     */
    fun createLine(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        strokeColor: Color = Color.BLACK,
        strokeWidth: Float = 2f,
        lineCap: LineCap = LineCap.ROUND,
        isArrow: Boolean = false
    ): LineShape {
        val shape = LineShape(
            id = nextShapeId++,
            name = "Line ${nextShapeId - 1}",
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            strokeColor = strokeColor,
            strokeWidth = strokeWidth,
            lineCap = lineCap,
            isArrow = isArrow
        )

        addShape(shape)
        Timber.d("Line created: ${shape.name} from ($startX, $startY) to ($endX, $endY)")
        return shape
    }

    /**
     * Add a shape to the manager
     */
    private fun addShape(shape: VectorShape) {
        val updatedShapes = _shapes.value + shape
        _shapes.value = updatedShapes
        _selectedShapeId.value = shape.id
    }

    /**
     * Get the currently selected shape
     */
    fun getSelectedShape(): VectorShape? {
        val id = _selectedShapeId.value ?: return null
        return _shapes.value.find { it.id == id }
    }

    /**
     * Select a shape by ID
     * @param shapeId The shape ID to select
     * @return True if shape exists
     */
    fun selectShape(shapeId: Long): Boolean {
        val shape = _shapes.value.find { it.id == shapeId } ?: return false
        _selectedShapeId.value = shapeId
        Timber.d("Shape selected: ${shape.name}")
        return true
    }

    /**
     * Remove a shape by ID
     * @param shapeId The shape ID to remove
     * @return True if removed successfully
     */
    fun removeShape(shapeId: Long): Boolean {
        val shape = _shapes.value.find { it.id == shapeId } ?: return false
        
        val updatedShapes = _shapes.value.filter { it.id != shapeId }
        _shapes.value = updatedShapes
        
        // If we removed the selected shape, clear selection
        if (_selectedShapeId.value == shapeId) {
            _selectedShapeId.value = null
        }
        
        Timber.d("Shape removed: ${shape.name}")
        return true
    }

    /**
     * Update shape fill color
     * @param shapeId The shape ID
     * @param fillColor New fill color (null for no fill)
     * @return True if updated
     */
    fun setShapeFillColor(shapeId: Long, fillColor: Color?): Boolean {
        val shape = _shapes.value.find { it.id == shapeId } ?: return false
        
        _shapes.value = _shapes.value.map { s ->
            if (s.id == shapeId) {
                s.copyWith(fillColor = fillColor)
            } else {
                s
            }
        }
        
        Timber.d("Shape fill color updated: ${shape.name}")
        return true
    }

    /**
     * Update shape stroke color
     * @param shapeId The shape ID
     * @param strokeColor New stroke color
     * @return True if updated
     */
    fun setShapeStrokeColor(shapeId: Long, strokeColor: Color): Boolean {
        val shape = _shapes.value.find { it.id == shapeId } ?: return false
        
        _shapes.value = _shapes.value.map { s ->
            if (s.id == shapeId) {
                s.copyWith(strokeColor = strokeColor)
            } else {
                s
            }
        }
        
        Timber.d("Shape stroke color updated: ${shape.name}")
        return true
    }

    /**
     * Update shape stroke width
     * @param shapeId The shape ID
     * @param strokeWidth New stroke width
     * @return True if updated
     */
    fun setShapeStrokeWidth(shapeId: Long, strokeWidth: Float): Boolean {
        val clampedWidth = strokeWidth.coerceAtLeast(0.1f)
        val shape = _shapes.value.find { it.id == shapeId } ?: return false
        
        _shapes.value = _shapes.value.map { s ->
            if (s.id == shapeId) {
                s.copyWith(strokeWidth = clampedWidth)
            } else {
                s
            }
        }
        
        Timber.d("Shape stroke width updated: ${shape.name} -> $clampedWidth")
        return true
    }

    /**
     * Toggle shape visibility
     * @param shapeId The shape ID
     * @param isVisible Visibility state
     * @return True if updated
     */
    fun setShapeVisibility(shapeId: Long, isVisible: Boolean): Boolean {
        val shape = _shapes.value.find { it.id == shapeId } ?: return false
        
        _shapes.value = _shapes.value.map { s ->
            if (s.id == shapeId) {
                s.copyWith(isVisible = isVisible)
            } else {
                s
            }
        }
        
        Timber.d("Shape visibility changed: ${shape.name} -> $isVisible")
        return true
    }

    /**
     * Apply transformation matrix to a shape
     * @param shapeId The shape ID
     * @param matrix The transformation matrix (9 values for 3x3 matrix)
     * @return True if updated
     */
    fun applyTransformation(shapeId: Long, matrix: FloatArray): Boolean {
        require(matrix.size == 9) { "Transformation matrix must have 9 values" }
        val shape = _shapes.value.find { it.id == shapeId } ?: return false
        
        _shapes.value = _shapes.value.map { s ->
            if (s.id == shapeId) {
                s.copyWith(transformationMatrix = matrix.copyOf())
            } else {
                s
            }
        }
        
        Timber.d("Shape transformation applied: ${shape.name}")
        return true
    }

    /**
     * Get all visible shapes
     */
    fun getVisibleShapes(): List<VectorShape> {
        return _shapes.value.filter { it.isVisible }
    }

    /**
     * Get shape count
     */
    fun getShapeCount(): Int = _shapes.value.size

    /**
     * Clear all shapes
     */
    fun clearAllShapes() {
        _shapes.value = emptyList()
        _selectedShapeId.value = null
        Timber.d("All shapes cleared")
    }

    /**
     * Check if a point intersects with any shape
     * @param x X coordinate
     * @param y Y coordinate
     * @return The topmost shape that contains the point, or null
     */
    fun getShapeAtPoint(x: Float, y: Float): VectorShape? {
        // Iterate from top to bottom (reverse order)
        return _shapes.value.asReversed().find { shape ->
            shape.isVisible && shape.containsPoint(x, y)
        }
    }

    /**
     * Duplicate a shape
     * @param shapeId The shape ID to duplicate
     * @return The new duplicated shape or null if failed
     */
    fun duplicateShape(shapeId: Long): VectorShape? {
        val originalShape = _shapes.value.find { it.id == shapeId } ?: return null
        
        val duplicatedShape = originalShape.copyWith(
            id = nextShapeId++
        )
        
        _shapes.value = _shapes.value + duplicatedShape
        _selectedShapeId.value = duplicatedShape.id
        
        Timber.d("Shape duplicated: ${originalShape.name} -> ${duplicatedShape.name}")
        return duplicatedShape
    }
}
