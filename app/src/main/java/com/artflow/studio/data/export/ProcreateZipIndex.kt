package com.artflow.studio.data.export

import com.artflow.studio.core.export.ProcreateReadBudget
import java.io.File
import java.io.RandomAccessFile

/** Reads only fixed-size ZIP headers before ZipFile can allocate the central directory. */
internal object ProcreateZipIndex {
    private const val DAMAGED = "The Procreate archive index is damaged"
    private const val END_SIGNATURE = 0x06054b50L
    private const val DIRECTORY_SIGNATURE = 0x02014b50L
    private const val LOCAL_SIGNATURE = 0x04034b50L
    private const val ZIP64_END_SIGNATURE = 0x06064b50L
    private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50L
    private const val END_BYTES = 22
    private const val MAX_COMMENT_BYTES = 65_535
    private const val DIRECTORY_HEADER_BYTES = 46
    private const val ZIP64_LOCATOR_BYTES = 20
    private const val ZIP64_END_BYTES = 56

    fun check(
        file: File,
        budget: ProcreateReadBudget,
        maximumEntries: Int,
    ) {
        budget.ensureActive()
        RandomAccessFile(file, "r").use { input ->
            val directory = directory(input)
            require(directory.entries in 0..maximumEntries.toLong()) { "This Procreate document has too many entries" }
            budget.reserveArchiveIndex(directory.bytes, directory.entries.toInt())
            checkEntries(input, directory, budget)
        }
    }

    private data class Directory(
        val offset: Long,
        val bytes: Long,
        val entries: Long,
    )

    private fun directory(input: RandomAccessFile): Directory {
        require(input.length() >= END_BYTES) { DAMAGED }
        val tailOffset = maxOf(0L, input.length() - END_BYTES - MAX_COMMENT_BYTES)
        val tail = ByteArray((input.length() - tailOffset).toInt())
        input.seek(tailOffset)
        input.readFully(tail)
        val end = findFooter(input, tail, tailOffset)
        require(tail.unsigned(end + 4, 2) == 0L && tail.unsigned(end + 6, 2) == 0L) { DAMAGED }
        val entries = tail.unsigned(end + 10, 2)
        val bytes = tail.unsigned(end + 12, 4)
        val offset = tail.unsigned(end + 16, 4)
        require(tail.unsigned(end + 8, 2) == entries) { DAMAGED }
        val endOffset = tailOffset + end
        val locator = ByteArray(ZIP64_LOCATOR_BYTES)
        if (endOffset >= ZIP64_LOCATOR_BYTES) {
            input.seek(endOffset - ZIP64_LOCATOR_BYTES)
            input.readFully(locator)
        }
        if (locator.unsigned(0, 4) == ZIP64_LOCATOR_SIGNATURE) {
            return zip64Directory(input, locator, endOffset, Directory(offset, bytes, entries))
        }
        require(entries < 0xffffL && bytes < 0xffffffffL && offset < 0xffffffffL) { DAMAGED }
        return locatedDirectory(offset, bytes, entries, endOffset)
    }

    /** Follow ZipFile's footer selection, including an otherwise valid ZIP with trailing padding. */
    private fun findFooter(
        input: RandomAccessFile,
        tail: ByteArray,
        tailOffset: Long,
    ): Int {
        for (at in tail.size - END_BYTES downTo 0) {
            if (tail.unsigned(at, 4) == END_SIGNATURE && footerMatches(input, tail, tailOffset, at)) return at
        }
        throw IllegalArgumentException(DAMAGED)
    }

    private fun footerMatches(
        input: RandomAccessFile,
        tail: ByteArray,
        tailOffset: Long,
        at: Int,
    ): Boolean {
        val commentEnd = at + END_BYTES + tail.unsigned(at + 20, 2)
        if (commentEnd == tail.size.toLong()) return true
        val directoryOffset = tailOffset + at - tail.unsigned(at + 12, 4)
        val localOffset = directoryOffset - tail.unsigned(at + 16, 4)
        if (localOffset < 0 || signatureAt(input, directoryOffset) != DIRECTORY_SIGNATURE) return false
        if (signatureAt(input, localOffset) != LOCAL_SIGNATURE) return false
        require(commentEnd <= tail.size) { DAMAGED }
        return true
    }

    private fun signatureAt(
        input: RandomAccessFile,
        offset: Long,
    ): Long {
        input.seek(offset)
        return Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
    }

    private fun zip64Directory(
        input: RandomAccessFile,
        locator: ByteArray,
        endOffset: Long,
        legacy: Directory,
    ): Directory {
        require(locator.unsigned(4, 4) == 0L && locator.unsigned(16, 4) == 1L) { DAMAGED }
        val offset = locator.unsigned(8, 8)
        val locatorOffset = endOffset - ZIP64_LOCATOR_BYTES
        require(offset in 0..(locatorOffset - ZIP64_END_BYTES)) { DAMAGED }
        input.seek(offset)
        val end = ByteArray(ZIP64_END_BYTES)
        input.readFully(end)
        require(end.unsigned(0, 4) == ZIP64_END_SIGNATURE) { DAMAGED }
        val recordBytes = end.unsigned(4, 8)
        require(recordBytes >= 44 && recordBytes == locatorOffset - offset - 12) { DAMAGED }
        require(end.unsigned(16, 4) == 0L && end.unsigned(20, 4) == 0L) { DAMAGED }
        val entries = end.unsigned(32, 8)
        val bytes = end.unsigned(40, 8)
        val directoryOffset = end.unsigned(48, 8)
        require(end.unsigned(24, 8) == entries) { DAMAGED }
        // ZipFile falls back to the legacy footer on conflicts; both views must agree here.
        require(legacy.entries == 0xffffL || legacy.entries == entries) { DAMAGED }
        require(legacy.bytes == 0xffffffffL || legacy.bytes == bytes) { DAMAGED }
        require(legacy.offset == 0xffffffffL || legacy.offset == directoryOffset) { DAMAGED }
        return locatedDirectory(directoryOffset, bytes, entries, offset)
    }

    private fun locatedDirectory(
        declaredOffset: Long,
        bytes: Long,
        entries: Long,
        footerOffset: Long,
    ): Directory {
        require(bytes in 0..footerOffset && entries >= 0) { DAMAGED }
        // ZipFile locates the directory from its footer, allowing a harmless prepended prefix.
        val actualOffset = footerOffset - bytes
        require(declaredOffset in 0..actualOffset) { DAMAGED }
        return Directory(actualOffset, bytes, entries)
    }

    private fun checkEntries(
        input: RandomAccessFile,
        directory: Directory,
        budget: ProcreateReadBudget,
    ) {
        val header = ByteArray(DIRECTORY_HEADER_BYTES)
        var remaining = directory.bytes
        var entries = 0L
        input.seek(directory.offset)
        while (remaining > 0) {
            budget.ensureActive()
            require(remaining >= DIRECTORY_HEADER_BYTES) { DAMAGED }
            input.readFully(header)
            require(header.unsigned(0, 4) == DIRECTORY_SIGNATURE) { DAMAGED }
            val variableBytes = header.unsigned(28, 2) + header.unsigned(30, 2) + header.unsigned(32, 2)
            require(variableBytes <= remaining - DIRECTORY_HEADER_BYTES) { DAMAGED }
            entries++
            require(entries <= directory.entries) { DAMAGED }
            input.seek(input.filePointer + variableBytes)
            remaining -= DIRECTORY_HEADER_BYTES + variableBytes
        }
        require(entries == directory.entries) { DAMAGED }
        budget.ensureActive()
    }

    private fun ByteArray.unsigned(
        offset: Int,
        length: Int,
    ): Long {
        var result = 0L
        for (index in 0 until length) result = result or ((this[offset + index].toLong() and 0xff) shl (index * 8))
        require(result >= 0) { DAMAGED }
        return result
    }
}
