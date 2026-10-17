package com.artflow.studio.core.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class Lz4CompressTest {
    private fun roundTrip(source: ByteArray): ByteArray {
        val packed = Lz4.compress(source)
        val out = ByteArray(source.size)
        val written = Lz4.decompress(packed, 0, packed.size, out, 0)
        assertTrue("wrote ${source.size} bytes, got $written", written == source.size)
        assertArrayEquals(source, out)
        return packed
    }

    @Test
    fun smallInputsBelowTheMatchingMinimumAreAllLiterals() {
        for (size in 0..13) {
            roundTrip(ByteArray(size) { (it * 37 + 11).toByte() })
        }
    }

    @Test
    fun randomBytesRoundTripAndRepetitionCompresses() {
        val random = Random(20260917)
        roundTrip(random.nextBytes(100_000))
        val repeated = ByteArray(200_000) { (it % 97).toByte() }
        assertTrue(roundTrip(repeated).size < repeated.size / 20)
    }

    @Test
    fun longLiteralAndMatchLengthsUseTheirExtensionBytes() {
        val random = Random(7)
        val literals = random.nextBytes(1_000)
        roundTrip(literals)
        val zeros = ByteArray(70_000)
        assertTrue(roundTrip(zeros).size < 1_000)
        roundTrip(literals + zeros + literals)
    }

    @Test
    fun matchesReachBackAcrossTheWholeWindow() {
        val random = Random(99)
        val block = random.nextBytes(60_000)
        val source = block + random.nextBytes(3_000) + block
        assertTrue(roundTrip(source).size < source.size - 50_000)
    }

    @Test
    fun aDecodedStreamOfEmptyInputIsEmpty() {
        val packed = Lz4.compress(ByteArray(0))
        assertTrue(packed.size <= 1)
        roundTrip(ByteArray(0))
    }
}
