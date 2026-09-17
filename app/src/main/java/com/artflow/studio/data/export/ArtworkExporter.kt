package com.artflow.studio.data.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
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
import kotlinx.coroutines.Dispatchers
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
                    val fileName =
                        options.fileName
                            ?: ExportNaming.defaultFileName(projectName, options.format)

                    val bytes: ByteArray =
                        when (options.format) {
                            ExportFormat.PNG -> BitmapPixelBridge.toPngBytes(prepared)
                            ExportFormat.JPEG ->
                                BitmapPixelBridge.toJpegBytes(
                                    flatten(prepared, options),
                                    options.quality,
                                    options.backgroundColor,
                                )
                            ExportFormat.WEBP ->
                                BitmapPixelBridge.toWebpBytes(
                                    flatten(prepared, options),
                                    lossless = options.quality >= 100,
                                    quality = options.quality,
                                )
                            ExportFormat.PDF -> buildPdf(listOf(prepared), options, projectName)
                            ExportFormat.PSD -> buildPsd(prepared, layers, options, targetWidth, targetHeight)
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
                        )
                    Timber.d("Exported ${options.format} (${result.sizeLabel}) to ${file.name}")
                    Result.success(result)
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

                    val prepared = frames.map { prepare(it, targetWidth, targetHeight, options) }
                    val fileName =
                        options.fileName
                            ?: ExportNaming.defaultFileName(projectName, options.format)

                    val bytes =
                        when (options.format) {
                            ExportFormat.GIF -> buildGif(prepared, delaysMs, options)
                            ExportFormat.MP4 -> buildMp4(prepared, delaysMs, options)
                            ExportFormat.FRAME_SEQUENCE -> buildFrameSequenceZip(prepared, projectName, options)
                            ExportFormat.PNG -> BitmapPixelBridge.toPngBytes(prepared.first())
                            ExportFormat.JPEG ->
                                BitmapPixelBridge.toJpegBytes(
                                    flatten(prepared.first(), options),
                                    options.quality,
                                    options.backgroundColor,
                                )
                            ExportFormat.PDF -> buildPdf(prepared, options, projectName)
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
                            width = targetWidth,
                            height = targetHeight,
                            frameCount = prepared.size,
                        )
                    Timber.d("Exported ${options.format} (${result.sizeLabel}, ${prepared.size} frames)")
                    Result.success(result)
                } catch (e: Exception) {
                    Timber.e(e, "Animation export failed")
                    Result.failure(ExportFailure(ExportError.EncodingFailed(e.message ?: "unknown").message))
                }
            }

        /** Multi-page PDF: one page per frame, with the chosen page size and DPI. */
        private fun buildPdf(
            frames: List<PixelBuffer>,
            options: ExportOptions,
            projectName: String,
        ): ByteArray {
            val document = PdfDocument()
            try {
                frames.forEachIndexed { index, frame ->
                    val oversample = options.pdfOversample.coerceIn(0.25f, 3f)
                    val imageWidth = max(1, (frame.width * oversample).roundToInt())
                    val imageHeight = max(1, (frame.height * oversample).roundToInt())
                    val rendered =
                        if (imageWidth == frame.width && imageHeight == frame.height) {
                            frame
                        } else {
                            frame.scaled(imageWidth, imageHeight)
                        }
                    val bitmap = BitmapPixelBridge.toBitmap(flatten(rendered, options))

                    val (pageWidth, pageHeight) =
                        if (options.pdfPageSize == PdfPageSize.FIT_CANVAS) {
                            // Points at the requested DPI so the PDF prints at the artist's intended size.
                            val dpi = options.dpi.coerceAtLeast(36).toFloat()
                            (frame.width * 72f / dpi) to (frame.height * 72f / dpi)
                        } else {
                            options.pdfPageSize.widthPt to options.pdfPageSize.heightPt
                        }

                    val pageInfo =
                        PdfDocument.PageInfo
                            .Builder(
                                pageWidth.roundToInt().coerceAtLeast(1),
                                pageHeight.roundToInt().coerceAtLeast(1),
                                index + 1,
                            ).apply {
                                setContentRect(
                                    Rect(
                                        0,
                                        0,
                                        pageWidth.roundToInt().coerceAtLeast(1),
                                        pageHeight.roundToInt().coerceAtLeast(1),
                                    ),
                                )
                            }.create()

                    val page = document.startPage(pageInfo)
                    val target = RectF(0f, 0f, pageWidth, pageHeight)
                    val source = Rect(0, 0, bitmap.width, bitmap.height)
                    page.canvas.drawBitmap(bitmap, source, target, Paint(Paint.FILTER_BITMAP_FLAG))
                    document.finishPage(page)
                    bitmap.recycle()
                }
            } finally {
                // No-op guard so a failure mid-loop still releases the document.
            }
            val out = ByteArrayOutputStream()
            document.writeTo(out)
            document.close()
            return out.toByteArray()
        }

        private fun buildPsd(
            composite: PixelBuffer,
            layers: List<LayerRaster>,
            options: ExportOptions,
            targetWidth: Int,
            targetHeight: Int,
        ): ByteArray {
            val scaled =
                if (targetWidth == composite.width && targetHeight == composite.height) {
                    composite
                } else {
                    composite.scaled(targetWidth, targetHeight)
                }
            val psdLayers =
                layers.map { entry ->
                    val buffer =
                        if (entry.buffer.width == targetWidth && entry.buffer.height == targetHeight) {
                            entry.buffer
                        } else {
                            entry.buffer.scaled(targetWidth, targetHeight)
                        }
                    PsdCodec.PsdLayer(
                        name = entry.name,
                        pixels = buffer,
                        opacity = (entry.layer.opacity * 255f).roundToInt().coerceIn(0, 255),
                        isVisible = entry.layer.isVisible,
                        blendMode = entry.layer.blendMode,
                    )
                }
            return PsdCodec.write(
                width = targetWidth,
                height = targetHeight,
                layers = psdLayers,
                composite = scaled,
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
                    zip.write(BitmapPixelBridge.toPngBytes(flatten(frame, options)))
                    zip.closeEntry()
                }
            }
            return out.toByteArray()
        }

        /**
         * MP4 via `MediaCodec` + `MediaMuxer` (H.264, one frame per input image).
         *
         * Uses `COLOR_FormatYUV420Flexible` in ByteBuffer input mode, which every API 26+ H.264
         * encoder supports. H.264 requires even dimensions, so odd canvases are padded by one pixel.
         */
        private fun buildMp4(
            frames: List<PixelBuffer>,
            delaysMs: List<Int>,
            options: ExportOptions,
        ): ByteArray {
            val source = frames.first()
            val width = if (source.width % 2 == 1) source.width - 1 else source.width
            val height = if (source.height % 2 == 1) source.height - 1 else source.height
            if (width < 16 || height < 16) {
                throw IllegalStateException("Canvas is too small for video export (minimum 16×16)")
            }

            val tempFile = File.createTempFile("artflow-video", ".mp4", context.cacheDir)
            val writer =
                Mp4Writer(
                    outputPath = tempFile.absolutePath,
                    width = width,
                    height = height,
                    bitrate = options.videoBitrate.coerceIn(500_000, 40_000_000),
                )
            writer.use { encoder ->
                var presentationTimeUs = 0L
                frames.forEachIndexed { index, frame ->
                    val scaled =
                        if (frame.width == width && frame.height == height) {
                            frame
                        } else {
                            frame.scaled(width, height)
                        }
                    encoder.encodeFrame(scaled.pixels, presentationTimeUs)
                    val frameMs = (delaysMs.getOrNull(index) ?: 83).coerceAtLeast(10)
                    presentationTimeUs += frameMs * 1000L
                }
            }

            val bytes = tempFile.readBytes()
            tempFile.delete()
            return bytes
        }

        /**
         * Small wrapper around one H.264 encoder + MP4 muxer pair.
         *
         * The muxer's track index is only known once the encoder reports its output format, so the
         * writer keeps that state instead of threading it through every call site.
         */
        private class Mp4Writer(
            private val outputPath: String,
            private val width: Int,
            private val height: Int,
            private val bitrate: Int,
        ) : AutoCloseable {
            private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            private val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            private val bufferInfo = MediaCodec.BufferInfo()
            private var trackIndex = -1
            private var muxerStarted = false
            private var released = false

            init {
                val format =
                    MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                        setInteger(
                            MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                        )
                        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                        setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    }
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
            }

            fun encodeFrame(
                argb: IntArray,
                presentationTimeUs: Long,
            ) {
                val yuv = argbToNv12(argb, width, height)
                var queued = false
                var attempts = 0
                while (!queued && attempts < 200) {
                    attempts++
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                        inputBuffer.clear()
                        if (inputBuffer.remaining() < yuv.size) {
                            // The encoder buffer is smaller than our frame: let it configure first.
                            drain()
                            codec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, 0)
                            continue
                        }
                        inputBuffer.put(yuv)
                        codec.queueInputBuffer(inputIndex, 0, yuv.size, presentationTimeUs, 0)
                        queued = true
                    } else {
                        drain()
                    }
                }
                drain()
            }

            /** Signals end-of-stream and muxes whatever is left. */
            private fun signalEndOfStream() {
                var attempts = 0
                var queued = false
                while (!queued && attempts < 200) {
                    attempts++
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        queued = true
                    } else {
                        drain()
                    }
                }

                var outputDone = false
                var guard = 0
                while (!outputDone && guard < 1000) {
                    guard++
                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    when {
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> outputDone = true
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> startMuxer()
                        outputIndex >= 0 -> {
                            writeOutput(outputIndex)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                        }
                    }
                }
            }

            private fun drain() {
                while (true) {
                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                    when {
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> startMuxer()
                        outputIndex >= 0 -> writeOutput(outputIndex)
                    }
                }
            }

            private fun startMuxer() {
                if (muxerStarted) return
                trackIndex = muxer.addTrack(codec.outputFormat)
                muxer.start()
                muxerStarted = true
            }

            private fun writeOutput(outputIndex: Int) {
                val outputBuffer = codec.getOutputBuffer(outputIndex)
                val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                if (outputBuffer != null && bufferInfo.size > 0 && !isConfig) {
                    if (!muxerStarted) startMuxer()
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                }
                codec.releaseOutputBuffer(outputIndex, false)
            }

            override fun close() {
                if (released) return
                released = true
                try {
                    signalEndOfStream()
                } catch (e: Exception) {
                    Timber.w(e, "Finishing the video encoder failed")
                }
                runCatching { codec.stop() }
                runCatching { codec.release() }
                if (muxerStarted) runCatching { muxer.stop() }
                runCatching { muxer.release() }
            }

            companion object {
                private const val TIMEOUT_US = 10_000L

                /**
                 * Converts ARGB pixels to NV12 (a Y plane followed by interleaved U/V).
                 * Transparent pixels are composited over white so they do not turn black in the video.
                 */
                fun argbToNv12(
                    pixels: IntArray,
                    width: Int,
                    height: Int,
                ): ByteArray {
                    val out = ByteArray(width * height * 3 / 2)
                    val uvOffset = width * height
                    for (y in 0 until height) {
                        for (x in 0 until width) {
                            val pixel = pixels[y * width + x]
                            val alpha = (pixel ushr 24) and 0xFF
                            var r = (pixel shr 16) and 0xFF
                            var g = (pixel shr 8) and 0xFF
                            var b = pixel and 0xFF
                            if (alpha != 255) {
                                val a = alpha / 255f
                                r = (r * a + 255 * (1 - a)).roundToInt().coerceIn(0, 255)
                                g = (g * a + 255 * (1 - a)).roundToInt().coerceIn(0, 255)
                                b = (b * a + 255 * (1 - a)).roundToInt().coerceIn(0, 255)
                            }
                            val yValue = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                            out[y * width + x] = yValue.coerceIn(0, 255).toByte()
                        }
                    }
                    for (y in 0 until height step 2) {
                        for (x in 0 until width step 2) {
                            var uSum = 0
                            var vSum = 0
                            var samples = 0
                            for (dy in 0 until 2) {
                                for (dx in 0 until 2) {
                                    val px = x + dx
                                    val py = y + dy
                                    if (px >= width || py >= height) continue
                                    val pixel = pixels[py * width + px]
                                    val r = (pixel shr 16) and 0xFF
                                    val g = (pixel shr 8) and 0xFF
                                    val b = pixel and 0xFF
                                    uSum += ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                                    vSum += ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                                    samples++
                                }
                            }
                            if (samples == 0) continue
                            val u = (uSum / samples).coerceIn(0, 255).toByte()
                            val v = (vSum / samples).coerceIn(0, 255).toByte()
                            val index = uvOffset + (y / 2) * width + (x and 1.inv())
                            if (index + 1 < out.size) {
                                out[index] = u
                                out[index + 1] = v
                            }
                        }
                    }
                    return out
                }
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
                out.pixels[i] = BlendModes.sourceOver(options.backgroundColor, source.pixels[i])
            }
            return out
        }

        // -----------------------------------------------------------------------------------------
        // Gallery publishing and sharing
        // -----------------------------------------------------------------------------------------

        /**
         * Copies an exported file into the device gallery under `Pictures/ArtFlow` so it shows up in
         * the user's photo app.
         *
         * @return the MediaStore uri as a string, or null when publishing failed.
         */
        suspend fun publishToGallery(
            result: ExportResult,
            displayName: String,
        ): String? =
            withContext(Dispatchers.IO) {
                try {
                    val values =
                        ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, result.fileName)
                            put(MediaStore.MediaColumns.MIME_TYPE, result.format.mimeType)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ArtFlow")
                                put(MediaStore.MediaColumns.IS_PENDING, 1)
                            }
                        }
                    val resolver = context.contentResolver
                    val collection =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        } else {
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        }
                    val uri = resolver.insert(collection, values) ?: return@withContext null
                    resolver.openOutputStream(uri)?.use { output ->
                        File(result.filePath).inputStream().use { input -> input.copyTo(output) }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    }
                    Timber.d("Published $displayName to the gallery")
                    uri.toString()
                } catch (e: Exception) {
                    Timber.w(e, "Could not publish the export to the gallery")
                    null
                }
            }

        /** Share intent for an exported file, routed through the app's FileProvider. */
        fun shareIntent(
            result: ExportResult,
            chooserTitle: String = "Share artwork",
        ): Intent {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, File(result.filePath))
            val send =
                Intent(Intent.ACTION_SEND).apply {
                    type = result.format.mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, result.fileName)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            return Intent.createChooser(send, chooserTitle)
        }

        /** Open-in-place intent for the exported file (PDF viewers, image apps). */
        fun viewIntent(result: ExportResult): Intent {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, File(result.filePath))
            return Intent(Intent.ACTION_VIEW).apply {
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
