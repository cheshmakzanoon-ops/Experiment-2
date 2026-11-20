package com.artflow.studio.data.local

import com.artflow.studio.core.export.AbrReader
import com.artflow.studio.core.export.BinaryPlist
import com.artflow.studio.core.export.BrushDecodeBudget
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.data.export.ArtflowPackage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BrushDecodeAdmissionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun namesIdentifyMetadataAndImageOnlyBrushLayoutsWithoutDecodingTheirPayloads() {
        assertTrue(brushCandidate("A/Brush.archive", "A/Sub01/Shape.png"))
        assertTrue(brushCandidate("A/shape.png", "A/grain.png"))
        assertFalse(brushCandidate("notes.txt", "Preview.png"))
    }

    @Test
    fun modelMembersKeepTheirInputContractWhenTextureNamesResembleBrushes() {
        for (extension in listOf("OBJ", "gltf", "glb", "usd", "usda", "usdc", "usdz")) {
            assertFalse(extension, brushCandidate("model.$extension", "textures/Shape.png", "Grain.png", "Brush.archive"))
        }
    }

    @Test
    fun palettesKeepTheirInputContractWhenExtraMembersResembleBrushes() {
        assertFalse(brushCandidate("Palette/Swatches.json", "Shape.png", "Grain.png", "Brush.archive"))
    }

    private fun brushCandidate(vararg names: String): Boolean {
        val file = temporary.newFile()
        file.writeBytes(zip(*names.map { it to byteArrayOf(1, 2, 3) }.toTypedArray()))
        return ArtflowPackage.inspectArchiveEntries(file, BrushArchives::isBrushOnlyArchive)
    }

    @Test
    fun centeredSamplingMatchesThePreviousSquarePlaneForEveryCoverageByte() {
        val dimensions = listOf(1 to 1, 7 to 17, 17 to 7, 32 to 32, 256 to 256, 1 to 512, 257 to 3, 3 to 513, 511 to 7, 9 to 511)
        for ((width, height) in dimensions) {
            val tip = AbrReader.Tip(null, width, height, ByteArray(width * height) { (it * 17 + 3).toByte() })
            assertArrayEquals("$width x $height", legacyTile(tip).values, PaddedBrushTip.tile(tip).values)
        }
    }

    @Test
    fun theLargestSkinnyTipFitsWithoutAllocatingItsEmptySquare() {
        val tip = Sample(1, 5000, ByteArray(5000) { -1 })
        val tile = requireNotNull(BrushArchives.readAbr(abr(listOf(tip)), MIB).single().shape)
        assertEquals(256 * 256, tile.values.size)
        assertTrue(tile.values.any { it.toInt() and 255 > 0 })
        assertTrue(tile.values.all { it.toInt() and 255 <= 14 })
    }

    @Test
    fun tinyAbrTipsPayTheAccumulatedOutputBeforeDecodingAnotherTip() {
        val admitted = List(4) { Sample(1, 1, byteArrayOf(it.toByte())) }
        assertEquals(4, BrushArchives.readAbr(abr(admitted), MIB).size)
        // The fifth compressed row would fail PackBits if its decoder were reached.
        val invalidFifth = Sample(1, 1, byteArrayOf(0, 1, 127), compressed = true)
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                BrushArchives.readAbr(abr(admitted + invalidFifth), MIB)
            }
        assertEquals(BrushDecodeBudget.MEMORY_MESSAGE, failure.message)
    }

    @Test
    fun rawAndCoveragePlanesAreAdmittedBeforeTheAbrDecoderAllocatesThem() {
        val compact = abr(listOf(Sample(200, 200, ByteArray(400), compressed = true)))
        assertTrue(compact.size < 1024)
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                AbrReader.read(compact, BrushDecodeBudget(128 * 1024), includeTiles = false)
            }
        assertEquals(BrushDecodeBudget.MEMORY_MESSAGE, failure.message)
    }

    @Test
    fun oversizedExpansionIsRejectedBeforeCallingTheImageDecoder() {
        var decoded = false
        val archive = zip("Shape.png" to ByteArray(400 * 1024))
        assertTrue(archive.size < 1024)
        assertThrows(IllegalArgumentException::class.java) {
            BrushArchives.readForImport(archive, MIB) { _, _ ->
                decoded = true
                PixelBuffer(1, 1)
            }
        }
        assertFalse(decoded)
    }

    @Test
    fun retainedDirectoryNamesConsumeTheSameAllowanceBeforeImageDecoding() {
        var decoded = false
        val members = List(20) { index -> ("x".repeat(10_000) + index + "/Shape.png") to byteArrayOf(1) }
        val archive = zip(*members.toTypedArray())
        assertTrue(archive.size < 500 * 1024)
        assertThrows(IllegalArgumentException::class.java) {
            BrushArchives.readForImport(archive, 2 * MIB) { _, _ ->
                decoded = true
                PixelBuffer(1, 1)
            }
        }
        assertFalse(decoded)
    }

    @Test
    fun metadataAllocationRejectionCannotTurnIntoSilentlyMissingSettings() {
        var decoded = false
        val repeated = repeatedDataPlist()
        assertTrue(repeated.size < 3000)
        assertThrows(BinaryPlist.AllocationLimitException::class.java) {
            BrushArchives.readForImport(zip("Brush.archive" to repeated, "Shape.png" to byteArrayOf(1)), 8 * MIB) { _, _ ->
                decoded = true
                PixelBuffer(1, 1)
            }
        }
        assertFalse(decoded)
    }

    @Test
    fun ordinaryDamagedOptionalSettingsStillAllowAReadableImage() {
        val imported =
            BrushArchives
                .readForImport(
                    zip("Brush.archive" to byteArrayOf(1, 2, 3), "Shape.png" to byteArrayOf(1)),
                    2 * MIB,
                ) { _, allowance ->
                    assertTrue(allowance > MIB)
                    PixelBuffer(2, 2, IntArray(4) { -1 })
                }.single()
        assertNull(imported.settings)
        assertNotNull(imported.shape)
        assertArrayEquals(ByteArray(256 * 256) { -1 }, imported.shape!!.values)
    }

    @Test
    fun brushInputGrowthStopsAtTheHeadroomLimitBeforeParsing() {
        var read = 0L
        val input =
            object : InputStream() {
                override fun read(): Int = 0.also { read++ }

                override fun read(
                    target: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int = length.also { read += it }
            }
        assertThrows(IllegalArgumentException::class.java) { BrushArchives.readInput(input, MIB) }
        assertTrue(read in 1 until MIB / 3)
    }

    @Test
    fun oversizedFolderDeclarationsAreRejectedBeforeCollectingEveryMatch() {
        val entries = (0..201).joinToString("") { "<string>Brush $it</string>" }
        val plist = "<plist><dict><key>brushes</key><array>$entries</array></dict></plist>".toByteArray()
        assertThrows(IllegalArgumentException::class.java) { BrushArchives.listedFolders(plist) }
    }

    private fun legacyTile(tip: AbrReader.Tip): CustomGrains.Tile {
        val side = maxOf(tip.width, tip.height)
        val square = PixelBuffer(side, side, IntArray(side * side) { 0xFF000000.toInt() })
        val left = (side - tip.width) / 2
        val top = (side - tip.height) / 2
        for (y in 0 until tip.height) {
            for (x in 0 until tip.width) {
                val value = tip.values[y * tip.width + x].toInt() and 255
                square.pixels[(top + y) * side + left + x] = 0xFF000000.toInt() or (value shl 16) or (value shl 8) or value
            }
        }
        return CustomGrains.tileFrom(square, normalizeContrast = false)
    }

    private data class Sample(
        val width: Int,
        val height: Int,
        val data: ByteArray,
        val compressed: Boolean = false,
    )

    private fun abr(samples: List<Sample>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeShort(1)
            out.writeShort(samples.size)
            samples.forEach { sample ->
                out.writeShort(2)
                out.writeInt(34 + sample.data.size)
                out.write(ByteArray(15))
                out.writeInt(0)
                out.writeInt(0)
                out.writeInt(sample.height)
                out.writeInt(sample.width)
                out.writeShort(8)
                out.writeByte(if (sample.compressed) 1 else 0)
                out.write(sample.data)
            }
        }
        return bytes.toByteArray()
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun repeatedDataPlist(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("bplist00".toByteArray())
        val arrayAt = out.size()
        out.write(byteArrayOf(0xAF.toByte(), 0x12) + ByteBuffer.allocate(4).putInt(1000).array())
        out.write(ByteArray(1000) { 1 })
        val dataAt = out.size()
        out.write(byteArrayOf(0x4F, 0x12) + ByteBuffer.allocate(4).putInt(1024).array())
        out.write(ByteArray(1024))
        val tableAt = out.size()
        out.write(
            ByteBuffer
                .allocate(8)
                .putInt(arrayAt)
                .putInt(dataAt)
                .array(),
        )
        val trailer = ByteBuffer.allocate(32)
        trailer.position(6)
        trailer
            .put(4)
            .put(1)
            .putLong(2)
            .putLong(0)
            .putLong(tableAt.toLong())
        out.write(trailer.array())
        return out.toByteArray()
    }

    private companion object {
        const val MIB = 1024L * 1024
    }
}
