package com.artflow.studio.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ProcreateNodeBudgetTest {
    @Test
    fun compactSharedGroupDagIsRefusedBeforeThumbnailDecoding() {
        val archive = KeyedArchiveBuilder()
        val root = sharedTree(archive, 12)
        var thumbnailDecoded = false

        assertThrows(IllegalArgumentException::class.java) {
            open(archive, listOf(root)) { thumbnailDecoded = true }
        }
        assertFalse(thumbnailDecoded)
    }

    @Test
    fun theSupportedFlatLayerBoundaryStillOpens() {
        val archive = KeyedArchiveBuilder()
        val layer = layer(archive)
        val document = open(archive, List(ProcreateReader.MAX_NODES) { layer })

        assertEquals(ProcreateReader.MAX_NODES, document.layers().size)
        assertEquals(List(ProcreateReader.MAX_NODES) { "Ink" }, document.layers().map { it.name })
    }

    @Test
    fun groupsAndChildrenConsumeTheSameDocumentAllowance() {
        for (extra in 0..1) {
            val archive = KeyedArchiveBuilder()
            val layer = layer(archive)
            val group = group(archive, List(ProcreateReader.MAX_NODES - 1 + extra) { layer })
            if (extra == 0) {
                assertEquals(ProcreateReader.MAX_NODES - 1, open(archive, listOf(group)).layers().size)
            } else {
                assertThrows(IllegalArgumentException::class.java) { open(archive, listOf(group)) }
            }
        }
    }

    @Test
    fun separateRootsCannotResetTheTraversalAllowance() {
        val archive = KeyedArchiveBuilder()
        val root = sharedTree(archive, 9) // Each occurrence contains 1023 nodes.
        assertEquals(512, open(archive, listOf(root)).layers().size)
        assertThrows(IllegalArgumentException::class.java) { open(archive, listOf(root, root)) }
    }

    @Test
    fun cyclicGroupsStillFailTheDepthBoundary() {
        val archive = KeyedArchiveBuilder()
        val fields = mutableMapOf<String, Any?>("\$class" to archive.classOf("SilicaGroup"), "name" to "Cycle")
        val root = archive.add(fields)
        fields["children"] = archive.array(listOf(root))

        assertThrows(IllegalArgumentException::class.java) { open(archive, listOf(root)) }
    }

    private fun sharedTree(
        archive: KeyedArchiveBuilder,
        levels: Int,
    ): BinaryPlist.Uid {
        var node = layer(archive)
        repeat(levels) { node = group(archive, listOf(node, node)) }
        return node
    }

    private fun layer(archive: KeyedArchiveBuilder): BinaryPlist.Uid =
        archive.instance("SilicaLayer", mapOf("name" to "Ink", "UUID" to "ink"))

    private fun group(
        archive: KeyedArchiveBuilder,
        children: List<BinaryPlist.Uid>,
    ): BinaryPlist.Uid = archive.instance("SilicaGroup", mapOf("name" to "Group", "children" to archive.array(children)))

    private fun open(
        archive: KeyedArchiveBuilder,
        roots: List<BinaryPlist.Uid>,
        thumbnailDecoded: () -> Unit = {},
    ): ProcreateReader.Document {
        val bytes = archive.encode(mapOf("size" to "{16, 16}", "tileSize" to 16L, "layers" to archive.array(roots)))
        val files =
            object : ProcreateReader.Files {
                override val names = listOf("Document.archive", "QuickLook/Thumbnail.png")

                override fun read(name: String): ByteArray? =
                    when (name) {
                        "Document.archive" -> bytes
                        "QuickLook/Thumbnail.png" -> byteArrayOf(1)
                        else -> null
                    }
            }
        return ProcreateReader.open(files) {
            thumbnailDecoded()
            null
        }
    }
}
