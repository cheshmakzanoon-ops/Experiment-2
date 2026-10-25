package com.artflow.studio.core.export

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.zip.ZipInputStream

/** Native mask metadata and raw coverage are checked separately from the application's tile reader. */
class ProcreateMaskExportTest {
    @Test
    fun nativeMaskTypeAndStoredCoverageBytesMatchTheOneChannelContract() {
        val coverage = ByteArray(256 * 256).also { Random(20261025L).nextBytes(it) }
        coverage[0] = 0
        coverage[1] = 128.toByte()
        coverage[2] = 255.toByte()
        val written = write(256, 256, coverage)
        assertEquals(3L, written.mask["type"])
        assertNotEquals(3L, written.layer["type"])
        val maskTile = requireNotNull(written.files["${written.maskUuid}/0~0.lz4"])
        // The seeded, incompressible coverage takes the stored-block path, so neither
        // ProcreateReader nor the production LZ4 decoder supplies this byte oracle.
        assertEquals("bvx-", maskTile.copyOfRange(0, 4).toString(Charsets.ISO_8859_1))
        assertEquals(256 * 256, rawSize(maskTile))
        assertArrayEquals(coverage, maskTile.copyOfRange(8, maskTile.size - 4))
        assertEquals("bv4\$", maskTile.takeLast(4).toByteArray().toString(Charsets.ISO_8859_1))
        assertEquals(256 * 256 * 4, rawSize(requireNotNull(written.files["${written.layerUuid}/0~0.lz4"])))
        assertEquals(256 * 256 * 4, rawSize(requireNotNull(written.files["${written.compositeUuid}/0~0.lz4"])))
    }

    @Test
    fun sparseMaskTilesKeepWhitePaddingAndOmitFullyRevealedTiles() {
        val width = 513
        val height = 257
        val coverage = ByteArray(width * height) { 255.toByte() }
        coverage[0] = 0
        coverage[255 * width + 255] = 128.toByte()
        coverage[256] = 64
        coverage[256 * width + 512] = 17
        val written = write(width, height, coverage)
        val stored = written.files.keys.filter { it.startsWith("${written.maskUuid}/") }
        assertEquals(setOf("0~0.lz4", "1~0.lz4", "2~1.lz4"), stored.map { it.substringAfter('/') }.toSet())
        for ((column, row) in listOf(0 to 0, 1 to 0, 2 to 1)) {
            val tile = requireNotNull(written.files["${written.maskUuid}/$column~$row.lz4"])
            assertEquals(256 * 256, rawSize(tile))
            val expected =
                ByteArray(256 * 256) { at ->
                    val x = column * 256 + at % 256
                    val y = row * 256 + at / 256
                    if (x < width && y < height) coverage[y * width + x] else 255.toByte()
                }
            assertArrayEquals(expected, Lz4.decompressApple(tile, expected.size))
        }
        val reopened = open(written)
        assertArrayEquals(coverage, requireNotNull(reopened.mask(reopened.layers().single())).coverage)
    }

    @Test
    fun aFullyRevealedMaskKeepsItsNativeDescriptorWithoutPixelTiles() {
        val coverage = ByteArray(8 * 5) { 255.toByte() }
        val written = write(8, 5, coverage)
        assertEquals(3L, written.mask["type"])
        assertTrue(written.files.keys.none { it.startsWith("${written.maskUuid}/") })
        val reopened = open(written)
        assertArrayEquals(coverage, requireNotNull(reopened.mask(reopened.layers().single())).coverage)
    }

    @Test
    fun typedCoverageRoundTripsAtAmbiguousAndThinCanvasSizesWithoutChangingPaint() {
        for ((width, height) in listOf(128 to 128, 1 to 1, 1 to 257, 257 to 1)) {
            val coverage = ByteArray(width * height) { ((it % width) * 17 + (it / width) * 31).toByte() }
            val written = write(width, height, coverage)
            val reopened = open(written)
            val layer = reopened.layers().single()
            assertArrayEquals("${width}x$height", coverage, requireNotNull(reopened.mask(layer)).coverage)
            assertTrue(reopened.pixels(layer).pixels.all { it == PAINT })
            assertTrue(requireNotNull(reopened.composite()).pixels.all { it == PAINT })
        }
    }

    @Test
    fun disabledMasksKeepTheirCoverageAndUseTheNativeMaskHiddenField() {
        val coverage = ByteArray(64) { intArrayOf(0, 128, 255)[it % 3].toByte() }
        for (enabled in listOf(true, false)) {
            val written = write(8, 8, coverage, maskEnabled = enabled)
            assertEquals(!enabled, written.mask["hidden"])
            assertEquals(false, written.layer["hidden"])
            assertEquals(3L, written.mask["type"])
            val reopened = open(written)
            val layer = reopened.layers().single()
            assertEquals(enabled, layer.maskEnabled)
            assertTrue(layer.visible)
            assertArrayEquals(coverage, requireNotNull(reopened.mask(layer)).coverage)
            assertTrue(reopened.pixels(layer).pixels.all { it == PAINT })
        }
    }

    private class Written(
        val files: Map<String, ByteArray>,
        archive: KeyedArchive,
    ) {
        private val document = requireNotNull(archive.root)
        val layer = requireNotNull(archive.map(archive.array(document["layers"]).single()))
        val mask = requireNotNull(archive.map(layer["mask"]))
        val maskUuid = requireNotNull(archive.string(mask["UUID"]))
        val layerUuid = requireNotNull(archive.string(layer["UUID"]))
        val compositeUuid = requireNotNull(archive.string(archive.map(document["composite"])?.get("UUID")))
    }

    private fun write(
        width: Int,
        height: Int,
        coverage: ByteArray,
        maskEnabled: Boolean = true,
    ): Written {
        val pixels = PixelBuffer.filled(width, height, PAINT)
        val bytes =
            ProcreateWriter.write(
                width,
                height,
                listOf(
                    ProcreateWriter.Layer(
                        "Masked paint",
                        pixels,
                        mask = SelectionMask(width, height, coverage),
                        maskEnabled = maskEnabled,
                    ),
                ),
                pixels,
            )
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { files[it.name] = zip.readBytes() }
        }
        return Written(files, KeyedArchive(BinaryPlist.read(requireNotNull(files["Document.archive"]))))
    }

    /** Reads the native block's declared raw length without asking the application's tile-layout code. */
    private fun rawSize(tile: ByteArray): Int = ByteBuffer.wrap(tile, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun open(written: Written): ProcreateReader.Document =
        ProcreateReader.open(
            object : ProcreateReader.Files {
                override val names = written.files.keys

                override fun read(name: String): ByteArray? = written.files[name]
            },
        ) { null }

    companion object {
        private const val PAINT = 0xFF114477.toInt()
    }
}
