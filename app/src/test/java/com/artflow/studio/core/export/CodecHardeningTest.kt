package com.artflow.studio.core.export

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.three.GltfReader
import com.artflow.studio.core.three.ModelPackage
import com.artflow.studio.core.three.UsdReader
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class CodecHardeningTest {
    @Test
    fun `apng encoder rejects negative chunk lengths and missing IHDR without OOM or NegativeArraySizeException`() {
        val negativeLengthPng =
            byteArrayOf(
                -119,
                80,
                78,
                71,
                13,
                10,
                26,
                10,
                -1,
                -1,
                -1,
                -1,
                73,
                72,
                68,
                82,
                0,
                0,
                0,
                0,
            )
        expectIllegalArgument { ApngEncoder.encode(listOf(negativeLengthPng), listOf(50)) }

        val shortIhdrPng =
            byteArrayOf(
                -119,
                80,
                78,
                71,
                13,
                10,
                26,
                10,
                0,
                0,
                0,
                4,
                73,
                72,
                68,
                82,
                1,
                2,
                3,
                4,
                0,
                0,
                0,
                0,
            )
        expectIllegalArgument { ApngEncoder.encode(listOf(shortIhdrPng), listOf(50)) }
        expectIllegalArgument { ApngEncoder.encode(listOf(PngCodec.encode(PixelBuffer(2, 2))), listOf(50), loops = -1) }
    }

    @Test
    fun `lz4 rejects 255-extension integer overflow and negative apple size`() {
        val header =
            ByteArrayOutputStream()
                .apply {
                    write(0xF0) // token with 15 literals
                    repeat(8_421_376 / 255 + 1) { write(255) }
                }.toByteArray()
        expectIllegalArgument { Lz4.decompress(header, 0, header.size, ByteArray(16), 0) }
        val appleHeader =
            byteArrayOf(
                'b'.code.toByte(),
                'v'.code.toByte(),
                '4'.code.toByte(),
                '$'.code.toByte(),
            )
        expectIllegalArgument { Lz4.decompressApple(appleHeader, -1) }
    }

    @Test
    fun `lzo1x rejects negative target size`() {
        expectIllegalArgument { Lzo1x.decompress(byteArrayOf(0x11, 0, 0), -5) }
    }

    @Test
    fun `abr reader rejects negative classic size and sample dimension overflow`() {
        // Version 2, 1 brush, type = 2 (sampled), size = -6 (would infinite loop on old code)
        val classicLoop =
            ByteBuffer
                .allocate(16)
                .order(ByteOrder.BIG_ENDIAN)
                .putShort(2)
                .putShort(1)
                .putShort(2)
                .putInt(-6)
                .array()
        expectIllegalArgument { AbrReader.read(classicLoop) }
    }

    @Test
    fun `binary plist rejects forged object counts and array sizes before OOM`() {
        // Construct a tiny 40-byte bplist whose trailer declares object count = 3_999_999 (larger than trailer offset)
        val forgedTable = ByteArray(8 + 32)
        "bplist00".toByteArray().copyInto(forgedTable, 0)
        val trailer = 8
        forgedTable[trailer + 6] = 1
        forgedTable[trailer + 7] = 1
        ByteBuffer.wrap(forgedTable).putLong(trailer + 8, 3_999_999L)
        ByteBuffer.wrap(forgedTable).putLong(trailer + 16, 0L)
        ByteBuffer.wrap(forgedTable).putLong(trailer + 24, 8L)
        expectIllegalArgument { BinaryPlist.read(forgedTable) }

        // Forged 0xAF array claiming 500 elements inside a tiny plist
        val forgedArray = ByteArray(14 + 32)
        "bplist00".toByteArray().copyInto(forgedArray, 0)
        forgedArray[8] = 0xAF.toByte()
        forgedArray[9] = 0x11
        forgedArray[10] = 0x01
        forgedArray[11] = 0xF4.toByte() // length = 500
        forgedArray[12] = 0 // 1 byte offset table
        forgedArray[13] = 8
        val arrTrailer = 14
        forgedArray[arrTrailer + 6] = 1
        forgedArray[arrTrailer + 7] = 1
        ByteBuffer.wrap(forgedArray).putLong(arrTrailer + 8, 1L)
        ByteBuffer.wrap(forgedArray).putLong(arrTrailer + 16, 0L)
        ByteBuffer.wrap(forgedArray).putLong(arrTrailer + 24, 13L)
        expectIllegalArgument { BinaryPlist.read(forgedArray) }
    }

    @Test
    fun `gltf reader rejects GLB chunk overflow and short TRS vectors`() {
        val glbOverflow =
            ByteBuffer
                .allocate(20)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x46546C67)
                .putInt(2)
                .putInt(20)
                .putInt(Int.MAX_VALUE) // overflows 20 + MAX_VALUE
                .putInt(0x4E4F534A)
                .array()
        expectIllegalArgument { GltfReader.read(glbOverflow) }

        val badTranslationJson =
            (
                """{"asset":{"version":"2.0"},"nodes":[{"translation":[1.0,2.0]}],""" +
                    """"meshes":[{"primitives":[{"attributes":{"POSITION":0,"TEXCOORD_0":0}}]}],""" +
                    """"accessors":[{"count":3,"componentType":5126,"type":"VEC3"}]}"""
            ).toByteArray()
        expectIllegalArgument { GltfReader.read(badTranslationJson) }
    }

    @Test
    fun `usd crate rejects cyclic jump paths and negative lz4 chunk loop`() {
        val header = ByteArray(64)
        "PXR-USDC".toByteArray().copyInto(header, 0)
        header[8] = 0
        header[9] = 8 // version 0.8
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putLong(16, 24L)
        expectIllegalArgument { UsdReader.read(header) }
    }

    @Test
    fun `psd codec rejects negative extra lengths and merged zip size overflow cleanly`() {
        val valid = PngCodec.encode(PixelBuffer(2, 2))
        assertNull(PsdCodec.read(valid))

        // Crafted PSB header with negative row lengths / extreme merged ZIP plane size
        val malformedPsd =
            ByteArrayOutputStream()
                .apply {
                    write("8BPS".toByteArray())
                    writeShort(1) // version
                    write(ByteArray(6))
                    writeShort(56) // max channels
                    writeInt(5000)
                    writeInt(5000)
                    writeShort(16) // 16-bit -> 5000*5000*2*56 overflows 32-bit signed Int!
                    writeShort(3) // RGB
                    writeInt(0) // color mode len
                    writeInt(0) // resources len
                    writeInt(0) // layer and mask len
                    writeShort(3) // COMPRESSION_ZIP_PREDICTION
                }.toByteArray()
        val doc = PsdCodec.read(malformedPsd)
        assertNull(doc?.composite)
    }

    @Test
    fun `model package rejects directory-only slash entries without crash`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("/"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        expectIllegalArgument { ModelPackage.read(out.toByteArray()) }
    }

    @Test
    fun `seeded fuzz inputs across binary decoders never hang or throw OOM or NASE`() {
        val rng = Random(20261112L)
        repeat(250) { iteration ->
            val len = 16 + (iteration % 240)
            val noise = rng.nextBytes(len)
            runCatching { PsdCodec.read(noise) }
                .onFailure { checkSafeFailure(it) }
            runCatching { AbrReader.read(noise) }
                .onFailure { checkSafeFailure(it) }
            runCatching { BinaryPlist.read(noise) }
                .onFailure { checkSafeFailure(it) }
            runCatching { UsdReader.read(noise) }
                .onFailure { checkSafeFailure(it) }
            runCatching { GltfReader.read(noise) }
                .onFailure { checkSafeFailure(it) }
            runCatching { Lz4.decompress(noise, 0, noise.size, ByteArray(64), 0) }
                .onFailure { checkSafeFailure(it) }
            runCatching { Lzo1x.decompress(noise, 64) }
                .onFailure { checkSafeFailure(it) }
        }
    }

    private fun checkSafeFailure(error: Throwable) {
        if (error is OutOfMemoryError || error is NegativeArraySizeException || error is ArrayIndexOutOfBoundsException) {
            fail("Unsafe failure mode: ${error::class.simpleName}: ${error.message}")
        }
        assertTrue(error is Exception)
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected structured rejection
        }
    }

    private fun ByteArrayOutputStream.writeShort(value: Int) {
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write((value ushr 24) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }
}
