package com.artflow.studio.core.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class BinaryPlistBoundaryTest {
    @Test
    fun fixedWidthScalarsCannotConsumeTheOffsetTableOrTrailer() {
        val markers = listOf(0x10, 0x11, 0x12, 0x13, 0x14, 0x22, 0x23, 0x33, 0x80, 0x81, 0x82)
        for (marker in markers) {
            val bytes = binaryPlist(byteArrayOf(marker.toByte()))
            assertThrows("marker=$marker", IllegalArgumentException::class.java) { BinaryPlist.read(bytes) }
        }
    }

    @Test
    fun dataAndStringsCannotUseTheOffsetTableAsMissingPayload() {
        for (marker in listOf(0x41, 0x51, 0x61)) {
            val bytes = binaryPlist(byteArrayOf(marker.toByte()))
            assertThrows("marker=$marker", IllegalArgumentException::class.java) { BinaryPlist.read(bytes) }
        }
    }

    @Test
    fun containerReferencesMustEndBeforeTheOffsetTable() {
        for (marker in listOf(0xA1, 0xC1, 0xD1)) {
            // The first offset starts with zero bytes. Previously these became references to
            // object zero, producing ["key"] or {"key": "key"} without any reference payload.
            val bytes = binaryPlist(ascii("key"), byteArrayOf(marker.toByte()), top = 1)
            assertThrows("marker=$marker", IllegalArgumentException::class.java) { BinaryPlist.read(bytes) }
        }
    }

    @Test
    fun extendedLengthIntegersCannotConsumeTheOffsetTable() {
        val records =
            listOf(
                byteArrayOf(0x4F),
                byteArrayOf(0x4F, 0x10),
                byteArrayOf(0x4F, 0x14) + ByteArray(15),
            )
        for (record in records) {
            assertThrows(IllegalArgumentException::class.java) { BinaryPlist.read(binaryPlist(record)) }
        }
    }

    @Test
    fun anExtendedLengthRequiresAnIntegerMarker() {
        for (marker in listOf(0x00, 0x50, 0x80, 0xA0, 0xD0)) {
            val bytes = binaryPlist(byteArrayOf(0x4F, marker.toByte(), 1, 0x7B))
            assertThrows("marker=$marker", IllegalArgumentException::class.java) { BinaryPlist.read(bytes) }
        }
    }

    @Test
    fun wideLengthsCannotDiscardTheirHighBytes() {
        val length = ByteArray(16).apply { this[0] = 1 }
        for (marker in listOf(0x4F, 0x5F, 0x6F, 0xAF, 0xCF, 0xDF)) {
            // A length of 2^120 must not become an empty data/string/container value.
            val bytes = binaryPlist(byteArrayOf(marker.toByte(), 0x14) + length)
            assertThrows("marker=$marker", IllegalArgumentException::class.java) { BinaryPlist.read(bytes) }
        }
    }

    @Test
    fun wideUidsCannotAliasAnIndexFromTheirLowBytes() {
        for (size in 9..16) {
            val bytes = binaryPlist(uid(size, nonzeroHighByte = true))
            assertThrows("size=$size", IllegalArgumentException::class.java) { BinaryPlist.read(bytes) }
        }
    }

    @Test
    fun zeroPaddedLengthsAndUidsRemainReadable() {
        val length = ByteArray(16).apply { this[lastIndex] = 1 }
        val bytes = binaryPlist(byteArrayOf(0x4F, 0x14) + length + byteArrayOf(0x7B))
        assertArrayEquals(byteArrayOf(0x7B), BinaryPlist.read(bytes) as ByteArray)
        for (size in 1..16) {
            assertEquals(BinaryPlist.Uid(1), BinaryPlist.read(binaryPlist(uid(size))))
        }
    }

    @Test
    fun supportedScalarWidthsAndSignedIntegersStillDecode() {
        assertEquals(-42L, BinaryPlist.read(binaryPlist(byteArrayOf(0x13) + ByteBuffer.allocate(8).putLong(-42).array())))
        val wideInteger = byteArrayOf(0x14) + ByteArray(8) + ByteBuffer.allocate(8).putLong(5_000_000_000).array()
        assertEquals(5_000_000_000L, BinaryPlist.read(binaryPlist(wideInteger)))
        assertEquals(0.5, BinaryPlist.read(binaryPlist(byteArrayOf(0x22) + ByteBuffer.allocate(4).putFloat(0.5f).array())))
        assertEquals(0.5, BinaryPlist.read(binaryPlist(byteArrayOf(0x23) + ByteBuffer.allocate(8).putDouble(0.5).array())))
    }

    @Test(timeout = 5_000)
    fun cyclicObjectReferencesStillFailWithinTheDepthLimit() {
        val selfCycle = binaryPlist(byteArrayOf(0xA1.toByte(), 0))
        val mutualCycle = binaryPlist(byteArrayOf(0xA1.toByte(), 1), byteArrayOf(0xA1.toByte(), 0))
        for (bytes in listOf(selfCycle, mutualCycle)) {
            assertThrows(IllegalArgumentException::class.java) { BinaryPlist.read(bytes) }
        }
    }

    @Test
    fun theExistingObjectDepthBoundaryIsPreserved() {
        fun nested(levels: Int): ByteArray {
            val objects = (0 until levels).map { byteArrayOf(0xA1.toByte(), (it + 1).toByte()) } + listOf(byteArrayOf(0x09))
            return binaryPlist(*objects.toTypedArray())
        }

        var value = BinaryPlist.read(nested(63))
        repeat(63) { value = (value as List<*>).single() }
        assertEquals(true, value)
        assertThrows(IllegalArgumentException::class.java) { BinaryPlist.read(nested(64)) }
    }

    @Test
    fun paddedRootUidsReachTheSupportedDocumentAndBrushReaders() {
        for (size in listOf(1, 9, 16)) {
            val bytes = keyedArchive(uid(size))
            assertEquals("Boundary", openDocument(bytes).name)
            assertEquals("Boundary", ProcreateBrush.read(bytes).name)
        }
    }

    @Test
    fun documentAndBrushReadersRefuseForgedRootUids() {
        val bytes = keyedArchive(uid(9, nonzeroHighByte = true))
        assertThrows(IllegalArgumentException::class.java) { openDocument(bytes) }
        assertThrows(IllegalArgumentException::class.java) { ProcreateBrush.read(bytes) }
    }

    @Test
    fun documentAndBrushReadersRefuseMetadataThatEndsInTheOffsetTable() {
        val bytes = keyedArchive(uid(1), name = byteArrayOf(0x51))
        assertThrows(IllegalArgumentException::class.java) { openDocument(bytes) }
        assertThrows(IllegalArgumentException::class.java) { ProcreateBrush.read(bytes) }
    }

    private fun openDocument(bytes: ByteArray): ProcreateReader.Document {
        val files =
            object : ProcreateReader.Files {
                override val names = listOf("Document.archive")

                override fun read(name: String): ByteArray? = bytes.takeIf { name == "Document.archive" }
            }
        return ProcreateReader.open(files) { null }
    }

    /** Independent records for { $objects: ["$null", {size, name}], $top: {root: UID(1)} }. */
    private fun keyedArchive(
        rootUid: ByteArray,
        name: ByteArray = ascii("Boundary"),
    ): ByteArray =
        binaryPlist(
            byteArrayOf(0xD2.toByte(), 1, 2, 3, 4),
            ascii("\$objects"),
            ascii("\$top"),
            byteArrayOf(0xA2.toByte(), 5, 6),
            byteArrayOf(0xD1.toByte(), 7, 8),
            ascii("\$null"),
            byteArrayOf(0xD2.toByte(), 9, 10, 11, 12),
            ascii("root"),
            rootUid,
            ascii("size"),
            ascii("name"),
            ascii("{16, 16}"),
            name,
        )

    private fun uid(
        size: Int,
        nonzeroHighByte: Boolean = false,
    ): ByteArray {
        val value = ByteArray(size).apply { this[lastIndex] = 1 }
        if (nonzeroHighByte) value[0] = 1
        return byteArrayOf((0x80 or (size - 1)).toByte()) + value
    }

    private fun ascii(value: String): ByteArray = byteArrayOf((0x50 or value.length).toByte()) + value.toByteArray(Charsets.US_ASCII)

    /** Handwritten object records, four-byte offsets, and one-byte object references. */
    private fun binaryPlist(
        vararg objects: ByteArray,
        top: Int = 0,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("bplist00".toByteArray(Charsets.US_ASCII))
        val offsets = IntArray(objects.size)
        objects.forEachIndexed { index, bytes ->
            offsets[index] = out.size()
            out.write(bytes)
        }
        val table = out.size()
        offsets.forEach { out.write(ByteBuffer.allocate(4).putInt(it).array()) }
        val trailer = ByteBuffer.allocate(32)
        trailer.put(6, 4)
        trailer.put(7, 1)
        trailer.putLong(8, objects.size.toLong())
        trailer.putLong(16, top.toLong())
        trailer.putLong(24, table.toLong())
        out.write(trailer.array())
        return out.toByteArray()
    }
}
