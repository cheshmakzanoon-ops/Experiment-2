package com.artflow.studio.core.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LzStreamCompletionTest {
    @Test
    fun completeLzoStreamsKeepExactAndSmallerOutputs() {
        val raw = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(raw, Lzo1x.decompress(lzoLiteral(raw), raw.size))
        assertArrayEquals(raw, Lzo1x.decompress(lzoLiteral(raw), 32))
        assertArrayEquals(ByteArray(0), Lzo1x.decompress(byteArrayOf(17, 0, 0), 0))
    }

    @Test
    fun lzoOverlappingMatchesAndExtendedLengthsKeepRepeatingCopiedBytes() {
        // One initial 'A', then an M3 match at distance one, then the end marker.
        val shortMatch = byteArrayOf(18, 65, 63, 0, 0, 17, 0, 0)
        val extendedMatch = byteArrayOf(18, 65, 32, 1, 0, 0, 17, 0, 0)
        assertArrayEquals(ByteArray(34) { 65 }, Lzo1x.decompress(shortMatch, 34))
        assertArrayEquals(ByteArray(35) { 65 }, Lzo1x.decompress(extendedMatch, 35))
    }

    @Test
    fun lzoRejectsTrailingBytesAndConcatenatedStreams() {
        val packed = lzoLiteral(byteArrayOf(1, 2, 3, 4))
        assertDamaged { Lzo1x.decompress(packed + byteArrayOf(0), 4) }
        assertDamaged { Lzo1x.decompress(packed + packed, 8) }
    }

    @Test
    fun lzoRejectsNonterminalZeroDistanceMatchLengths() {
        val prefix = byteArrayOf(21, 1, 2, 3, 4)
        for (token in 18..23) {
            assertDamaged { Lzo1x.decompress(prefix + byteArrayOf(token.toByte(), 0, 0), 32) }
        }
        assertDamaged { Lzo1x.decompress(prefix + byteArrayOf(16, 1, 0, 0), 32) }
    }

    @Test
    fun lzoRequiresEveryEndMarkerByteEvenAfterAllPixelsAreWritten() {
        val packed = lzoLiteral(byteArrayOf(1, 2, 3, 4))
        for (missing in 1..3) {
            assertDamaged { Lzo1x.decompress(packed.copyOf(packed.size - missing), 4) }
        }
    }

    @Test
    fun appleStoredAliasesAndExistingEndMarkersKeepExactAndSmallerOutputs() {
        val raw = byteArrayOf(1, 2, 3, 4)
        for (magic in listOf("bv4-", "bvx-")) {
            for (end in listOf("bv4$", "bvx$")) {
                val packed = stored(raw, magic) + end.toByteArray(Charsets.ISO_8859_1)
                assertArrayEquals(raw, Lz4.decompressApple(packed, raw.size))
                assertArrayEquals(raw, Lz4.decompressApple(packed, 32))
            }
        }
        assertArrayEquals(ByteArray(0), Lz4.decompressApple(appleEnd, 0))
    }

    @Test
    fun appleCompressedBlocksKeepOverlapAndEarlierBlockDictionary() {
        // One 'A' plus an overlapping eleven-byte match, followed by five final literals.
        val overlap = byteArrayOf(0x17, 65, 1, 0, 0x50, 66, 67, 68, 69, 70)
        val repeated = ByteArray(12) { 65 } + byteArrayOf(66, 67, 68, 69, 70)
        assertArrayEquals(repeated, Lz4.decompressApple(compressed(overlap, repeated.size) + appleEnd, repeated.size))

        // The second block starts with a match that reaches four bytes into the first block.
        val dictionary = byteArrayOf(97, 98, 99, 100)
        val following = byteArrayOf(0x04, 4, 0, 0x50, 69, 70, 71, 72, 73)
        val expected = dictionary + dictionary + dictionary + byteArrayOf(69, 70, 71, 72, 73)
        val packed = stored(dictionary) + compressed(following, 13) + appleEnd
        assertArrayEquals(expected, Lz4.decompressApple(packed, expected.size))
    }

    @Test
    fun appleRequiresCompleteEndMarkerEvenAfterFullOutput() {
        val raw = byteArrayOf(1, 2, 3, 4)
        val blocks = listOf(stored(raw), compressed(byteArrayOf(0x40) + raw, raw.size))
        for (block in blocks) {
            for (present in 0..3) {
                assertDamaged { Lz4.decompressApple(block + appleEnd.copyOf(present), raw.size) }
            }
        }
    }

    @Test
    fun appleRejectsTrailingBytesAndAdditionalFrames() {
        val packed = stored(byteArrayOf(1, 2, 3, 4)) + appleEnd
        assertDamaged { Lz4.decompressApple(packed + byteArrayOf(0), 4) }
        assertDamaged { Lz4.decompressApple(packed + packed, 8) }
    }

    @Test
    fun appleStillRefusesMismatchedBlockLengthsAndInvalidBackreferences() {
        assertDamaged { Lz4.decompressApple(compressed(byteArrayOf(0x40, 1, 2, 3, 4), 3) + appleEnd, 8) }
        assertDamaged { Lz4.decompressApple(compressed(byteArrayOf(0x00, 1, 0), 4) + appleEnd, 8) }
        assertDamaged { Lz4.decompressApple(stored(byteArrayOf(1, 2, 3, 4)) + appleEnd, 3) }
    }

    @Test
    fun completeCroppedTilesKeepPixelsAndReportNoDamage() {
        val encodings =
            listOf(
                "chunk" to lzoLiteral(rgba),
                "lz4" to (stored(rgba) + appleEnd),
                "lz4" to (stored(rgba, "bv4-") + appleEnd),
                "lz4" to (compressed(byteArrayOf(0xF0.toByte(), 49) + rgba, rgba.size) + appleEnd),
            )
        for ((extension, packed) in encodings) {
            val document = openTile(packed, extension)
            assertArrayEquals(IntArray(16) { 0xFF102030.toInt() }, document.pixels(document.layers().single()).pixels)
            assertEquals(0, document.damagedTileCount)
        }
    }

    @Test
    fun incompleteAppleTilesAreCountedAsDamaged() {
        val packed = stored(rgba) + appleEnd
        for (missing in 1..4) assertDamagedTile(packed.copyOf(packed.size - missing), "lz4")
    }

    @Test
    fun trailingAndMalformedLzoTilesAreCountedAsDamaged() {
        val packed = lzoLiteral(rgba)
        assertDamagedTile(packed + byteArrayOf(0), "chunk")
        assertDamagedTile(packed + packed, "chunk")
        assertDamagedTile(packed.copyOf(packed.size - 3) + byteArrayOf(18, 0, 0), "chunk")
    }

    @Test
    fun trailingAppleTilesAreCountedAsDamaged() {
        val packed = stored(rgba) + appleEnd
        assertDamagedTile(packed + byteArrayOf(0), "lz4")
        assertDamagedTile(packed + packed, "lz4")
    }

    private fun assertDamaged(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("Expected IllegalArgumentException, got $failure", failure is IllegalArgumentException)
    }

    private fun assertDamagedTile(
        packed: ByteArray,
        extension: String,
    ) {
        val document = openTile(packed, extension)
        assertTrue(document.pixels(document.layers().single()).pixels.all { it == 0 })
        assertEquals(1, document.damagedTileCount)
    }

    private fun openTile(
        packed: ByteArray,
        extension: String,
    ): ProcreateReader.Document {
        val archive = KeyedArchiveBuilder()
        val layer = archive.instance("SilicaLayer", mapOf("name" to "Ink", "UUID" to "INK"))
        val description =
            archive.encode(mapOf("size" to "{4, 4}", "tileSize" to 16L, "layers" to archive.array(listOf(layer))))
        val entries = mapOf("Document.archive" to description, "INK/0~0.$extension" to packed)
        return ProcreateReader.open(
            object : ProcreateReader.Files {
                override val names = entries.keys

                override fun read(name: String) = entries[name]
            },
        ) { null }
    }

    /** Independent short initial-literal LZO streams; all samples fit its 238-byte maximum. */
    private fun lzoLiteral(raw: ByteArray): ByteArray = byteArrayOf((raw.size + 17).toByte()) + raw + byteArrayOf(17, 0, 0)

    private fun stored(
        raw: ByteArray,
        magic: String = "bvx-",
    ): ByteArray = magic.toByteArray(Charsets.ISO_8859_1) + littleInt(raw.size) + raw

    private fun compressed(
        packed: ByteArray,
        size: Int,
    ): ByteArray = "bv41".toByteArray(Charsets.ISO_8859_1) + littleInt(size) + littleInt(packed.size) + packed

    private fun littleInt(value: Int): ByteArray =
        ByteBuffer
            .allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(value)
            .array()

    private val appleEnd = "bv4$".toByteArray(Charsets.ISO_8859_1)
    private val rgba = ByteArray(64) { byteArrayOf(16, 32, 48, -1)[it % 4] }
}
