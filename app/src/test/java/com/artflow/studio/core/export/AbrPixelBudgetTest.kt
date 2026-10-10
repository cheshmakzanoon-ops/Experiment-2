package com.artflow.studio.core.export

import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class AbrPixelBudgetTest {
    /** A version 1 file of uncompressed square tips, laid out the way [AbrReader] reads them. */
    private fun squareTips(
        tips: Int,
        side: Int,
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeShort(1) // version
            out.writeShort(tips)
            repeat(tips) {
                out.writeShort(2) // sampled
                out.writeInt(15 + 16 + 2 + 1 + side * side) // header, bounds, depth, compression, pixels
                out.write(ByteArray(15)) // misc, spacing, antialiasing
                out.writeInt(0) // top
                out.writeInt(0) // left
                out.writeInt(side) // bottom
                out.writeInt(side) // right
                out.writeShort(8) // depth
                out.writeByte(0) // uncompressed
                out.write(ByteArray(side * side))
            }
        }
        return bytes.toByteArray()
    }

    @Test
    fun tipsInOneFileShareAPixelBudget() {
        // Three 5000 x 5000 tips hold 75 million pixels, more than one file may hold, so the third is refused.
        assertThrows(IllegalArgumentException::class.java) { AbrReader.read(squareTips(tips = 3, side = 5000)) }
    }
}
