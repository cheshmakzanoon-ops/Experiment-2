package com.artflow.studio.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Native archives contain both a flat pixel-layer index and a separate visible hierarchy. */
class ProcreateHierarchyTest {
    @Test
    fun nativeTreeWinsOverFlatListWithoutDuplicatingLayers() {
        val archive = KeyedArchiveBuilder()
        val first = layer(archive, "First")
        val second = layer(archive, "Second")
        val third = layer(archive, "Third")
        val tail = layer(archive, "Tail")
        val inner = group(archive, "Inner", listOf(second, third))
        val outer = group(archive, "Outer", listOf(first, inner), mapOf("opacity" to 0.5))
        val document = open(archive, listOf(first, second, third, tail), archive.array(listOf(outer, tail)))
        assertEquals(listOf("Outer", "Tail"), document.nodes.map { it.name })
        val folder = document.nodes.first() as ProcreateReader.Group
        assertEquals(0.5f, folder.opacity, 0f)
        assertEquals(listOf("First", "Inner"), folder.children.map { it.name })
        val nested = folder.children.last() as ProcreateReader.Group
        assertEquals(listOf("Second", "Third"), nested.children.map { it.name })
        assertEquals(listOf("First", "Second", "Third", "Tail"), document.layers().map { it.name })
        assertEquals(
            4,
            document
                .layers()
                .map { it.uuid }
                .toSet()
                .size,
        )
    }

    @Test
    fun nativeGroupVisibilityIsDistinctFromPixelLayerVisibility() {
        val archive = KeyedArchiveBuilder()
        val ink = layer(archive, "Ink", mapOf("hidden" to false, "isHidden" to true))
        val hidden = group(archive, "Hidden", listOf(ink), mapOf("isHidden" to true, "hidden" to false))
        val shown = group(archive, "Shown", emptyList(), mapOf("isHidden" to false, "hidden" to true))
        val legacy = group(archive, "Legacy", emptyList(), mapOf("hidden" to true))
        val document = open(archive, listOf(ink), archive.array(listOf(hidden, shown, legacy)))
        assertFalse(document.nodes[0].visible)
        assertTrue(document.nodes[1].visible)
        assertFalse(document.nodes[2].visible)
        assertTrue(document.layers().single().visible)
    }

    @Test
    fun legacyGroupedLayersStaySupportedWhenNativeHierarchyIsAbsent() {
        for (kind in 0..2) {
            val archive = KeyedArchiveBuilder()
            val ink = layer(archive, "Ink")
            val folder = group(archive, "Legacy", listOf(ink), mapOf("hidden" to true))
            val hierarchy =
                when (kind) {
                    0 -> null
                    1 -> BinaryPlist.Uid(0)
                    else -> archive.add(mapOf("notAnArray" to true))
                }
            val document = open(archive, listOf(folder), hierarchy)
            assertEquals("Legacy", document.nodes.single().name)
            assertFalse(document.nodes.single().visible)
            assertEquals("Ink", document.layers().single().name)
        }
    }

    @Test
    fun anExplicitEmptyNativeTreeDoesNotResurrectTheFlatIndex() {
        val archive = KeyedArchiveBuilder()
        val ink = layer(archive, "Flat index only")
        assertTrue(open(archive, listOf(ink), archive.array(emptyList())).nodes.isEmpty())
    }

    @Test
    fun nativeHierarchyRetainsTheExistingDepthLimit() {
        val archive = KeyedArchiveBuilder()
        val ink = layer(archive, "Ink")
        var node = ink
        repeat(ProcreateReader.MAX_NESTING + 1) { node = group(archive, "Depth $it", listOf(node)) }
        val failure = runCatching { open(archive, listOf(ink), archive.array(listOf(node))) }.exceptionOrNull()
        assertTrue(failure.toString(), failure is IllegalArgumentException)
    }

    private fun layer(
        archive: KeyedArchiveBuilder,
        name: String,
        fields: Map<String, Any?> = emptyMap(),
    ): BinaryPlist.Uid =
        archive.instance(
            "SilicaLayer",
            mapOf("name" to archive.add(name), "UUID" to archive.add(name)) + fields,
        )

    private fun group(
        archive: KeyedArchiveBuilder,
        name: String,
        children: List<BinaryPlist.Uid>,
        fields: Map<String, Any?> = emptyMap(),
    ): BinaryPlist.Uid =
        archive.instance(
            "SilicaGroup",
            mapOf("name" to archive.add(name), "children" to archive.array(children)) + fields,
        )

    private fun open(
        archive: KeyedArchiveBuilder,
        flat: List<BinaryPlist.Uid>,
        hierarchy: BinaryPlist.Uid?,
    ): ProcreateReader.Document {
        val root =
            mutableMapOf<String, Any?>(
                "\$class" to archive.classOf("SilicaDocument"),
                "size" to archive.add("{32, 16}"),
                "tileSize" to 16L,
                "layers" to archive.array(flat),
            )
        if (hierarchy != null) root["unwrappedLayers"] = hierarchy
        val bytes = archive.encode(root)
        val files =
            object : ProcreateReader.Files {
                override val names = listOf("Document.archive")

                override fun read(name: String): ByteArray? = bytes.takeIf { name == "Document.archive" }
            }
        return ProcreateReader.open(files) { null }
    }
}
