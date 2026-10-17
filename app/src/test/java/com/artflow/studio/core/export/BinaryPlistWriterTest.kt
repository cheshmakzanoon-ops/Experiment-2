package com.artflow.studio.core.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BinaryPlistWriterTest {
    @Test
    fun scalarsAndContainersReadBackAsWritten() {
        val value =
            mapOf(
                "flag" to true,
                "off" to false,
                "nothing" to null,
                "small" to 7L,
                "medium" to 300L,
                "large" to 70_000L,
                "huge" to 5_000_000_000L,
                "negative" to -42L,
                "real" to 0.75,
                "text" to "ASCII text longer than fourteen bytes",
                "unicode" to "Café ✓ 絵",
                "empty" to "",
                "data" to byteArrayOf(0, 1, 2, -1, 127),
                "list" to listOf(1L, "two", listOf(3.5)),
            )
        val read = BinaryPlist.read(BinaryPlist.write(value)) as Map<*, *>
        assertEquals(true, read["flag"])
        assertEquals(false, read["off"])
        assertNull(read["nothing"])
        assertEquals(7L, read["small"])
        assertEquals(300L, read["medium"])
        assertEquals(70_000L, read["large"])
        assertEquals(5_000_000_000L, read["huge"])
        assertEquals(-42L, read["negative"])
        assertEquals(0.75, read["real"])
        assertEquals("ASCII text longer than fourteen bytes", read["text"])
        assertEquals("Café ✓ 絵", read["unicode"])
        assertEquals("", read["empty"])
        assertArrayEquals(byteArrayOf(0, 1, 2, -1, 127), read["data"] as ByteArray)
        assertEquals(listOf(1L, "two", listOf(3.5)), read["list"])
    }

    @Test
    fun longContainersAndManyKeysAreCountedCorrectly() {
        val items = (0 until 300).map { it.toLong() * 1_000 }
        val keys = (0 until 40).associate { "key-$it" to it.toLong() }
        val read = BinaryPlist.read(BinaryPlist.write(mapOf("items" to items, "keys" to keys))) as Map<*, *>
        assertEquals(items, read["items"])
        assertEquals(keys, read["keys"])
    }

    @Test
    fun uidReferencesAreKeptAsReferences() {
        val archive = KeyedArchiveBuilder()
        val name = archive.add("Layer")
        val layer = archive.instance("SilicaLayer", mapOf("name" to name, "opacity" to 1.0))
        val tree = archive.array(listOf(layer))
        val bytes = archive.encode(mapOf("layers" to tree))
        val keyed = KeyedArchive(BinaryPlist.read(bytes))
        val root = requireNotNull(keyed.root)
        val children = keyed.array(root["layers"])
        assertEquals(1, children.size)
        val item = requireNotNull(children.single() as Map<*, *>)
        assertEquals("Layer", keyed.string(item["name"]))
        assertEquals("SilicaLayer", keyed.className(item))
        assertEquals(1.0, (item["opacity"] as Number).toDouble(), 0.0)
    }

    @Test
    fun aDocumentReadsBackWithItsStructure() {
        val archive = KeyedArchiveBuilder()
        val sizeText = archive.add("{300, 200}")
        val bytes = archive.encode(mapOf("size" to sizeText, "tileSize" to 256L))
        assertTrue(BinaryPlist.isBinaryPlist(bytes))
        val keyed = KeyedArchive(BinaryPlist.read(bytes))
        assertEquals("{300, 200}", keyed.string(keyed.root?.get("size")))
        assertEquals(256L, keyed.root?.get("tileSize"))
    }

    @Test
    fun anUnsupportedValueIsRefused() {
        val failure = runCatching { BinaryPlist.write(mapOf("bad" to java.io.File("x"))) }.exceptionOrNull()
        assertTrue("$failure", failure is IllegalArgumentException)
    }
}
