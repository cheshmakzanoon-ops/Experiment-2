package com.artflow.studio.core.export

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.layer.BlendMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * Fixtures were built with the reference LZO (liblzo2) and LZ4 libraries and Python's keyed
 * plist writer (see tools/fixtures/make_procreate_fixtures.py). "upright" stores its tiles top
 * down in RGBA with LZO and cut edge tiles; "turned" stores the same artwork turned a quarter and
 * mirrored, in BGRA, in Apple's LZ4 container with whole edge tiles.
 */
class ProcreateReaderTest {
    private fun fixture(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/procreate/$name")).use { it.readBytes() }

    private fun files(bytes: ByteArray): ProcreateReader.Files {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes() }
        }
        return object : ProcreateReader.Files {
            override val names = entries.keys

            override fun read(name: String) = entries[name]
        }
    }

    private fun decode(bytes: ByteArray): PixelBuffer? =
        ImageIO.read(bytes.inputStream())?.let { image ->
            PixelBuffer(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
        }

    private fun open(name: String) = ProcreateReader.open(files(fixture(name)), ::decode)

    // The layers as the fixture generator draws them, upright, straight ARGB.
    private fun argb(
        a: Int,
        r: Int,
        g: Int,
        b: Int,
    ) = if (a == 0) 0 else (a shl 24) or (r shl 16) or (g shl 8) or b

    private val expected: Map<String, (Int, Int) -> Int> =
        mapOf(
            "Sky" to { x, y -> argb(255, x * 255 / 299, y * 255 / 199, 200) },
            "Line" to { x, y -> if (abs(2 * x - 3 * y) < 6) argb(255, 0, 0, 0) else 0 },
            "Color" to { x, y ->
                when {
                    x in 50 until 150 && y in 40 until 120 -> argb(255, 255, 0, 0)
                    x in 200 until 260 && y in 150 until 190 -> argb(128, 0, 128, 255)
                    else -> 0
                }
            },
            "Masked" to { x, y -> if ((x - 240) * (x - 240) + (y - 60) * (y - 60) < 2500) argb(255, 0, 0, 255) else 0 },
        )

    private fun assertPixels(
        name: String,
        pixels: PixelBuffer,
    ) {
        assertEquals(300, pixels.width)
        assertEquals(200, pixels.height)
        val draw = expected.getValue(name)
        for (y in 0 until 200) {
            for (x in 0 until 300) {
                val want = draw(x, y)
                val got = pixels.pixels[y * 300 + x]
                for (shift in listOf(24, 16, 8, 0)) {
                    val difference = abs(((want shr shift) and 0xFF) - ((got shr shift) and 0xFF))
                    assertTrue("$name ($x, $y): ${Integer.toHexString(got)} instead of ${Integer.toHexString(want)}", difference <= 1)
                }
            }
        }
    }

    @Test
    fun lzoMatchesTheReferenceLibrary() {
        val original = fixture("lzo-original.bin")
        for (name in listOf("lzo1x_1.bin", "lzo1x_999.bin")) {
            assertArrayEquals(name, original, Lzo1x.decompress(fixture(name), original.size))
        }
    }

    @Test
    fun damagedLzoIsRefused() {
        val packed = fixture("lzo1x_999.bin")
        for (length in listOf(1, packed.size / 2, packed.size - 3)) {
            val failure = runCatching { Lzo1x.decompress(packed.copyOf(length), 34_346) }.exceptionOrNull()
            assertTrue("length $length: $failure", failure == null || failure is IllegalArgumentException)
        }
        val failure = runCatching { Lzo1x.decompress(packed, 1000) }.exceptionOrNull()
        assertTrue("$failure", failure is IllegalArgumentException)
    }

    @Test
    fun theDocumentDescribesItsCanvasLayersAndGroups() {
        val document = open("upright.procreate")
        assertEquals(300, document.width)
        assertEquals(200, document.height)
        assertEquals(264, document.dpi)
        assertEquals("Fixture", document.name)
        assertEquals(0xFFE6D9CC.toInt(), document.background)
        assertEquals(listOf("Sky", "Inks", "Masked"), document.nodes.map { it.name })
        val sky = document.nodes[0] as ProcreateReader.Layer
        assertEquals(false, sky.visible)
        assertEquals(BlendMode.VIVID_LIGHT, sky.blendMode)
        val inks = document.nodes[1] as ProcreateReader.Group
        assertEquals(0.5f, inks.opacity)
        val color = inks.children[0] as ProcreateReader.Layer
        assertEquals("Color", color.name)
        assertEquals(BlendMode.MULTIPLY, color.blendMode)
        assertEquals(0.75f, color.opacity)
        assertTrue(color.clipped && color.alphaLocked)
        assertEquals(listOf("Sky", "Color", "Line", "Masked"), document.layers().map { it.name })
    }

    @Test
    fun uprightLzoTilesWithCutEdgesDecode() {
        val document = open("upright.procreate")
        document.layers().forEach { assertPixels(it.name, document.pixels(it)) }
        val masked = document.layers().last()
        val mask = requireNotNull(document.mask(masked))
        assertEquals(255, mask.coverage[60 * 300 + 100].toInt() and 0xFF)
        assertEquals(0, mask.coverage[60 * 300 + 260].toInt() and 0xFF)
        assertNull(document.mask(document.layers().first()))
    }

    @Test
    fun aTurnedMirroredBgraLz4DocumentIsCalibratedUprightByItsThumbnail() {
        val document = open("turned.procreate")
        assertEquals(300, document.width)
        assertEquals(200, document.height)
        document.layers().forEach { assertPixels(it.name, document.pixels(it)) }
        val mask = requireNotNull(document.mask(document.layers().last()))
        assertEquals(255, mask.coverage[60 * 300 + 100].toInt() and 0xFF)
        assertEquals(0, mask.coverage[60 * 300 + 260].toInt() and 0xFF)
    }

    @Test
    fun aZipWithoutADocumentIsRefused() {
        val empty =
            object : ProcreateReader.Files {
                override val names = emptyList<String>()

                override fun read(name: String): ByteArray? = null
            }
        assertTrue(runCatching { ProcreateReader.open(empty) { null } }.exceptionOrNull() is IllegalArgumentException)
    }
}
