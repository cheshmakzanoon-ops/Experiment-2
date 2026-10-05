package com.artflow.studio.core.export

import com.artflow.studio.core.pixels.PixelBuffer
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.Inflater

/** A small PNG decoder for tests (Android unit tests have no javax.imageio): 8-bit, not interlaced. */
internal object TestPng {
    fun decode(bytes: ByteArray): PixelBuffer? {
        val data = ByteBuffer.wrap(bytes)
        if (bytes.size < SIGNATURE) return null
        data.position(SIGNATURE)
        var width = 0
        var height = 0
        var channels = 0
        val compressed = ByteArrayOutputStream()
        while (data.remaining() >= CHUNK_OVERHEAD) {
            val length = data.int
            val kind = String(ByteArray(4).also { data.get(it) }, Charsets.ISO_8859_1)
            if (length < 0 || length > data.remaining() - 4) return null
            val body = ByteArray(length).also { data.get(it) }
            data.int
            when (kind) {
                "IHDR" -> {
                    val header = ByteBuffer.wrap(body)
                    width = header.int
                    height = header.int
                    val depth = body[8].toInt()
                    channels = CHANNELS[body[9].toInt()] ?: return null
                    if (depth != 8 || body[12].toInt() != 0) return null
                }
                "IDAT" -> compressed.write(body)
            }
        }
        if (width <= 0 || height <= 0 || channels == 0) return null
        val stride = width * channels
        val raw = ByteArray(height * (stride + 1))
        Inflater().apply { setInput(compressed.toByteArray()) }.inflate(raw)
        val rows = unfilter(raw, height, stride, channels)
        return PixelBuffer(width, height).also { image ->
            for (y in 0 until height) {
                for (x in 0 until width) image.pixels[y * width + x] = pixel(rows, y * stride + x * channels, channels)
            }
        }
    }

    private fun unfilter(
        raw: ByteArray,
        height: Int,
        stride: Int,
        bpp: Int,
    ): ByteArray {
        val out = ByteArray(height * stride)
        for (y in 0 until height) {
            val filter = raw[y * (stride + 1)].toInt()
            for (i in 0 until stride) {
                val value = raw[y * (stride + 1) + 1 + i].toInt() and 0xFF
                val left = if (i >= bpp) out[y * stride + i - bpp].toInt() and 0xFF else 0
                val up = if (y > 0) out[(y - 1) * stride + i].toInt() and 0xFF else 0
                val upLeft = if (y > 0 && i >= bpp) out[(y - 1) * stride + i - bpp].toInt() and 0xFF else 0
                val predicted =
                    when (filter) {
                        1 -> left
                        2 -> up
                        3 -> (left + up) / 2
                        4 -> paeth(left, up, upLeft)
                        else -> 0
                    }
                out[y * stride + i] = (value + predicted).toByte()
            }
        }
        return out
    }

    private fun paeth(
        a: Int,
        b: Int,
        c: Int,
    ): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return if (pa <= pb && pa <= pc) {
            a
        } else if (pb <= pc) {
            b
        } else {
            c
        }
    }

    private fun pixel(
        rows: ByteArray,
        at: Int,
        channels: Int,
    ): Int {
        fun byte(k: Int) = rows[at + k].toInt() and 0xFF
        return when (channels) {
            1 -> (0xFF shl 24) or (byte(0) * 0x010101)
            2 -> (byte(1) shl 24) or (byte(0) * 0x010101)
            3 -> (0xFF shl 24) or (byte(0) shl 16) or (byte(1) shl 8) or byte(2)
            else -> (byte(3) shl 24) or (byte(0) shl 16) or (byte(1) shl 8) or byte(2)
        }
    }

    private const val SIGNATURE = 8
    private const val CHUNK_OVERHEAD = 12
    private val CHANNELS = mapOf(0 to 1, 2 to 3, 4 to 2, 6 to 4)
}
