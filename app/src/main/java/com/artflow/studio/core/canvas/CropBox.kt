package com.artflow.studio.core.canvas

import com.artflow.studio.core.pixels.IntBounds
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Crop & Resize's on-canvas box: which part a touch grabbed and how dragging it reshapes the box. */
object CropBox {
    /** Box edges in canvas pixels; [right] and [bottom] are exclusive. */
    data class Box(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top

        /** Whole pixels covered by the box, as inclusive bounds. */
        fun toBounds(): IntBounds = IntBounds(left.roundToInt(), top.roundToInt(), right.roundToInt() - 1, bottom.roundToInt() - 1)

        companion object {
            fun of(
                width: Int,
                height: Int,
            ) = Box(0f, 0f, width.toFloat(), height.toFloat())
        }
    }

    /** A locked crop proportion, as in Procreate's Crop & Resize aspect ratios. */
    data class Aspect(
        val width: Float,
        val height: Float,
        val label: String,
    ) {
        val ratio: Float get() = width / height

        companion object {
            val Square = Aspect(1f, 1f, "1:1")
            val FourThree = Aspect(4f, 3f, "4:3")
            val ThreeTwo = Aspect(3f, 2f, "3:2")
            val SixteenNine = Aspect(16f, 9f, "16:9")
            val presets = listOf(Square, FourThree, ThreeTwo, SixteenNine)
        }
    }

    /** What a touch grabbed: an edge or corner, or the box itself to move it. */
    data class Grip(
        val left: Boolean = false,
        val top: Boolean = false,
        val right: Boolean = false,
        val bottom: Boolean = false,
    ) {
        val moves: Boolean get() = !left && !top && !right && !bottom
    }

    /** The edges within [tolerance] of ([x], [y]); inside the box with none near, the whole box. */
    fun grip(
        box: Box,
        x: Float,
        y: Float,
        tolerance: Float,
    ): Grip? {
        val nearX = y in box.top - tolerance..box.bottom + tolerance
        val nearY = x in box.left - tolerance..box.right + tolerance
        val grip =
            Grip(
                left = nearX && abs(x - box.left) <= tolerance,
                top = nearY && abs(y - box.top) <= tolerance,
                right = nearX && abs(x - box.right) <= tolerance,
                bottom = nearY && abs(y - box.bottom) <= tolerance,
            )
        val inside = x in box.left..box.right && y in box.top..box.bottom
        return if (!grip.moves || inside) grip else null
    }

    /**
     * [start] after dragging [grip] by ([dx], [dy]), kept inside a [width] × [height] canvas.
     *
     * With an [aspect] the box keeps that proportion: dragging a corner anchors the opposite corner,
     * and dragging one edge grows or shrinks the other side around the box's centre, so the crop
     * never jumps. Moving the whole box ignores the lock, as it does in Procreate.
     */
    fun drag(
        start: Box,
        grip: Grip,
        dx: Float,
        dy: Float,
        width: Int,
        height: Int,
        aspect: Aspect? = null,
    ): Box {
        val free = freeDrag(start, grip, dx, dy, width, height)
        if (aspect == null || grip.moves) return free
        return constrained(start, free, grip, width, height, aspect)
    }

    private fun freeDrag(
        start: Box,
        grip: Grip,
        dx: Float,
        dy: Float,
        width: Int,
        height: Int,
    ): Box {
        val w = width.toFloat()
        val h = height.toFloat()
        if (grip.moves) {
            val x = dx.coerceIn(-start.left, w - start.right)
            val y = dy.coerceIn(-start.top, h - start.bottom)
            return Box(start.left + x, start.top + y, start.right + x, start.bottom + y)
        }
        val left = if (grip.left) (start.left + dx).coerceIn(0f, start.right - MIN_SIZE) else start.left
        val top = if (grip.top) (start.top + dy).coerceIn(0f, start.bottom - MIN_SIZE) else start.top
        val right = if (grip.right) (start.right + dx).coerceIn(left + MIN_SIZE, w) else start.right
        val bottom = if (grip.bottom) (start.bottom + dy).coerceIn(top + MIN_SIZE, h) else start.bottom
        return Box(left, top, right, bottom)
    }

    private fun constrained(
        start: Box,
        free: Box,
        grip: Grip,
        canvasWidth: Int,
        canvasHeight: Int,
        aspect: Aspect,
    ): Box {
        val ratio = aspect.ratio
        // The edge or centre the drag pivots on: the opposite side of the grabbed edge.
        val anchorX =
            when {
                grip.left -> free.right
                grip.right -> free.left
                else -> (start.left + start.right) / 2f
            }
        val anchorY =
            when {
                grip.top -> free.bottom
                grip.bottom -> free.top
                else -> (start.top + start.bottom) / 2f
            }
        val horizontal = grip.left || grip.right
        val vertical = grip.top || grip.bottom
        // A corner follows whichever axis the pointer moved further, so dragging out never shrinks the box.
        var boxWidth =
            when {
                horizontal && vertical ->
                    if (abs(free.width - start.width) >= abs(free.height - start.height)) free.width else free.height * ratio
                horizontal -> free.width
                else -> free.height * ratio
            }
        var boxHeight = boxWidth / ratio
        val roomX =
            when {
                !horizontal -> 2f * min(anchorX, canvasWidth.toFloat() - anchorX)
                grip.left -> anchorX
                else -> canvasWidth.toFloat() - anchorX
            }
        val roomY =
            when {
                !vertical -> 2f * min(anchorY, canvasHeight.toFloat() - anchorY)
                grip.top -> anchorY
                else -> canvasHeight.toFloat() - anchorY
            }
        val fit = min(1f, min(roomX / boxWidth, roomY / boxHeight))
        boxWidth *= fit
        boxHeight *= fit
        if (boxWidth < MIN_SIZE) {
            boxWidth = MIN_SIZE
            boxHeight = MIN_SIZE / ratio
        } else if (boxHeight < MIN_SIZE) {
            boxHeight = MIN_SIZE
            boxWidth = MIN_SIZE * ratio
        }
        val left =
            when {
                grip.left -> anchorX - boxWidth
                grip.right -> anchorX
                else -> anchorX - boxWidth / 2f
            }
        val top =
            when {
                grip.top -> anchorY - boxHeight
                grip.bottom -> anchorY
                else -> anchorY - boxHeight / 2f
            }
        // The fit above is computed from the pivot, so the result is inside the canvas by construction;
        // clamp against rounding so a locked box can never leave the artwork.
        val clampedLeft = left.coerceIn(0f, maxOf(0f, canvasWidth - boxWidth))
        val clampedTop = top.coerceIn(0f, maxOf(0f, canvasHeight - boxHeight))
        return Box(clampedLeft, clampedTop, clampedLeft + boxWidth, clampedTop + boxHeight)
    }

    private const val MIN_SIZE = 8f
}
