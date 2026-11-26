package com.artflow.studio.core.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class AbrPackBitsTest {
    @Test
    fun literalAndRepeatRunsPreserveCoverageAndAllowNoOps() {
        val sample =
            packedTip(
                width = 5,
                rows =
                    listOf(
                        byteArrayOf(-128, 1, 0, 127, -128, -2, -1, -128),
                        byteArrayOf(-128, 4, -1, -128, 64, 1, 0, -128),
                    ),
            )
        val tip = AbrReader.read(abr(sample)).single()
        assertArrayEquals(byteArrayOf(0, 127, -1, -1, -1, -1, -128, 64, 1, 0), tip.values)
    }

    @Test
    fun sixteenBitRunsKeepEveryHighByteAndAllowTrailingNoOps() {
        val sample = packedTip(3, listOf(byteArrayOf(-128, 3, 0, 99, 127, 51, -1, -1, -128)), depth = 16)
        assertArrayEquals(byteArrayOf(0, 127, -1), AbrReader.read(abr(sample)).single().values)
    }

    @Test
    fun anEmptyPackedRowIsRejected() {
        assertDamaged(packedTip(1, listOf(byteArrayOf())))
    }

    @Test
    fun aShortLiteralRowIsRejected() {
        assertDamaged(packedTip(3, listOf(byteArrayOf(1, 12, 34))))
    }

    @Test
    fun aShortRepeatRowIsRejected() {
        assertDamaged(packedTip(3, listOf(byteArrayOf(-1, 12))))
    }

    @Test
    fun aNoOpOnlyRowIsRejected() {
        assertDamaged(packedTip(1, listOf(byteArrayOf(-128, -128))))
    }

    @Test
    fun aSixteenBitSampleMissingItsLowByteIsRejected() {
        assertDamaged(packedTip(1, listOf(byteArrayOf(0, -1)), depth = 16))
    }

    @Test
    fun aShortRowCannotBorrowPixelsFromTheFollowingRow() {
        assertDamaged(packedTip(2, listOf(byteArrayOf(0, 12), byteArrayOf(1, 34, 56))))
    }

    @Test
    fun aDamagedLaterTipRejectsTheCompleteBatch() {
        val valid = packedTip(2, listOf(byteArrayOf(1, 0, -1)))
        val damaged = packedTip(2, listOf(byteArrayOf()))
        assertThrows(IllegalArgumentException::class.java) { AbrReader.read(abr(valid, damaged)) }
    }

    private fun assertDamaged(sample: ByteArray) {
        assertThrows(IllegalArgumentException::class.java) { AbrReader.read(abr(sample)) }
    }

    private fun packedTip(
        width: Int,
        rows: List<ByteArray>,
        depth: Int = 8,
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write(ByteArray(15)) // Miscellaneous fields, spacing, antialiasing and short bounds.
            out.writeInt(0)
            out.writeInt(0)
            out.writeInt(rows.size)
            out.writeInt(width)
            out.writeShort(depth)
            out.writeByte(1)
            rows.forEach { out.writeShort(it.size) }
            rows.forEach(out::write)
        }
        return bytes.toByteArray()
    }

    private fun abr(vararg samples: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeShort(1)
            out.writeShort(samples.size)
            samples.forEach { sample ->
                out.writeShort(2)
                out.writeInt(sample.size)
                out.write(sample)
            }
        }
        return bytes.toByteArray()
    }
}
