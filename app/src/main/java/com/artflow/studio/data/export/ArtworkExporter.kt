package com.artflow.studio.data.export

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import com.artflow.studio.core.export.ExportError
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportNaming
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.export.ExportResult
import com.artflow.studio.core.export.FitMode
import com.artflow.studio.core.export.GifEncoder
import com.artflow.studio.core.export.PdfPageSize
import com.artflow.studio.core.export.PsdCodec
import com.artflow.studio.core.pixels.BlendModes
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.domain.model.layer.Layer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt
import android.graphics.Canvas as AndroidCanvas

/** One layer's pixels plus the compositing properties needed to export it faithfully. */
data class LayerRaster(
    val name: String,
    val buffer: PixelBuffer,
    val layer: Layer,
)

/**
 * Export pipeline (Phases 38-44).
 *
 * Owns format encoding, output sizing/fitting, writing into the project's export folder, optional
 * publishing to the device gallery, and share intents. Each format is implemented from the parts
 * that are actually available on API 26+ — PNG/JPEG/WebP via the platform encoders, PDF via
 * `PdfDocument`, GIF via our own encoder, PSD via our own codec, MP4 via `MediaCodec`.
 */
@Singleton
class ArtworkExporter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val storage: ProjectStorage,
    ) {
        /**
         * Exports a still image.
         *
         * @param composite the flattened artwork (already honouring visibility and blend modes).
         * @param layers layer rasters, used by PSD export.
         */
        suspend fun exportStill(
            projectId: Long,
            projectName: String,
            composite: PixelBuffer,
            layers: List<LayerRaster>,
            options: ExportOptions,
            hasAdjustmentLayers: Boolean = false,
        ): Result<ExportResult> =
            withContext(Dispatchers.Default) {
                try {
                    val (targetWidth, targetHeight) =
                        ExportNaming.resolveSize(
                            composite.width,
                            composite.height,
                            options,
                        )
                    if (!com.artflow.studio.core.canvas.CanvasOperations
                            .isSizeSafe(targetWidth, targetHeight)
                    ) {
                        return@withContext Result.failure(
                            ExportFailure(ExportError.TooLarge(targetWidth, targetHeight).message),
                        )
                    }

                    val prepared = prepare(composite, targetWidth, targetHeight, options)
                    val fileName = ExportNaming.fileName(projectName, options)

                    val bytes: ByteArray =
                        when (options.format) {
                            ExportFormat.PNG -> BitmapPixelBridge.toPngBytes(flatten(prepared, options), options.dpi)
                            ExportFormat.JPEG ->
                                BitmapPixelBridge.toJpegBytes(
                                    flatten(prepared, options),
                                    options.quality,
                                    options.backgroundColor,
                                    options.dpi,
                                )
                            ExportFormat.WEBP ->
                                BitmapPixelBridge.toWebpBytes(
                                    flatten(prepared, options),
                                    lossless = options.quality >= 100,
                                    quality = options.quality,
                                )
                            ExportFormat.PDF -> buildPdf(listOf(prepared), options)
                            ExportFormat.PSD -> buildPsd(prepared, layers, options, hasAdjustmentLayers)
                            else -> return@withContext Result.failure(
                                ExportFailure(ExportError.UnsupportedFormat(options.format).message),
                            )
                        }

                    val file = storage.saveExport(projectId, fileName, bytes)
                    val result =
                        ExportResult(
                            format = options.format,
                            filePath = file.absolutePath,
                            fileName = fileName,
                            byteCount = bytes.size.toLong(),
                            width = prepared.width,
                            height = prepared.height,
                            warning =
                                if (options.format == ExportFormat.PSD && hasAdjustmentLayers) {
                                    "Adjustment effects are baked into a visible Artwork layer; original pixel layers are included hidden."
                                } else {
                                    null
                                },
                        )
                    Timber.d("Exported ${options.format} (${result.sizeLabel}) to ${file.name}")
                    Result.success(result)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    Timber.e(e, "Still export failed")
                    Result.failure(ExportFailure(ExportError.EncodingFailed(e.message ?: "unknown").message))
                }
            }

        /**
         * Exports an animation: GIF, MP4, a zipped PNG sequence, or the current frame as a still.
         */
        suspend fun exportAnimation(
            projectId: Long,
            projectName: String,
            frames: List<PixelBuffer>,
            delaysMs: List<Int>,
            options: ExportOptions,
        ): Result<ExportResult> =
            withContext(Dispatchers.Default) {
                try {
                    if (frames.isEmpty()) {
                        return@withContext Result.failure(
                            ExportFailure(ExportError.NoFrames.message),
                        )
                    }
                    val base = frames.first()
                    val (targetWidth, targetHeight) = ExportNaming.resolveSize(base.width, base.height, options)
                    if (!com.artflow.studio.core.canvas.CanvasOperations
                            .isSizeSafe(targetWidth, targetHeight)
                    ) {
                        return@withContext Result.failure(
                            ExportFailure(ExportError.TooLarge(targetWidth, targetHeight).message),
                        )
                    }

                    require(frames.size <= ProjectStorage.MAX_FRAMES) { "Too many frames to export" }
                    require(targetWidth.toLong() * targetHeight * 4L * frames.size <= Runtime.getRuntime().maxMemory() / 4) {
                        "The animation is too large for this device; reduce the output scale"
                    }
                    val prepared =
                        frames.map {
                            currentCoroutineContext().ensureActive()
                            prepare(it, targetWidth, targetHeight, options)
                        }
                    val fileName = ExportNaming.fileName(projectName, options)

                    val bytes =
                        when (options.format) {
                            ExportFormat.GIF -> buildGif(prepared, delaysMs, options)
                            ExportFormat.MP4 -> buildMp4(prepared, delaysMs, options)
                            ExportFormat.FRAME_SEQUENCE -> buildFrameSequenceZip(prepared, projectName, options)
                            ExportFormat.PNG -> BitmapPixelBridge.toPngBytes(flatten(prepared.first(), options), options.dpi)
                            ExportFormat.JPEG ->
                                BitmapPixelBridge.toJpegBytes(
                                    flatten(prepared.first(), options),
                                    options.quality,
                                    options.backgroundColor,
                                    options.dpi,
                                )
                            ExportFormat.PDF -> buildPdf(prepared, options)
                            else -> return@withContext Result.failure(
                                ExportFailure(ExportError.UnsupportedFormat(options.format).message),
                            )
                        }

                    val file = storage.saveExport(projectId, fileName, bytes)
                    val result =
                        ExportResult(
                            format = options.format,
                            filePath = file.absolutePath,
                            fileName = fileName,
                            byteCount = bytes.size.toLong(),
                            width = if (options.format == ExportFormat.MP4) (targetWidth + 1) and -2 else targetWidth,
                            height = if (options.format == ExportFormat.MP4) (targetHeight + 1) and -2 else targetHeight,
                            frameCount = prepared.size,
                        )
                    Timber.d("Exported ${options.format} (${result.sizeLabel}, ${prepared.size} frames)")
                    Result.success(result)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    Timber.e(e, "Animation export failed")
                    Result.failure(ExportFailure(ExportError.EncodingFailed(e.message ?: "unknown").message))
                }
            }

        /** Every page and bitmap is released even when rendering or writing fails. */
        private fun buildPdf(
            frames: List<PixelBuffer>,
            options: ExportOptions,
        ): ByteArray {
            val document = PdfDocument()
            try {
                frames.forEachIndexed { index, frame ->
                    val oversample = options.pdfOversample.coerceIn(0.25f, 3f)
                    val imageWidth = max(1, (frame.width * oversample).roundToInt())
                    val imageHeight = max(1, (frame.height * oversample).roundToInt())
                    check(
                        com.artflow.studio.core.canvas.CanvasOperations
                            .isSizeSafe(imageWidth, imageHeight),
                    ) {
                        "The PDF image is too large; reduce its scale"
                    }
                    val rendered =
                        if (imageWidth == frame.width && imageHeight == frame.height) {
                            frame
                        } else {
                            frame.scaled(imageWidth, imageHeight)
                        }
                    val bitmap = BitmapPixelBridge.toBitmap(flatten(rendered, options))
                    try {
                        val dpi = options.dpi.coerceAtLeast(36).toFloat()
                        val pageWidth =
                            if (options.pdfPageSize == PdfPageSize.FIT_CANVAS) {
                                frame.width * 72f / dpi
                            } else {
                                options.pdfPageSize.widthPt
                            }
                        val pageHeight =
                            if (options.pdfPageSize == PdfPageSize.FIT_CANVAS) {
                                frame.height * 72f / dpi
                            } else {
                                options.pdfPageSize.heightPt
                            }
                        val width = pageWidth.roundToInt().coerceAtLeast(1)
                        val height = pageHeight.roundToInt().coerceAtLeast(1)
                        val page = document.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
                        try {
                            val placement = ExportNaming.applyFit(bitmap.width, bitmap.height, width, height, FitMode.FIT)
                            val target =
                                RectF(
                                    placement.offsetX.toFloat(),
                                    placement.offsetY.toFloat(),
                                    (placement.offsetX + placement.drawWidth).toFloat(),
                                    (placement.offsetY + placement.drawHeight).toFloat(),
                                )
                            page.canvas.drawBitmap(bitmap, null, target, Paint(Paint.FILTER_BITMAP_FLAG))
                        } finally {
                            document.finishPage(page)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
                return ByteArrayOutputStream().use { out ->
                    document.writeTo(out)
                    out.toByteArray()
                }
            } finally {
                document.close()
            }
        }

        private fun buildPsd(
            composite: PixelBuffer,
            layers: List<LayerRaster>,
            options: ExportOptions,
            hasAdjustmentLayers: Boolean,
        ): ByteArray {
            val psdLayers =
                layers
                    .map { entry ->
                        PsdCodec.PsdLayer(
                            name = entry.name,
                            pixels = prepare(entry.buffer, composite.width, composite.height, options),
                            opacity = (entry.layer.opacity * 255f).roundToInt().coerceIn(0, 255),
                            isVisible = !hasAdjustmentLayers && (options.includeHiddenLayers || entry.layer.isVisible),
                            blendMode = entry.layer.blendMode,
                            isClippingMask = entry.layer.isClippingMask,
                        )
                    }.toMutableList()
            if (hasAdjustmentLayers) {
                psdLayers += PsdCodec.PsdLayer("Artwork (rendered adjustments)", flatten(composite, options))
            } else if (options.flattenOntoBackground) {
                psdLayers.add(
                    0,
                    PsdCodec.PsdLayer(
                        "Export background",
                        PixelBuffer.filled(composite.width, composite.height, options.backgroundColor or 0xFF000000.toInt()),
                    ),
                )
            }
            return PsdCodec.write(
                width = composite.width,
                height = composite.height,
                layers = psdLayers,
                composite = flatten(composite, options),
                dpi = options.dpi,
                useRle = options.psdUseRle,
            )
        }

        private fun buildGif(
            frames: List<PixelBuffer>,
            delaysMs: List<Int>,
            options: ExportOptions,
        ): ByteArray {
            val width = frames.first().width
            val height = frames.first().height
            val argbFrames =
                frames.map { frame ->
                    if (options.flattenOntoBackground) {
                        flatten(frame, options).pixels
                    } else {
                        frame.pixels
                    }
                }
            return GifEncoder.encode(
                frames = argbFrames,
                width = width,
                height = height,
                delaysMs = delaysMs,
                loop = options.gifLoop,
                matteColor = options.backgroundColor,
                keepTransparency = options.keepGifTransparency && !options.flattenOntoBackground,
            )
        }

        private fun buildFrameSequenceZip(
            frames: List<PixelBuffer>,
            projectName: String,
            options: ExportOptions,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                val prefix = ExportNaming.sanitize(projectName).ifEmpty { "ArtFlow" }
                frames.forEachIndexed { index, frame ->
                    val entry = ZipEntry("%s_%04d.png".format(prefix, index + 1))
                    zip.putNextEntry(entry)
                    zip.write(BitmapPixelBridge.toPngBytes(flatten(frame, options), options.dpi))
                    zip.closeEntry()
                }
            }
            return out.toByteArray()
        }

        /** The encoder preserves every source pixel and pads odd dimensions instead of shrinking. */
        private suspend fun buildMp4(
            frames: List<PixelBuffer>,
            delaysMs: List<Int>,
            options: ExportOptions,
        ): ByteArray {
            val tempFile = File.createTempFile("artflow-video", ".mp4", context.cacheDir)
            return try {
                Mp4Encoder.encode(tempFile, frames, delaysMs, options)
                withContext(Dispatchers.IO) { tempFile.readBytes() }
            } finally {
                tempFile.delete()
            }
        }

        /** Applies the output scale and fit mode, returning a freshly sized buffer. */
        private fun prepare(
            source: PixelBuffer,
            targetWidth: Int,
            targetHeight: Int,
            options: ExportOptions,
        ): PixelBuffer {
            if (source.width == targetWidth && source.height == targetHeight) return source
            return if (options.fitMode == FitMode.STRETCH) {
                source.scaled(targetWidth, targetHeight)
            } else {
                val placement =
                    ExportNaming.applyFit(
                        source.width,
                        source.height,
                        targetWidth,
                        targetHeight,
                        options.fitMode,
                    )
                val resized = source.scaled(placement.drawWidth, placement.drawHeight)
                val canvas = PixelBuffer(placement.outputWidth, placement.outputHeight)
                if (options.flattenOntoBackground) canvas.fill(options.backgroundColor)
                canvas.drawInto(resized, placement.offsetX, placement.offsetY)
                canvas
            }
        }

        /** Composites the artwork onto the background colour, removing transparency. */
        private fun flatten(
            source: PixelBuffer,
            options: ExportOptions,
        ): PixelBuffer {
            if (!options.flattenOntoBackground) return source
            val out = PixelBuffer(source.width, source.height)
            for (i in out.pixels.indices) {
                out.pixels[i] = BlendModes.sourceOver(options.backgroundColor or 0xFF000000.toInt(), source.pixels[i])
            }
            return out
        }

        // -----------------------------------------------------------------------------------------
        // Gallery publishing and sharing
        // -----------------------------------------------------------------------------------------

        /** Publishes only supported image/video formats, removing incomplete MediaStore rows. */
        suspend fun publishToGallery(
            result: ExportResult,
            displayName: String,
        ): String? =
            withContext(Dispatchers.IO) {
                if (!result.format.supportsGallery) return@withContext null
                val resolver = context.contentResolver
                var inserted: Uri? = null
                var complete = false
                try {
                    val source = storage.exportedFile(result.filePath)
                    val video = result.format == ExportFormat.MP4
                    val values =
                        ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, result.fileName)
                            put(MediaStore.MediaColumns.MIME_TYPE, result.format.mimeType)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                val folder = if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                                put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/ArtFlow")
                                put(MediaStore.MediaColumns.IS_PENDING, 1)
                            }
                        }
                    val collection =
                        if (video) {
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        } else {
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        }
                    val uri = requireNotNull(resolver.insert(collection, values)) { "Cannot create a gallery item" }
                    inserted = uri
                    requireNotNull(resolver.openOutputStream(uri, "w")) { "Cannot open the gallery item" }.use { output ->
                        source.inputStream().use { input -> input.copyTo(output) }
                    }
                    currentCoroutineContext().ensureActive()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        check(resolver.update(uri, values, null, null) == 1) { "Cannot finish the gallery item" }
                    }
                    complete = true
                    Timber.d("Published $displayName to the gallery")
                    uri.toString()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: java.io.IOException) {
                    galleryFailure(error)
                } catch (error: SecurityException) {
                    galleryFailure(error)
                } catch (error: IllegalArgumentException) {
                    galleryFailure(error)
                } catch (error: IllegalStateException) {
                    galleryFailure(error)
                } catch (error: UnsupportedOperationException) {
                    galleryFailure(error)
                } finally {
                    if (!complete) {
                        inserted?.let { uri ->
                            runCatching { resolver.delete(uri, null, null) }
                                .onFailure { Timber.w(it, "Could not remove an incomplete gallery item") }
                        }
                    }
                }
            }

        private fun galleryFailure(error: Exception): String? {
            Timber.w(error, "Could not publish the export to the gallery")
            return null
        }

        /** Copies an existing export into the exact destination granted by the system picker. */
        suspend fun writeToDocument(
            filePath: String,
            destination: Uri,
        ) = withContext(Dispatchers.IO) {
            require(destination.scheme == "content") { "Choose a document destination" }
            val source = storage.exportedFile(filePath)
            requireNotNull(context.contentResolver.openOutputStream(destination, "wt")) { "Cannot open the chosen file" }.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
                output.flush()
            }
        }

        /** Share intent for an exported file, routed through the app's FileProvider. */
        fun shareIntent(
            result: ExportResult,
            chooserTitle: String = "Share artwork",
        ): Intent {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, storage.exportedFile(result.filePath))
            val send =
                Intent(Intent.ACTION_SEND).apply {
                    type = result.format.mimeType
                    clipData = ClipData.newRawUri(result.fileName, uri)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, result.fileName)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            return Intent.createChooser(send, chooserTitle)
        }

        /** Open-in-place intent for the exported file (PDF viewers, image apps). */
        fun viewIntent(result: ExportResult): Intent {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, storage.exportedFile(result.filePath))
            return Intent(Intent.ACTION_VIEW).apply {
                clipData = ClipData.newRawUri(result.fileName, uri)
                setDataAndType(uri, result.format.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        /** Renders a bitmap for quick previews (used by the export sheet). */
        fun previewBitmap(
            buffer: PixelBuffer,
            maxSize: Int = 512,
        ): Bitmap {
            val scale =
                minOf(
                    maxSize.toFloat() / buffer.width.coerceAtLeast(1),
                    maxSize.toFloat() / buffer.height.coerceAtLeast(1),
                ).coerceAtMost(1f)
            val scaled =
                if (scale >= 1f) {
                    buffer
                } else {
                    buffer.scaled(
                        (buffer.width * scale).roundToInt().coerceAtLeast(1),
                        (buffer.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
            return BitmapPixelBridge.toBitmap(scaled)
        }

        /** Draws a checkerboard behind transparency, matching the canvas view. */
        fun withCheckerboard(
            buffer: PixelBuffer,
            cellSize: Int = 16,
        ): Bitmap {
            val bitmap = createBitmap(buffer.width, buffer.height)
            val canvas = AndroidCanvas(bitmap)
            val light = Paint().apply { color = 0xFFFFFFFF.toInt() }
            val dark = Paint().apply { color = 0xFFCCCCCC.toInt() }
            for (y in 0 until buffer.height step cellSize) {
                for (x in 0 until buffer.width step cellSize) {
                    val paint = if (((x / cellSize) + (y / cellSize)) % 2 == 0) light else dark
                    canvas.drawRect(
                        x.toFloat(),
                        y.toFloat(),
                        (x + cellSize).toFloat(),
                        (y + cellSize).toFloat(),
                        paint,
                    )
                }
            }
            val overlay = BitmapPixelBridge.toBitmap(buffer)
            canvas.drawBitmap(overlay, 0f, 0f, Paint())
            overlay.recycle()
            return bitmap
        }
    }

/**
 * Carries an [ExportError] message through the standard `Result` type.
 *
 * Export failures are all recoverable (bad size, unsupported format, encoder error), so the
 * pipeline reports them as values rather than throwing across coroutine boundaries.
 */
class ExportFailure(
    message: String,
) : Exception(message)
