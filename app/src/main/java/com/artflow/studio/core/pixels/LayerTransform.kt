package com.artflow.studio.core.pixels

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Free transform of layer pixels: translate, uniform/non-uniform scale, rotation and flips about
 * a pivot. When a selection is active only the selected pixels float; the unselected remainder
 * stays in place, matching the behaviour artists expect from Procreate's transform tool.
 *
 * Mapping (source → destination): `dest = pivot + t + R · S · (src − pivot)`, where `S` carries
 * the flips as negative scale factors.
 */
object LayerTransform {
    data class Params(
        val pivotX: Float,
        val pivotY: Float,
        val translateX: Float = 0f,
        val translateY: Float = 0f,
        val scaleX: Float = 1f,
        val scaleY: Float = 1f,
        val rotationDegrees: Float = 0f,
        val flipHorizontal: Boolean = false,
        val flipVertical: Boolean = false,
    ) {
        private val isUnscaledUpright: Boolean
            get() = scaleX == 1f && scaleY == 1f && rotationDegrees % 360f == 0f && !flipHorizontal && !flipVertical

        val isIdentity: Boolean
            get() = isUnscaledUpright && translateX == 0f && translateY == 0f

        /** True when the transform is an integer translation that needs no resampling. */
        val isPureIntegerTranslation: Boolean
            get() = isUnscaledUpright && translateX == floor(translateX) && translateY == floor(translateY)
    }

    /** One-finger editing modes of the transform tool. */
    enum class Mode(
        val displayName: String,
    ) {
        MOVE("Move"),
        UNIFORM("Uniform"),
        FREEFORM("Freeform"),
        ROTATE("Rotate"),
    }

    const val MIN_SCALE = 0.02f
    const val MAX_SCALE = 50f

    /**
     * Derives transform parameters from a one-finger drag from ([startX], [startY]) to ([x], [y])
     * around [pivotX]/[pivotY], according to [mode]. [base] supplies flips chosen in the UI.
     */
    fun fromDrag(
        mode: Mode,
        pivotX: Float,
        pivotY: Float,
        startX: Float,
        startY: Float,
        x: Float,
        y: Float,
        base: Params = Params(pivotX, pivotY),
    ): Params =
        when (mode) {
            Mode.MOVE -> base.copy(pivotX = pivotX, pivotY = pivotY, translateX = x - startX, translateY = y - startY)
            Mode.UNIFORM -> {
                val start = hypot(startX - pivotX, startY - pivotY)
                val factor = if (start < 1f) 1f else (hypot(x - pivotX, y - pivotY) / start).clampScale()
                base.copy(pivotX = pivotX, pivotY = pivotY, scaleX = factor, scaleY = factor)
            }
            Mode.FREEFORM -> {
                val sx = ratio(x - pivotX, startX - pivotX)
                val sy = ratio(y - pivotY, startY - pivotY)
                base.copy(pivotX = pivotX, pivotY = pivotY, scaleX = sx, scaleY = sy)
            }
            Mode.ROTATE -> {
                val a0 = atan2(startY - pivotY, startX - pivotX)
                val a1 = atan2(y - pivotY, x - pivotX)
                base.copy(pivotX = pivotX, pivotY = pivotY, rotationDegrees = Math.toDegrees((a1 - a0).toDouble()).toFloat())
            }
        }

    private fun ratio(
        now: Float,
        start: Float,
    ): Float = if (abs(start) < 4f) 1f else (abs(now) / abs(start)).clampScale()

    private fun Float.clampScale(): Float = coerceIn(MIN_SCALE, MAX_SCALE)

    /**
     * Renders [source] transformed by [params] into [target] (same size as [source]).
     * [selection] limits which pixels float; [highQuality] chooses bilinear over nearest sampling.
     */
    fun render(
        source: PixelBuffer,
        target: PixelBuffer,
        params: Params,
        selection: SelectionMask? = null,
        highQuality: Boolean = true,
    ) {
        require(source.width == target.width && source.height == target.height) { "Buffer sizes differ" }
        val (floating, blend) = split(source, target, selection)
        val mask = if (blend) selection else null
        if (params.isIdentity) {
            composite(target, floating, 0, 0, mask != null)
            return
        }
        if (params.isPureIntegerTranslation) {
            composite(target, floating, params.translateX.toInt(), params.translateY.toInt(), mask != null)
            return
        }
        val bounds = floating.contentBounds() ?: return
        val radians = Math.toRadians(params.rotationDegrees.toDouble())
        val cosR = cos(radians).toFloat()
        val sinR = sin(radians).toFloat()
        val sx = params.scaleX * if (params.flipHorizontal) -1f else 1f
        val sy = params.scaleY * if (params.flipVertical) -1f else 1f
        require(abs(sx) > 1e-6f && abs(sy) > 1e-6f) { "Scale must be non-zero" }
        val px = params.pivotX
        val py = params.pivotY
        val tx = params.translateX
        val ty = params.translateY

        // Forward-map the source bounds to find the destination rectangle to scan.
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        val cornersX = floatArrayOf(bounds.left.toFloat(), bounds.right + 1f, bounds.left.toFloat(), bounds.right + 1f)
        val cornersY = floatArrayOf(bounds.top.toFloat(), bounds.top.toFloat(), bounds.bottom + 1f, bounds.bottom + 1f)
        for (i in 0 until 4) {
            val u = (cornersX[i] - px) * sx
            val v = (cornersY[i] - py) * sy
            val dx = px + tx + u * cosR - v * sinR
            val dy = py + ty + u * sinR + v * cosR
            minX = min(minX, dx)
            minY = min(minY, dy)
            maxX = max(maxX, dx)
            maxY = max(maxY, dy)
        }
        val x0 = max(0, floor(minX).toInt() - 1)
        val y0 = max(0, floor(minY).toInt() - 1)
        val x1 = min(target.width - 1, ceil(maxX).toInt() + 1)
        val y1 = min(target.height - 1, ceil(maxY).toInt() + 1)
        if (x1 < x0 || y1 < y0) return
        val invSx = 1f / sx
        val invSy = 1f / sy
        for (y in y0..y1) {
            val row = y * target.width
            val cy = y + 0.5f - py - ty
            for (x in x0..x1) {
                val cx = x + 0.5f - px - tx
                // Inverse rotation then inverse scale.
                val u = (cx * cosR + cy * sinR) * invSx
                val v = (-cx * sinR + cy * cosR) * invSy
                val srcX = px + u
                val srcY = py + v
                val sample = if (highQuality) floating.sampleBilinear(srcX, srcY) else floating.sampleNearest(srcX, srcY)
                if ((sample ushr 24) == 0) continue
                val i = row + x
                target.pixels[i] = if (mask == null) sample else BlendModes.sourceOver(target.pixels[i], sample)
            }
        }
    }

    private fun composite(
        target: PixelBuffer,
        floating: PixelBuffer,
        dx: Int,
        dy: Int,
        blend: Boolean,
    ) {
        val width = target.width
        for (i in target.pixels.indices) {
            val sx = i % width - dx
            val sy = i / width - dy
            val p = if (sx in 0 until floating.width && sy in 0 until floating.height) floating.pixels[sy * floating.width + sx] else 0
            if ((p ushr 24) != 0) target.pixels[i] = if (blend) BlendModes.sourceOver(target.pixels[i], p) else p
        }
    }

    /**
     * Writes the pixels that stay put into [target] and returns the pixels that float, plus
     * whether the floating part must blend over a remainder (true when a selection is active).
     */
    internal fun split(
        source: PixelBuffer,
        target: PixelBuffer,
        selection: SelectionMask?,
    ): Pair<PixelBuffer, Boolean> {
        val mask = selection?.takeIf { it.width == source.width && it.height == source.height && it.isActive() }
        if (mask == null) {
            target.clear()
            return source to false
        }
        val floating = PixelBuffer(source.width, source.height)
        for (i in source.pixels.indices) {
            val c = mask.alphaAt(i)
            val p = source.pixels[i]
            when {
                c <= 0f -> target.pixels[i] = p
                c >= 1f -> {
                    target.pixels[i] = 0
                    floating.pixels[i] = p
                }
                else -> {
                    target.pixels[i] = Channels.scaleAlpha(p, 1f - c)
                    floating.pixels[i] = Channels.scaleAlpha(p, c)
                }
            }
        }
        return floating to true
    }

    /** Bounding box of the pixels that would float, or null when there is nothing to transform. */
    fun floatingBounds(
        source: PixelBuffer,
        selection: SelectionMask?,
    ): IntBounds? {
        val mask = selection?.takeIf { it.width == source.width && it.height == source.height && it.isActive() }
        var minX = source.width
        var minY = source.height
        var maxX = -1
        var maxY = -1
        for (i in source.pixels.indices) {
            if ((source.pixels[i] ushr 24) == 0 || (mask != null && mask.alphaAt(i) <= 0f)) continue
            val x = i % source.width
            val y = i / source.width
            minX = min(minX, x)
            maxX = max(maxX, x)
            minY = min(minY, y)
            maxY = max(maxY, y)
        }
        return if (maxX < minX) null else IntBounds(minX, minY, maxX, maxY)
    }

    /** Centre of the pixels that would float, used as the default transform pivot. */
    fun pivotOf(
        source: PixelBuffer,
        selection: SelectionMask?,
    ): Pair<Float, Float> {
        val bounds = floatingBounds(source, selection) ?: return source.width / 2f to source.height / 2f
        return (bounds.left + bounds.right + 1) / 2f to (bounds.top + bounds.bottom + 1) / 2f
    }
}
