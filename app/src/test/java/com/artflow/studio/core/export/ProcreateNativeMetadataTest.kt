package com.artflow.studio.core.export

import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.color.IccProfile
import com.artflow.studio.core.color.KnownIccProfiles
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipInputStream

/** Checks native archive fields independently of the application reader's tree traversal. */
class ProcreateNativeMetadataTest {
    @Test
    fun nativeTreeAndFlatIndexShareEachPixelObjectOnceAndExcludeMasks() {
        val pixels = PixelBuffer.filled(3, 2, 0xFFCC2200.toInt())
        val bytes =
            ProcreateWriter.write(
                3,
                2,
                listOf(
                    ProcreateWriter.Layer("Loose", pixels, mask = SelectionMask(3, 2, ByteArray(6) { 128.toByte() })),
                    ProcreateWriter.Group(
                        "Outer",
                        0.5f,
                        false,
                        listOf(
                            ProcreateWriter.Group("Inner", children = listOf(ProcreateWriter.Layer("Nested", pixels))),
                            ProcreateWriter.Layer("Last", pixels),
                        ),
                    ),
                ),
                pixels,
            )
        val archive = KeyedArchive(BinaryPlist.read(requireNotNull(entries(bytes)["Document.archive"])))
        val root = requireNotNull(archive.root)
        val flat = references(archive, root["layers"])
        val tree = references(archive, root["unwrappedLayers"])
        assertEquals(3, flat.size)
        assertEquals(3, flat.toSet().size)
        assertEquals(listOf("Loose", "Nested", "Last"), flat.map { archive.string(archive.map(it)?.get("name")) })
        assertTrue(flat.all { archive.className(it) == "SilicaLayer" })
        val outer = requireNotNull(archive.map(tree.last()))
        assertEquals("SilicaGroup", archive.className(outer))
        assertEquals(true, outer["isHidden"])
        assertEquals(0.5, outer["opacity"])
        val inner = requireNotNull(archive.map(references(archive, outer["children"]).first()))
        val leaves = listOf(tree.first()) + references(archive, inner["children"]) + references(archive, outer["children"]).last()
        assertEquals(flat, leaves)
        assertFalse(flat.contains(root["composite"]))
        assertFalse(flat.contains(archive.map(flat.first())?.get("mask")))
    }

    @Test
    fun displayP3ProfileTagsUnconvertedLayersCompositeAndBackground() {
        val native = intArrayOf(0xFFE6462D.toInt(), 0xFF14B478.toInt(), 0xFF2846E6.toInt(), 0xFFBE1EB9.toInt())
        val pixels = PixelBuffer(4, 1, native)
        val bytes =
            ProcreateWriter.write(
                4,
                1,
                listOf(ProcreateWriter.Layer("P3", pixels)),
                pixels,
                background = 0xFFF01E5A.toInt(),
                colorProfile = ColorProfile.DISPLAY_P3,
            )
        val files = entries(bytes)
        val archive = KeyedArchive(BinaryPlist.read(requireNotNull(files["Document.archive"])))
        val descriptor = requireNotNull(archive.map(requireNotNull(archive.root)["colorProfile"]))
        assertEquals("SiColorProfile", archive.className(descriptor))
        assertEquals("Display P3", archive.string(descriptor["SiColorProfileArchiveICCNameKey"]))
        val icc = requireNotNull(archive.data(descriptor["SiColorProfileArchiveICCDataKey"]))
        assertArrayEquals(IccProfile.displayP3, icc)
        assertEquals(ColorProfile.DISPLAY_P3, KnownIccProfiles.recognize(icc))
        val document = open(files)
        assertEquals(ColorProfile.DISPLAY_P3, document.colorProfile)
        assertFalse(document.hasUnsupportedColorProfile)
        assertEquals(0xFFF01E5A.toInt(), document.background)
        assertArrayEquals(native, document.pixels(document.layers().single()).pixels)
        assertArrayEquals(native, requireNotNull(document.composite()).pixels)
        assertEquals(
            ColorProfile.DISPLAY_P3,
            ExportOptions(format = ExportFormat.PROCREATE, colorProfile = ColorProfile.DISPLAY_P3).outputProfile,
        )
    }

    @Test
    fun srgbExportsKeepTheirHistoricalChannelsWithoutAnUnsupportedProfileNotice() {
        val pixels = PixelBuffer.filled(3, 2, 0xFF1278D4.toInt())
        val document = open(entries(ProcreateWriter.write(3, 2, listOf(ProcreateWriter.Layer("sRGB", pixels)), pixels)))
        assertEquals(ColorProfile.SRGB, document.colorProfile)
        assertFalse(document.hasUnsupportedColorProfile)
        assertNull(document.background)
        assertArrayEquals(pixels.pixels, document.pixels(document.layers().single()).pixels)
    }

    @Test
    fun anEmptyNativeGroupDoesNotBecomeAPixelIndexEntry() {
        val bytes =
            ProcreateWriter.write(
                2,
                2,
                listOf(ProcreateWriter.Group("Empty", visible = false, children = emptyList())),
                PixelBuffer(2, 2),
            )
        val files = entries(bytes)
        val archive = KeyedArchive(BinaryPlist.read(requireNotNull(files["Document.archive"])))
        assertTrue(references(archive, requireNotNull(archive.root)["layers"]).isEmpty())
        val document = open(files)
        assertTrue(document.layers().isEmpty())
        val group = document.nodes.single() as ProcreateReader.Group
        assertFalse(group.visible)
        assertTrue(group.children.isEmpty())
    }

    private fun references(
        archive: KeyedArchive,
        value: Any?,
    ): List<BinaryPlist.Uid> = (requireNotNull(archive.map(value))["NS.objects"] as List<*>).map { it as BinaryPlist.Uid }

    private fun entries(bytes: ByteArray): Map<String, ByteArray> =
        linkedMapOf<String, ByteArray>().also { result ->
            ZipInputStream(bytes.inputStream()).use { zip ->
                generateSequence { zip.nextEntry }.forEach { result[it.name] = zip.readBytes() }
            }
        }

    private fun open(entries: Map<String, ByteArray>): ProcreateReader.Document =
        ProcreateReader.open(
            object : ProcreateReader.Files {
                override val names = entries.keys

                override fun read(name: String) = entries[name]
            },
        ) { null }
}
