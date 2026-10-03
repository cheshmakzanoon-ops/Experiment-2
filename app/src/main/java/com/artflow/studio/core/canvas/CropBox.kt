package com.artflow.studio.core.canvas

import com.artflow.studio.core.pixels.IntBounds
import kotlin.math.abs
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

    /** [start] after dragging [grip] by ([dx], [dy]), kept inside a [width] × [height] canvas. */
    fun drag(
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

    private const val MIN_SIZE = 8f
}
