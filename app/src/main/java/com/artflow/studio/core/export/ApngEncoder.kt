package com.artflow.studio.core.export

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.CRC32

/**
 * Animated PNG from ordinary PNG frames of one size: the first frame's image data is the default
 * image, later frames become fdAT chunks, and each frame keeps its own delay.
 */
object ApngEncoder {
    private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    private class Chunk(
        val type: String,
        val data: ByteArray,
    )

    /** [frames] are complete PNG files; [delaysMs] holds one delay per frame; [loops] 0 repeats forever. */
    fun encode(
        frames: List<ByteArray>,
        delaysMs: List<Int>,
        loops: Int = 0,
    ): ByteArray {
        require(frames.isNotEmpty() && frames.size == delaysMs.size) { "Each frame needs a delay" }
        val parsed = frames.map(::chunks)
        val header = parsed.first().first { it.type == "IHDR" }
        val size = header.data.copyOfRange(0, 8)
        require(parsed.all { frame -> sizeOf(frame).contentEquals(size) }) { "Animated PNG frames must share one size" }
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        val data = DataOutputStream(out)
        write(data, "IHDR", header.data)
        write(data, "acTL", ints(frames.size, loops))
        var sequence = 0
        parsed.forEachIndexed { index, frame ->
            write(data, "fcTL", frameControl(sequence++, header.data, delaysMs[index]))
            frame.filter { it.type == "IDAT" }.forEach { image ->
                if (index == 0) {
                    write(data, "IDAT", image.data)
                } else {
                    write(data, "fdAT", ints(sequence++) + image.data)
                }
            }
        }
        write(data, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun sizeOf(frame: List<Chunk>): ByteArray = frame.first { it.type == "IHDR" }.data.copyOfRange(0, 8)

    private fun chunks(png: ByteArray): List<Chunk> {
        require(png.size > SIGNATURE.size && png.copyOfRange(0, SIGNATURE.size).contentEquals(SIGNATURE)) { "Not a PNG frame" }
        val input = DataInputStream(png.inputStream(SIGNATURE.size, png.size - SIGNATURE.size))
        val result = mutableListOf<Chunk>()
        while (input.available() >= CHUNK_OVERHEAD) {
            val length = input.readInt()
            val type = ByteArray(4).also { input.readFully(it) }.decodeToString()
            val body = ByteArray(length).also { input.readFully(it) }
            input.readInt() // CRC, recomputed on output
            result += Chunk(type, body)
            if (type == "IEND") break
        }
        return result
    }

    /** Full-canvas frame control: replaces the previous frame, shown for [delayMs]. */
    private fun frameControl(
        sequence: Int,
        header: ByteArray,
        delayMs: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeInt(sequence)
        data.write(header, 0, 8) // width and height
        data.writeInt(0) // x offset
        data.writeInt(0) // y offset
        data.writeShort(delayMs.coerceIn(1, Short.MAX_VALUE.toInt()))
        data.writeShort(MILLISECONDS)
        data.writeByte(0) // dispose: none
        data.writeByte(0) // blend: source replaces the frame
        return out.toByteArray()
    }

    private fun ints(vararg values: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        values.forEach(data::writeInt)
        return out.toByteArray()
    }

    private fun write(
        out: DataOutputStream,
        type: String,
        body: ByteArray,
    ) {
        val name = type.encodeToByteArray()
        out.writeInt(body.size)
        out.write(name)
        out.write(body)
        val crc = CRC32()
        crc.update(name)
        crc.update(body)
        out.writeInt(crc.value.toInt())
    }

    private const val CHUNK_OVERHEAD = 12
    private const val MILLISECONDS = 1000
}
