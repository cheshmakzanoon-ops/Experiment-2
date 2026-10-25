package com.artflow.studio.core.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/** Single-channel coverage is a native mask format; ordinary image tiles remain premultiplied RGBA. */
class ProcreateMaskTilesTest {
    @Test
    fun oneChannelMaskRetainsEveryCoverageValueAndMissingTilesReveal() {
        val entries = document(32, 16)
        entries["MASK/0~0.lz4"] = apple(ByteArray(256) { it.toByte() })
        entries["INK/0~0.lz4"] = apple(rgba(16, 16) { 0xFF114477.toInt() })
        val opened = open(entries)
        val layer = opened.layers().single()
        val mask = requireNotNull(opened.mask(layer))
        val pixels = opened.pixels(layer)
        for (y in 0 until 16) {
            for (x in 0 until 32) {
                assertEquals("($x, $y)", if (x < 16) y * 16 + x else 255, mask.coverageAt(x, y))
                assertEquals(if (x < 16) 0xFF114477.toInt() else 0, pixels.pixels[y * 32 + x])
            }
        }
    }

    @Test
    fun oneChannelBytesAreAcceptedOnlyForMasks() {
        val entries = document(16, 16)
        val singleChannel = apple(ByteArray(256) { 128.toByte() })
        entries["MASK/0~0.lz4"] = singleChannel
        entries["INK/0~0.lz4"] = singleChannel
        val opened = open(entries)
        val layer = opened.layers().single()
        assertTrue(opened.pixels(layer).pixels.all { it == 0 })
        assertArrayEquals(ByteArray(256) { 128.toByte() }, requireNotNull(opened.mask(layer)).coverage)
    }

    @Test
    fun croppedSingleChannelEdgeTilesKeepTheirRowStrideAndPosition() {
        assertEdges(wholeTiles = false)
    }

    @Test
    fun paddedSingleChannelEdgeTilesIgnoreOffCanvasBytes() {
        assertEdges(wholeTiles = true)
    }

    @Test
    fun oneChannelMaskUsesTheSameCalibratedTurnAndMirrorAsItsLayer() {
        val entries = linkedMapOf<String, ByteArray>()
        requireNotNull(javaClass.getResourceAsStream("/procreate/turned.procreate")).use { source ->
            ZipInputStream(source).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes() }
            }
        }
        // The fixture generator's mirror after a counterclockwise turn stores
        // upright(x,y) at stored(199-y,299-x), independently of the reader's index calculation.
        // Its stored canvas is 200x300, and every mask tile is a padded 128x128 square.
        entries.keys.filter { it.startsWith("MASK-UUID/") }.forEach { name ->
            val tile =
                name
                    .substringAfter('/')
                    .substringBefore('.')
                    .split('~')
                    .map(String::toInt)
            val coverage =
                ByteArray(128 * 128) { at ->
                    val x = tile[0] * 128 + at % 128
                    val y = tile[1] * 128 + at / 128
                    (x * 13 + y * 7).toByte()
                }
            entries[name] = apple(coverage)
        }
        val opened = ProcreateReader.open(files(entries), TestPng::decode)
        assertEquals(300, opened.width)
        assertEquals(200, opened.height)
        val mask = requireNotNull(opened.mask(opened.layers().last()))
        val expected = ByteArray(300 * 200) { at -> ((199 - at / 300) * 13 + (299 - at % 300) * 7).toByte() }
        assertArrayEquals(expected, mask.coverage)
    }

    @Test
    fun legacyRgbaAlphaAndAmbiguousCroppedTileLengthsKeepTheirMeaning() {
        // 8x8 RGBA is also the length of a padded 16x16 single-channel tile.
        // Historical ArtFlow exports omitted the native mask type. Keep their RGBA interpretation.
        val entries = document(8, 8, maskType = null)
        val colors = intArrayOf(0xFF000000.toInt(), 0x80000000.toInt(), 0x80404040.toInt(), 0)
        entries["MASK/0~0.lz4"] = apple(rgba(8, 8) { colors[it % colors.size] })
        val opened = open(entries)
        assertTrue(opened.layers().single().maskEnabled)
        val expected = intArrayOf(0, 127, 191, 255)
        assertArrayEquals(
            ByteArray(64) { expected[it % expected.size].toByte() },
            requireNotNull(opened.mask(opened.layers().single())).coverage,
        )
    }

    @Test
    fun declaredSingleChannelMasksResolveAmbiguousPaddedTileLengths() {
        // Native mask type 2 is normalized to type 3; both have a one-channel backing.
        for (type in listOf(2L, 3L)) {
            val entries = document(8, 8, maskType = type)
            entries["MASK/0~0.lz4"] = apple(ByteArray(256) { (it * 2).toByte() })
            entries["INK/0~0.lz4"] = apple(rgba(8, 8) { 0xFF114477.toInt() })
            val opened = open(entries)
            val layer = opened.layers().single()
            val expected = ByteArray(64) { at -> ((at / 8 * 16 + at % 8) * 2).toByte() }
            assertArrayEquals("type $type", expected, requireNotNull(opened.mask(layer)).coverage)
            assertTrue(opened.pixels(layer).pixels.all { it == 0xFF114477.toInt() })
        }
    }

    @Test
    fun declaredSingleChannelMasksRetainUnambiguousFullAndCroppedRgbaCompatibility() {
        val entries = document(19, 18)
        val colors = intArrayOf(0xFF000000.toInt(), 0x80000000.toInt(), 0x80404040.toInt(), 0)
        entries["MASK/0~0.lz4"] = apple(rgba(16, 16) { colors[it % colors.size] })
        entries["MASK/1~1.lz4"] = apple(rgba(3, 2) { colors[it % colors.size] })
        val expectedCoverage = intArrayOf(0, 127, 191, 255)
        val expected =
            ByteArray(19 * 18) { at ->
                val x = at % 19
                val y = at / 19
                when {
                    x < 16 && y < 16 -> expectedCoverage[(y * 16 + x) % 4]
                    x >= 16 && y >= 16 -> expectedCoverage[((y - 16) * 3 + x - 16) % 4]
                    else -> 255
                }.toByte()
            }
        val opened = open(entries)
        assertArrayEquals(expected, requireNotNull(opened.mask(opened.layers().single())).coverage)
    }

    @Test
    fun damagedUnsupportedAndOutOfBoundsMaskTilesStayRevealed() {
        val entries = document(32, 16)
        entries["MASK/0~0.lz4"] = apple(ByteArray(17))
        entries["MASK/1~0.lz4"] = "bv41".toByteArray() + ByteArray(8)
        entries["MASK/2147483647~0.lz4"] = apple(ByteArray(256))
        val opened = open(entries)
        assertArrayEquals(ByteArray(512) { 255.toByte() }, requireNotNull(opened.mask(opened.layers().single())).coverage)
    }

    private fun assertEdges(wholeTiles: Boolean) {
        val entries = document(19, 18)
        for ((column, row) in listOf(1 to 0, 0 to 1, 1 to 1)) {
            val width = if (wholeTiles) 16 else minOf(16, 19 - column * 16)
            val height = if (wholeTiles) 16 else minOf(16, 18 - row * 16)
            val coverage =
                ByteArray(width * height) { at ->
                    ((column * 16 + at % width) * 11 + (row * 16 + at / width) * 7).toByte()
                }
            entries["MASK/$column~$row.lz4"] = apple(coverage)
        }
        val opened = open(entries)
        val expected =
            ByteArray(19 * 18) { at ->
                val x = at % 19
                val y = at / 19
                (if (x < 16 && y < 16) 255 else x * 11 + y * 7).toByte()
            }
        assertArrayEquals(expected, requireNotNull(opened.mask(opened.layers().single())).coverage)
    }

    private fun document(
        width: Int,
        height: Int,
        maskType: Long? = 3L,
    ): MutableMap<String, ByteArray> {
        val archive = KeyedArchiveBuilder()
        val fields = mutableMapOf<String, Any?>("UUID" to archive.add("MASK"))
        maskType?.let { fields["type"] = it }
        val mask = archive.instance("SilicaLayer", fields)
        val ink =
            archive.instance(
                "SilicaLayer",
                mapOf("UUID" to archive.add("INK"), "name" to archive.add("Ink"), "mask" to mask),
            )
        val root =
            mapOf(
                "\$class" to archive.classOf("SilicaDocument"),
                "size" to archive.add("{$width, $height}"),
                "tileSize" to 16L,
                "layers" to archive.array(listOf(ink)),
            )
        return linkedMapOf("Document.archive" to archive.encode(root))
    }

    /** Packs already-premultiplied channels; deliberately no image writer participates in the fixture. */
    private fun rgba(
        width: Int,
        height: Int,
        pixel: (Int) -> Int,
    ): ByteArray =
        ByteArray(width * height * 4) { at ->
            val shift = intArrayOf(16, 8, 0, 24)[at % 4]
            (pixel(at / 4) ushr shift).toByte()
        }

    /** Independent stored-block Apple framing avoids sharing the production compressor's layout decisions. */
    private fun apple(raw: ByteArray): ByteArray =
        ByteArrayOutputStream()
            .apply {
                write("bvx-".toByteArray())
                write(
                    ByteBuffer
                        .allocate(4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(raw.size)
                        .array(),
                )
                write(raw)
                write("bvx$".toByteArray())
            }.toByteArray()

    private fun files(entries: Map<String, ByteArray>): ProcreateReader.Files =
        object : ProcreateReader.Files {
            override val names = entries.keys

            override fun read(name: String): ByteArray? = entries[name]
        }

    private fun open(entries: Map<String, ByteArray>): ProcreateReader.Document = ProcreateReader.open(files(entries)) { null }
}
