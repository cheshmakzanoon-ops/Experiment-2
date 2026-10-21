package com.artflow.studio.core.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Independent fixtures put another layer immediately after the mask's declared channel. */
class PsdMaskChannelBoundsTest {
    @Test
    fun truncatedMaskCannotTakeItsMissingRepeatValueFromTheNextLayer() {
        // The second row promises two bytes but contains only its repeat control. The next
        // layer starts with a raw compression header, which the old decoder used as coverage.
        assertNull(PsdCodec.read(psd(rle(intArrayOf(2, 2), byteArrayOf(-1, 64, -1)))))
    }

    @Test
    fun literalRunCannotBorrowBytesFromTheNextRow() {
        assertNull(PsdCodec.read(psd(rle(intArrayOf(2, 3), byteArrayOf(1, 64, 1, 10, 20)))))
    }

    @Test
    fun missingRepeatValueIsRejectedWithinItsDeclaredRow() {
        assertNull(PsdCodec.read(psd(rle(intArrayOf(2, 1), byteArrayOf(-1, 64, -1)))))
    }

    @Test
    fun rowsMustExpandToTheirOwnWidth() {
        // Too few samples, too many literal samples, and too many repeated samples.
        val cases =
            listOf(
                rle(intArrayOf(2, 3), byteArrayOf(0, 10, 1, 20, 30)),
                rle(intArrayOf(4, 2), byteArrayOf(2, 10, 20, 30, -1, 64)),
                rle(intArrayOf(2, 2), byteArrayOf(-2, 64, -1, 80)),
            )
        cases.forEach { assertNull(PsdCodec.read(psd(it))) }
    }

    @Test
    fun missingCompressionHeaderAndIncompleteRowLengthTableAreRejected() {
        assertNull(PsdCodec.read(psd(byteArrayOf(0))))
        assertNull(PsdCodec.read(psd(byteArrayOf(0, 1, 0, 2))))
    }

    @Test
    fun rawMaskMustContainItsCompletePlane() {
        assertNull(PsdCodec.read(psd(byteArrayOf(0, 0, 10, 20, 30))))
    }

    @Test
    fun validNoOpsRespectRowBoundariesAndLeaveTheFollowingLayerIntact() {
        val mask = rle(intArrayOf(5, 3), byteArrayOf(-128, 1, 10, 20, -128, -1, 80, -128))
        assertMaskAndNeighbor(mask, byteArrayOf(10, 20, 80, 80))
    }

    @Test
    fun validRawMaskLeavesTheFollowingLayerIntact() {
        assertMaskAndNeighbor(byteArrayOf(0, 0, 10, 20, 30, 40), byteArrayOf(10, 20, 30, 40))
    }

    @Test
    fun sixteenBitRleRowsAreBoundedBeforeDownsampling() {
        val mask = rle(intArrayOf(5, 5), byteArrayOf(3, 0x12, 0x34, -128, 1, 3, -1, -1, 0, -1))
        assertMaskAndNeighbor(mask, byteArrayOf(0x12, -128, -1, 0), depth = 16)
    }

    private fun assertMaskAndNeighbor(
        mask: ByteArray,
        expected: ByteArray,
        depth: Int = 8,
    ) {
        val document = requireNotNull(PsdCodec.read(psd(mask, depth)))
        assertEquals(2, document.layers.size)
        assertArrayEquals(expected, document.layers[0].mask!!.coverage)
        assertArrayEquals(IntArray(4) { 0xFF335577.toInt() }, document.layers[1].pixels.pixels)
    }

    private fun rle(
        rowLengths: IntArray,
        payload: ByteArray,
    ): ByteArray =
        ByteArrayOutputStream()
            .apply {
                short(1)
                rowLengths.forEach { short(it) }
                write(payload)
            }.toByteArray()

    /** Two 2 x 2 layers: one masked, then a raw RGB sentinel. No production writer is used. */
    private fun psd(
        mask: ByteArray,
        depth: Int = 8,
    ): ByteArray {
        fun raw(value: Int) =
            ByteArrayOutputStream()
                .apply {
                    short(0)
                    repeat(4) {
                        write(value)
                        if (depth == 16) write(0)
                    }
                }.toByteArray()

        val planes = listOf(0 to raw(0x33), 1 to raw(0x55), 2 to raw(0x77), -1 to raw(255))
        val channels = listOf(planes + (-2 to mask), planes)
        val info = ByteArrayOutputStream()
        info.short(-2)
        channels.forEachIndexed { index, layerChannels ->
            info.int(0)
            info.int(0)
            info.int(2)
            info.int(2)
            info.short(layerChannels.size)
            layerChannels.forEach { (id, data) ->
                info.short(id)
                info.int(data.size)
            }
            info.write("8BIMnorm".toByteArray(Charsets.US_ASCII))
            info.write(byteArrayOf(-1, 0, 0, 0))
            val extra = ByteArrayOutputStream()
            if (index == 0) {
                extra.int(20)
                extra.int(0)
                extra.int(0)
                extra.int(2)
                extra.int(2)
                extra.write(byteArrayOf(-1, 0, 0, 0))
            } else {
                extra.int(0)
            }
            extra.int(0) // blending ranges
            extra.write(byteArrayOf(1, ('A'.code + index).toByte(), 0, 0))
            info.int(extra.size())
            info.write(extra.toByteArray())
        }
        channels.forEach { layerChannels -> layerChannels.forEach { (_, data) -> info.write(data) } }
        if (info.size() % 2 != 0) info.write(0)

        val section = ByteArrayOutputStream()
        section.int(info.size())
        section.write(info.toByteArray())
        section.int(0) // global layer mask
        return ByteArrayOutputStream()
            .apply {
                write("8BPS".toByteArray(Charsets.US_ASCII))
                short(1)
                write(ByteArray(6))
                short(4)
                int(2)
                int(2)
                short(depth)
                short(3)
                int(0) // colour mode data
                int(0) // resources
                int(section.size())
                write(section.toByteArray())
                short(0) // raw merged image
                repeat(4) { write(ByteArray(4 * depth / 8)) }
            }.toByteArray()
    }

    private fun ByteArrayOutputStream.short(value: Int) {
        write(value ushr 8)
        write(value)
    }

    private fun ByteArrayOutputStream.int(value: Int) {
        write(value ushr 24)
        write(value ushr 16)
        write(value ushr 8)
        write(value)
    }
}
