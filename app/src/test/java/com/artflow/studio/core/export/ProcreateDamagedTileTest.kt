package com.artflow.studio.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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
}
