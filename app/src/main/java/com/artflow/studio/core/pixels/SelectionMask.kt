package com.artflow.studio.core.pixels

import kotlin.math.min

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

    /** Edge-extended box feathering. Work is bounded by the canvas, not by the radius. */
    fun feathered(
        radius: Int,
        checkActive: () -> Unit = {},
    ): SelectionMask = SelectionCoverage.feather(this, radius, checkActive)

    /**
     * Binary square-radius dilation/erosion, matching repeated 3x3 operations at threshold 128.
     * Outside-canvas samples are unselected. Both signs, including Int.MIN_VALUE, take O(pixel count).
     */
    fun expanded(
        amount: Int,
        checkActive: () -> Unit = {},
    ): SelectionMask = SelectionCoverage.expand(this, amount, checkActive)

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
            checkActive: () -> Unit = {},
        ): SelectionMask {
            checkActive()
            val out = SelectionMask(buffer.width, buffer.height)
            for (i in out.coverage.indices) {
                if (i % 8192 == 0) checkActive()
                val a = (buffer.pixels[i] ushr 24) and 0xFF
                out.coverage[i] = if (a >= threshold) a.toByte() else 0
            }
            return out
        }

        /** Rectangular marquee. Degenerate or fully off-canvas rectangles have empty coverage. */
        fun rectangle(
            width: Int,
            height: Int,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            checkActive: () -> Unit = {},
        ): SelectionMask = SelectionGeometry.rectangle(width, height, left, top, right, bottom, checkActive)

        /** Elliptical marquee with 4x4 subpixel coverage and finite, overflow-safe geometry. */
        fun ellipse(
            width: Int,
            height: Int,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            checkActive: () -> Unit = {},
        ): SelectionMask = SelectionGeometry.ellipse(width, height, left, top, right, bottom, checkActive)

        /** Even-odd lasso with 4x4 subpixel coverage, including self-intersecting polygons. */
        fun polygon(
            width: Int,
            height: Int,
            points: List<Pair<Float, Float>>,
            checkActive: () -> Unit = {},
        ): SelectionMask = SelectionGeometry.polygon(width, height, points, checkActive)

        /** Continuous brush-path coverage; distance outside the canvas never sets an iteration count. */
        fun fromStroke(
            width: Int,
            height: Int,
            points: List<Pair<Float, Float>>,
            radius: Float,
            checkActive: () -> Unit = {},
        ): SelectionMask = SelectionGeometry.stroke(width, height, points, radius, checkActive)

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
            checkActive: () -> Unit = {},
        ): SelectionMask =
            ColorSelection.wand(buffer, startX, startY, tolerance, contiguous, antiAlias, respectExistingSelection, checkActive)

        /** Global colour matching with the same alpha-aware metric as the magic wand. */
        fun colorRange(
            buffer: PixelBuffer,
            color: Int,
            tolerance: Int,
            antiAlias: Boolean = true,
            checkActive: () -> Unit = {},
        ): SelectionMask = ColorSelection.range(buffer, color, tolerance, antiAlias, checkActive)

        /** Intersection of two masks (used when "add to selection" is off). */
        fun intersect(
            a: SelectionMask,
            b: SelectionMask,
            checkActive: () -> Unit = {},
        ): SelectionMask {
            checkActive()
            val out = SelectionMask(min(a.width, b.width), min(a.height, b.height))
            for (y in 0 until out.height) {
                checkActive()
                for (x in 0 until out.width) {
                    val av = a.coverage[y * a.width + x].toInt() and 0xFF
                    val bv = b.coverage[y * b.width + x].toInt() and 0xFF
                    out.coverage[y * out.width + x] = min(av, bv).toByte()
                }
            }
            return out
        }

        /** Circular selection with the same one-pixel inward edge as brush-path selection. */
        fun fromCircle(
            width: Int,
            height: Int,
            centerX: Float,
            centerY: Float,
            radius: Float,
            checkActive: () -> Unit = {},
        ): SelectionMask = fromStroke(width, height, listOf(centerX to centerY), radius, checkActive)
    }
}
