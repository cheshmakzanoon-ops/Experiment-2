// Harness shim of androidx.media3.muxer.MediaMuxerCompat (media3-muxer 1.10.x): same surface as android.media.MediaMuxer.
@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.media3.muxer

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer

class MediaMuxerCompat(
    path: String,
    format: Int,
) {
    fun addTrack(format: MediaFormat): Int = throw UnsupportedOperationException("harness stub")

    fun start() {}

    fun stop() {}

    fun release() {}

    fun writeSampleData(
        trackIndex: Int,
        byteBuf: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
    ) {}

    fun setOrientationHint(degrees: Int) {}

    fun setLocation(
        latitude: Float,
        longitude: Float,
    ) {}

    companion object {
        const val OUTPUT_FORMAT_MP4: Int = 0
    }
}
