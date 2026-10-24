package com.artflow.studio.core.export

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.LayerBlendContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.zip.ZipInputStream

class ProcreateLayerBlendContractTest {
    @Test
    fun extendedBlendTwoWinsIncludingZeroAndStandardDarken() {
        val normal = open(mapOf("extendedBlend2" to 0L, "extendedBlend" to 19L, "blend" to 1L))
        assertEquals(BlendMode.NORMAL, normal.blendMode)
        assertEquals(LayerBlendContract.STANDARD, normal.blendContract)
        val standard = open(mapOf("extendedBlend2" to 34L, "extendedBlend" to 19L, "blend" to 19L))
        assertEquals(BlendMode.DARKEN, standard.blendMode)
        assertEquals(LayerBlendContract.STANDARD, standard.blendContract)
    }

    @Test
    fun archiveNineteenOptsInThroughEachSupportedKey() {
        for (fields in listOf(mapOf("extendedBlend2" to 19L), mapOf("extendedBlend" to 19L), mapOf("blend" to 19L))) {
            val layer = open(fields)
            assertEquals(BlendMode.DARKEN, layer.blendMode)
            assertEquals(LayerBlendContract.PROCREATE_DARKEN_19, layer.blendContract)
        }
    }

    @Test
    fun unsupportedPresentHigherKeyDoesNotActivateAConflictingLowerKey() {
        val layer = open(mapOf("extendedBlend2" to 999L, "extendedBlend" to 19L, "blend" to 19L))
        assertEquals(BlendMode.NORMAL, layer.blendMode)
        assertEquals(LayerBlendContract.STANDARD, layer.blendContract)
        val absent = open(emptyMap())
        assertEquals(BlendMode.NORMAL, absent.blendMode)
        assertEquals(LayerBlendContract.STANDARD, absent.blendContract)
    }

    @Test
    fun nativeWriterUsesTheVerifiedNewAndLegacyDarkenFields() {
        for (contract in LayerBlendContract.entries) {
            val files = written(contract)
            val archive = KeyedArchive(BinaryPlist.read(requireNotNull(files.read("Document.archive"))))
            val layer = requireNotNull(archive.map(archive.array(requireNotNull(archive.root)["layers"]).single()))
            val current = if (contract == LayerBlendContract.STANDARD) 34L else 19L
            assertEquals(current, layer["extendedBlend2"])
            assertEquals(19L, layer["extendedBlend"])
            assertEquals(19L, layer["blend"])
            val opened = ProcreateReader.open(files) { null }.layers().single()
            assertEquals(BlendMode.DARKEN, opened.blendMode)
            assertEquals(contract, opened.blendContract)
        }
    }

    @Test
    fun nativeLayerVariantDoesNotChangeTheBrushBlendMap() {
        assertEquals(BlendMode.DARKEN, ProcreateBlendModes.of(19))
        assertEquals(19, ProcreateBlendModes.numberOf(BlendMode.DARKEN))
        assertNull(ProcreateBlendModes.of(34))
    }

    @Test
    fun writerRejectsAContradictoryContract() {
        assertThrows(IllegalArgumentException::class.java) {
            ProcreateWriter.write(
                16,
                16,
                listOf(
                    ProcreateWriter.Layer(
                        "Invalid",
                        PixelBuffer(16, 16),
                        blendMode = BlendMode.MULTIPLY,
                        blendContract = LayerBlendContract.PROCREATE_DARKEN_19,
                    ),
                ),
                PixelBuffer(16, 16),
            )
        }
    }

    private fun open(fields: Map<String, Any?>): ProcreateReader.Layer {
        val archive = KeyedArchiveBuilder()
        val layer = archive.instance("SilicaLayer", mapOf("name" to archive.add("Native")) + fields)
        val bytes =
            archive.encode(
                mapOf(
                    "\$class" to archive.classOf("SilicaDocument"),
                    "size" to archive.add("{16, 16}"),
                    "tileSize" to 16L,
                    "layers" to archive.array(listOf(layer)),
                ),
            )
        return ProcreateReader.open(files(mapOf("Document.archive" to bytes))) { null }.layers().single()
    }

    private fun written(contract: LayerBlendContract): ProcreateReader.Files {
        val layer = ProcreateWriter.Layer("Darken", PixelBuffer(16, 16), blendMode = BlendMode.DARKEN, blendContract = contract)
        val bytes = ProcreateWriter.write(16, 16, listOf(layer), PixelBuffer(16, 16))
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes() }
        }
        return files(entries)
    }

    private fun files(entries: Map<String, ByteArray>) =
        object : ProcreateReader.Files {
            override val names = entries.keys

            override fun read(name: String): ByteArray? = entries[name]
        }
}
