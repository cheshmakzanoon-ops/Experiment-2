package com.artflow.studio.core.export

import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

class BinaryPlistBudgetTest {
    /** Builds a binary plist whose objects are the given encoded records, with object 0 as the root. */
    private fun binaryPlist(objects: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("bplist00".toByteArray(Charsets.US_ASCII))
        val offsets = IntArray(objects.size)
        objects.forEachIndexed { index, bytes ->
            offsets[index] = out.size()
            out.write(bytes)
        }
        val table = out.size()
        offsets.forEach { out.write(it) }
        val trailer = ByteArray(32)
        trailer[6] = 1 // offset size
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
}
