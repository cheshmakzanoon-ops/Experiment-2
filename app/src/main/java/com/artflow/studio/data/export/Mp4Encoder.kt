package com.artflow.studio.data.export

import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.SystemClock
import androidx.media3.muxer.MediaMuxerCompat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.pixels.BlendModes
import com.artflow.studio.core.pixels.PixelBuffer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer

/** H.264 export with device-declared YUV plane layouts and checked, bounded codec progress. */
internal object Mp4Encoder {
    suspend fun encode(
        output: File,
        frames: List<PixelBuffer>,
        delaysMs: List<Int>,
        options: ExportOptions,
    ) {
        require(frames.isNotEmpty()) { "There are no frames to encode" }
        val width = (frames.first().width + 1) and -2
        val height = (frames.first().height + 1) and -2
        require(width >= 16 && height >= 16) { "Video export needs at least 16 by 16 pixels" }
        require(frames.all { it.width <= width && it.height <= height }) { "Video frame sizes do not match" }
        val fps = options.animationFpsOverride?.coerceIn(1, 60) ?: 30
        val format =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_BIT_RATE, options.videoBitrate.coerceIn(500_000, 40_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
        val name =
            requireNotNull(MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)) {
                "This device has no H.264 encoder for $width by $height pixels; try a smaller export"
            }
        // OMX encoders on older Android require an explicit level whenever profile is set.
        // Use an advertised Baseline level rather than an arbitrary constant for every device.
        val capabilities =
            MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .codecInfos
                .first { it.name == name }
                .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val level =
            capabilities.profileLevels
                .filter { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline }
                .maxOfOrNull { it.level }
                ?: throw UnsupportedOperationException("This device does not advertise an H.264 Baseline level")
        format.setInteger(MediaFormat.KEY_LEVEL, level)
        val codec = MediaCodec.createByCodecName(name)
        try {
            val muxer = MediaMuxerCompat(output.absolutePath, MediaMuxerCompat.OUTPUT_FORMAT_MP4)
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                val session = EncodingSession(codec, muxer)
                var presentationUs = 0L
                frames.forEachIndexed { index, frame ->
                    currentCoroutineContext().ensureActive()
                    val inputIndex = session.inputIndex()
                    val image = requireNotNull(codec.getInputImage(inputIndex)) { "The encoder did not provide a writable YUV image" }
                    fillImage(image, frame, width, height, options.backgroundColor)
                    // The codec owns the Image again after queueInputBuffer; never retain its planes.
                    codec.queueInputBuffer(inputIndex, 0, width * height * 3 / 2, presentationUs, 0)
                    val durationUs =
                        options.animationFpsOverride?.let { 1_000_000L / it.coerceIn(1, 60) }
                            ?: ((delaysMs.getOrNull(index) ?: 83).coerceIn(10, 60_000) * 1000L)
                    presentationUs += durationUs
                    session.drain(waitForEnd = false)
                }
                val endIndex = session.inputIndex()
                codec.queueInputBuffer(endIndex, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                session.drain(waitForEnd = true)
                session.finish(presentationUs, frames.size)
                codec.stop()
            } finally {
                muxer.release()
            }
        } finally {
            codec.release()
        }
        check(output.length() > 0L) { "The encoder produced an empty video" }
    }

    private class EncodingSession(
        private val codec: MediaCodec,
        private val muxer: MediaMuxerCompat,
    ) {
        private val info = MediaCodec.BufferInfo()
        private var track = -1
        private var outputEnded = false
        private var samplesWritten = 0
        private var previousPts = -1L

        suspend fun inputIndex(): Int {
            val deadline = SystemClock.elapsedRealtime() + PROGRESS_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                currentCoroutineContext().ensureActive()
                val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (index >= 0) return index
                drain(waitForEnd = false)
            }
            error("The video encoder timed out waiting for an input buffer; no video was published")
        }

        suspend fun drain(waitForEnd: Boolean) {
            val deadline = SystemClock.elapsedRealtime() + PROGRESS_TIMEOUT_MS
            while (!outputEnded) {
                currentCoroutineContext().ensureActive()
                check(SystemClock.elapsedRealtime() < deadline) { "The video encoder did not finish its output" }
                when (val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> if (!waitForEnd) return
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(track == -1) { "The encoder changed format after video writing started" }
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (index >= 0) writeOutput(index)
                }
            }
        }

        private fun writeOutput(index: Int) {
            try {
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (info.size > 0 && !config) {
                    check(track >= 0) { "The encoder produced samples without a video track" }
                    check(info.presentationTimeUs > previousPts) { "The encoder returned out-of-order frames" }
                    val buffer = requireNotNull(codec.getOutputBuffer(index)) { "The encoded frame is unavailable" }
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    info.flags = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
                    muxer.writeSampleData(track, buffer, info)
                    previousPts = info.presentationTimeUs
                    samplesWritten++
                }
                outputEnded = eos
            } finally {
                codec.releaseOutputBuffer(index, false)
            }
        }

        fun finish(
            endUs: Long,
            expectedFrames: Int,
        ) {
            check(outputEnded && track >= 0 && samplesWritten == expectedFrames) {
                "The video encoder did not preserve every frame ($samplesWritten of $expectedFrames)"
            }
            check(endUs > previousPts) { "Invalid video duration" }
            // Portable muxing honors the final duration even on Android 8, whose platform muxer ignores this EOS.
            val end = MediaCodec.BufferInfo().apply { set(0, 0, endUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM) }
            muxer.writeSampleData(track, ByteBuffer.allocate(0), end)
            muxer.stop()
        }
    }

    /** Image.Plane declares both strides; flexible YUV is not necessarily NV12 or tightly packed. */
    private suspend fun fillImage(
        image: Image,
        frame: PixelBuffer,
        width: Int,
        height: Int,
        background: Int,
    ) {
        check(image.format == ImageFormat.YUV_420_888 && image.planes.size == 3) { "Unsupported encoder input image" }
        check(image.width >= width && image.height >= height) { "The encoder input is smaller than the artwork" }
        val matte = background or (0xFF shl 24)
        val pixels = IntArray(width * height) { matte }
        for (y in 0 until frame.height) {
            currentCoroutineContext().ensureActive()
            for (x in 0 until frame.width) pixels[y * width + x] = BlendModes.sourceOver(matte, frame.pixels[y * frame.width + x])
        }
        val luma = PlaneWriter(image.planes[0])
        val u = PlaneWriter(image.planes[1])
        val v = PlaneWriter(image.planes[2])
        for (y in 0 until height) {
            currentCoroutineContext().ensureActive()
            for (x in 0 until width) {
                val color = pixels[y * width + x]
                val red = color shr 16 and 255
                val green = color shr 8 and 255
                val blue = color and 255
                luma.put(x, y, ((66 * red + 129 * green + 25 * blue + 128) shr 8) + 16)
            }
        }
        for (y in 0 until height step 2) {
            currentCoroutineContext().ensureActive()
            for (x in 0 until width step 2) {
                val rgb = averagedRgb(pixels, width, x, y)
                val red = rgb shr 16 and 255
                val green = rgb shr 8 and 255
                val blue = rgb and 255
                u.put(x / 2, y / 2, ((-38 * red - 74 * green + 112 * blue + 128) shr 8) + 128)
                v.put(x / 2, y / 2, ((112 * red - 94 * green - 18 * blue + 128) shr 8) + 128)
            }
        }
    }

    private fun averagedRgb(
        pixels: IntArray,
        width: Int,
        x: Int,
        y: Int,
    ): Int {
        var red = 0
        var green = 0
        var blue = 0
        for (dy in 0..1) {
            for (dx in 0..1) {
                val color = pixels[(y + dy) * width + x + dx]
                red += color shr 16 and 255
                green += color shr 8 and 255
                blue += color and 255
            }
        }
        return ((red / 4) shl 16) or ((green / 4) shl 8) or (blue / 4)
    }

    private class PlaneWriter(
        plane: Image.Plane,
    ) {
        private val buffer = plane.buffer
        private val start = buffer.position()
        private val rowStride = plane.rowStride
        private val pixelStride = plane.pixelStride

        fun put(
            x: Int,
            y: Int,
            value: Int,
        ) {
            buffer.put(start + y * rowStride + x * pixelStride, value.coerceIn(0, 255).toByte())
        }
    }

    private const val DEQUEUE_TIMEOUT_US = 10_000L
    private const val PROGRESS_TIMEOUT_MS = 15_000L
}
