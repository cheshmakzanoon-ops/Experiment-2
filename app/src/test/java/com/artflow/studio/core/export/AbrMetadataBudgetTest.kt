package com.artflow.studio.core.export

import com.artflow.studio.data.local.BrushArchives
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class AbrMetadataBudgetTest {
    @Test
    fun unicodePresetNamesAndNormalisedKeysSurviveEitherSectionOrder() {
        val expected = "墨筆 🖌️ Ω"
        for (subversion in 1..2) {
            for (descriptorFirst in listOf(false, true)) {
                val input =
                    abr(
                        samples = listOf(sample("\$MiXeD-Key\u0000", subversion = subversion)),
                        metadata = metadata("  $expected  \u0000", "  \$\$MIXED-KEY\u0000"),
                        descriptorFirst = descriptorFirst,
                        subversion = subversion,
                    )
                val tip = read(input).single()
                assertEquals(expected, tip.name)
                assertArrayEquals(byteArrayOf(-1), tip.values)
            }
        }
    }

    @Test
    fun maximumLengthClassicAndPresetNamesRemainComplete() {
        val name = "墨🖌".repeat(341) + "Ω"
        assertEquals(1024, name.length)
        assertEquals(name, read(classic(name)).single().name)
        assertEquals(name, read(abr(metadata = metadata(name))).single().name)
    }

    @Test
    fun aClassicNameThatDoesNotFitRejectsInsteadOfBeingTruncated() {
        assertMemoryRefused(classic("墨".repeat(1024)), 80 * KIB)
    }

    @Test
    fun overlappingNamesAndKeysSpendTheAllowanceBeforeEachDecode() {
        for (marker in listOf(NAME_KEY, SAMPLED_KEY)) {
            val input = abr(metadata = overlapping(marker))
            assertTrue(input.size < 22 * KIB)
            assertMemoryRefused(input)
        }
    }

    @Test
    fun duplicateKeysAndDiscardedEmptyNamesStillSpendTheWorkAllowance() {
        for (descriptor in listOf(metadata("First"), text(NAME_KEY, ""))) {
            val repeated = encoded { out -> repeat(1000) { out.write(descriptor) } }
            assertMemoryRefused(abr(metadata = repeated))
        }
    }

    @Test
    fun retainedAsciiSampleKeysConsumeTheSameAllowance() {
        val samples = List(256) { sample("abcdefgh-ijkl-mnop-qrst-uvwxyz012345") }
        val input = abr(samples = samples)
        assertEquals(256, read(input, MIB).size)
        assertMemoryRefused(input)
    }

    @Test
    fun descriptorAdmissionPrecedesTheFollowingSampleDecoder() {
        // The empty PackBits row would report damage if the sample decoder were reached.
        val input =
            abr(
                samples = listOf(sample(damaged = true)),
                metadata = metadata("墨".repeat(1024)),
                descriptorFirst = true,
            )
        assertMemoryRefused(input, 80 * KIB)
    }

    @Test
    fun metadataAndPixelWorkShareOneAllowance() {
        val descriptor = metadata("墨".repeat(1024))
        val pixels = listOf(sample(side = 64))
        assertEquals(1, read(abr(metadata = descriptor), 88 * KIB).size)
        assertArrayEquals(ByteArray(4096) { -1 }, read(abr(samples = pixels), 88 * KIB).single().values)
        assertMemoryRefused(abr(samples = pixels, metadata = descriptor, descriptorFirst = true), 88 * KIB)
    }

    @Test
    fun aPresetNameCannotBorrowTextFromTheFollowingSection() {
        val descriptor =
            encoded { out ->
                out.writeBytes(NAME_KEY)
                out.writeInt(100)
                out.write(text(SAMPLED_KEY, "tip"))
            }
        val input = abr(metadata = descriptor, trailing = section("more", ByteArray(256) { 65 }))
        assertNull(read(input).single().name)
    }

    @Test
    fun aSampleReferenceCannotBorrowItsLastByteFromTheFollowingSection() {
        val descriptor =
            encoded { out ->
                out.write(text(NAME_KEY, "Visible"))
                out.writeBytes(SAMPLED_KEY)
                out.writeInt(1)
                out.writeByte(0) // The following section's first byte would complete UTF-16 '8'.
            }
        val input = abr(samples = listOf(sample("8")), metadata = descriptor, trailing = section("more", byteArrayOf()))
        assertNull(read(input).single().name)
    }

    @Test
    fun malformedOptionalUnicodeStillAllowsAnUnnamedTip() {
        val descriptor =
            encoded { out ->
                out.writeBytes(NAME_KEY)
                out.writeInt(Int.MAX_VALUE)
                out.write(text(SAMPLED_KEY, "tip"))
            }
        assertNull(read(abr(metadata = descriptor)).single().name)
    }

    @Test
    fun metadataRefusalAfterEarlierTipsRejectsTheCompleteImportedBatch() {
        val samples = listOf(sample(), sample("second"))
        assertEquals(2, BrushArchives.readAbr(abr(samples = samples), MIB).size)
        val input = abr(samples = samples, metadata = overlapping(SAMPLED_KEY))
        val failure = assertThrows(IllegalArgumentException::class.java) { BrushArchives.readAbr(input, MIB) }
        assertEquals(BrushDecodeBudget.MEMORY_MESSAGE, failure.message)
    }

    private fun read(
        input: ByteArray,
        allowance: Long = 256 * KIB,
    ): List<AbrReader.Tip> = AbrReader.read(input, BrushDecodeBudget(allowance), includeTiles = false)

    private fun assertMemoryRefused(
        input: ByteArray,
        allowance: Long = 256 * KIB,
    ) {
        val budget = BrushDecodeBudget(allowance)
        assertTrue("The encoded input must fit before metadata admission", input.size <= budget.encodedLimit)
        val failure = assertThrows(IllegalArgumentException::class.java) { AbrReader.read(input, budget, includeTiles = false) }
        assertEquals(BrushDecodeBudget.MEMORY_MESSAGE, failure.message)
    }

    private fun metadata(
        name: String,
        key: String = "tip",
    ): ByteArray = text(NAME_KEY, name) + text(SAMPLED_KEY, key)

    private fun text(
        marker: String,
        value: String,
    ): ByteArray =
        encoded { out ->
            out.writeBytes(marker)
            out.writeInt(value.length)
            out.write(value.toByteArray(Charsets.UTF_16BE))
        }

    private fun overlapping(marker: String): ByteArray =
        encoded { out ->
            out.write(text(NAME_KEY, "Ordinary"))
            repeat(1000) {
                out.writeBytes(marker)
                out.writeInt(1024)
            }
            out.write(ByteArray(2048) { 65 })
        }

    private fun classic(name: String): ByteArray =
        encoded { out ->
            val body =
                encoded { sample ->
                    sample.write(ByteArray(6)) // Miscellaneous fields and spacing.
                    sample.writeInt(name.length)
                    sample.write(name.toByteArray(Charsets.UTF_16BE))
                    sample.write(ByteArray(9)) // Antialiasing and short bounds.
                    sample.writeInt(0)
                    sample.writeInt(0)
                    sample.writeInt(1)
                    sample.writeInt(1)
                    sample.writeShort(8)
                    sample.writeByte(0)
                    sample.writeByte(255)
                }
            out.writeShort(2)
            out.writeShort(1)
            out.writeShort(2)
            out.writeInt(body.size)
            out.write(body)
        }

    private fun sample(
        key: String = "tip",
        side: Int = 1,
        subversion: Int = 1,
        damaged: Boolean = false,
    ): ByteArray =
        encoded { out ->
            val prefix = if (subversion == 1) 47 else 301
            out.writeByte(key.length)
            out.writeBytes(key)
            out.write(ByteArray(prefix - 1 - key.length))
            out.writeInt(0)
            out.writeInt(0)
            out.writeInt(side)
            out.writeInt(side)
            out.writeShort(8)
            out.writeByte(1)
            repeat(side) { out.writeShort(if (damaged) 0 else 2) }
            if (!damaged) {
                repeat(side) {
                    out.writeByte(1 - side)
                    out.writeByte(255)
                }
            }
        }

    private fun abr(
        samples: List<ByteArray> = listOf(sample()),
        metadata: ByteArray = byteArrayOf(),
        descriptorFirst: Boolean = false,
        subversion: Int = 1,
        trailing: ByteArray = byteArrayOf(),
    ): ByteArray =
        encoded { out ->
            out.writeShort(6)
            out.writeShort(subversion)
            val tips =
                section(
                    "samp",
                    encoded { entries ->
                        samples.forEach { sample ->
                            entries.writeInt(sample.size)
                            entries.write(sample)
                            entries.write(ByteArray((4 - sample.size % 4) % 4))
                        }
                    },
                )
            val descriptor = section("desc", metadata)
            out.write(if (descriptorFirst) descriptor else tips)
            out.write(if (descriptorFirst) tips else descriptor)
            out.write(trailing)
        }

    private fun section(
        tag: String,
        body: ByteArray,
    ): ByteArray =
        encoded { out ->
            out.writeBytes("8BIM")
            out.writeBytes(tag)
            out.writeInt(body.size)
            out.write(body)
        }

    private fun encoded(write: (DataOutputStream) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use(write)
        return bytes.toByteArray()
    }

    private companion object {
        const val KIB = 1024L
        const val MIB = 1024 * KIB
        const val NAME_KEY = "Nm  TEXT"
        const val SAMPLED_KEY = "sampledDataTEXT"
    }
}
