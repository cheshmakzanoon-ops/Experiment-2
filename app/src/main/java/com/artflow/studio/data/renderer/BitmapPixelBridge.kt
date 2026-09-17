package com.artflow.studio.data.renderer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.core.export.PngCodec
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import java.io.ByteArrayOutputStream

/**
 * Adapter between [PixelBuffer] (the pure-Kotlin pixel representation all tools operate on) and
 * Android's [Bitmap] (what the platform can encode, decode, draw and upload to the GPU).
 *
 * Keeping this conversion in exactly one place means every tool is testable on the JVM while the
 * app still benefits from the platform codecs.
 *
 * Bitmaps returned by [toBitmap] are always owned by the caller and must be recycled.
 */
object BitmapPixelBridge {
    fun toBitmap(
        buffer: PixelBuffer,
        premultiplied: Boolean = true,
    ): Bitmap =
        Bitmap.createBitmap(buffer.width, buffer.height, Bitmap.Config.ARGB_8888).apply {
            setPremultiplied(premultiplied)
            setPixels(buffer.pixels, 0, buffer.width, 0, 0, buffer.width, buffer.height)
        }

    /** Copies a bitmap into a buffer. The bitmap is not recycled. */
    fun fromBitmap(bitmap: Bitmap): PixelBuffer {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return PixelBuffer(width, height, pixels)
    }

    /** Encodes to PNG (lossless, keeps alpha). */
    fun toPngBytes(
        buffer: PixelBuffer,
        dpi: Int? = null,
    ): ByteArray = PngCodec.encode(buffer, dpi)

    /**
     * Encodes to JPEG. JPEG has no alpha channel, so transparent pixels are composited over
     * [matteColor] first (white by default) — otherwise they would come out black.
     */
    fun toJpegBytes(
        buffer: PixelBuffer,
        quality: Int = 92,
        matteColor: Int = 0xFFFFFFFF.toInt(),
        dpi: Int = 72,
    ): ByteArray {
        val flattened = PixelBuffer(buffer.width, buffer.height)
        for (i in flattened.pixels.indices) {
            flattened.pixels[i] =
                com.artflow.studio.core.pixels.BlendModes.sourceOver(
                    matteColor or 0xFF000000.toInt(),
                    buffer.pixels[i],
                )
        }
        val bitmap = toBitmap(flattened)
        return try {
            ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)) { "JPEG encoding failed" }
                com.artflow.studio.core.export.JpegDensity
                    .withDpi(out.toByteArray(), dpi)
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Encodes to lossless WebP (smaller than PNG for photographic layers on API 30+). */
    fun toWebpBytes(
        buffer: PixelBuffer,
        lossless: Boolean = true,
        quality: Int = 90,
    ): ByteArray {
        val bitmap = toBitmap(buffer, premultiplied = false)
        return try {
            ByteArrayOutputStream().use { out ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && lossless) {
                    check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)) { "WebP encoding failed" }
                } else {
                    check(bitmap.compress(Bitmap.CompressFormat.WEBP, quality.coerceIn(1, 100), out)) { "WebP encoding failed" }
                }
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Decodes PNG/JPEG/WebP bytes into a buffer, or null when the data is not an image. */
    fun fromEncodedBytes(bytes: ByteArray): PixelBuffer? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        require(CanvasOperations.isSizeSafe(bounds.outWidth, bounds.outHeight)) { "Decoded image exceeds the canvas limits" }
        val options =
            BitmapFactory.Options().apply {
                inPremultiplied = false
                inScaled = false
            }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return try {
            fromBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Decodes an image file, downsampling so a 50 megapixel import cannot exhaust memory.
     * The returned buffer keeps the image's aspect ratio.
     */
    fun decodeFile(
        path: String,
        maxDimension: Int = 4096,
    ): PixelBuffer? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options =
            BitmapFactory.Options().apply {
                inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        val bitmap = BitmapFactory.decodeFile(path, options) ?: return null
        val decoded = fromBitmap(bitmap)
        bitmap.recycle()
        return if (decoded.width > maxDimension || decoded.height > maxDimension) {
            val scale = maxDimension.toFloat() / maxOf(decoded.width, decoded.height)
            decoded.scaled(
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
            )
        } else {
            decoded
        }
    }

    /** Power-of-two downsampling factor that keeps the longest side within [maxDimension]. */
    fun calculateSampleSize(
        width: Int,
        height: Int,
        maxDimension: Int,
    ): Int {
        require(width > 0 && height > 0 && maxDimension > 0) { "Image dimensions must be positive" }
        var sampleSize = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= maxDimension) {
            longest /= 2
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * Rasterises a selection mask's outline into pixels, used for the marching-ants band when a
     * GL overlay is not available (exports) and for baking a mask into a layer mask image.
     */
    fun maskToBuffer(
        mask: SelectionMask,
        color: Int = 0xFFFFFFFF.toInt(),
    ): PixelBuffer = mask.toMaskBitmap(color)

    /**
     * Builds a [PixelBuffer] from a path-based shape (rectangle/ellipse/polygon) by rasterising it
     * with the platform renderer. Shape tools use this so they inherit correct anti-aliasing.
     */
    fun rasterizePath(
        width: Int,
        height: Int,
        build: (Path) -> Unit,
        fillColor: Int,
        strokeColor: Int = 0,
        strokeWidth: Float = 0f,
    ): PixelBuffer {
        val buffer = PixelBuffer(width, height)
        val bitmap = toBitmap(buffer)
        val canvas = Canvas(bitmap)
        val path = Path().apply(build)
        if ((fillColor ushr 24) != 0) {
            canvas.drawPath(
                path,
                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.FILL
                    this.color = fillColor
                },
            )
        }
        if ((strokeColor ushr 24) != 0 && strokeWidth > 0f) {
            canvas.drawPath(
                path,
                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE
                    this.color = strokeColor
                    this.strokeWidth = strokeWidth
                    strokeCap = android.graphics.Paint.Cap.ROUND
                    strokeJoin = android.graphics.Paint.Join.ROUND
                },
            )
        }
        val result = fromBitmap(bitmap)
        bitmap.recycle()
        return result
    }

    /** Converts a rectangle in buffer space into a platform [RectF] (shape/text placement). */
    fun toRectF(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): RectF = RectF(left, top, right, bottom)

    /** Creates a [Region] for hit-testing a path against the canvas bounds. */
    fun regionFor(
        path: Path,
        width: Int,
        height: Int,
    ): Region {
        val region = Region(0, 0, width, height)
        region.setPath(path, Region(0, 0, width, height))
        return region
    }
}
