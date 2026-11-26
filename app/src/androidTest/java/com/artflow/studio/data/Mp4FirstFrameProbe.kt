package com.artflow.studio.data

import android.graphics.Color
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import com.artflow.studio.core.lifecycle.withCleanup
import java.io.File
import kotlin.math.pow
import kotlin.math.roundToInt

/** Reads actual decoded YUV without the old platform thumbnail converter's fixed BT.601 matrix. */
internal object Mp4FirstFrameProbe {
    fun colors(
        file: File,
        width: Int,
        height: Int,
        points: List<Pair<Int, Int>>,
    ): List<Int> {
        val extractor = MediaExtractor()
        return withCleanup({ extractor.release() }) {
            extractor.setDataSource(file.absolutePath)
            extractor.selectTrack(0)
            check(extractor.sampleTime == 0L && extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
            val format = extractor.getTrackFormat(0)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            withCleanup({ codec.release() }) {
                codec.configure(format, null, null, 0)
                codec.start()
                withCleanup({ codec.stop() }) {
                    // Feed exactly the first sync sample and explicitly finish input. A decoder
                    // may buffer even one frame until EOS; never require a following dummy frame.
                    val input = codec.dequeueInputBuffer(5_000_000L)
                    check(input >= 0) { "No input buffer for the first MP4 frame" }
                    val buffer = requireNotNull(codec.getInputBuffer(input))
                    val size = extractor.readSampleData(buffer, 0)
                    check(size > 0) { "The first MP4 sample is empty" }
                    codec.queueInputBuffer(input, 0, size, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    drainFirstFrame(codec, width, height, points)
                }
            }
        }
    }

    private fun drainFirstFrame(
        codec: MediaCodec,
        width: Int,
        height: Int,
        points: List<Pair<Int, Int>>,
    ): List<Int> {
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        val info = MediaCodec.BufferInfo()
        var colors: List<Int>? = null
        var ended = false
        while (!ended) {
            check(SystemClock.elapsedRealtime() < deadline) { "The first MP4 frame did not decode and drain" }
            val output = codec.dequeueOutputBuffer(info, 10_000L)
            if (output < 0) continue
            withCleanup({ codec.releaseOutputBuffer(output, false) }) {
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    check(colors == null && info.presentationTimeUs == 0L) { "Unexpected decoded first-frame count or timestamp" }
                    val image = requireNotNull(codec.getOutputImage(output)) { "The decoder did not expose its YUV pixels" }
                    colors = withCleanup({ image.close() }) { sampleColors(image, width, height, points) }
                }
                ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            }
        }
        return requireNotNull(colors) { "The decoder reached EOS without a picture" }
    }

    private fun sampleColors(
        image: Image,
        width: Int,
        height: Int,
        points: List<Pair<Int, Int>>,
    ): List<Int> {
        check(image.format == ImageFormat.YUV_420_888 && image.planes.size == 3)
        val crop = image.cropRect
        check(crop.width() == width && crop.height() == height) { "The decoded MP4 crop does not match the exported dimensions" }
        return points.map { (x, y) ->
            check(x in 0 until width && y in 0 until height)
            val column = x + crop.left
            val row = y + crop.top
            val luma = sample(image.planes[0], column, row)
            val cb = sample(image.planes[1], column / 2, row / 2)
            val cr = sample(image.planes[2], column / 2, row / 2)
            toSrgb(luma, cb, cr)
        }
    }

    private fun sample(
        plane: Image.Plane,
        x: Int,
        y: Int,
    ): Int = plane.buffer.get(plane.buffer.position() + y * plane.rowStride + x * plane.pixelStride).toInt() and 255

    // Independent inverse of ITU-R BT.709-6 sections 1 and 3, followed by the sRGB transfer.
    // Do not call production colour helpers here: these values verify their encoded output.
    private fun toSrgb(
        luma: Int,
        cb: Int,
        cr: Int,
    ): Int {
        val y = (luma - 16) / 219.0
        val red = y + 1.5748 * (cr - 128) / 224.0
        val blue = y + 1.8556 * (cb - 128) / 224.0
        val green = (y - 0.2126 * red - 0.0722 * blue) / 0.7152

        fun srgbChannel(signal: Double): Int {
            val bounded = signal.coerceIn(0.0, 1.0)
            val linear = if (bounded < 0.081) bounded / 4.5 else ((bounded + 0.099) / 1.099).pow(1 / 0.45)
            val srgb = if (linear <= 0.0031308) 12.92 * linear else 1.055 * linear.pow(1 / 2.4) - 0.055
            return (srgb * 255).roundToInt().coerceIn(0, 255)
        }
        return Color.rgb(srgbChannel(red), srgbChannel(green), srgbChannel(blue))
    }
}
