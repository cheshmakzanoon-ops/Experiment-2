package com.artflow.studio.core.pixels

import java.util.concurrent.ArrayBlockingQueue
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal fun checkedPixelCount(
    width: Int,
    height: Int,
): Int {
    require(width > 0 && height > 0) { "Pixel dimensions must be positive" }
    val count = width.toLong() * height.toLong()
    require(count <= 40_000_000L && width <= 8192 && height <= 8192) { "Pixel dimensions exceed the supported limits" }
    return count.toInt()
}

/**
 * A mutable ARGB_8888 pixel buffer.
 *
 * Deliberately free of `android.graphics` so every drawing tool, filter and adjustment can be
 * unit-tested on the JVM. Device code adapts these buffers to/from [android.graphics.Bitmap]
 * through `BitmapPixelBridge`.
 *
 * Colour channels are stored **non-premultiplied** (the same convention Android uses for
 * ARGB_8888 buffers when read through `getPixels`), which keeps round-tripping lossless.
 */
class PixelBuffer(
    val width: Int,
    val height: Int,
    val pixels: IntArray = IntArray(checkedPixelCount(width, height)),
) {
    init {
        require(width > 0 && height > 0) { "PixelBuffer must be at least 1x1 (got ${width}x$height)" }
        require(pixels.size == checkedPixelCount(width, height)) {
            "Pixel array size ${pixels.size} does not match ${width}x$height"
        }
    }

    val size: Int get() = pixels.size

    fun index(
        x: Int,
        y: Int,
    ): Int = y * width + x

    fun contains(
        x: Int,
        y: Int,
    ): Boolean = x in 0 until width && y in 0 until height

    /** Raw pixel access without bounds checking (hot paths). */
    fun getUnchecked(
        x: Int,
        y: Int,
    ): Int = pixels[y * width + x]

    /** Returns 0 (transparent) outside the buffer so sampling never throws. */
    fun getSafe(
        x: Int,
        y: Int,
    ): Int = if (contains(x, y)) pixels[y * width + x] else 0

    fun setUnchecked(
        x: Int,
        y: Int,
        argb: Int,
    ) {
        pixels[y * width + x] = argb
    }

    fun setSafe(
        x: Int,
        y: Int,
        argb: Int,
    ) {
        if (contains(x, y)) pixels[y * width + x] = argb
    }

    fun fill(argb: Int) {
        pixels.fill(argb)
    }

    fun clear() {
        pixels.fill(0)
    }

    fun copy(): PixelBuffer = PixelBuffer(width, height, pixels.copyOf())

    fun isEmpty(): Boolean = pixels.all { (it ushr 24) == 0 }

    /** Count of pixels with a non-zero alpha channel. */
    fun opaquePixelCount(): Int = pixels.count { (it ushr 24) != 0 }

    fun pixelAt(offset: Int): Int = pixels[offset]

    /**
     * Nearest-neighbour sample. Used by tools that need the pre-edit state of a pixel while the
     * buffer is being modified in place.
     */
    fun sampleNearest(
        xf: Float,
        yf: Float,
    ): Int = getSafe(floor(xf).toInt(), floor(yf).toInt())

    /**
     * Bilinear sample in buffer space. Out-of-bounds taps read as transparent.
     * Used for scaling, liquify and transform resampling.
     */
    fun sampleBilinear(
        xf: Float,
        yf: Float,
    ): Int {
        val x = xf - 0.5f
        val y = yf - 0.5f
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0

        val p00 = getSafe(x0, y0)
        val p10 = getSafe(x0 + 1, y0)
        val p01 = getSafe(x0, y0 + 1)
        val p11 = getSafe(x0 + 1, y0 + 1)

        return mix4(p00, p10, p01, p11, fx, fy)
    }

    /** Draw [src] into this buffer at ([dx], [dy]) with source-over compositing. */
    fun drawInto(
        src: PixelBuffer,
        dx: Int,
        dy: Int,
        opacity: Float = 1f,
    ) {
        val alpha = opacity.coerceIn(0f, 1f)
        for (y in 0 until src.height) {
            val ty = y + dy
            if (ty < 0 || ty >= height) continue
            val srcRow = y * src.width
            val dstRow = ty * width
            for (x in 0 until src.width) {
                val tx = x + dx
                if (tx < 0 || tx >= width) continue
                val dstOffset = dstRow + tx
                val srcPixel = src.pixels[srcRow + x]
                if (alpha >= 1f) {
                    pixels[dstOffset] = BlendModes.sourceOver(pixels[dstOffset], srcPixel)
                } else {
                    val faded = Channels.scaleAlpha(srcPixel, alpha)
                    pixels[dstOffset] = BlendModes.sourceOver(pixels[dstOffset], faded)
                }
            }
        }
    }

    /** The bounding box of non-transparent pixels, or null when the buffer is fully transparent. */
    fun contentBounds(): IntBounds? {
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if ((pixels[row + x] ushr 24) != 0) {
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

    /** Crop to an inclusive rectangle. Coordinates are clamped to the buffer. */
    fun crop(bounds: IntBounds): PixelBuffer {
        val left = bounds.left.coerceIn(0, width - 1)
        val top = bounds.top.coerceIn(0, height - 1)
        val right = bounds.right.coerceIn(left, width - 1)
        val bottom = bounds.bottom.coerceIn(top, height - 1)

        val outWidth = right - left + 1
        val outHeight = bottom - top + 1
        val out = PixelBuffer(outWidth, outHeight)
        for (y in 0 until outHeight) {
            System.arraycopy(pixels, (top + y) * width + left, out.pixels, y * outWidth, outWidth)
        }
        return out
    }

    /** Resize using bilinear sampling. */
    fun scaled(
        newWidth: Int,
        newHeight: Int,
    ): PixelBuffer {
        require(newWidth > 0 && newHeight > 0) { "Scale target must be positive" }
        if (newWidth == width && newHeight == height) return copy()

        val out = PixelBuffer(newWidth, newHeight)
        val scaleX = width.toFloat() / newWidth
        val scaleY = height.toFloat() / newHeight
        for (y in 0 until newHeight) {
            val sy = ((y + 0.5f) * scaleY).coerceIn(0.5f, height - 0.5f)
            val row = y * newWidth
            for (x in 0 until newWidth) {
                // Resizing extends edge texels; transparent sampling remains correct for transforms.
                val sx = ((x + 0.5f) * scaleX).coerceIn(0.5f, width - 0.5f)
                out.pixels[row + x] = sampleBilinear(sx, sy)
            }
        }
        return out
    }

    /** Mirror horizontally (left <-> right). */
    fun flippedHorizontally(): PixelBuffer {
        val out = PixelBuffer(width, height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                out.pixels[row + x] = pixels[row + (width - 1 - x)]
            }
        }
        return out
    }

    /** Mirror vertically (top <-> bottom). */
    fun flippedVertically(): PixelBuffer {
        val out = PixelBuffer(width, height)
        for (y in 0 until height) {
            val srcRow = (height - 1 - y) * width
            System.arraycopy(pixels, srcRow, out.pixels, y * width, width)
        }
        return out
    }

    /** Rotate by a multiple of 90 degrees (clockwise for positive values). */
    fun rotated(degrees: Int): PixelBuffer {
        val normalized = ((degrees % 360) + 360) % 360
        return when (normalized) {
            0 -> copy()
            90 -> transpose(clockwise = true)
            180 ->
                PixelBuffer(width, height).also { out ->
                    for (i in pixels.indices) out.pixels[i] = pixels[pixels.size - 1 - i]
                }
            270 -> transpose(clockwise = false)
            else -> throw IllegalArgumentException("Only 90-degree steps are supported, got $degrees")
        }
    }

    private fun transpose(clockwise: Boolean): PixelBuffer {
        val out = PixelBuffer(height, width)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val targetX = if (clockwise) height - 1 - y else y
                val targetY = if (clockwise) x else width - 1 - x
                out.pixels[targetY * out.width + targetX] = pixels[y * width + x]
            }
        }
        return out
    }

    /** Pads or crops to exactly [targetWidth] x [targetHeight], anchoring content at the top-left. */
    fun resizedCanvas(
        targetWidth: Int,
        targetHeight: Int,
    ): PixelBuffer {
        val out = PixelBuffer(targetWidth, targetHeight)
        val copyWidth = min(width, targetWidth)
        val copyHeight = min(height, targetHeight)
        for (y in 0 until copyHeight) {
            System.arraycopy(pixels, y * width, out.pixels, y * targetWidth, copyWidth)
        }
        return out
    }

    /**
     * Resample through a canvas transform (translate/scale/rotate) using bilinear sampling.
     * Used by transform commits and by canvas rotation, where strips of pixels move.
     */
    fun transformed(
        targetWidth: Int,
        targetHeight: Int,
        translateX: Float,
        translateY: Float,
        scaleX: Float,
        scaleY: Float,
        rotationDegrees: Float,
        pivotX: Float,
        pivotY: Float,
    ): PixelBuffer {
        val out = PixelBuffer(targetWidth, targetHeight)
        val radians = Math.toRadians(rotationDegrees.toDouble())
        val cos = kotlin.math.cos(radians).toFloat()
        val sin = kotlin.math.sin(radians).toFloat()
        val safeScaleX = if (abs(scaleX) < 1e-4f) 1e-4f else scaleX
        val safeScaleY = if (abs(scaleY) < 1e-4f) 1e-4f else scaleY

        for (y in 0 until targetHeight) {
            val row = y * targetWidth
            for (x in 0 until targetWidth) {
                // Inverse transform: destination -> source.
                var sx = x + 0.5f - translateX - pivotX
                var sy = y + 0.5f - translateY - pivotY
                val rx = sx * cos + sy * sin
                val ry = -sx * sin + sy * cos
                sx = rx / safeScaleX + pivotX
                sy = ry / safeScaleY + pivotY
                if (sx < -1f || sy < -1f || sx > width + 1f || sy > height + 1f) continue
                out.pixels[row + x] = sampleBilinear(sx, sy)
            }
        }
        return out
    }

    /** Bounds of this buffer shifted by an offset (helper for transform commits). */
    fun translatedBounds(
        dx: Float,
        dy: Float,
    ): IntBounds =
        IntBounds(
            floor(dx).toInt(),
            floor(dy).toInt(),
            floor(dx).toInt() + width - 1,
            floor(dy).toInt() + height - 1,
        )

    companion object {
        fun filled(
            width: Int,
            height: Int,
            argb: Int,
        ): PixelBuffer = PixelBuffer(width, height).also { it.fill(argb) }

        fun mix4(
            p00: Int,
            p10: Int,
            p01: Int,
            p11: Int,
            fx: Float,
            fy: Float,
        ): Int {
            val w00 = (1 - fx) * (1 - fy)
            val w10 = fx * (1 - fy)
            val w01 = (1 - fx) * fy
            val w11 = fx * fy
            return Channels.fromFloats(
                a =
                    Channels.alpha(p00) * w00 + Channels.alpha(p10) * w10 +
                        Channels.alpha(p01) * w01 + Channels.alpha(p11) * w11,
                r =
                    Channels.red(p00) * w00 + Channels.red(p10) * w10 +
                        Channels.red(p01) * w01 + Channels.red(p11) * w11,
                g =
                    Channels.green(p00) * w00 + Channels.green(p10) * w10 +
                        Channels.green(p01) * w01 + Channels.green(p11) * w11,
                b =
                    Channels.blue(p00) * w00 + Channels.blue(p10) * w10 +
                        Channels.blue(p01) * w01 + Channels.blue(p11) * w11,
            )
        }
    }
}

/** Inclusive integer rectangle in pixel space. */
data class IntBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left + 1
    val height: Int get() = bottom - top + 1
    val isEmpty: Boolean get() = right < left || bottom < top

    fun contains(
        x: Int,
        y: Int,
    ): Boolean = x in left..right && y in top..bottom

    fun inflated(amount: Int): IntBounds = IntBounds(left - amount, top - amount, right + amount, bottom + amount)

    fun intersect(other: IntBounds): IntBounds =
        IntBounds(
            max(left, other.left),
            max(top, other.top),
            min(right, other.right),
            min(bottom, other.bottom),
        )

    fun clamped(
        maxWidth: Int,
        maxHeight: Int,
    ): IntBounds =
        IntBounds(
            left.coerceIn(0, maxWidth - 1),
            top.coerceIn(0, maxHeight - 1),
            right.coerceIn(0, maxWidth - 1),
            bottom.coerceIn(0, maxHeight - 1),
        )

    /** Grow to cover a point, so a caller can accumulate a dirty rectangle. */
    fun union(other: IntBounds): IntBounds =
        IntBounds(
            min(left, other.left),
            min(top, other.top),
            max(right, other.right),
            max(bottom, other.bottom),
        )

    companion object {
        fun aroundRectangle(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            padding: Int = 0,
        ) = IntBounds(
            floor(min(left, right)).toInt() - padding,
            floor(min(top, bottom)).toInt() - padding,
            kotlin.math.ceil(max(left, right)).toInt() + padding,
            kotlin.math.ceil(max(top, bottom)).toInt() + padding,
        )
    }
}

/**
 * A bounded, thread-safe pool of [PixelBuffer]s.
 *
 * Compositing allocates a canvas-sized buffer per layer and per result; on a large canvas that is
 * tens of megabytes per buffer, and allocating them per composite pass produced the allocation
 * churn (and GC pauses) behind out-of-memory crashes on big documents. Recycling buffers through
 * this pool keeps those allocations off the render path without changing any pixel behaviour.
 *
 * Threadsafety comes from [ArrayBlockingQueue]: releases past capacity are discarded instead of
 * blocking, and consumers drain under the queue's own lock, so no additional synchronisation is
 * needed here.
 */
class PixelBufferPool(
    /** Maximum number of idle buffers retained for reuse; releases past this are discarded. */
    private val maxCapacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(maxCapacity > 0) { "Pool capacity must be positive (got $maxCapacity)" }
    }

    private val pool = ArrayBlockingQueue<PixelBuffer>(maxCapacity)

    /**
     * Takes a buffer from the pool, or allocates a fresh one when the pool is empty.
     *
     * The returned buffer is **cleared to transparent black** whether it came from the pool or was
     * newly allocated, so callers never see stale pixels from a previous owner. Pooled buffers with
     * mismatched dimensions are discarded rather than resized, which keeps [obtain]
     * allocation-free for the canvas size the pool is warmed with.
     */
    fun obtain(
        width: Int,
        height: Int,
    ): PixelBuffer {
        val safeWidth = max(1, width)
        val safeHeight = max(1, height)
        while (true) {
            val recycled = pool.poll() ?: return PixelBuffer(safeWidth, safeHeight)
            if (recycled.width == safeWidth && recycled.height == safeHeight) {
                recycled.clear()
                return recycled
            }
            // Wrong size for this canvas: drop it and try the next candidate.
        }
    }

    /**
     * Returns [buffer] to the pool for reuse, or discards it when the pool is full.
     *
     * Returns `true` when the buffer was pooled and `false` when it was discarded; either way the
     * caller must stop using the buffer afterwards — a later [obtain] hands it to another owner.
     */
    fun release(buffer: PixelBuffer): Boolean = pool.offer(buffer)

    /** Drops every pooled buffer immediately (called when the compositor is disposed). */
    fun clear() {
        pool.clear()
    }

    companion object {
        /** Enough buffers for a typical multi-layer composite pass to stay allocation-free. */
        const val DEFAULT_CAPACITY: Int = 8
    }
}

/**
 * Channel helpers. Alpha is 0-255, colour channels are 0-255 floats internally so blending maths
 * stays readable and matches the W3C compositing formulas.
 */
object Channels {
    fun alpha(argb: Int): Float = ((argb ushr 24) and 0xFF).toFloat()

    fun red(argb: Int): Float = ((argb ushr 16) and 0xFF).toFloat()

    fun green(argb: Int): Float = ((argb ushr 8) and 0xFF).toFloat()

    fun blue(argb: Int): Float = (argb and 0xFF).toFloat()

    fun fromFloats(
        a: Float,
        r: Float,
        g: Float,
        b: Float,
    ): Int {
        val ai = a.roundToInt().coerceIn(0, 255)
        val ri = r.roundToInt().coerceIn(0, 255)
        val gi = g.roundToInt().coerceIn(0, 255)
        val bi = b.roundToInt().coerceIn(0, 255)
        return (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    fun argb(
        a: Int,
        r: Int,
        g: Int,
        b: Int,
    ): Int = ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    fun scaleAlpha(
        argb: Int,
        factor: Float,
    ): Int {
        val a = (alpha(argb) * factor).roundToInt().coerceIn(0, 255)
        return (argb and 0x00FFFFFF) or (a shl 24)
    }

    fun withAlpha(
        argb: Int,
        alpha: Int,
    ): Int = (argb and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

    fun luminance(argb: Int): Float = (0.2126f * red(argb) + 0.7152f * green(argb) + 0.0722f * blue(argb)) / 255f
}
