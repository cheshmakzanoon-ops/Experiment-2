package com.artflow.studio.data.local

import com.artflow.studio.data.export.ArtflowPackage
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Holds the acquired model/preview inodes while their path names may be atomically replaced.
 * Each opened reader has its own cursor; closing a reader does not release the owning scope.
 * This protects cooperative atomic writers, not an external writer mutating an open inode.
 */
internal class PackageAssetSnapshots : Closeable {
    private val inputs = ArrayList<FileInputStream>(3)
    private var closed = false

    fun capture(
        path: String,
        file: File,
        maxBytes: Long,
    ): ArtflowPackage.EntrySource {
        check(!closed) { "The package snapshot is closed" }
        val input = FileInputStream(file)
        inputs.add(input)
        val channel = input.channel
        val length = channel.size()
        require(length in 1..maxBytes) { "Missing or invalid artwork asset: $path" }
        return ArtflowPackage.EntrySource(path) { SnapshotInputStream(channel, length) }
    }

    override fun close() {
        closed = true
        var failure: IOException? = null
        inputs.forEach { input ->
            try {
                input.close()
            } catch (error: IOException) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        inputs.clear()
        failure?.let { throw it }
    }

    private class SnapshotInputStream(
        private val channel: FileChannel,
        private val length: Long,
    ) : InputStream() {
        private var position = 0L
        private var closed = false
        private val single = ByteArray(1)

        override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xFF

        override fun read(
            buffer: ByteArray,
            offset: Int,
            count: Int,
        ): Int {
            ensureOpen()
            if (offset < 0 || count < 0 || count > buffer.size - offset) {
                throw IndexOutOfBoundsException("Invalid package asset read range")
            }
            if (count == 0) return 0
            if (position >= length) return -1
            val requested = minOf(count.toLong(), length - position).toInt()
            val actual = channel.read(ByteBuffer.wrap(buffer, offset, requested), position)
            if (actual < 0) throw EOFException("A captured artwork asset was truncated")
            position += actual
            return actual
        }

        override fun skip(count: Long): Long {
            ensureOpen()
            val skipped = count.coerceIn(0L, length - position)
            position += skipped
            return skipped
        }

        override fun available(): Int {
            ensureOpen()
            return minOf(Int.MAX_VALUE.toLong(), length - position).toInt()
        }

        override fun close() {
            closed = true
        }

        private fun ensureOpen() {
            if (closed || !channel.isOpen) throw IOException("The package asset snapshot is closed")
        }
    }
}
