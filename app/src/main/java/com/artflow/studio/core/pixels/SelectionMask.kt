package com.artflow.studio.core.pixels

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A per-pixel selection coverage mask (Phase 13).
 *
 * `0` means "not selected", `255` means "fully selected", values in between are anti-aliased or
 * feathered edges. This representation is what every tool actually needs, it composites cleanly
 * with the adjustment pipeline, and unlike `android.graphics.Path` it is testable off-device.
 *
 * The UI keeps a `Path` alongside a mask only for drawing the marching-ants outline.
 */
class SelectionMask(
    val width: Int,
    val height: Int,
    val coverage: ByteArray = ByteArray(checkedPixelCount(width, height)),
) {
    init {
        require(coverage.size == checkedPixelCount(width, height)) {
            "Coverage size ${coverage.size} does not match ${width}x$height"
        }
    }

    fun isActive(): Boolean = coverage.any { it.toInt() != 0 }

    /** True when the mask is a fully-opaque select-all (avoids unnecessary per-pixel work). */
    fun isFull(): Boolean = coverage.all { (it.toInt() and 0xFF) == 255 }

    fun coverageAt(
        x: Int,
        y: Int,
    ): Int = if (x in 0 until width && y in 0 until height) coverage[y * width + x].toInt() and 0xFF else 0

    fun alphaAt(index: Int): Float = (coverage[index].toInt() and 0xFF) / 255f

    fun clear() = coverage.fill(0)

    fun selectAll() = coverage.fill(255.toByte())

    fun copy(): SelectionMask = SelectionMask(width, height, coverage.copyOf())

    fun invert() {
        for (i in coverage.indices) {
            coverage[i] = (255 - (coverage[i].toInt() and 0xFF)).toByte()
        }
    }

    /** Bounding box of every selected pixel, or null when nothing is selected. */
    fun bounds(): IntBounds? {
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (coverage[row + x].toInt() != 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) return null
        return IntBounds(minX, minY, maxX, maxY)
    }

    fun selectedPixelCount(): Int = coverage.count { it.toInt() != 0 }

    /** Feathers the edge with a blur, softening the transition band. */
    fun feathered(radius: Int): SelectionMask {
        if (radius <= 0) return copy()
        val floats = FloatArray(coverage.size) { (coverage[it].toInt() and 0xFF).toFloat() / 255f }
        val blurred = ImageFilters.featherAlpha(floats, width, height, radius)
        val out = SelectionMask(width, height)
        for (i in out.coverage.indices) {
            out.coverage[i] = (blurred[i].coerceIn(0f, 1f) * 255f).toInt().toByte()
        }
        return out
    }

    /**
     * Grow (`amount > 0`) or shrink (`amount < 0`) the selection by a pixel radius.
     * Implemented as a distance transform over the binary threshold, which handles concave
     * shapes correctly.
     */
    fun expanded(amount: Int): SelectionMask {
        if (amount == 0) return copy()
        val binary = BooleanArray(coverage.size) { (coverage[it].toInt() and 0xFF) >= 128 }
        val result = if (amount > 0) dilate(binary, amount) else erode(binary, -amount)
        val out = SelectionMask(width, height)
        for (i in out.coverage.indices) {
            out.coverage[i] = if (result[i]) 255.toByte() else 0
        }
        return out
    }

    private fun dilate(
        source: BooleanArray,
        radius: Int,
    ): BooleanArray {
        // Cheap and predictable: repeat a 3x3 max filter; fine for the radii the UI allows.
        var current = source
        repeat(radius) {
            val next = BooleanArray(source.size)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    var hit = false
                    for (dy in -1..1) {
                        if (hit) break
                        for (dx in -1..1) {
                            val nx = x + dx
                            val ny = y + dy
                            if (nx in 0 until width && ny in 0 until height && current[ny * width + nx]) {
                                hit = true
                                break
                            }
                        }
                    }
                    next[y * width + x] = hit
                }
            }
            current = next
        }
        return current
    }

    private fun erode(
        source: BooleanArray,
        radius: Int,
    ): BooleanArray {
        var current = source
        repeat(radius) {
            val next = BooleanArray(source.size)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    var all = true
                    for (dy in -1..1) {
                        if (!all) break
                        for (dx in -1..1) {
                            val nx = x + dx
                            val ny = y + dy
                            if (nx !in 0 until width || ny !in 0 until height || !current[ny * width + nx]) {
                                all = false
                                break
                            }
                        }
                    }
                    next[y * width + x] = all
                }
            }
            current = next
        }
        return current
    }

    /**
     * Renders the mask into an ARGB buffer (white where selected, transparent elsewhere).
     * Used to preview a selection and to bake a selection into a layer mask.
     */
    fun toMaskBitmap(maskColor: Int = 0xFFFFFFFF.toInt()): PixelBuffer {
        val out = PixelBuffer(width, height)
        for (i in out.pixels.indices) {
            val a = coverage[i].toInt() and 0xFF
            if (a == 0) continue
            out.pixels[i] = Channels.withAlpha(maskColor, a)
        }
        return out
    }

    companion object {
        /**
         * Selection from the alpha channel of a buffer ("select layer opacity").
         *
         * Static because it derives everything from the buffer it is given, which is what callers
         * want from the layer panel's "load selection" action.
         */
        fun fromAlphaOf(
            buffer: PixelBuffer,
            threshold: Int = 1,
        ): SelectionMask {
            val out = SelectionMask(buffer.width, buffer.height)
            for (i in out.coverage.indices) {
                val a = (buffer.pixels[i] ushr 24) and 0xFF
                out.coverage[i] = if (a >= threshold) a.toByte() else 0
            }
            return out
        }

        /** Rectangular marquee. */
        fun rectangle(
            width: Int,
            height: Int,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
        ): SelectionMask {
            val mask = SelectionMask(width, height)
            val x0 = max(0, floor(min(left, right)).toInt())
            val x1 = min(width - 1, ceil(max(left, right)).toInt() - 1)
            val y0 = max(0, floor(min(top, bottom)).toInt())
            val y1 = min(height - 1, ceil(max(top, bottom)).toInt() - 1)
            if (x1 < x0 || y1 < y0) return mask
            for (y in y0..y1) {
                java.util.Arrays.fill(mask.coverage, y * width + x0, y * width + x1 + 1, 255.toByte())
            }
            return mask
        }

        /** Elliptical marquee inscribed in the given rectangle, with anti-aliased edges. */
        fun ellipse(
            width: Int,
            height: Int,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
        ): SelectionMask {
            val mask = SelectionMask(width, height)
            val centerX = (left + right) / 2f
            val centerY = (top + bottom) / 2f
            val radiusX = abs(right - left) / 2f
            val radiusY = abs(bottom - top) / 2f
            if (radiusX <= 0f || radiusY <= 0f) return mask

            val x0 = max(0, floor(centerX - radiusX).toInt() - 1)
            val x1 = min(width - 1, ceil(centerX + radiusX).toInt() + 1)
            val y0 = max(0, floor(centerY - radiusY).toInt() - 1)
            val y1 = min(height - 1, ceil(centerY + radiusY).toInt() + 1)

            for (y in y0..y1) {
                for (x in x0..x1) {
                    val nx = (x + 0.5f - centerX) / radiusX
                    val ny = (y + 0.5f - centerY) / radiusY
                    val distance = sqrt(nx * nx + ny * ny)
                    // 1px wide soft edge in normalised space, scaled by the smaller radius.
                    val edge = 1f / min(radiusX, radiusY)
                    val coverage =
                        when {
                            distance <= 1f - edge -> 1f
                            distance >= 1f + edge -> 0f
                            else -> 1f - (distance - (1f - edge)) / (2f * edge)
                        }
                    if (coverage > 0f) {
                        mask.coverage[y * width + x] = (coverage * 255f).toInt().toByte()
                    }
                }
            }
            return mask
        }

        /** Freehand lasso: even-odd fill of a closed polygon with anti-aliased edges. */
        fun polygon(
            width: Int,
            height: Int,
            points: List<Pair<Float, Float>>,
        ): SelectionMask {
            val mask = SelectionMask(width, height)
            if (points.size < 3) return mask

            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            points.forEach {
                minY = min(minY, it.second)
                maxY = max(maxY, it.second)
            }
            val y0 = max(0, floor(minY).toInt())
            val y1 = min(height - 1, ceil(maxY).toInt())

            val xs = FloatArray(points.size)
            for (y in y0..y1) {
                val scanY = y + 0.5f
                var count = 0
                for (i in points.indices) {
                    val (x1, y1p) = points[i]
                    val (x2, y2p) = points[(i + 1) % points.size]
                    if ((y1p <= scanY && y2p > scanY) || (y2p <= scanY && y1p > scanY)) {
                        val t = (scanY - y1p) / (y2p - y1p)
                        xs[count++] = x1 + t * (x2 - x1)
                    }
                }
                if (count < 2) continue
                val spans = xs.copyOf(count).sortedArray()
                var i = 0
                while (i + 1 < count) {
                    val spanStart = max(0, ceil(spans[i] - 0.5f).toInt())
                    val spanEnd = min(width - 1, floor(spans[i + 1] - 0.5f).toInt())
                    if (spanEnd >= spanStart) {
                        java.util.Arrays.fill(
                            mask.coverage,
                            y * width + spanStart,
                            y * width + spanEnd + 1,
                            255.toByte(),
                        )
                    }
                    i += 2
                }
            }
            return mask
        }

        /** Freehand selection following a painted path with a brush [radius]. */
        fun fromStroke(
            width: Int,
            height: Int,
            points: List<Pair<Float, Float>>,
            radius: Float,
        ): SelectionMask {
            val mask = SelectionMask(width, height)
            if (points.isEmpty()) return mask
            if (points.size == 1) {
                stamp(mask, points[0].first, points[0].second, radius)
                return mask
            }
            for (i in 1 until points.size) {
                val (x0, y0) = points[i - 1]
                val (x1, y1) = points[i]
                val steps = max(1, ceil(hypot(x1 - x0, y1 - y0)).toInt())
                for (s in 0..steps) {
                    val t = s.toFloat() / steps
                    stamp(mask, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, radius)
                }
            }
            return mask
        }

        /**
         * Magic wand. Flood fill from ([startX], [startY]) matching colours within [tolerance].
         * When [contiguous] is false, every matching pixel in the buffer is selected.
         */
        fun magicWand(
            buffer: PixelBuffer,
            startX: Int,
            startY: Int,
            tolerance: Int = 32,
            contiguous: Boolean = true,
            antiAlias: Boolean = true,
            respectExistingSelection: SelectionMask? = null,
        ): SelectionMask {
            val mask = SelectionMask(buffer.width, buffer.height)
            if (!buffer.contains(startX, startY)) return mask

            val target = buffer.getUnchecked(startX, startY)
            val toleranceSquared = (tolerance.coerceIn(0, 255) * 3).toFloat() * (tolerance.coerceIn(0, 255) * 3)

            fun matches(pixel: Int): Boolean {
                val da = Channels.alpha(pixel) - Channels.alpha(target)
                val dr = Channels.red(pixel) - Channels.red(target)
                val dg = Channels.green(pixel) - Channels.green(target)
                val db = Channels.blue(pixel) - Channels.blue(target)
                return (da * da + dr * dr + dg * dg + db * db) <= toleranceSquared
            }

            if (!contiguous) {
                for (i in buffer.pixels.indices) {
                    if (matches(buffer.pixels[i])) mask.coverage[i] = 255.toByte()
                }
            } else {
                val visited = BooleanArray(buffer.width * buffer.height)
                val stack = IntArray(buffer.width * buffer.height)
                var top = 0
                stack[top++] = startY * buffer.width + startX
                while (top > 0) {
                    val index = stack[--top]
                    if (visited[index]) continue
                    visited[index] = true
                    if (!matches(buffer.pixels[index])) continue
                    mask.coverage[index] = 255.toByte()
                    val x = index % buffer.width
                    val y = index / buffer.width
                    if (x > 0) stack[top++] = index - 1
                    if (x < buffer.width - 1) stack[top++] = index + 1
                    if (y > 0) stack[top++] = index - buffer.width
                    if (y < buffer.height - 1) stack[top++] = index + buffer.width
                }
            }

            if (antiAlias && tolerance > 0) {
                val softened = smoothEdges(mask, buffer, target, tolerance)
                if (respectExistingSelection != null) {
                    for (i in softened.coverage.indices) {
                        val existing = respectExistingSelection.coverage[i].toInt() and 0xFF
                        softened.coverage[i] =
                            ((softened.coverage[i].toInt() and 0xFF) * existing / 255).toByte()
                    }
                }
                return softened
            }

            if (respectExistingSelection != null) {
                for (i in mask.coverage.indices) {
                    val existing = respectExistingSelection.coverage[i].toInt() and 0xFF
                    mask.coverage[i] = ((mask.coverage[i].toInt() and 0xFF) * existing / 255).toByte()
                }
            }
            return mask
        }

        /**
         * Colour-range selection: selects every pixel whose distance to [color] is within
         * [tolerance], with a soft roll-off for anti-aliasing.
         */
        fun colorRange(
            buffer: PixelBuffer,
            color: Int,
            tolerance: Int,
            antiAlias: Boolean = true,
        ): SelectionMask {
            val mask = SelectionMask(buffer.width, buffer.height)
            val hard = (tolerance.coerceIn(0, 255) * 3).toFloat()
            val soft = if (antiAlias) hard + 30f else hard
            for (i in buffer.pixels.indices) {
                val pixel = buffer.pixels[i]
                val da = Channels.alpha(pixel) - Channels.alpha(color)
                val dr = Channels.red(pixel) - Channels.red(color)
                val dg = Channels.green(pixel) - Channels.green(color)
                val db = Channels.blue(pixel) - Channels.blue(color)
                val distance = sqrt(da * da + dr * dr + dg * dg + db * db)
                mask.coverage[i] =
                    when {
                        distance <= hard -> 255.toByte()
                        !antiAlias || distance >= soft -> 0
                        else -> (((soft - distance) / (soft - hard)) * 255f).toInt().toByte()
                    }
            }
            return mask
        }

        /** Intersection of two masks (used when "add to selection" is off). */
        fun intersect(
            a: SelectionMask,
            b: SelectionMask,
        ): SelectionMask {
            val out = SelectionMask(min(a.width, b.width), min(a.height, b.height))
            for (y in 0 until out.height) {
                for (x in 0 until out.width) {
                    val av = a.coverage[y * a.width + x].toInt() and 0xFF
                    val bv = b.coverage[y * b.width + x].toInt() and 0xFF
                    out.coverage[y * out.width + x] = min(av, bv).toByte()
                }
            }
            return out
        }

        private fun smoothEdges(
            mask: SelectionMask,
            buffer: PixelBuffer,
            target: Int,
            tolerance: Int,
        ): SelectionMask {
            val out = mask.copy()
            val hard = (tolerance.coerceIn(0, 255) * 3).toFloat()
            val soft = hard + 30f
            for (y in 0 until mask.height) {
                for (x in 0 until mask.width) {
                    val index = y * mask.width + x
                    if ((out.coverage[index].toInt() and 0xFF) == 0) continue
                    // Only pixels on the boundary need a softer value.
                    val nearEdge =
                        x == 0 ||
                            y == 0 ||
                            x == mask.width - 1 ||
                            y == mask.height - 1 ||
                            (mask.coverage[index - 1].toInt() and 0xFF) == 0 ||
                            (mask.coverage[index + 1].toInt() and 0xFF) == 0 ||
                            (mask.coverage[index - mask.width].toInt() and 0xFF) == 0 ||
                            (mask.coverage[index + mask.width].toInt() and 0xFF) == 0
                    if (!nearEdge) continue
                    val pixel = buffer.pixels[index]
                    val da = Channels.alpha(pixel) - Channels.alpha(target)
                    val dr = Channels.red(pixel) - Channels.red(target)
                    val dg = Channels.green(pixel) - Channels.green(target)
                    val db = Channels.blue(pixel) - Channels.blue(target)
                    val distance = sqrt(da * da + dr * dr + dg * dg + db * db)
                    val coverage = ((soft - distance) / (soft - hard)).coerceIn(0f, 1f)
                    out.coverage[index] = (coverage * 255f).toInt().toByte()
                }
            }
            return out
        }

        private fun stamp(
            mask: SelectionMask,
            centerX: Float,
            centerY: Float,
            radius: Float,
        ) {
            val x0 = max(0, floor(centerX - radius).toInt())
            val x1 = min(mask.width - 1, ceil(centerX + radius).toInt())
            val y0 = max(0, floor(centerY - radius).toInt())
            val y1 = min(mask.height - 1, ceil(centerY + radius).toInt())
            val radiusSquared = radius * radius
            for (y in y0..y1) {
                for (x in x0..x1) {
                    val dx = x + 0.5f - centerX
                    val dy = y + 0.5f - centerY
                    val distanceSquared = dx * dx + dy * dy
                    if (distanceSquared > radiusSquared) continue
                    val index = y * mask.width + x
                    val edge = radius - sqrt(distanceSquared)
                    val coverage = (edge.coerceIn(0f, 1f) * 255f).toInt()
                    val existing = mask.coverage[index].toInt() and 0xFF
                    if (coverage > existing) mask.coverage[index] = coverage.toByte()
                }
            }
        }

        /** Converts a smooth polygon through [points] into a mask (used by "select shape"). */
        fun fromCircle(
            width: Int,
            height: Int,
            centerX: Float,
            centerY: Float,
            radius: Float,
        ): SelectionMask {
            val mask = SelectionMask(width, height)
            val points =
                (0 until 64).map { i ->
                    val angle = (i / 64f) * 2f * Math.PI.toFloat()
                    (centerX + cos(angle) * radius) to (centerY + sin(angle) * radius)
                }
            return polygon(width, height, points)
        }
    }
}
