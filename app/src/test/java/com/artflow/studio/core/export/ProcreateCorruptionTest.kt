package com.artflow.studio.core.export

import com.artflow.studio.core.pixels.PixelBuffer
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Procreate readers under corruption: documents (the two synthetic fixtures), brush archives (the fixture set's
 * brush) and the decoders their tiles and archives use. Damaged input must read or be refused with
 * IllegalArgumentException. An IOException from the zip container itself is also a refusal, as the zip layer
 * fails before any Procreate code runs.
 */
class ProcreateCorruptionTest {
    private fun fixture(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/procreate/$name")).use { it.readBytes() }

    private fun refusal(failure: Throwable) = failure is IllegalArgumentException || failure is IOException

    private fun filesOf(bytes: ByteArray): ProcreateReader.Files {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes() }
        }
        return object : ProcreateReader.Files {
            override val names = entries.keys

            override fun read(name: String) = entries[name]
        }
    }

    private fun document(bytes: ByteArray) {
        val opened = ProcreateReader.open(filesOf(bytes)) { runCatching { TestPng.decode(it) }.getOrNull() }
        opened.layers().forEach { layer ->
            opened.pixels(layer).pixels.size
            opened.mask(layer)?.coverage?.size
        }
        opened.composite()?.pixels?.size
    }

    /** The brush archive of the fixture set's first brush (folder "A-INKED"). */
    private fun brushArchive(): ByteArray {
        val entries = filesOf(fixture("fixture.brushset"))
        return requireNotNull(entries.read("A-INKED/Brush.archive"))
    }

    /** One raw LZ4 block in Apple's container, as a tile is stored: a single LZ4 block then the end marker. */
    private fun appleBlock(raw: ByteArray): ByteArray {
        val packed = Lz4.compress(raw)
        val out = ByteArrayOutputStream()
        out.write("bv41".toByteArray(Charsets.ISO_8859_1))
        listOf(raw.size, packed.size).forEach { value -> (0 until 4).forEach { out.write(value ushr (8 * it)) } }
        out.write(packed)
        out.write("bv4\$".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    private fun sample(): ByteArray = ByteArray(6000) { ((it * 7) % 97 + it / 50).toByte() }

    @Test
    fun aCorruptedUprightDocumentIsReadOrRefused() {
        Corruption.check("procreate upright", fixture("upright.procreate"), seed = 2001L, accepted = ::refusal, parse = ::document)
    }

    @Test
    fun aCorruptedTurnedDocumentIsReadOrRefused() {
        Corruption.check("procreate turned", fixture("turned.procreate"), seed = 2002L, accepted = ::refusal, parse = ::document)
    }

    @Test
    fun aCorruptedBrushArchiveIsReadOrRefused() {
        Corruption.check("procreate brush", brushArchive(), seed = 2003L, accepted = ::refusal) { bytes ->
            ProcreateBrush.read(bytes)
        }
    }

    @Test
    fun aCorruptedPropertyListIsReadOrRefused() {
        Corruption.check("bplist", brushArchive(), seed = 2004L, accepted = ::refusal) { bytes ->
            BinaryPlist.read(bytes)
        }
    }

    @Test
    fun aCorruptedLzoStreamIsDecodedOrRefused() {
        Corruption.check("lzo", fixture("lzo1x_999.bin"), seed = 2005L, accepted = ::refusal) { bytes ->
            Lzo1x.decompress(bytes, 34_346)
        }
    }

    @Test
    fun aCorruptedLz4BlockIsDecodedOrRefused() {
        val raw = sample()
        val packed = Lz4.compress(raw)
        Corruption.check("lz4", packed, seed = 2006L, accepted = ::refusal) { bytes ->
            Lz4.decompress(bytes, 0, bytes.size, ByteArray(raw.size), 0)
        }
    }

    @Test
    fun aCorruptedAppleTileIsDecodedOrRefused() {
        val tile = appleBlock(sample())
        Corruption.check("apple tile", tile, seed = 2007L, accepted = ::refusal) { bytes ->
            Lz4.decompressApple(bytes, 6000)
        }
    }

    @Test
    fun theWriterOutputSurvivesCorruptionToo() {
        val pixels = PixelBuffer(33, 21).also { image -> image.pixels.indices.forEach { image.pixels[it] = (0xFF shl 24) or it } }
        val written = ProcreateWriter.write(33, 21, listOf(ProcreateWriter.Layer("Only", pixels)), pixels, name = "Fuzz")
        Corruption.check("written document", written, seed = 2008L, accepted = ::refusal, parse = ::document)
    }
}
