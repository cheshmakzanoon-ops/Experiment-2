package com.artflow.studio.data.renderer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ImageDecodeBudgetTest {
    @Test
    fun imageJustBelowTwiceTheEdgeLimitIsSampledBeforeAllocation() {
        val plan = ImageDecodeBudget.plan(8191, 8191, 4096, Long.MAX_VALUE)
        assertEquals(2, plan.sampleSize)
        assertEquals(4096, plan.width)
        assertEquals(4096, plan.height)
    }

    @Test
    fun oddDimensionsCannotRoundPastTheRequestedEdge() {
        assertEquals(2, ImageDecodeBudget.sampleSize(4097, 2049, 4096))
        assertEquals(2, ImageDecodeBudget.sampleSize(65, 127, 64))
        val plan = ImageDecodeBudget.plan(65, 127, 64, Long.MAX_VALUE)
        assertEquals(33, plan.width)
        assertEquals(64, plan.height)
    }

    @Test
    fun dimensionsAlreadyWithinTheLimitAreNotSampled() {
        assertEquals(1, ImageDecodeBudget.sampleSize(4096, 3072, 4096))
        assertEquals(1, ImageDecodeBudget.sampleSize(1, 1, 1))
        assertEquals(ImageDecodeBudget.Plan(1, 131, 67), ImageDecodeBudget.plan(131, 67, 4096, 8 * MIB))
    }

    @Test
    fun outputAccountsForAllSimultaneousRastersAndDecoderWorkspace() {
        // A 256-square F16 bitmap, ARGB copy and IntArray alone occupy 1 MiB. With the
        // decoder's reserved workspace/rows, a 2 MiB total budget needs a smaller sample.
        val plan = ImageDecodeBudget.plan(1024, 1024, 1024, 2 * MIB)
        assertEquals(8, plan.sampleSize)
        assertEquals(128, plan.width)
        assertEquals(128, plan.height)
    }

    @Test
    fun decoderMetadataIsRejectedWhenEvenOneOutputPixelCannotFit() {
        val failure = assertThrows(IllegalArgumentException::class.java) { ImageDecodeBudget.plan(2, 2, 4096, 0) }
        assertTrue(failure.message.orEmpty().contains("memory"))
        // Long row arithmetic must not wrap a hostile header into an affordable allocation.
        assertThrows(IllegalArgumentException::class.java) { ImageDecodeBudget.plan(Int.MAX_VALUE, 1, 4096, 512 * MIB) }
    }

    @Test
    fun samplingHonorsTheCanvasPixelCeilingEvenWithAnUnlimitedBudget() {
        val plan = ImageDecodeBudget.plan(8192, 8192, 8192, Long.MAX_VALUE)
        assertEquals(2, plan.sampleSize)
        assertEquals(4096, plan.width)
        assertEquals(4096, plan.height)
    }

    @Test
    fun currentHeadroomCanBeStricterThanTheMaximumHeapShare() {
        assertEquals(16 * MIB, ImageDecodeBudget.availableBytes(256 * MIB, 240 * MIB, 16 * MIB))
        assertEquals(256 * MIB / 3, ImageDecodeBudget.availableBytes(256 * MIB, 64 * MIB, 48 * MIB))
        assertEquals(0, ImageDecodeBudget.availableBytes(256 * MIB, 256 * MIB, 0))
    }

    @Test
    fun extremeDimensionsEitherFitOrFailWithoutIntegerOverflow() {
        assertEquals(1 shl 30, ImageDecodeBudget.sampleSize(Int.MAX_VALUE, 1, 2))
        assertThrows(IllegalArgumentException::class.java) { ImageDecodeBudget.sampleSize(Int.MAX_VALUE, 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { ImageDecodeBudget.sampleSize(0, 1, 4096) }
        assertThrows(IllegalArgumentException::class.java) { ImageDecodeBudget.sampleSize(1, 1, 0) }
    }

    @Test
    fun unexpectedDecoderOutputCannotTriggerAnotherLargePixelCopy() {
        val plan = ImageDecodeBudget.plan(128, 64, 64, 8 * MIB)
        plan.checkDecodedSize(64, 32)
        plan.checkDecodedSize(32, 64) // An orientation transform does not change the allocation.
        assertThrows(IllegalArgumentException::class.java) { plan.checkDecodedSize(65, 32) }
        assertThrows(IllegalArgumentException::class.java) { plan.checkDecodedSize(64, 33) }
        assertThrows(IllegalArgumentException::class.java) { plan.checkDecodedSize(Int.MAX_VALUE, Int.MAX_VALUE) }
    }

    @Test
    fun encodedSnapshotStopsAtItsBudgetWithoutReadingTheWholeProvider() {
        val source = ByteArrayInputStream(ByteArray(4096))
        val failure = assertThrows(IllegalArgumentException::class.java) { ImageDecodeBudget.readEncoded(source, MIB + 32) }
        assertTrue(failure.message.orEmpty().contains("too large"))
        assertEquals(4096 - 9, source.available())
    }

    @Test
    fun aZeroLengthBulkReadMakesProgressAndPreservesTheActualBytes() {
        val bytes = byteArrayOf(1, 2, 0, 4)
        val source =
            object : ByteArrayInputStream(bytes) {
                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int = 0
            }
        assertArrayEquals(bytes, ImageDecodeBudget.readEncoded(source, 2 * MIB))
    }

    private companion object {
        const val MIB = 1024 * 1024L
    }
}
