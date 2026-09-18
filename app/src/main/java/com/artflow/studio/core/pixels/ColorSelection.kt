package com.artflow.studio.core.pixels

import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Alpha-aware colour matching. Hidden RGB must not distinguish two transparent pixels. */
internal object ColorSelection {
    fun wand(
        buffer: PixelBuffer,
        x: Int,
        y: Int,
        tolerance: Int,
        contiguous: Boolean,
        antiAlias: Boolean,
        limit: SelectionMask?,
        checkActive: () -> Unit,
    ): SelectionMask {
        checkActive()
        if (!buffer.contains(x, y)) return SelectionMask(buffer.width, buffer.height)
        val target = buffer.getUnchecked(x, y)
        val hard = tolerance.coerceIn(0, 255) * 3f
        val mask =
            if (contiguous) {
                ConnectedRegion(buffer, target, hard * hard, checkActive).fill(x, y)
            } else {
                range(buffer, target, tolerance, antiAlias, checkActive)
            }
        val result = if (contiguous && antiAlias && tolerance > 0) soften(mask, buffer, target, hard, checkActive) else mask
        if (limit != null) {
            for (row in 0 until result.height) {
                checkActive()
                for (column in 0 until result.width) {
                    val index = row * result.width + column
                    result.coverage[index] = (result.coverageAt(column, row) * limit.coverageAt(column, row) / 255).toByte()
                }
            }
        }
        return result
    }

    fun range(
        buffer: PixelBuffer,
        color: Int,
        tolerance: Int,
        antiAlias: Boolean,
        checkActive: () -> Unit,
    ): SelectionMask {
        checkActive()
        val out = SelectionMask(buffer.width, buffer.height)
        val hard = tolerance.coerceIn(0, 255) * 3f
        for (y in 0 until buffer.height) {
            checkActive()
            for (x in 0 until buffer.width) {
                val index = y * buffer.width + x
                out.coverage[index] = coverage(buffer.pixels[index], color, hard, antiAlias && tolerance > 0).toByte()
            }
        }
        return out
    }

    private fun distanceSquared(
        a: Int,
        b: Int,
    ): Float {
        val aa = Channels.alpha(a)
        val ba = Channels.alpha(b)
        val da = aa - ba
        val dr = (Channels.red(a) * aa - Channels.red(b) * ba) / 255f
        val dg = (Channels.green(a) * aa - Channels.green(b) * ba) / 255f
        val db = (Channels.blue(a) * aa - Channels.blue(b) * ba) / 255f
        return da * da + dr * dr + dg * dg + db * db
    }

    private fun coverage(
        pixel: Int,
        target: Int,
        hard: Float,
        antiAlias: Boolean,
    ): Int {
        val distance = sqrt(distanceSquared(pixel, target))
        if (distance <= hard) return 255
        if (!antiAlias || distance >= hard + 30f) return 0
        return ((hard + 30f - distance) / 30f * 255f).roundToInt().coerceIn(0, 255)
    }

    /** A one-pixel soft fringe cannot connect another hard island through a weak-colour bridge. */
    private fun soften(
        mask: SelectionMask,
        buffer: PixelBuffer,
        target: Int,
        hard: Float,
        checkActive: () -> Unit,
    ): SelectionMask {
        val out = mask.copy()
        for (y in 0 until mask.height) {
            checkActive()
            for (x in 0 until mask.width) {
                val index = y * mask.width + x
                if (mask.coverage[index].toInt() != 0) continue
                val adjacent =
                    mask.coverageAt(x - 1, y) + mask.coverageAt(x + 1, y) +
                        mask.coverageAt(x, y - 1) + mask.coverageAt(x, y + 1) > 0
                if (adjacent) out.coverage[index] = coverage(buffer.pixels[index], target, hard, true).toByte()
            }
        }
        return out
    }

    /** Discover/mark entire runs BEFORE queuing. No recursion, per-pixel objects or duplicate entries. */
    private class ConnectedRegion(
        val source: PixelBuffer,
        val target: Int,
        val threshold: Float,
        val checkActive: () -> Unit,
    ) {
        private val mask = SelectionMask(source.width, source.height)
        private var stack = IntArray(min(128, source.size * 2))
        private var top = 0

        fun fill(
            x: Int,
            y: Int,
        ): SelectionMask {
            discover(x, y)
            while (top > 0) {
                checkActive()
                val right = stack[--top]
                val left = stack[--top]
                val row = left / source.width
                if (row > 0) scan(left % source.width, right % source.width, row - 1)
                if (row + 1 < source.height) scan(left % source.width, right % source.width, row + 1)
            }
            return mask
        }

        private fun scan(
            left: Int,
            right: Int,
            y: Int,
        ) {
            var x = left
            while (x <= right) x = discover(x, y)
        }

        private fun unseen(index: Int): Boolean =
            mask.coverage[index].toInt() == 0 && distanceSquared(source.pixels[index], target) <= threshold

        /** Returns the next column after this run, or after the rejected seed. */
        private fun discover(
            x: Int,
            y: Int,
        ): Int {
            val row = y * source.width
            if (!unseen(row + x)) return x + 1
            var left = x
            var right = x
            while (left > 0 && unseen(row + left - 1)) left--
            while (right + 1 < source.width && unseen(row + right + 1)) right++
            java.util.Arrays.fill(mask.coverage, row + left, row + right + 1, 255.toByte())
            if (top + 2 > stack.size) stack = stack.copyOf(min(source.size * 2, stack.size * 2))
            stack[top++] = row + left
            stack[top++] = row + right
            return right + 1
        }
    }
}
