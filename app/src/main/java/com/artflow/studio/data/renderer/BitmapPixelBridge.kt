package com.artflow.studio.data.renderer

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.color.IccProfile
import com.artflow.studio.core.export.JpegIcc
import com.artflow.studio.core.export.PngCodec
import com.artflow.studio.core.pixels.ImportPreparation
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

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
        require(CanvasOperations.isSizeSafe(width, height)) { "Decoded image exceeds the canvas limits" }
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return PixelBuffer(width, height, pixels)
    }

    /** Encodes to PNG (lossless, keeps alpha). */
    fun toPngBytes(
        buffer: PixelBuffer,
        dpi: Int? = null,
        profile: ColorProfile = ColorProfile.SRGB,
    ): ByteArray = PngCodec.encode(buffer, dpi, profile)

    /**
     * Encodes to JPEG. JPEG has no alpha channel, so transparent pixels are composited over
     * [matteColor] first (white by default) — otherwise they would come out black.
     */
    fun toJpegBytes(
        buffer: PixelBuffer,
        quality: Int = 92,
        matteColor: Int = 0xFFFFFFFF.toInt(),
        dpi: Int = 72,
        profile: ColorProfile = ColorProfile.SRGB,
    ): ByteArray {
        val flattened = PixelBuffer(buffer.width, buffer.height)
        for (i in flattened.pixels.indices) {
            flattened.pixels[i] =
                com.artflow.studio.core.pixels.BlendModes.sourceOver(
                    matteColor or 0xFF000000.toInt(),
                    buffer.pixels[i],
                )
        }
        val out = ByteArrayOutputStream()
        val success =
            runCatching {
                val bitmap = toBitmap(flattened)
                try {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
                } finally {
                    bitmap.recycle()
                }
            }.getOrDefault(false)

        val rawBytes =
            if (success && out.size() > 0) {
                out.toByteArray()
            } else {
                out.reset()
                check(encodeJpegJvmFallback(flattened, out)) { "JPEG encoding failed" }
                out.toByteArray()
            }
        val jpeg =
            com.artflow.studio.core.export.JpegDensity
                .withDpi(rawBytes, dpi)
        return IccProfile.forProfile(profile)?.let { JpegIcc.withProfile(jpeg, it) } ?: jpeg
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
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return decodeJvmFallback(bytes)
        }
        require(CanvasOperations.isSizeSafe(bounds.outWidth, bounds.outHeight)) { "Decoded image exceeds the canvas limits" }
        val options =
            BitmapFactory.Options().apply {
                inPremultiplied = false
                inScaled = false
            }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return decodeJvmFallback(bytes)
        return try {
            fromBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** Brush geometry is sampled only by the authored tile importer, never by the platform decoder. */
    internal fun decodeBrushImage(
        bytes: ByteArray,
        memoryBudgetBytes: Long,
    ): PixelBuffer? =
        withImportMemory {
            val (width, height) = encodedDimensions(bytes) ?: return@withImportMemory null
            val allowance = minOf(memoryBudgetBytes, ImageDecodeBudget.currentBytes())
            val plan = ImageDecodeBudget.plan(width, height, CanvasOperations.MAX_DIMENSION, allowance)
            require(plan.sampleSize == 1) { "These brush images need too much memory. Import fewer or smaller images." }
            val options =
                BitmapFactory.Options().apply {
                    inPremultiplied = false
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return@withImportMemory null
            importedPixels(bitmap, plan)
        }

    /** A document thumbnail keeps its encoded geometry, with admission before bitmap allocation. */
    internal fun decodeProcreateThumbnail(
        bytes: ByteArray,
        memoryBudgetBytes: Long,
    ): PixelBuffer? =
        withImportMemory {
            val (width, height) = encodedDimensions(bytes) ?: return@withImportMemory null
            val budget = minOf(memoryBudgetBytes, ImageDecodeBudget.currentBytes())
            val plan = ImageDecodeBudget.plan(width, height, CanvasOperations.MAX_DIMENSION, budget - bytes.size.toLong() * 3)
            require(plan.sampleSize == 1) { "The Procreate thumbnail needs too much memory" }
            val options =
                BitmapFactory.Options().apply {
                    inPremultiplied = false
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return@withImportMemory null
            importedPixels(bitmap, plan)
        }

    /** Metadata only: model preparation admits every source before allocating the first bitmap. */
    fun encodedDimensions(bytes: ByteArray): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
    }

    /** Full texture samples keep their UV interpretation; reject pressure rather than silently resizing a plane. */
    fun decodeModelTexture(bytes: ByteArray): PixelBuffer? =
        withImportMemory {
            val (width, height) = encodedDimensions(bytes) ?: return@withImportMemory null
            val plan = ImageDecodeBudget.plan(width, height, CanvasOperations.MAX_DIMENSION, ImageDecodeBudget.currentBytes() - bytes.size)
            require(plan.sampleSize == 1) { "This model texture needs too much memory; use smaller textures" }
            val options =
                BitmapFactory.Options().apply {
                    inPremultiplied = false
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return@withImportMemory null
            importedPixels(bitmap, plan)
        }

    private fun decodeJvmFallback(bytes: ByteArray): PixelBuffer? =
        runCatching {
            val imageIOClass = Class.forName("javax.imageio.ImageIO")
            val readMethod = imageIOClass.getMethod("read", java.io.InputStream::class.java)
            val bufferedImage = readMethod.invoke(null, java.io.ByteArrayInputStream(bytes)) ?: return null
            val getWidth = bufferedImage.javaClass.getMethod("getWidth")
            val getHeight = bufferedImage.javaClass.getMethod("getHeight")
            val width = getWidth.invoke(bufferedImage) as Int
            val height = getHeight.invoke(bufferedImage) as Int
            if (!CanvasOperations.isSizeSafe(width, height)) return null
            val pixels = IntArray(width * height)
            val getRGB =
                bufferedImage.javaClass.getMethod(
                    "getRGB",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    IntArray::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
            getRGB.invoke(bufferedImage, 0, 0, width, height, pixels, 0, width)
            PixelBuffer(width, height, pixels)
        }.getOrNull()

    private fun encodeJpegJvmFallback(
        buffer: PixelBuffer,
        out: java.io.OutputStream,
    ): Boolean =
        runCatching {
            val imageClass = Class.forName("java.awt.image.BufferedImage")
            val constructor =
                imageClass.getConstructor(
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
            val bufferedImage = constructor.newInstance(buffer.width, buffer.height, 1)
            val setRGB =
                imageClass.getMethod(
                    "setRGB",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    IntArray::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
            setRGB.invoke(bufferedImage, 0, 0, buffer.width, buffer.height, buffer.pixels, 0, buffer.width)
            val imageIOClass = Class.forName("javax.imageio.ImageIO")
            val renderedImageClass = Class.forName("java.awt.image.RenderedImage")
            val writeMethod =
                imageIOClass.getMethod(
                    "write",
                    renderedImageClass,
                    String::class.java,
                    java.io.OutputStream::class.java,
                )
            writeMethod.invoke(null, bufferedImage, "jpg", out) as Boolean
        }.getOrDefault(false)

    /**
     * Decodes an imported image file within the edge and heap budgets before allocating pixels.
     * The power-of-two sample can be smaller than [maxDimension] on memory-constrained devices.
     */
    fun decodeFile(
        path: String,
        maxDimension: Int = 4096,
        memoryBudgetBytes: Long = ImageDecodeBudget.currentBytes(),
    ): PixelBuffer? =
        withImportMemory {
            val file = File(path)
            if (!file.isFile) return@withImportMemory null
            val budget = minOf(memoryBudgetBytes, ImageDecodeBudget.currentBytes())
            val bytes = file.inputStream().use { ImageDecodeBudget.readEncoded(it, budget) }
            decodeSampledBytes(bytes, maxDimension, budget)
        }

    private fun decodeSampledBytes(
        bytes: ByteArray,
        maxDimension: Int,
        memoryBudgetBytes: Long,
    ): PixelBuffer? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val orientation = photoOrientation(bytes)
        val (width, height) = ImageOrientation.dimensions(bounds.outWidth, bounds.outHeight, orientation)
        val plan = ImageDecodeBudget.plan(width, height, maxDimension, memoryBudgetBytes - bytes.size * 3L)
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = plan.sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return ImageOrientation.apply(importedPixels(bitmap, plan), orientation).also { plan.checkDecodedSize(it.width, it.height) }
    }

    private fun photoOrientation(bytes: ByteArray): Int =
        try {
            bytes.inputStream().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        } catch (_: IOException) {
            // Unsupported or unreadable optional metadata keeps the encoded row/column order.
            ExifInterface.ORIENTATION_NORMAL
        }

    /**
     * Decodes a picked image (honouring EXIF rotation and mirroring on every supported API), downsampled so its longest
     * side stays within [maxDimension] and its simultaneous rasters fit the import heap budget.
     * A bounded encoded snapshot makes metadata and pixels consistent even if a provider changes
     * what it returns between opens. The metadata pass chooses a sample before allocating pixels.
     */
    fun decodeUri(
        resolver: ContentResolver,
        uri: Uri,
        maxDimension: Int = 4096,
        memoryBudgetBytes: Long = ImageDecodeBudget.currentBytes(),
    ): PixelBuffer? = decodeImportedSource({ resolver.openInputStream(uri) }, maxDimension, memoryBudgetBytes)

    /** The one-open source contract also covers providers whose content changes between opens. */
    internal fun decodeImportedSource(
        openStream: () -> InputStream?,
        maxDimension: Int,
        memoryBudgetBytes: Long,
    ): PixelBuffer? =
        withImportMemory {
            val budget = minOf(memoryBudgetBytes, ImageDecodeBudget.currentBytes())
            val bytes = openStream()?.use { ImageDecodeBudget.readEncoded(it, budget) } ?: return@withImportMemory null
            if (Build.VERSION.SDK_INT <
                Build.VERSION_CODES.P
            ) {
                return@withImportMemory decodeSampledBytes(bytes, maxDimension, budget)
            }
            var plan: ImageDecodeBudget.Plan? = null
            val bitmap =
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    val target =
                        ImageDecodeBudget.plan(
                            info.size.width,
                            info.size.height,
                            maxDimension,
                            budget - bytes.size * 3L,
                        )
                    plan = target
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    // Unlike targetSampleSize, the exact bounded size leaves no decoder-specific
                    // rounding above the allocation plan. ImageDecoder handles EXIF orientation.
                    decoder.setTargetSize(target.width, target.height)
                }
            importedPixels(bitmap, plan)
        }

    /** Decoder-internal/native allocations and other app work can exceed the metadata estimate. */
    private fun withImportMemory(decode: () -> PixelBuffer?): PixelBuffer? =
        ImportPreparation.withAdmission {
            try {
                decode()
            } catch (error: OutOfMemoryError) {
                // Stream/bitmap owners unwind first. Report a failed import without publishing pixels.
                throw IllegalArgumentException(ImageDecodeBudget.MEMORY_MESSAGE, error)
            }
        }

    private fun importedPixels(
        bitmap: Bitmap,
        plan: ImageDecodeBudget.Plan?,
    ): PixelBuffer =
        try {
            requireNotNull(plan) { "The image decoder did not provide allocation metadata" }.checkDecodedSize(bitmap.width, bitmap.height)
            val software =
                if (bitmap.config == Bitmap.Config.ARGB_8888) {
                    bitmap
                } else {
                    requireNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)) { "Not enough memory to convert the imported image" }
                }
            try {
                fromBitmap(software)
            } finally {
                if (software !== bitmap) software.recycle()
            }
        } finally {
            bitmap.recycle()
        }

    /** Power-of-two downsampling factor that keeps the longest side within [maxDimension]. */
    fun calculateSampleSize(
        width: Int,
        height: Int,
        maxDimension: Int,
    ): Int = ImageDecodeBudget.sampleSize(width, height, maxDimension)

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
