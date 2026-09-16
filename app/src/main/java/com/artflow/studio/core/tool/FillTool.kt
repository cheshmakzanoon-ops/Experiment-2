package com.artflow.studio.core.tool

import com.artflow.studio.core.pixels.BlendModes
import com.artflow.studio.core.pixels.Channels
import com.artflow.studio.core.pixels.ImageFilters
import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Paint bucket / area fill (Phase 22).
 *
 * Implements a scanline flood fill (iterative, no recursion, so a 8000x8000 canvas cannot blow the
 * stack), global fill, anti-aliased edges, gap closing and pattern fills. Works on an ARGB
 * [PixelBuffer] so it is fully unit-testable.
 */
object FillTool {

    data class Settings(
        /** 0..255 colour distance tolerance. */
        val tolerance: Int = 32,
        /** When false, every matching pixel in the buffer is filled, not just the connected region. */
        val contiguous: Boolean = true,
        /** Selection/layer-mask coverage, already sized to the buffer. */
        val mask: SelectionMask? = null,
        /** Restrict the fill to pixels that already have alpha (used by alpha-locked layers). */
        val alphaLock: Boolean = false,
        /** Soften the fill edge over 1px so it does not look jagged. */
        val antiAlias: Boolean = true,
        /** Grow the fill region by this many pixels before filling (closes hairline gaps). */
        val gapClose: Int = 0,
        /** How the fill colour is applied over existing pixels. */
        val mode: BlendModeChoice = BlendModeChoice.NORMAL,
        /** Pattern tiling; when present the fill uses the pattern instead of a solid colour. */
        val pattern: PixelBuffer? = null,
        /** Sample the fill colour from the composite rather than the target layer. */
        val sampleAllLayers: Boolean = false
    )

    /** How a bucket fill blends into the existing pixels. */
    enum class BlendModeChoice { NORMAL, MULTIPLY, SCREEN, DARKEN, LIGHTEN }

    /** Result of a fill, including the dirty rectangle so callers can invalidate precisely. */
    data class Result(
        val filledPixels: Int,
        val bounds: IntBounds?,
        val changed: Boolean
    )

    /**
     * Flood fill starting at ([startX], [startY]).
     *
     * @param target the layer pixels being modified in place.
     * @param source the pixels used to decide what "matches" (the composite when
     *   [Settings.sampleAllLayers] is set, otherwise [target]).
     */
    fun floodFill(
        target: PixelBuffer,
        startX: Int,
        startY: Int,
        color: Int,
        settings: Settings = Settings(),
        source: PixelBuffer = target
    ): Result {
        if (!target.contains(startX, startY)) return Result(0, null, changed = false)
        if (source.width != target.width || source.height != target.height) {
            return Result(0, null, changed = false)
        }

        val width = target.width
        val height = target.height
        val startColor = source.getUnchecked(startX, startY)
        val toleranceSquared = toleranceSquared(settings.tolerance)

        fun matches(pixel: Int): Boolean = colorDistanceSquared(pixel, startColor) <= toleranceSquared

        val filled = SelectionMask(width, height)
        if (settings.contiguous) {
            scanlineFill(filled, source, startX, startY, settings)
        } else {
            for (i in source.pixels.indices) {
                if (matches(source.pixels[i])) filled.coverage[i] = 255.toByte()
            }
        }

        if (!filled.isActive()) return Result(0, null, changed = false)

        return applyFill(target, filled, color, settings)
    }

    /**
     * Fill the whole layer (or the current selection when one is active).
     */
    fun fillAll(
        target: PixelBuffer,
        color: Int,
        settings: Settings = Settings()
    ): Result {
        val coverage = settings.mask?.copy() ?: SelectionMask(target.width, target.height).apply { selectAll() }
        if (!coverage.isActive()) return Result(0, null, changed = false)
        return applyFill(target, coverage, color, settings)
    }

    /** Fill the mask itself: paints the selection outline (Phase 13: "stroke selection"). */
    fun strokeSelection(
        target: PixelBuffer,
        color: Int,
        width: Int,
        settings: Settings = Settings()
    ): Result {
        val mask = settings.mask ?: return Result(0, null, changed = false)
        val inner = mask.expanded(-max(1, width / 2))
        val outer = mask.expanded(max(1, width / 2))
        val outline = SelectionMask(target.width, target.height)
        for (i in outline.coverage.indices) {
            val o = outer.coverage[i].toInt() and 0xFF
            val n = inner.coverage[i].toInt() and 0xFF
            outline.coverage[i] = (o - n).coerceAtLeast(0).toByte()
        }
        if (!outline.isActive()) return Result(0, null, changed = false)
        return applyFill(target, outline, color, settings)
    }

    /** Copies [pattern] tiles across the fill area (Phase 22: pattern fill). */
    fun patternFill(
        target: PixelBuffer,
        pattern: PixelBuffer,
        settings: Settings = Settings()
    ): Result = fillAll(target, color = 0, settings = settings.copy(pattern = pattern))

    private fun applyFill(
        target: PixelBuffer,
        coverage: SelectionMask,
        color: Int,
        settings: Settings
    ): Result {
        var count = 0
        var minX = target.width
        var minY = target.height
        var maxX = -1
        var maxY = -1

        val feather = if (settings.antiAlias) softenedCoverage(coverage) else coverage
        val pattern = settings.pattern

        for (y in 0 until target.height) {
            val row = y * target.width
            for (x in 0 until target.width) {
                val index = row + x
                var alpha = feather.alphaAt(index)
                if (alpha <= 0f) continue
                if (settings.alphaLock) {
                    // Alpha lock: the fill only affects pixels that are already opaque, and is
                    // weighted by how opaque they are so soft edges stay soft.
                    val destinationAlpha = (target.pixels[index] ushr 24) and 0xFF
                    if (destinationAlpha == 0) continue
                    alpha *= destinationAlpha / 255f
                    if (alpha <= 0f) continue
                }

                val sourceColor = if (pattern != null) {
                    pattern.getUnchecked(x % pattern.width, y % pattern.height)
                } else {
                    color
                }
                val existing = target.pixels[index]
                val blended = when (settings.mode) {
                    BlendModeChoice.NORMAL -> BlendModes.sourceOver(
                        existing,
                        Channels.scaleAlpha(sourceColor, alpha)
                    )
                    BlendModeChoice.MULTIPLY -> ImageFilters.lerpArgb(
                        existing,
                        BlendModes.blend(existing, sourceColor, com.artflow.studio.domain.model.layer.BlendMode.MULTIPLY),
                        alpha
                    )
                    BlendModeChoice.SCREEN -> ImageFilters.lerpArgb(
                        existing,
                        BlendModes.blend(existing, sourceColor, com.artflow.studio.domain.model.layer.BlendMode.SCREEN),
                        alpha
                    )
                    BlendModeChoice.DARKEN -> ImageFilters.lerpArgb(
                        existing,
                        BlendModes.blend(existing, sourceColor, com.artflow.studio.domain.model.layer.BlendMode.DARKEN),
                        alpha
                    )
                    BlendModeChoice.LIGHTEN -> ImageFilters.lerpArgb(
                        existing,
                        BlendModes.blend(existing, sourceColor, com.artflow.studio.domain.model.layer.BlendMode.LIGHTEN),
                        alpha
                    )
                }

                if (blended != existing) {
                    target.pixels[index] = blended
                    count++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        val bounds = if (maxX >= minX && maxY >= minY) IntBounds(minX, minY, maxX, maxY) else null
        return Result(count, bounds, count > 0)
    }

    /** Anti-aliased edge: pixels on the boundary of the filled region get partial coverage. */
    private fun softenedCoverage(coverage: SelectionMask): SelectionMask {
        val out = coverage.copy()
        val width = coverage.width
        val height = coverage.height
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                if ((out.coverage[index].toInt() and 0xFF) != 0) continue

                var neighbours = 0
                if ((coverage.coverage[index - 1].toInt() and 0xFF) != 0) neighbours++
                if ((coverage.coverage[index + 1].toInt() and 0xFF) != 0) neighbours++
                if ((coverage.coverage[index - width].toInt() and 0xFF) != 0) neighbours++
                if ((coverage.coverage[index + width].toInt() and 0xFF) != 0) neighbours++
                if (neighbours > 0) {
                    out.coverage[index] = (neighbours * 60).coerceAtMost(255).toByte()
                }
            }
        }
        return out
    }

    /**
     * Scanline flood fill. Iterative with an explicit stack of spans, which keeps memory flat and
     * avoids the per-pixel object allocation a naive queue would incur.
     */
    private fun scanlineFill(
        mask: SelectionMask,
        source: PixelBuffer,
        startX: Int,
        startY: Int,
        settings: Settings
    ) {
        val width = source.width
        val height = source.height
        val startColor = source.getUnchecked(startX, startY)
        val toleranceSquared = toleranceSquared(settings.tolerance)
        val gapClose = settings.gapClose.coerceIn(0, 16)

        /**
         * Connectivity test. With gap closing enabled, a pixel counts as "connected" when any
         * pixel inside the gap window matches, which lets the fill jump hairline gaps in line art.
         */
        fun matches(x: Int, y: Int): Boolean {
            if (gapClose == 0) {
                return colorDistanceSquared(source.getUnchecked(x, y), startColor) <= toleranceSquared
            }
            for (dy in -gapClose..gapClose) {
                for (dx in -gapClose..gapClose) {
                    if (colorDistanceSquared(source.getSafe(x + dx, y + dy), startColor) <= toleranceSquared) {
                        return true
                    }
                }
            }
            return false
        }

        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(startX, startY))

        val scanned = BooleanArray(width * height)

        while (stack.isNotEmpty()) {
            val span = stack.removeLast()
            var x = span[0]
            val y = span[1]
            if (scanned[y * width + x]) continue

            // Walk left to the beginning of the matching run.
            while (x > 0 && matches(x - 1, y) && !scanned[y * width + x - 1]) x--

            var spanAbove = false
            var spanBelow = false
            var cx = x
            while (cx < width && matches(cx, y) && !scanned[y * width + cx]) {
                mask.coverage[y * width + cx] = 255.toByte()
                scanned[y * width + cx] = true

                if (y > 0) {
                    val above = matches(cx, y - 1)
                    if (above && !scanned[(y - 1) * width + cx]) {
                        if (!spanAbove) {
                            stack.addLast(intArrayOf(cx, y - 1))
                            spanAbove = true
                        }
                    } else {
                        spanAbove = false
                    }
                }
                if (y < height - 1) {
                    val below = matches(cx, y + 1)
                    if (below && !scanned[(y + 1) * width + cx]) {
                        if (!spanBelow) {
                            stack.addLast(intArrayOf(cx, y + 1))
                            spanBelow = true
                        }
                    } else {
                        spanBelow = false
                    }
                }
                cx++
            }
        }
    }

    /** Squared RGBA distance so we can compare without a square root on every pixel. */
    fun colorDistanceSquared(a: Int, b: Int): Float {
        val da = Channels.alpha(a) - Channels.alpha(b)
        val dr = Channels.red(a) - Channels.red(b)
        val dg = Channels.green(a) - Channels.green(b)
        val db = Channels.blue(a) - Channels.blue(b)
        return da * da + dr * dr + dg * dg + db * db
    }

    /** Matches `SelectionMask`'s tolerance handling: tolerance scales to a 3-channel radius. */
    fun toleranceSquared(tolerance: Int): Float {
        val scaled = tolerance.coerceIn(0, 255) * 3f
        return scaled * scaled
    }

    /** Colour similarity in `0..1`, used by the fill preview and the magic-wand slider label. */
    fun similarity(a: Int, b: Int): Float {
        val distance = sqrt(colorDistanceSquared(a, b))
        return (1f - distance / (255f * 2f)).coerceIn(0f, 1f)
    }

    /** Convenience: fill the whole canvas with a background colour (used by "Clear canvas"). */
    fun clearToColor(target: PixelBuffer, color: Int): Result {
        val before = target.copy()
        target.fill(color)
        val bounds = IntBounds(0, 0, target.width - 1, target.height - 1)
        var changed = false
        for (i in target.pixels.indices) {
            if (target.pixels[i] != before.pixels[i]) {
                changed = true
                break
            }
        }
        return Result(target.pixels.size, bounds, changed)
    }

    /** Percentage of the layer covered by the selection, for the fill preview readout. */
    fun coveragePercent(mask: SelectionMask): Int {
        val selected = mask.selectedPixelCount()
        if (selected == 0) return 0
        val total = mask.width * mask.height
        return ((selected.toFloat() / total) * 100f).roundToInt().coerceIn(0, 100)
    }

    /** Largest fill area we allow before warning the user (guards against mis-taps at 0 tolerance). */
    const val LARGE_FILL_WARNING_PERCENT = 90
}
