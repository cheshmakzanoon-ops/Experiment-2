package com.artflow.studio.core.canvas

import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Canvas-level operations (Phase 35): resize, crop, rotate, flip, trim and DPI changes.
 *
 * Every function is a pure transformation of a [PixelBuffer] plus metadata, so the editor can
 * apply them to every layer and every animation frame through one code path, and they can be
 * verified without a device.
 */
object CanvasOperations {

    /** How a resize treats the artwork. */
    enum class ResizeMode(val displayName: String) {
        /** Resample the pixels to the new size (the content changes scale). */
        RESAMPLE("Resample"),
        /** Keep the pixel size, change the canvas, anchor the content. */
        CANVAS("Change canvas size"),
        /** Keep the pixel size and repeat the artwork to fill the new canvas. */
        TILE("Tile")
    }

    /** Where existing artwork is placed when the canvas grows or shrinks. */
    enum class Anchor(val displayName: String) {
        TOP_LEFT("Top left"),
        TOP_CENTER("Top centre"),
        TOP_RIGHT("Top right"),
        MIDDLE_LEFT("Middle left"),
        CENTER("Centre"),
        MIDDLE_RIGHT("Middle right"),
        BOTTOM_LEFT("Bottom left"),
        BOTTOM_CENTER("Bottom centre"),
        BOTTOM_RIGHT("Bottom right")
    }

    data class CanvasProperties(
        val width: Int,
        val height: Int,
        val dpi: Int,
        val backgroundColor: Int
    ) {
        /** Print size in inches at the current DPI, shown in the canvas info panel. */
        val widthInches: Float get() = if (dpi <= 0) 0f else width / dpi.toFloat()
        val heightInches: Float get() = if (dpi <= 0) 0f else height / dpi.toFloat()

        /** Megapixels, used to warn about memory pressure before a resize. */
        val megapixels: Float get() = (width.toLong() * height) / 1_000_000f

        /** Estimated ARGB memory for one layer, in megabytes. */
        val megabytesPerLayer: Float get() = (width.toLong() * height * 4) / (1024f * 1024f)
    }

    data class Result(
        val buffer: PixelBuffer,
        val properties: CanvasProperties
    )

    /** Resamples the artwork to a new pixel size. */
    fun resample(buffer: PixelBuffer, width: Int, height: Int, properties: CanvasProperties): Result {
        val targetWidth = width.coerceIn(1, MAX_DIMENSION)
        val targetHeight = height.coerceIn(1, MAX_DIMENSION)
        return Result(
            buffer = buffer.scaled(targetWidth, targetHeight),
            properties = properties.copy(width = targetWidth, height = targetHeight)
        )
    }

    /**
     * Changes the canvas size without scaling the artwork. Content is placed according to [anchor]
     * and any extra space is filled with [fillColor] (transparent by default).
     */
    fun resizeCanvas(
        buffer: PixelBuffer,
        width: Int,
        height: Int,
        anchor: Anchor,
        properties: CanvasProperties,
        fillColor: Int = 0
    ): Result {
        val targetWidth = width.coerceIn(1, MAX_DIMENSION)
        val targetHeight = height.coerceIn(1, MAX_DIMENSION)
        val canvas = PixelBuffer(targetWidth, targetHeight)
        if (fillColor != 0) canvas.fill(fillColor)

        val offsetX = when (anchor) {
            Anchor.TOP_LEFT, Anchor.MIDDLE_LEFT, Anchor.BOTTOM_LEFT -> 0
            Anchor.TOP_CENTER, Anchor.CENTER, Anchor.BOTTOM_CENTER -> (targetWidth - buffer.width) / 2
            Anchor.TOP_RIGHT, Anchor.MIDDLE_RIGHT, Anchor.BOTTOM_RIGHT -> targetWidth - buffer.width
        }
        val offsetY = when (anchor) {
            Anchor.TOP_LEFT, Anchor.TOP_CENTER, Anchor.TOP_RIGHT -> 0
            Anchor.MIDDLE_LEFT, Anchor.CENTER, Anchor.MIDDLE_RIGHT -> (targetHeight - buffer.height) / 2
            Anchor.BOTTOM_LEFT, Anchor.BOTTOM_CENTER, Anchor.BOTTOM_RIGHT -> targetHeight - buffer.height
        }

        canvas.drawInto(buffer, offsetX, offsetY)
        return Result(canvas, properties.copy(width = targetWidth, height = targetHeight))
    }

    /** Repeats the artwork to fill a larger canvas. */
    fun tile(
        buffer: PixelBuffer,
        width: Int,
        height: Int,
        properties: CanvasProperties
    ): Result {
        val targetWidth = width.coerceIn(1, MAX_DIMENSION)
        val targetHeight = height.coerceIn(1, MAX_DIMENSION)
        val canvas = PixelBuffer(targetWidth, targetHeight)
        var y = 0
        while (y < targetHeight) {
            var x = 0
            while (x < targetWidth) {
                canvas.drawInto(buffer, x, y)
                x += max(1, buffer.width)
            }
            y += max(1, buffer.height)
        }
        return Result(canvas, properties.copy(width = targetWidth, height = targetHeight))
    }

    /** Crop to the given inclusive bounds. */
    fun crop(buffer: PixelBuffer, bounds: IntBounds, properties: CanvasProperties): Result {
        val clamped = bounds.intersect(IntBounds(0, 0, buffer.width - 1, buffer.height - 1))
        if (clamped.isEmpty) return Result(buffer, properties)
        val cropped = buffer.crop(clamped)
        return Result(
            cropped,
            properties.copy(width = cropped.width, height = cropped.height)
        )
    }

    /** Rotates by a multiple of 90 degrees, swapping the canvas dimensions when needed. */
    fun rotate(buffer: PixelBuffer, degrees: Int, properties: CanvasProperties): Result {
        val rotated = buffer.rotated(degrees)
        return Result(
            rotated,
            properties.copy(width = rotated.width, height = rotated.height)
        )
    }

    /** Free rotation by an arbitrary angle, growing the canvas so nothing is clipped. */
    fun rotateFree(buffer: PixelBuffer, degrees: Float, properties: CanvasProperties): Result {
        val radians = Math.toRadians(degrees.toDouble())
        val cos = kotlin.math.abs(kotlin.math.cos(radians)).toFloat()
        val sin = kotlin.math.abs(kotlin.math.sin(radians)).toFloat()
        val newWidth = (buffer.width * cos + buffer.height * sin).roundToInt().coerceIn(1, MAX_DIMENSION)
        val newHeight = (buffer.width * sin + buffer.height * cos).roundToInt().coerceIn(1, MAX_DIMENSION)
        val rotated = buffer.transformed(
            targetWidth = newWidth,
            targetHeight = newHeight,
            translateX = (newWidth - buffer.width) / 2f,
            translateY = (newHeight - buffer.height) / 2f,
            scaleX = 1f,
            scaleY = 1f,
            rotationDegrees = degrees,
            pivotX = buffer.width / 2f,
            pivotY = buffer.height / 2f
        )
        return Result(rotated, properties.copy(width = newWidth, height = newHeight))
    }

    enum class FlipAxis { HORIZONTAL, VERTICAL }

    fun flip(buffer: PixelBuffer, axis: FlipAxis, properties: CanvasProperties): Result = Result(
        buffer = when (axis) {
            FlipAxis.HORIZONTAL -> buffer.flippedHorizontally()
            FlipAxis.VERTICAL -> buffer.flippedVertically()
        },
        properties = properties
    )

    /** Removes fully transparent margins so exports hug the artwork. */
    fun trimTransparent(buffer: PixelBuffer, properties: CanvasProperties, padding: Int = 0): Result {
        val content = buffer.contentBounds() ?: return Result(buffer, properties)
        val padded = content.inflated(padding.coerceAtLeast(0))
            .intersect(IntBounds(0, 0, buffer.width - 1, buffer.height - 1))
        return crop(buffer, padded, properties)
    }

    /** Fits the canvas to the content bounds. */
    fun fitToContent(buffer: PixelBuffer, properties: CanvasProperties): Result =
        trimTransparent(buffer, properties, padding = 0)

    /** Pads the canvas out to the content plus a uniform margin. */
    fun expandToContent(buffer: PixelBuffer, properties: CanvasProperties, margin: Int = 32): Result {
        val content = buffer.contentBounds() ?: return Result(buffer, properties)
        val padded = content.inflated(margin)
        return resizeCanvas(
            buffer = buffer,
            width = padded.width,
            height = padded.height,
            anchor = Anchor.CENTER,
            properties = properties
        )
    }

    /** Changes DPI only; the pixel data is untouched (this is a print-resolution change). */
    fun changeDpi(properties: CanvasProperties, dpi: Int): CanvasProperties =
        properties.copy(dpi = dpi.coerceIn(MIN_DPI, MAX_DPI))

    /**
     * Changes DPI while keeping the printed size, which does require resampling.
     * Used by "resample to 300 DPI for print".
     */
    fun resampleToDpi(buffer: PixelBuffer, properties: CanvasProperties, dpi: Int): Result {
        val targetDpi = dpi.coerceIn(MIN_DPI, MAX_DPI)
        if (targetDpi == properties.dpi) return Result(buffer, properties)
        val scale = targetDpi / properties.dpi.toFloat()
        val width = (buffer.width * scale).roundToInt().coerceIn(1, MAX_DIMENSION)
        val height = (buffer.height * scale).roundToInt().coerceIn(1, MAX_DIMENSION)
        val scaled = buffer.scaled(width, height)
        return Result(scaled, properties.copy(width = width, height = height, dpi = targetDpi))
    }

    /** Straightens a scanned/skewed artwork by rotating and auto-trimming. */
    fun straighten(buffer: PixelBuffer, degrees: Float, properties: CanvasProperties): Result {
        val rotated = rotateFree(buffer, degrees, properties)
        return trimTransparent(rotated.buffer, rotated.properties)
    }

    /** Common canvas sizes offered in the new-canvas dialog. */
    data class Preset(
        val name: String,
        val width: Int,
        val height: Int,
        val dpi: Int
    ) {
        val label: String get() = "$name · $width×$height @ ${dpi}dpi"
    }

    val PRESETS: List<Preset> = listOf(
        Preset("Screen HD", 1920, 1080, 72),
        Preset("Screen 4K", 3840, 2160, 72),
        Preset("Tablet", 2560, 1600, 132),
        Preset("Square 2048", 2048, 2048, 132),
        Preset("Square 4096", 4096, 4096, 132),
        Preset("Sketch 3000×2000", 3000, 2000, 132),
        Preset("Comic Page", 2480, 3508, 300),
        Preset("A4 Print", 2480, 3508, 300),
        Preset("A3 Print", 3508, 4961, 300),
        Preset("US Letter", 2550, 3300, 300),
        Preset("Instagram Post", 1080, 1080, 72),
        Preset("Instagram Story", 1080, 1920, 72),
        Preset("Twitter Header", 1500, 500, 72),
        Preset("Print 5×7", 1500, 2100, 300),
        Preset("Print 8×10", 2400, 3000, 300)
    )

    fun presetByName(name: String): Preset? = PRESETS.firstOrNull { it.name == name }

    /** Guard rails against allocations large enough to crash the app. */
    const val MAX_DIMENSION = 8192
    const val MAX_PIXELS = 40_000_000L
    const val MIN_DPI = 36
    const val MAX_DPI = 1200

    /** True when the requested size is safe to allocate. */
    fun isSizeSafe(width: Int, height: Int): Boolean =
        width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION && width.toLong() * height <= MAX_PIXELS

    /** Human-readable reason a size was rejected, or null when it is fine. */
    fun sizeWarning(width: Int, height: Int): String? = when {
        width < 1 || height < 1 -> "Canvas must be at least 1×1"
        width > MAX_DIMENSION || height > MAX_DIMENSION ->
            "Maximum canvas side is ${MAX_DIMENSION}px"
        width.toLong() * height > MAX_PIXELS ->
            "Canvas exceeds ${MAX_PIXELS / 1_000_000} megapixels"
        else -> null
    }

    /** Aspect-ratio-preserving size for a target width. */
    fun scaleToWidth(width: Int, height: Int, targetWidth: Int): Pair<Int, Int> {
        val ratio = height / width.toFloat()
        return targetWidth to max(1, (targetWidth * ratio).roundToInt())
    }

    /** Aspect-ratio-preserving size for a target height. */
    fun scaleToHeight(width: Int, height: Int, targetHeight: Int): Pair<Int, Int> {
        val ratio = width / height.toFloat()
        return max(1, (targetHeight * ratio).roundToInt()) to targetHeight
    }

    /** Scales so the artwork fits inside [maxWidth] x [maxHeight]. */
    fun fitInside(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        val scale = min(maxWidth / width.toFloat(), maxHeight / height.toFloat())
        if (scale >= 1f) return width to height
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /** Largest square crop centred on the canvas (used by "crop to square"). */
    fun centeredSquare(width: Int, height: Int): IntBounds {
        val size = min(width, height)
        val left = (width - size) / 2
        val top = (height - size) / 2
        return IntBounds(left, top, left + size - 1, top + size - 1)
    }
}
