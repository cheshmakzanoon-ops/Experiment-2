package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.BlendModes
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.layer.LayerEffects
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws [LayerEffects] behind a layer's pixels: the drop shadow at the back, the outline over it
 * and the layer's own paint on top. Both effects follow the layer's coverage, so they cost O(pixels)
 * whatever their size: the outline from an exact distance transform, the shadow's softness from
 * three box blurs, which together approximate a Gaussian.
 */
object LayerEffectsRenderer {
    /** Applies [effects] to [content] in place. */
    fun apply(
        content: PixelBuffer,
        effects: LayerEffects,
    ) {
        if (effects.isEmpty) return
        val width = content.width
        val height = content.height
        val count = width * height
        val coverage = FloatArray(count) { (content.pixels[it] ushr 24) / 255f }
        if (coverage.none { it > 0f }) return

        val outline = effects.outline?.let { outlineCoverage(coverage, width, height, it.width) }
        val back = IntArray(count)
        effects.shadow?.let { shadow ->
            // The shadow is cast by everything in front of it: the paint and its outline.
            val caster = outline?.let { o -> FloatArray(count) { maxOf(o[it], coverage[it]) } } ?: coverage
            val cast = shadowCoverage(caster, width, height, shadow)
            val rgb = shadow.color and 0x00FFFFFF
            val strength = shadow.opacity.coerceIn(0f, 1f) * ((shadow.color ushr 24) / 255f)
            for (i in 0 until count) {
                val alpha = (cast[i] * strength * 255f).roundToInt()
                if (alpha > 0) back[i] = (alpha shl 24) or rgb
            }
        }
        effects.outline?.let { style ->
            val rgb = style.color and 0x00FFFFFF
            val strength = (style.color ushr 24) / 255f
            val ring = checkNotNull(outline)
            for (i in 0 until count) {
                val alpha = (ring[i] * strength * 255f).roundToInt()
                if (alpha > 0) back[i] = BlendModes.sourceOver(back[i], (alpha shl 24) or rgb)
            }
        }
        for (i in 0 until count) {
            if (back[i] != 0) content.pixels[i] = BlendModes.sourceOver(back[i], content.pixels[i])
        }
    }

    /**
     * Coverage of a border [width] pixels wide around [coverage]: 1 within [width] of a pixel that
     * is at least half covered, falling to 0 over the next pixel so the edge stays smooth.
     */
    internal fun outlineCoverage(
        coverage: FloatArray,
        width: Int,
        height: Int,
        outlineWidth: Float,
    ): FloatArray {
        val reach = outlineWidth.coerceIn(0f, LayerEffects.MAX_OUTLINE_WIDTH)
        if (reach <= 0f) return FloatArray(coverage.size)
        val distance = distanceToCovered(coverage, width, height)
        return FloatArray(coverage.size) { i -> (reach + 0.5f - sqrt(distance[i])).coerceIn(0f, 1f) }
    }

    /** [caster] moved by the shadow's offset and softened by its blur. */
    internal fun shadowCoverage(
        caster: FloatArray,
        width: Int,
        height: Int,
        shadow: LayerEffects.Shadow,
    ): FloatArray {
        val distance = shadow.distance.coerceIn(0f, LayerEffects.MAX_SHADOW_DISTANCE)
        val radians = Math.toRadians(shadow.angleDegrees.toDouble())
        val dx = (cos(radians) * distance).roundToInt()
        val dy = (sin(radians) * distance).roundToInt()
        val moved = FloatArray(caster.size)
        for (y in 0 until height) {
            val sourceY = y - dy
            if (sourceY !in 0 until height) continue
            for (x in 0 until width) {
                val sourceX = x - dx
                if (sourceX in 0 until width) moved[y * width + x] = caster[sourceY * width + sourceX]
            }
        }
        // Three box passes of this radius have about the spread of a Gaussian with sigma = blur / 2.
        val radius = (shadow.blur.coerceIn(0f, LayerEffects.MAX_SHADOW_BLUR) / 2f).roundToInt()
        if (radius > 0) repeat(BOX_PASSES) { boxBlur(moved, width, height, radius) }
        return moved
    }

    /**
     * Squared Euclidean distance from each pixel to the nearest pixel at least half covered, by the
     * separable transform of Felzenszwalb and Huttenlocher: exact, and linear in the pixel count.
     */
    private fun distanceToCovered(
        coverage: FloatArray,
        width: Int,
        height: Int,
    ): FloatArray {
        val field = FloatArray(coverage.size) { if (coverage[it] >= HALF) 0f else INFINITY }
        val longest = maxOf(width, height)
        val line = FloatArray(longest)
        val out = FloatArray(longest)
        val hull = IntArray(longest)
        val bounds = FloatArray(longest + 1)
        for (x in 0 until width) {
            for (y in 0 until height) line[y] = field[y * width + x]
            transform(line, height, out, hull, bounds)
            for (y in 0 until height) field[y * width + x] = out[y]
        }
        for (y in 0 until height) {
            val row = y * width
            System.arraycopy(field, row, line, 0, width)
            transform(line, width, out, hull, bounds)
            System.arraycopy(out, 0, field, row, width)
        }
        return field
    }

    /**
     * One-dimensional squared distance transform of the first [length] values of [f] into [d]: the
     * lower envelope of the parabolas rooted at each finite value. Empty pixels root none.
     */
    private fun transform(
        f: FloatArray,
        length: Int,
        d: FloatArray,
        hull: IntArray,
        bounds: FloatArray,
    ) {
        var k = -1
        for (q in 0 until length) {
            if (f[q] >= INFINITY) continue
            if (k < 0) {
                k = 0
            } else {
                var s = intersection(f, q, hull[k])
                while (s <= bounds[k]) {
                    k--
                    s = intersection(f, q, hull[k])
                }
                k++
                bounds[k] = s
            }
            hull[k] = q
            if (k == 0) bounds[0] = Float.NEGATIVE_INFINITY
            bounds[k + 1] = Float.POSITIVE_INFINITY
        }
        if (k < 0) {
            d.fill(INFINITY, 0, length)
            return
        }
        k = 0
        for (q in 0 until length) {
            while (bounds[k + 1] < q) k++
            val offset = (q - hull[k]).toFloat()
            d[q] = offset * offset + f[hull[k]]
        }
    }

    /** Where the parabolas rooted at [q] and [p] cross. */
    private fun intersection(
        f: FloatArray,
        q: Int,
        p: Int,
    ): Float {
        val qq = f[q].toDouble() + q.toDouble() * q
        val pp = f[p].toDouble() + p.toDouble() * p
        return ((qq - pp) / (2.0 * (q - p))).toFloat()
    }

    /** Box blur of [radius] in place, rows then columns, treating everything past the edge as empty. */
    private fun boxBlur(
        values: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
    ) {
        val longest = maxOf(width, height)
        val line = FloatArray(longest)
        val scale = 1f / (2 * radius + 1)
        for (y in 0 until height) {
            System.arraycopy(values, y * width, line, 0, width)
            blurLine(line, width, radius, scale) { x, v -> values[y * width + x] = v }
        }
        for (x in 0 until width) {
            for (y in 0 until height) line[y] = values[y * width + x]
            blurLine(line, height, radius, scale) { y, v -> values[y * width + x] = v }
        }
    }

    private inline fun blurLine(
        line: FloatArray,
        length: Int,
        radius: Int,
        scale: Float,
        write: (Int, Float) -> Unit,
    ) {
        var sum = 0f
        for (i in 0..minOf(radius, length - 1)) sum += line[i]
        for (i in 0 until length) {
            write(i, sum * scale)
            val enter = i + radius + 1
            val leave = i - radius
            if (enter < length) sum += line[enter]
            if (leave >= 0) sum -= line[leave]
        }
    }

    private const val HALF = 0.5f
    private const val INFINITY = 1e20f
    private const val BOX_PASSES = 3
}
