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
import com.artflow.studio.core.lifecycle.withCleanup
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
        require(frames.all { it.width <= width && it.height <= height }) { "Video frame sizes do not match" }
        encode(output, width, height, frames.size, delaysMs, options) { frames[it] }
    }

    /** True when this device has an H.264 encoder for frames of about this size. */
    fun supports(
        width: Int,
        height: Int,
    ): Boolean {
        val format =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, (width + 1) and -2, (height + 1) and -2).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            }
        return runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) != null }.getOrDefault(false)
    }

    /**
     * Streams [frameCount] frames from [frameAt] so long recordings (such as time-lapse replays)
     * never hold every decoded frame in memory at once.
     */
    suspend fun encode(
        output: File,
        width: Int,
        height: Int,
        frameCount: Int,
        delaysMs: List<Int>,
        options: ExportOptions,
        frameAt: suspend (Int) -> PixelBuffer,
    ) {
        require(frameCount > 0) { "There are no frames to encode" }
        require(width >= 16 && height >= 16 && width % 2 == 0 && height % 2 == 0) { "Video export needs even sizes of at least 16 pixels" }
        val fps = options.animationFpsOverride?.coerceIn(1, 60) ?: 30
        val format =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                describeEncodedColour(this)
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
        withCleanup({ codec.release() }) {
            val muxer = MediaMuxerCompat(output.absolutePath, MediaMuxerCompat.OUTPUT_FORMAT_MP4)
            // Release can finalize the muxer and fail; keep the encoding failure or cancellation primary.
            withCleanup({ muxer.release() }) {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                val session = EncodingSession(codec, muxer)
                var presentationUs = 0L
                repeat(frameCount) { index ->
                    currentCoroutineContext().ensureActive()
                    val frame = frameAt(index)
                    require(frame.width <= width && frame.height <= height) { "Video frame sizes do not match" }
                    val inputIndex = session.inputIndex()
                    val image = requireNotNull(codec.getInputImage(inputIndex)) { "The encoder did not provide a writable YUV image" }
                    // Prepared pixels and the matte must use the same output colour profile.
                    fillImage(image, frame, width, height, options.outputBackground)
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
                session.finish(presentationUs, frameCount)
                codec.stop()
            }
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
                        // Some codecs omit or replace requested color aspects. The muxer must
                        // describe the YUV samples we supplied while retaining codec-specific data.
                        val encodedFormat = codec.outputFormat.apply { describeEncodedColour(this) }
                        track = muxer.addTrack(encodedFormat)
                        muxer.start()
                    }
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (index >= 0) writeOutput(index)
                }
            }
        }

        private fun writeOutput(index: Int) {
            withCleanup({ codec.releaseOutputBuffer(index, false) }) {
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

    private fun describeEncodedColour(format: MediaFormat) {
        // fillImage converts sRGB to BT.709's transfer curve before its limited-range matrix.
        // Repeat these aspects on the output: older OMX codecs can omit them from outputFormat.
        format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
        format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
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
        val videoMatte = Bt709VideoColor.fromSrgb(matte)
        val pixels = IntArray(width * height) { videoMatte }
        for (y in 0 until frame.height) {
            currentCoroutineContext().ensureActive()
            for (x in 0 until frame.width) {
                val opaqueSrgb = BlendModes.sourceOver(matte, frame.pixels[y * frame.width + x])
                pixels[y * width + x] = Bt709VideoColor.fromSrgb(opaqueSrgb)
            }
        }
        val luma = PlaneWriter(image.planes[0])
        val u = PlaneWriter(image.planes[1])
        val v = PlaneWriter(image.planes[2])
        for (y in 0 until height) {
            currentCoroutineContext().ensureActive()
            for (x in 0 until width) {
                luma.put(x, y, Bt709VideoColor.luma(pixels[y * width + x]))
            }
        }
        for (y in 0 until height step 2) {
            currentCoroutineContext().ensureActive()
            for (x in 0 until width step 2) {
                val rgb = Bt709VideoColor.average2x2(pixels, width, x, y)
                u.put(x / 2, y / 2, Bt709VideoColor.chromaBlue(rgb))
                v.put(x / 2, y / 2, Bt709VideoColor.chromaRed(rgb))
            }
        }
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
