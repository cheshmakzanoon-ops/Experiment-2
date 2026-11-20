package com.artflow.studio.core.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class BinaryPlistBudgetTest {
    /** Builds a binary plist whose objects are the given encoded records, with object 0 as the root. */
    private fun binaryPlist(
        objects: List<ByteArray>,
        aliasedOffsets: Map<Int, Int> = emptyMap(),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("bplist00".toByteArray(Charsets.US_ASCII))
        val offsets = IntArray(objects.size)
        objects.forEachIndexed { index, bytes ->
            offsets[index] = out.size()
            out.write(bytes)
        }
        val table = out.size()
        offsets.indices.forEach { index -> out.write(ByteBuffer.allocate(4).putInt(offsets[aliasedOffsets[index] ?: index]).array()) }
        val trailer = ByteArray(32)
        trailer[6] = 4 // offset size
        trailer[7] = 1 // reference size
        writeLong(trailer, 8, objects.size.toLong())
        writeLong(trailer, 16, 0L) // root object
        writeLong(trailer, 24, table.toLong())
        out.write(trailer)
        return out.toByteArray()
    }

    private fun writeLong(
        target: ByteArray,
        at: Int,
        value: Long,
    ) {
        for (i in 0 until 8) target[at + i] = (value ushr (56 - 8 * i)).toByte()
    }

    @Test(timeout = 10_000)
    fun aSharedGraphWithExponentialPathsIsRefusedInsteadOfExpanded() {
        // Object i is an array of two references to object i + 1, so there are 2^levels paths
        // through 60 shared levels. Without a read budget, expanding them takes practically forever.
        val levels = 60
        val arrays = (0 until levels).map { i -> byteArrayOf(0xA2.toByte(), (i + 1).toByte(), (i + 1).toByte()) }
        val plist = binaryPlist(arrays + listOf(byteArrayOf(0x09))) // last object is `true`
        assertThrows(IllegalArgumentException::class.java) { BinaryPlist.read(plist) }
    }

    @Test
    fun repeatedDataReferencesConsumeOneCumulativeAllocationAllowance() {
        val data = ByteArray(1024) { it.toByte() }
        val plist = binaryPlist(listOf(sized(0xA0, 1000) + ByteArray(1000) { 1 }, sized(0x40, data.size) + data))

        assertAllocationRejected(plist, 32L * 1024)
        val decoded = BinaryPlist.read(plist, 2L * 1024 * 1024) as List<*>
        assertEquals(1000, decoded.size)
        assertArrayEquals(data, decoded.first() as ByteArray)
        assertArrayEquals(data, decoded.last() as ByteArray)
        assertNotSame(decoded.first(), decoded.last())
    }

    @Test
    fun differentObjectIndicesThatAliasOneOffsetStillConsumeTheAllowance() {
        val data = ByteArray(256) { it.toByte() }
        val plist =
            binaryPlist(
                listOf(byteArrayOf(0xA2.toByte(), 1, 2), sized(0x40, data.size) + data, byteArrayOf(0)),
                aliasedOffsets = mapOf(2 to 1),
            )

        assertAllocationRejected(plist, 512)
        val decoded = BinaryPlist.read(plist, 2048) as List<*>
        assertArrayEquals(data, decoded[0] as ByteArray)
        assertArrayEquals(data, decoded[1] as ByteArray)
        assertNotSame(decoded[0], decoded[1])
    }

    @Test
    fun repeatedAsciiAndUtf16StringsReserveDecodedCharacterStorage() {
        for (unicode in listOf(false, true)) {
            val text = (if (unicode) "\u0100" else "A").repeat(128)
            val bytes = text.toByteArray(if (unicode) Charsets.UTF_16BE else Charsets.ISO_8859_1)
            val marker = if (unicode) 0x60 else 0x50
            val plist = binaryPlist(listOf(sized(0xA0, 64) + ByteArray(64) { 1 }, sized(marker, text.length) + bytes))

            assertAllocationRejected(plist, 8L * 1024)
            val decoded = BinaryPlist.read(plist, 128L * 1024) as List<*>
            assertEquals(List(64) { text }, decoded)
        }
    }

    @Test
    fun arrayAndDictionaryCapacityAreAdmittedBeforeFollowingTheirReferences() {
        for (dictionary in listOf(false, true)) {
            val marker = if (dictionary) 0xD0 else 0xA0
            val references = if (dictionary) 512 else 256
            // The references are deliberately invalid. Allocation admission must reject the
            // container before visiting them, rather than first constructing a large result.
            val plist = binaryPlist(listOf(sized(marker, 256) + ByteArray(references) { 255.toByte() }))
            assertAllocationRejected(plist, 512)
        }
    }

    @Test
    fun dictionaryKeysCannotStringifyAnUnbudgetedNestedContainer() {
        val plist = BinaryPlist.write(mapOf(listOf("nested", "key") to "value"))
        assertThrows(IllegalArgumentException::class.java) { BinaryPlist.read(plist) }
    }

    @Test
    fun injectedAllocationLimitsCannotDisableOrWidenTheProductionAllowance() {
        val plist = BinaryPlist.write(true)
        for (limit in listOf(0L, -1L, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { BinaryPlist.read(plist, limit) }
        }
        assertEquals(true, BinaryPlist.read(plist, 1024))
    }

    private fun sized(
        marker: Int,
        length: Int,
    ): ByteArray = byteArrayOf((marker or 0xF).toByte(), 0x12) + ByteBuffer.allocate(4).putInt(length).array()

    private fun assertAllocationRejected(
        plist: ByteArray,
        limit: Long,
    ) {
        val failure = assertThrows(IllegalArgumentException::class.java) { BinaryPlist.read(plist, limit) }
        assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("decoded memory"))
    }
}
