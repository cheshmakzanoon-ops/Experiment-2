package com.artflow.studio.domain.model.shape

import android.graphics.Path
import com.artflow.studio.domain.model.Color

/**
 * Domain model representing a vector shape
 * Implements Phase 24: Shape Tools
 */
sealed class VectorShape {
    abstract val id: Long
    abstract val name: String
    abstract val path: Path
    abstract val fillColor: Color?
    abstract val strokeColor: Color
    abstract val strokeWidth: Float
    abstract val isVisible: Boolean
    abstract val isFilled: Boolean
    abstract val transformationMatrix: FloatArray

    /**
     * Get the bounding box of this shape
     */
    abstract fun getBounds(): RectF

    /**
     * Check if a point is inside this shape
     */
    abstract fun containsPoint(x: Float, y: Float): Boolean

    /**
     * Create a copy of this shape with modified properties
     */
    abstract fun copyWith(
        id: Long = this.id,
        fillColor: Color? = this.fillColor,
        strokeColor: Color = this.strokeColor,
        strokeWidth: Float = this.strokeWidth,
        isVisible: Boolean = this.isVisible,
        isFilled: Boolean = this.isFilled,
        transformationMatrix: FloatArray = this.transformationMatrix
    ): VectorShape
}

/**
 * Rectangle shape
 */
data class RectangleShape(
    override val id: Long,
    override val name: String = "Rectangle",
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val cornerRadius: Float = 0f,
    override val fillColor: Color? = null,
    override val strokeColor: Color = Color.BLACK,
    override val strokeWidth: Float = 2f,
    override val isVisible: Boolean = true,
    override val isFilled: Boolean = true,
    override val transformationMatrix: FloatArray = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    )
) : VectorShape() {

    override val path: Path by lazy {
        createPath()
    }

    private fun createPath(): Path {
        return Path().apply {
            if (cornerRadius > 0) {
                addRoundRect(
                    x, y, x + width, y + height,
                    cornerRadius, cornerRadius,
                    Path.Direction.CW
                )
            } else {
                addRect(x, y, x + width, y + height, Path.Direction.CW)
            }
            transform(android.graphics.Matrix().apply {
                setValues(transformationMatrix)
            })
        }
    }

    override fun getBounds(): RectF {
        return RectF(x, y, x + width, y + height)
    }

    override fun containsPoint(x: Float, y: Float): Boolean {
        return x >= this.x && x <= this.x + width &&
               y >= this.y && y <= this.y + height
    }

    override fun copyWith(
        id: Long,
        fillColor: Color?,
        strokeColor: Color,
        strokeWidth: Float,
        isVisible: Boolean,
        isFilled: Boolean,
        transformationMatrix: FloatArray
    ): VectorShape {
        return copy(
            id = id,
            fillColor = fillColor,
            strokeColor = strokeColor,
            strokeWidth = strokeWidth,
            isVisible = isVisible,
            isFilled = isFilled,
            transformationMatrix = transformationMatrix
        )
    }
}

/**
 * Ellipse/Circle shape
 */
data class EllipseShape(
    override val id: Long,
    override val name: String = "Ellipse",
    val centerX: Float,
    val centerY: Float,
    val radiusX: Float,
    val radiusY: Float,
    override val fillColor: Color? = null,
    override val strokeColor: Color = Color.BLACK,
    override val strokeWidth: Float = 2f,
    override val isVisible: Boolean = true,
    override val isFilled: Boolean = true,
    override val transformationMatrix: FloatArray = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    )
) : VectorShape() {

    override val path: Path by lazy {
        createPath()
    }

    private fun createPath(): Path {
        return Path().apply {
            addOval(
                centerX - radiusX,
                centerY - radiusY,
                centerX + radiusX,
                centerY + radiusY,
                Path.Direction.CW
            )
            transform(android.graphics.Matrix().apply {
                setValues(transformationMatrix)
            })
        }
    }

    override fun getBounds(): RectF {
        return RectF(
            centerX - radiusX,
            centerY - radiusY,
            centerX + radiusX,
            centerY + radiusY
        )
    }

    override fun containsPoint(x: Float, y: Float): Boolean {
        val normalizedX = (x - centerX) / radiusX
        val normalizedY = (y - centerY) / radiusY
        return (normalizedX * normalizedX + normalizedY * normalizedY) <= 1.0f
    }

    override fun copyWith(
        id: Long,
        fillColor: Color?,
        strokeColor: Color,
        strokeWidth: Float,
        isVisible: Boolean,
        isFilled: Boolean,
        transformationMatrix: FloatArray
    ): VectorShape {
        return copy(
            id = id,
            fillColor = fillColor,
            strokeColor = strokeColor,
            strokeWidth = strokeWidth,
            isVisible = isVisible,
            isFilled = isFilled,
            transformationMatrix = transformationMatrix
        )
    }
}

/**
 * Polygon shape (triangle, pentagon, hexagon, etc.)
 */
data class PolygonShape(
    override val id: Long,
    override val name: String = "Polygon",
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val sides: Int = 3,
    val rotationDegrees: Float = 0f,
    override val fillColor: Color? = null,
    override val strokeColor: Color = Color.BLACK,
    override val strokeWidth: Float = 2f,
    override val isVisible: Boolean = true,
    override val isFilled: Boolean = true,
    override val transformationMatrix: FloatArray = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    )
) : VectorShape() {

    override val path: Path by lazy {
        createPath()
    }

    private fun createPath(): Path {
        return Path().apply {
            val angleStep = (2 * Math.PI / sides).toFloat()
            val rotationRad = Math.toRadians(rotationDegrees.toDouble()).toFloat()

            for (i in 0 until sides) {
                val angle = i * angleStep + rotationRad
                val px = centerX + radius * kotlin.math.cos(angle.toDouble()).toFloat()
                val py = centerY + radius * kotlin.math.sin(angle.toDouble()).toFloat()

                if (i == 0) {
                    moveTo(px, py)
                } else {
                    lineTo(px, py)
                }
            }
            close()

            transform(android.graphics.Matrix().apply {
                setValues(transformationMatrix)
            })
        }
    }

    override fun getBounds(): RectF {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        val angleStep = (2 * Math.PI / sides).toFloat()
        val rotationRad = Math.toRadians(rotationDegrees.toDouble()).toFloat()

        for (i in 0 until sides) {
            val angle = i * angleStep + rotationRad
            val px = centerX + radius * kotlin.math.cos(angle.toDouble()).toFloat()
            val py = centerY + radius * kotlin.math.sin(angle.toDouble()).toFloat()

            minX = minOf(minX, px)
            minY = minOf(minY, py)
            maxX = maxOf(maxX, px)
            maxY = maxOf(maxY, py)
        }

        return RectF(minX, minY, maxX, maxY)
    }

    override fun containsPoint(x: Float, y: Float): Boolean {
        // Point in polygon test using ray casting algorithm
        var inside = false
        val angleStep = (2 * Math.PI / sides).toFloat()
        val rotationRad = Math.toRadians(rotationDegrees.toDouble()).toFloat()

        val points = List(sides) { i ->
            val angle = i * angleStep + rotationRad
            Pair(
                centerX + radius * kotlin.math.cos(angle.toDouble()).toFloat(),
                centerY + radius * kotlin.math.sin(angle.toDouble()).toFloat()
            )
        }

        var j = sides - 1
        for (i in 0 until sides) {
            val xi = points[i].first
            val yi = points[i].second
            val xj = points[j].first
            val yj = points[j].second

            if (((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi)) {
                inside = !inside
            }
            j = i
        }

        return inside
    }

    override fun copyWith(
        id: Long,
        fillColor: Color?,
        strokeColor: Color,
        strokeWidth: Float,
        isVisible: Boolean,
        isFilled: Boolean,
        transformationMatrix: FloatArray
    ): VectorShape {
        return copy(
            id = id,
            fillColor = fillColor,
            strokeColor = strokeColor,
            strokeWidth = strokeWidth,
            isVisible = isVisible,
            isFilled = isFilled,
            transformationMatrix = transformationMatrix
        )
    }
}

/**
 * Line shape
 */
data class LineShape(
    override val id: Long,
    override val name: String = "Line",
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    override val fillColor: Color? = null,  // Not used for lines
    override val strokeColor: Color = Color.BLACK,
    override val strokeWidth: Float = 2f,
    override val isVisible: Boolean = true,
    override val isFilled: Boolean = false,
    override val transformationMatrix: FloatArray = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    ),
    val lineCap: LineCap = LineCap.ROUND,
    val isArrow: Boolean = false,
    val arrowSize: Float = 10f
) : VectorShape() {

    override val path: Path by lazy {
        createPath()
    }

    private fun createPath(): Path {
        return Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)

            if (isArrow) {
                addArrowHead(endX, endY)
            }

            transform(android.graphics.Matrix().apply {
                setValues(transformationMatrix)
            })
        }
    }

    private fun Path.addArrowHead(x: Float, y: Float) {
        val angle = kotlin.math.atan2(y - startY, x - startX).toFloat()
        val arrowAngle = Math.toRadians(30.0).toFloat()

        val x1 = x - arrowSize * kotlin.math.cos(angle - arrowAngle).toDouble().toFloat()
        val y1 = y - arrowSize * kotlin.math.sin(angle - arrowAngle).toDouble().toFloat()
        val x2 = x - arrowSize * kotlin.math.cos(angle + arrowAngle).toDouble().toFloat()
        val y2 = y - arrowSize * kotlin.math.sin(angle + arrowAngle).toDouble().toFloat()

        moveTo(x, y)
        lineTo(x1, y1)
        moveTo(x, y)
        lineTo(x2, y2)
    }

    override fun getBounds(): RectF {
        val minX = minOf(startX, endX) - strokeWidth
        val minY = minOf(startY, endY) - strokeWidth
        val maxX = maxOf(startX, endX) + strokeWidth
        val maxY = maxOf(startY, endY) + strokeWidth
        return RectF(minX, minY, maxX, maxY)
    }

    override fun containsPoint(x: Float, y: Float): Boolean {
        // Check if point is near the line (within stroke width)
        val distance = pointToLineDistance(x, y, startX, startY, endX, endY)
        return distance <= strokeWidth / 2
    }

    private fun pointToLineDistance(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val A = px - x1
        val B = py - y1
        val C = x2 - x1
        val D = y2 - y1

        val dot = A * C + B * D
        val lenSq = C * C + D * D
        var param = -1f

        if (lenSq != 0f) {
            param = dot / lenSq
        }

        val xx: Float
        val yy: Float

        when {
            param < 0 -> {
                xx = x1
                yy = y1
            }
            param > 1 -> {
                xx = x2
                yy = y2
            }
            else -> {
                xx = x1 + param * C
                yy = y1 + param * D
            }
        }

        val dx = px - xx
        val dy = py - yy
        return kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    override fun copyWith(
        id: Long,
        fillColor: Color?,
        strokeColor: Color,
        strokeWidth: Float,
        isVisible: Boolean,
        isFilled: Boolean,
        transformationMatrix: FloatArray
    ): VectorShape {
        return copy(
            id = id,
            fillColor = fillColor,
            strokeColor = strokeColor,
            strokeWidth = strokeWidth,
            isVisible = isVisible,
            isFilled = isFilled,
            transformationMatrix = transformationMatrix
        )
    }
}

/**
 * Line cap style enumeration
 */
enum class LineCap {
    ROUND,
    SQUARE,
    BUTT
}

/**
 * Rectangle float data class for bounds
 */
data class RectF(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2
}

/**
 * Shape type enumeration
 */
enum class ShapeType {
    RECTANGLE,
    ELLIPSE,
    POLYGON,
    LINE
}

/**
 * Event types for shape operations
 */
sealed class ShapeEvent {
    data class ShapeAdded(val shape: VectorShape) : ShapeEvent()
    data class ShapeRemoved(val shapeId: Long) : ShapeEvent()
    data class ShapeModified(val shapeId: Long) : ShapeEvent()
    data class ShapeSelected(val shapeId: Long) : ShapeEvent()
    data class ShapeVisibilityChanged(val shapeId: Long, val isVisible: Boolean) : ShapeEvent()
}
