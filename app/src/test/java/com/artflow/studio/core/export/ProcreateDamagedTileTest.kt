package com.artflow.studio.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipInputStream

/** A present tile that cannot be decoded is reported, never silently left as a hole in the painting. */
class ProcreateDamagedTileTest {
    private fun fixture(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/procreate/$name")).use { it.readBytes() }

    private fun entriesOf(bytes: ByteArray): MutableMap<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes() }
        }
        return entries
    }

    private fun open(entries: Map<String, ByteArray>): ProcreateReader.Document =
        ProcreateReader.open(
            object : ProcreateReader.Files {
                override val names = entries.keys

                override fun read(name: String) = entries[name]
            },
        ) { runCatching { TestPng.decode(it) }.getOrNull() }

    private fun readEveryLayer(document: ProcreateReader.Document) {
        document.layers().forEach { layer ->
            document.pixels(layer)
            document.mask(layer)
        }
    }

    @Test
    fun anIntactDocumentReportsNoDamagedTiles() {
        val document = open(entriesOf(fixture("upright.procreate")))
        readEveryLayer(document)
        assertEquals(0, document.damagedTileCount)
    }

    @Test
    fun aTileWhoseDataCannotBeDecodedIsCounted() {
        val entries = entriesOf(fixture("upright.procreate"))
        val victim = entries.keys.first { it.startsWith("SKY-UUID/") && it.endsWith(".chunk") }
        // Present, non-empty, and not valid compressed tile data.
        entries[victim] = ByteArray(64) { (0xA5 xor it).toByte() }
        val document = open(entries)
        readEveryLayer(document)
        assertTrue("A garbled present tile must be counted, not skipped", document.damagedTileCount >= 1)
    }

    @Test
    fun completeZlibTilesKeepCroppedAndPaddedEdgeStridesAndPremultipliedChannels() {
        for ((storedWidth, storedHeight) in listOf(3 to 2, 16 to 16)) {
            val document = openTile(zlib(rgba(storedWidth, storedHeight)), 19, 18, "INK/1~1.tile")
            val pixels = document.pixels(document.layers().single())
            for (index in pixels.pixels.indices) {
                val x = index % 19
                val y = index / 19
                val expected =
                    if (x >= 16 && y >= 16) {
                        0x80000010.toInt() or ((64 + (x - 16) * 2) shl 16) or ((32 + (y - 16) * 2) shl 8)
                    } else {
                        0
                    }
                assertEquals("$storedWidth x $storedHeight at ($x, $y)", expected, pixels.pixels[index])
            }
            assertEquals(0, document.damagedTileCount)
        }
    }

    @Test
    fun missingZlibChecksumsDoNotTurnCompletePixelPrefixesIntoIntactTiles() {
        for ((width, height) in listOf(16 to 16, 3 to 2)) {
            val packed = zlib(rgba(width, height))
            for (missing in 1..4) assertDamagedTile(packed.copyOf(packed.size - missing), width, height)
        }
    }

    @Test
    fun zlibOutputBeyondOneTileIsReportedInsteadOfSilentlyTruncated() {
        assertDamagedTile(zlib(rgba(16, 16) + byteArrayOf(1)))
    }

    @Test
    fun trailingZlibBytesAndAdditionalStreamsAreReported() {
        val packed = zlib(rgba(16, 16))
        assertDamagedTile(packed + byteArrayOf(0))
        assertDamagedTile(packed + packed)
    }

    private fun assertDamagedTile(
        packed: ByteArray,
        width: Int = 16,
        height: Int = 16,
    ) {
        val document = openTile(packed, width, height)
        assertTrue(document.pixels(document.layers().single()).pixels.all { it == 0 })
        assertEquals(1, document.damagedTileCount)
    }

    private fun openTile(
        packed: ByteArray,
        width: Int = 16,
        height: Int = 16,
        path: String = "INK/0~0.tile",
    ): ProcreateReader.Document {
        val archive = KeyedArchiveBuilder()
        val layer = archive.instance("SilicaLayer", mapOf("name" to "Ink", "UUID" to "INK"))
        val description =
            archive.encode(
                mapOf("size" to "{$width, $height}", "tileSize" to 16L, "layers" to archive.array(listOf(layer))),
            )
        return open(mapOf("Document.archive" to description, path to packed))
    }

    /** Literal premultiplied RGBA; edge placement and straight channels are asserted independently. */
    private fun rgba(
        width: Int,
        height: Int,
    ): ByteArray =
        ByteArray(width * height * 4) { at ->
            when (at % 4) {
                0 -> 32 + at / 4 % width
                1 -> 16 + at / 4 / width
                2 -> 8
                else -> 128
            }.toByte()
        }

    /** JDK zlib framing is independent of the production Procreate writer and tile decoders. */
    private fun zlib(raw: ByteArray): ByteArray =
        ByteArrayOutputStream().also { output -> DeflaterOutputStream(output).use { it.write(raw) } }.toByteArray()
}
