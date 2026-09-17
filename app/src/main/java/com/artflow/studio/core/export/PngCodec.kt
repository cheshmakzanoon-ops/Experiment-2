package com.artflow.studio.core.export

import com.artflow.studio.core.pixels.PixelBuffer
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** Deterministic RGBA PNG output without a premultiplied Bitmap round-trip. */
object PngCodec {
    fun encode(
        buffer: PixelBuffer,
        dpi: Int? = null,
    ): ByteArray {
        require(dpi == null || dpi in 1..32_767) { "Invalid PNG resolution" }
        val bytes = ByteArrayOutputStream()
        val output = DataOutputStream(bytes)
        output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        val header = ByteArrayOutputStream(13)
        DataOutputStream(header).use {
            it.writeInt(buffer.width)
            it.writeInt(buffer.height)
            it.write(byteArrayOf(8, 6, 0, 0, 0))
        }
        chunk(output, "IHDR", header.toByteArray())
        if (dpi != null) {
            val density = ByteArrayOutputStream(9)
            DataOutputStream(density).use {
                val pixelsPerMetre = kotlin.math.round(dpi / 0.0254).toInt()
                it.writeInt(pixelsPerMetre)
                it.writeInt(pixelsPerMetre)
                it.writeByte(1)
            }
            chunk(output, "pHYs", density.toByteArray())
        }
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        try {
            DeflaterOutputStream(IdatStream(output), deflater).use { compressed ->
                writeRows(buffer, compressed)
            }
        } finally {
            deflater.end()
        }
        chunk(output, "IEND", byteArrayOf())
        return bytes.toByteArray()
    }

    private fun writeRows(
        buffer: PixelBuffer,
        compressed: OutputStream,
    ) {
        val row = ByteArray(buffer.width * 4 + 1)
        for (y in 0 until buffer.height) {
            row[0] = 1 // PNG Sub filter; improves flat-color and brush-stroke compression.
            for (x in 0 until buffer.width) {
                val pixel = buffer.pixels[y * buffer.width + x]
                val index = x * 4 + 1
                row[index] = (pixel ushr 16).toByte()
                row[index + 1] = (pixel ushr 8).toByte()
                row[index + 2] = pixel.toByte()
                row[index + 3] = (pixel ushr 24).toByte()
            }
            for (index in row.lastIndex downTo 5) row[index] = (row[index] - row[index - 4]).toByte()
            compressed.write(row)
        }
    }

    private fun chunk(
        output: DataOutputStream,
        type: String,
        data: ByteArray,
        length: Int = data.size,
    ) {
        val name = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32()
        crc.update(name)
        crc.update(data, 0, length)
        output.writeInt(length)
        output.write(name)
        output.write(data, 0, length)
        output.writeInt(crc.value.toInt())
    }

    /** Bounded IDAT chunks avoid a second full compressed-image allocation. */
    private class IdatStream(
        private val output: DataOutputStream,
    ) : OutputStream() {
        private val buffer = ByteArray(32 * 1024)
        private var count = 0

        override fun write(value: Int) {
            buffer[count++] = value.toByte()
            if (count == buffer.size) flush()
        }

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            var position = offset
            var remaining = length
            while (remaining > 0) {
                val copied = minOf(remaining, buffer.size - count)
                bytes.copyInto(buffer, count, position, position + copied)
                count += copied
                position += copied
                remaining -= copied
                if (count == buffer.size) flush()
            }
        }

        override fun flush() {
            if (count > 0) chunk(output, "IDAT", buffer, count)
            count = 0
        }

        override fun close() = flush()
    }
}
